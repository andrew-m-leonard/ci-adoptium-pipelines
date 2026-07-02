/**
 * DockerAgentHelper — runtime agent selection for build stages.
 *
 * Loaded with:
 *   def dockerAgentHelper = load('ci/jenkins/lib/DockerAgentHelper.groovy')
 *
 * This file is a CpsScript. All pipeline steps (echo, node, docker, etc.) are
 * called directly — no 'steps.' prefix.
 *
 * Public API:
 *   withBuildAgent(body)
 *     → Allocates a node by CONFIG_NODE_LABEL and, when CONFIG_DOCKER_IMAGE is
 *       set, runs the body inside that container.  Falls back to a plain node
 *       when no docker image is configured.
 *
 *   isPodmanNode()
 *     → Returns true when the Docker CLI on the current node is provided by
 *       Podman in Docker-emulation mode.  Must be called from within a node()
 *       block.
 *
 * Docker config env vars (set by ConfigHelper.generatePipelineConfig):
 *   CONFIG_DOCKER_IMAGE      — image name/tag to run (e.g. adoptopenjdk/centos7_build_image)
 *   CONFIG_DOCKER_REGISTRY   — registry URL for docker.withRegistry() login (optional)
 *   CONFIG_DOCKER_CREDENTIAL — Jenkins credential ID for registry login (optional,
 *                              only used when CONFIG_DOCKER_REGISTRY is also set)
 *   CONFIG_DOCKER_ARGS       — extra arguments forwarded to `docker run`
 *                              (e.g. '--volume /home/jenkins:/home/jenkins')
 *
 * Podman auto-detection:
 *   When Podman is detected as the container runtime the following adjustments
 *   are applied automatically (no config-repo change required):
 *
 *   --userns=keep-id  Maps the host Jenkins UID to the same UID inside the
 *                     container, preventing workspace ownership mismatches
 *                     caused by Podman's rootless user-namespace remapping.
 *
 *   docker.io/ prefix Podman does not resolve unqualified short names without
 *                     an unqualified-search registry in registries.conf.
 *                     docker.io/ is prepended automatically to match Docker's
 *                     implicit behaviour.
 *
 *   Podman run path   Jenkins' docker.image().inside() always injects -t
 *                     (allocate pseudo-TTY) at the Java level before our args
 *                     are appended.  Rootless Podman has no daemon and hangs
 *                     indefinitely trying to open a TTY on a Jenkins agent
 *                     process — --tty=false in dockerArgs cannot override this
 *                     because Podman's Docker shim gives -t precedence.
 *                     On Podman, .inside() is bypassed entirely: the container
 *                     is started with `docker run -d` (no -t), the body runs
 *                     via `docker exec`, and the container is stopped on exit.
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
 *   "adoptopenjdk/centos7_build_image"        → unqualified (no host)
 *   "ubuntu"                                  → unqualified (no host, no slash)
 *   "docker.io/adoptopenjdk/centos7_build_image" → qualified
 *   "registry.example.com:5000/myimage"       → qualified
 */
def isUnqualifiedImageName(String image) {
    def prefix = image.contains('/') ? image.split('/')[0] : image
    return !prefix.contains('.') && !prefix.contains(':')
}

/**
 * Run the body closure inside a Podman container without using
 * docker.image().inside(), which injects an un-overridable -t flag that
 * causes rootless Podman to hang waiting for a TTY.
 *
 * Strategy:
 *   1. docker run -d  — start the container detached (no -t, no cat keepalive)
 *   2. withEnv        — set DOCKER_CONTAINER_ID so every sh() step inside body
 *                       can be wrapped; but since body is arbitrary Groovy we
 *                       instead use a DOCKER_EXEC_PREFIX env var that stage
 *                       scripts can honour, AND we override the Jenkins sh step
 *                       by setting BUILD_IN_DOCKER_CONTAINER_ID so the
 *                       StageScriptRunner prefixes bash calls with docker exec.
 *   3. docker stop    — stop the container on exit (success or failure).
 *
 * The workspace is bind-mounted read-write at the same path so all file
 * operations inside the container land in the Jenkins workspace on the host.
 */
