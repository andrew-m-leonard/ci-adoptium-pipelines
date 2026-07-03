/**
 * DockerAgentHelper — runtime agent selection for build stages.
 *
 * Loaded with:
 *   def dockerAgentHelper = load('ci/jenkins/lib/DockerAgentHelper.groovy')
 *
 * This file is a CpsScript. All pipeline steps (echo, sh, node, docker, etc.)
 * are called directly — no 'steps.' prefix.
 *
 * Public API:
 *   withBuildAgent(body)
 *     → Allocates a node by CONFIG_NODE_LABEL and, when CONFIG_DOCKER_IMAGE is
 *       set, runs the body inside that container.  Falls back to a plain node
 *       when no docker image is configured.
 *
 *       Docker nodes  — uses docker.image().inside() (standard Jenkins DSL).
 *       Podman nodes  — uses `podman run` directly to avoid the un-overridable
 *                       -t flag that Jenkins' .inside() injects and that causes
 *                       rootless Podman to hang with no controlling TTY.
 *                       Stage scripts are dispatched via `podman exec`.
 *
 *   isPodmanNode()
 *     → Returns true when the Docker CLI on the current node is provided by
 *       Podman in Docker-emulation mode.  Must be called from within a node()
 *       block.
 *
 * Docker/Podman config env vars (set by ConfigHelper.generatePipelineConfig):
 *   CONFIG_DOCKER_IMAGE      — image name/tag to run (e.g. adoptopenjdk/centos7_build_image)
 *   CONFIG_DOCKER_REGISTRY   — registry URL for login (optional)
 *   CONFIG_DOCKER_CREDENTIAL — Jenkins credential ID for registry login (optional,
 *                              only used when CONFIG_DOCKER_REGISTRY is also set)
 *   CONFIG_DOCKER_ARGS       — extra arguments forwarded to `docker run` on Docker nodes
 *   CONFIG_PODMAN_ARGS       — extra arguments forwarded to `podman run` on Podman nodes,
 *                              used instead of CONFIG_DOCKER_ARGS when Podman is detected.
 *                              --userns keep-id and -u hostUid:hostGid are injected
 *                              automatically — do NOT add them here.
 *                              Use for extra flags only, e.g: "--security-opt label=disable"
 *
 * Podman auto-detection:
 *   When Podman is detected as the container runtime:
 *   - The `podman` binary is used directly (not the Docker shim) so Jenkins
 *     cannot inject the -t flag that causes rootless Podman to hang.
 *   - The container is started with -u <hostUid>:<hostGid> so it runs as the
 *     same uid as the Jenkins agent that owns the workspace bind-mount.
 *   - CONFIG_PODMAN_ARGS is used in place of CONFIG_DOCKER_ARGS (if set).
 *   - Unqualified image names (e.g. "adoptopenjdk/foo") are automatically
 *     prefixed with "docker.io/" — Podman does not resolve short names without
 *     an unqualified-search registry configured in registries.conf.
 *   - BUILD_PODMAN_CONTAINER_ID is set in the environment so StageScriptRunner
 *     dispatches shell scripts via `podman exec` into the running container.
 */

/**
 * Detect whether the Docker CLI on the current node is backed by Podman.
 * Must be called from within a node() block.
 *
 * 'docker --version' on a Podman shim prints e.g. "podman version 4.9.4".
 * On real Docker it prints "Docker version 24.0.5, build ...".
 * grep exit code 0 = match (Podman), 1 = no match (Docker).
 */
def isPodmanNode() {
    return sh(script: 'docker --version | grep -i podman', returnStatus: true) == 0
}

/**
 * Return true when the image name has no registry host prefix.
 *
 * Podman refuses to pull short names like "adoptopenjdk/centos7_build_image"
 * without an unqualified-search registry configured, whereas Docker implicitly
 * prepends "docker.io/".  A name is considered qualified when the portion
 * before the first '/' (or the whole name if there is no '/') contains a '.'
 * or ':', which are only valid in hostnames and host:port pairs respectively.
 *
 * Examples:
 *   "adoptopenjdk/centos7_build_image"           → unqualified (no host)
 *   "ubuntu"                                     → unqualified (no host, no slash)
 *   "docker.io/adoptopenjdk/centos7_build_image" → qualified
 *   "registry.example.com:5000/myimage"          → qualified
 */
