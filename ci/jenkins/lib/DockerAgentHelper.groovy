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
 *                              Set podmanArgs in the platform config for Podman-specific
 *                              flags, e.g: "--userns keep-id:uid=1002,gid=1003"
 *
 * Podman auto-detection:
 *   When Podman is detected as the container runtime:
 *   - The `podman` binary is used directly (not the Docker shim) so Jenkins
 *     cannot inject the -t flag that causes rootless Podman to hang.
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
 * Strategy:
 *   1. podman pull        — pull the image explicitly.
 *   2. podman run -d --rm — start the container detached with no -t.
 *                           podmanArgs from the config repo are passed here
 *                           (e.g. "--userns keep-id:uid=1002,gid=1003").
 *                           Workspace is bind-mounted at the same absolute path.
 *   3. withEnv            — expose BUILD_PODMAN_CONTAINER_ID so StageScriptRunner
 *                           dispatches shell scripts via `podman exec -w`.
 *   4. finally            — podman stop cleans up the container on any exit.
 *
 * Must be called from within a node() block.
 */
def runInPodmanContainer(String image, String podmanArgs, Closure body) {
    def ws          = env.WORKSPACE
    def containerId = ''
    try {
        echo "Pulling image (podman): ${image}"
        sh "podman pull '${image}'"

        echo "Starting Podman container: ${image}"
        containerId = sh(
            script: """podman run -d --rm \\
                         ${podmanArgs} \\
                         -v '${ws}:${ws}:rw,z' \\
                         -v '${ws}@tmp:${ws}@tmp:rw,z' \\
                         '${image}' \\
                         sleep infinity""",
            returnStdout: true
        ).trim()
        echo "Container started: ${containerId}"

        // Expose the container ID and workspace path so StageScriptRunner can
        // dispatch shell stage scripts via 'podman exec -w <ws> <id> bash ...'.
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