def runInPodmanContainer(String dockerImage, String dockerArgs, Closure body) {
    def ws         = env.WORKSPACE
    def containerId = ''
    try {
        echo "Starting Podman container: ${dockerImage}"
        // -d: detached  --rm: auto-remove on stop  --userns=keep-id: UID preservation
        // Workspace mounted at same absolute path so paths match inside and outside.
        containerId = sh(
            script: """docker run -d --rm \\
                         ${dockerArgs} \\
                         -w '${ws}' \\
                         -v '${ws}:${ws}:rw,z' \\
                         -v '${ws}@tmp:${ws}@tmp:rw,z' \\
                         '${dockerImage}' \\
                         sleep infinity""",
            returnStdout: true
        ).trim()
        echo "Container started: ${containerId}"

        // Expose the container ID so StageScriptRunner can prefix sh calls with
        // 'docker exec <id> bash ...' for shell-type stage scripts.
        withEnv(["BUILD_DOCKER_CONTAINER_ID=${containerId}"]) {
            body()
        }
    } finally {
        if (containerId) {
            echo "Stopping container: ${containerId}"
            sh(script: "docker stop ${containerId}", returnStatus: true)
        }
    }
}

/**
 * Execute body on the correct agent:
 *
 *   With CONFIG_DOCKER_IMAGE set:
 *     1. Allocate a node by CONFIG_NODE_LABEL.
 *     2. Auto-detect Podman or Docker.
 *     3. Apply Podman-specific adjustments (--userns=keep-id, docker.io/ prefix).
 *     4. If CONFIG_DOCKER_REGISTRY + CONFIG_DOCKER_CREDENTIAL are both set,
 *        perform a registry login before pulling/running the image.
 *     5. Pull the image explicitly.
 *     6a. Docker: use docker.image().inside() — the standard Jenkins DSL path.
 *     6b. Podman: use runInPodmanContainer() — bypasses .inside() to avoid the
 *         un-overridable -t flag that causes rootless Podman to hang.
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
            def dockerArgs = env.CONFIG_DOCKER_ARGS?.trim() ?: ''
            def podman     = isPodmanNode()

            if (podman) {
                echo 'Container runtime: Podman (Docker-emulation mode)'
                // Preserve Jenkins UID inside the container (rootless remapping fix).
                if (!dockerArgs.contains('--userns')) {
                    dockerArgs = (dockerArgs + ' --userns=keep-id').trim()
                }
                // Qualify unqualified image names — Podman won't guess the registry.
                if (!registry && isUnqualifiedImageName(dockerImage)) {
                    dockerImage = 'docker.io/' + dockerImage
                    echo "Resolved image to fully-qualified name: ${dockerImage}"
                }
            } else {
                echo 'Container runtime: Docker'
            }

            def logMsg = "Container agent: image='${dockerImage}'"
            if (registry)   { logMsg += " registry='${registry}'" }
            if (dockerArgs) { logMsg += " args='${dockerArgs}'" }
            echo logMsg

            // Capture locals for use inside the closures below.
            def resolvedImage = dockerImage
            def resolvedArgs  = dockerArgs

            def runInContainer = {
                echo "Pulling image: ${resolvedImage}"
                docker.image(resolvedImage).pull()
                if (podman) {
                    // Bypass .inside() — it injects an un-overridable -t flag
                    // that causes rootless Podman to hang with no TTY.
                    runInPodmanContainer(resolvedImage, resolvedArgs, body)
                } else {
                    docker.image(resolvedImage).inside(resolvedArgs) {
                        body()
                    }
                }
            }

            if (registry && credential) {
                docker.withRegistry(registry, credential) {
                    runInContainer()
                }
            } else {
                runInContainer()
            }
        } else {
            body()
        }
    }
}

return this