def isUnqualifiedImageName(String image) {
    def prefix = image.contains('/') ? image.split('/')[0] : image
    return !prefix.contains('.') && !prefix.contains(':')
}

/**
 * Run body inside a Podman container using the `podman` binary directly.
 *
 * Jenkins' docker.image().inside() always injects -t (allocate pseudo-TTY)
 * at the Java level.  Rootless Podman has no daemon — it tries to open a TTY
 * directly on the calling process and hangs indefinitely when there is no
 * controlling terminal on a Jenkins agent.  There is no way to suppress -t
 * from inside dockerArgs because Jenkins appends our args after its own.
 *
 * By calling `podman run` directly via sh() we have full control of every flag.
 *
 * UID matching:
 *   Rootless Podman uses subuid/subgid mappings by default — the host Jenkins
 *   uid (e.g. 1001) maps to a high, unmapped uid inside the container's user
 *   namespace, so the bind-mounted workspace appears unowned inside the container
 *   and crun refuses to chdir into it.
 *
 *   Fix: --userns keep-id maps the calling process uid (1001 on host) to the
 *   same uid 1001 INSIDE the container, so the workspace bind-mount appears
 *   owned by the same uid the process runs as.  We also pass -u hostUid:hostGid
 *   to make the container process uid match explicitly (regardless of what
 *   /etc/passwd says in the image).
 *
 * Strategy:
 *   1. podman pull           — pull the image explicitly.
 *   2. detect host uid:gid   — id -u / id -g, runs as Jenkins agent user.
 *   3. podman run -d --rm    — start the container detached with:
 *                                --userns keep-id  (maps host uid→same uid inside)
 *                                -u hostUid:hostGid (run process as that uid)
 *                              podmanArgs from the config repo are appended.
 *                              Workspace is bind-mounted at the same absolute path.
 *   4. withEnv               — expose BUILD_PODMAN_CONTAINER_ID so StageScriptRunner
 *                              dispatches shell scripts via `podman exec bash -c 'cd ws && ...'`.
 *   5. finally               — podman stop cleans up the container on any exit.
 *
 * We do NOT use `podman run -t` or `podman exec -w`: crun resolves the -w path
 * at exec-setup time and fails with "getcwd: No such file or directory" when the
 * workspace was (re)created on the host after the container started.  StageScriptRunner
 * uses `bash -c 'cd <ws> && ...'` instead, which resolves the path at shell runtime.
 *
 * Must be called from within a node() block.
 */
def runInPodmanContainer(String image, String podmanArgs, Closure body) {
    def ws          = env.WORKSPACE
    def containerId = ''
    try {
        echo "Pulling image (podman): ${image}"
        sh "podman pull '${image}'"

        // --userns keep-id: maps the calling process uid (host jenkins uid, e.g. 1001)
        // to the same uid inside the container's user namespace.  Without this,
        // rootless Podman uses the default subuid mapping which puts the host uid
        // at a high unmapped uid inside the container — the bind-mounted workspace
        // then appears unowned and crun refuses to chdir into it.
        // -u hostUid:hostGid: run the container process as that same uid so it
        // matches the workspace owner regardless of what the image's /etc/passwd says.
        def hostUid = sh(script: 'id -u', returnStdout: true).trim()
        def hostGid = sh(script: 'id -g', returnStdout: true).trim()
        echo "Starting Podman container: ${image} (uid=${hostUid} gid=${hostGid})"
        containerId = sh(
            script: """podman run -d --rm \\
                         --userns keep-id \\
                         -u '${hostUid}:${hostGid}' \\
                         ${podmanArgs} \\
                         -v '${ws}:${ws}:rw,z' \\
                         -v '${ws}@tmp:${ws}@tmp:rw,z' \\
                         '${image}' \\
                         sleep infinity""",
            returnStdout: true
        ).trim()
        echo "Container started: ${containerId}"

        // Expose the container ID and workspace path so StageScriptRunner can
        // dispatch stage scripts inside the container via:
        //   podman exec <id> bash -c 'cd <ws> && bash <script>'
        // NOTE: we do NOT mkdir -p the workspace here — initializeStage() calls
        // cleanWs() which wipes and recreates the workspace after this point.
        // StageScriptRunner creates required directories on the host (as the
        // Jenkins agent uid that owns the workspace) before each exec.
        withEnv([
            "BUILD_PODMAN_CONTAINER_ID=${containerId}",
            "BUILD_PODMAN_WORKSPACE=${ws}"
        ]) {
            body()
        }
    } finally {
        if (containerId) {
            echo "Stopping container: ${containerId}"
            sh(script: "podman stop '${containerId}'", returnStatus: true)
        }
    }
}

