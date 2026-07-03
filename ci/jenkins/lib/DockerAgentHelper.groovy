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
 *       set, runs the body inside that container using docker.image().inside().
 *       Falls back to a plain node when no docker image is configured.
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
 *   CONFIG_DOCKER_ARGS       — extra arguments forwarded to `docker run` on Docker nodes
 *   CONFIG_PODMAN_ARGS       — extra arguments forwarded to `docker run` on Podman nodes,
 *                              used instead of CONFIG_DOCKER_ARGS when Podman is detected.
 *                              Set podmanArgs in the platform config for Podman-specific
 *                              flags, e.g: "--userns keep-id:uid=1002,gid=1003"
 *
 * Podman auto-detection:
 *   When Podman is detected as the container runtime:
 *   - CONFIG_PODMAN_ARGS is used in place of CONFIG_DOCKER_ARGS (if set).
 *   - Unqualified image names (e.g. "adoptopenjdk/foo") are automatically
 *     prefixed with "docker.io/" — Podman does not resolve short names without
 *     an unqualified-search registry configured in registries.conf.
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
 * Execute body on the correct agent:
 *
 *   With CONFIG_DOCKER_IMAGE set:
 *     1. Allocate a node by CONFIG_NODE_LABEL.
 *     2. Detect Podman or Docker.
 *     3. On Podman: use CONFIG_PODMAN_ARGS if set (else fall back to CONFIG_DOCKER_ARGS),
 *        and qualify unqualified image names with docker.io/.
 *        On Docker: use CONFIG_DOCKER_ARGS.
 *     4. If CONFIG_DOCKER_REGISTRY + CONFIG_DOCKER_CREDENTIAL are both set,
 *        perform a docker.withRegistry() login before pulling/running the image.
 *     5. Pull the image explicitly so docker.image().inside()'s inspect check succeeds.
 *     6. Run body inside the container via docker.image().inside().
 *
 *   Without CONFIG_DOCKER_IMAGE:
 *     Allocate a node by CONFIG_NODE_LABEL and run body directly.
 */
def withBuildAgent(Closure body) {
    def nodeLabel   = env.CONFIG_NODE_LABEL?.trim() ?: 'worker'
    def dockerImage = env.CONFIG_DOCKER_IMAGE?.trim()

    node(nodeLabel) {
        if (dockerImage) {
            def registry    = env.CONFIG_DOCKER_REGISTRY?.trim()
            def credential  = env.CONFIG_DOCKER_CREDENTIAL?.trim()
            def podman      = isPodmanNode()

            // On Podman nodes use podmanArgs if defined, otherwise fall back to
            // dockerArgs.  This allows image-specific flags like
            // "--userns keep-id:uid=1002,gid=1003" to be set per-platform in the
            // config repo under the podmanArgs key without affecting Docker nodes.
            def runArgs = podman
                ? (env.CONFIG_PODMAN_ARGS?.trim() ?: env.CONFIG_DOCKER_ARGS?.trim() ?: '')
                : (env.CONFIG_DOCKER_ARGS?.trim() ?: '')

            if (podman) {
                echo 'Container runtime: Podman (Docker-emulation mode)'
                // Qualify unqualified image names — Podman won't guess the registry.
                if (!registry && isUnqualifiedImageName(dockerImage)) {
                    dockerImage = 'docker.io/' + dockerImage
                    echo "Resolved image to fully-qualified name: ${dockerImage}"
                }
            } else {
                echo 'Container runtime: Docker'
            }

            def logMsg = "Container agent: image='${dockerImage}' args='${runArgs}'"
            if (registry) { logMsg += " registry='${registry}'" }
            echo logMsg

            def resolvedImage = dockerImage
            def resolvedArgs  = runArgs

            def runInContainer = {
                echo "Pulling image: ${resolvedImage}"
                docker.image(resolvedImage).pull()
                docker.image(resolvedImage).inside(resolvedArgs) {
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
        } else {
            body()
        }
    }
}

return this