/**
 * Execute body on the correct agent:
 *
 *   With CONFIG_DOCKER_IMAGE set:
 *     1. Allocate a node by CONFIG_NODE_LABEL.
 *     2. Detect Podman or Docker.
 *     3. On Podman: use runInPodmanContainer() with CONFIG_PODMAN_ARGS (or
 *        CONFIG_DOCKER_ARGS as fallback), qualifying unqualified image names.
 *        On Docker: pull then docker.image().inside() with CONFIG_DOCKER_ARGS.
 *     4. Registry login (docker.withRegistry) applied on both paths if
 *        CONFIG_DOCKER_REGISTRY + CONFIG_DOCKER_CREDENTIAL are set.
 *
 *   Without CONFIG_DOCKER_IMAGE:
 *     Allocate a node by CONFIG_NODE_LABEL and run body directly.
 */
def withBuildAgent(Closure body) {
    def nodeLabel   = env.CONFIG_NODE_LABEL?.trim() ?: 'worker'
    def dockerImage = env.CONFIG_DOCKER_IMAGE?.trim()

    node(nodeLabel) {
        if (dockerImage) {
            def registry   = env.CONFIG_DOCKER_REGISTRY?.trim()
            def credential = env.CONFIG_DOCKER_CREDENTIAL?.trim()
            def podman     = isPodmanNode()

            if (podman) {
                echo 'Container runtime: Podman'
                // Qualify unqualified image names — Podman won't guess the registry.
                if (!registry && isUnqualifiedImageName(dockerImage)) {
                    dockerImage = 'docker.io/' + dockerImage
                    echo "Resolved image to fully-qualified name: ${dockerImage}"
                }
            } else {
                echo 'Container runtime: Docker'
            }

            def resolvedImage = dockerImage

            if (podman) {
                // Use podmanArgs if defined, otherwise fall back to dockerArgs.
                def podmanArgs = env.CONFIG_PODMAN_ARGS?.trim() ?: env.CONFIG_DOCKER_ARGS?.trim() ?: ''
                echo "Container agent (podman): image='${resolvedImage}' args='${podmanArgs}'"
                // Registry login for podman via podman login if credentials are set.
                if (registry && credential) {
                    withCredentials([usernamePassword(credentialsId: credential,
                                                      usernameVariable: 'REG_USER',
                                                      passwordVariable: 'REG_PASS')]) {
                        sh "podman login '${registry}' -u '${REG_USER}' -p '${REG_PASS}'"
                    }
                }
                runInPodmanContainer(resolvedImage, podmanArgs, body)
            } else {
                def dockerArgs = env.CONFIG_DOCKER_ARGS?.trim() ?: ''
                echo "Container agent (docker): image='${resolvedImage}' args='${dockerArgs}'"
                def runInContainer = {
                    echo "Pulling image: ${resolvedImage}"
                    docker.image(resolvedImage).pull()
                    docker.image(resolvedImage).inside(dockerArgs) {
                        body()
                    }
                }
                if (registry && credential) {
                    docker.withRegistry(registry, credential) {
                        runInContainer()
                    }
                } else {
                    runInContainer()
                }
            }
        } else {
            body()
        }
    }
}

return this
