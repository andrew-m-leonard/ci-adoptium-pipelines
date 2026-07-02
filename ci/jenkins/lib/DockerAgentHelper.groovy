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
 *   When Podman is detected as the container runtime, '--userns=keep-id' is
 *   appended automatically to dockerArgs (unless already present).  This maps
 *   the host Jenkins UID to the same UID inside the container, preventing
 *   workspace ownership mismatches caused by Podman's rootless user-namespace
 *   remapping.  No config-repo change is required to support Podman nodes.
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
 * Execute body on the correct agent:
 *
 *   With CONFIG_DOCKER_IMAGE set:
 *     1. Allocate a node by CONFIG_NODE_LABEL.
 *     2. Auto-detect Podman: if the Docker CLI shim is Podman, append
 *        '--userns=keep-id' to dockerArgs so the Jenkins UID is preserved
 *        inside the container.
 *     3. If CONFIG_DOCKER_REGISTRY + CONFIG_DOCKER_CREDENTIAL are both set,
 *        perform a docker.withRegistry() login before pulling/running the image.
 *     4. Pull the image explicitly so it is present before .inside() runs its
 *        'docker inspect' check.
 *     5. Run body inside the container, passing the resolved dockerArgs.
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

            // Auto-detect Podman and apply Podman-specific adjustments:
            //   --userns=keep-id  — maps the host Jenkins UID to the same UID
            //                       inside the container, preventing workspace
            //                       ownership mismatches from rootless remapping.
            //   docker.io/ prefix — Podman does not resolve unqualified short names
            //                       (e.g. "adoptopenjdk/foo") without an
            //                       unqualified-search registry in registries.conf.
            //                       When no explicit CONFIG_DOCKER_REGISTRY is set
            //                       we prepend "docker.io/" so Podman knows where
            //                       to pull from, matching Docker's implicit behaviour.
            if (isPodmanNode()) {
                echo 'Container runtime: Podman (Docker-emulation mode)'
                if (!dockerArgs.contains('--userns')) {
                    dockerArgs = (dockerArgs + ' --userns=keep-id').trim()
                }
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

            // Pull the image explicitly before calling .inside().
            // Jenkins' .inside() runs 'docker inspect' first to verify the image
            // is present locally — if it isn't, inspect fails rather than pulling.
            // Pulling here (inside withRegistry when credentials are set) ensures
            // the image is cached and the inspect check succeeds.
            def runInContainer = {
                echo "Pulling image: ${dockerImage}"
                docker.image(dockerImage).pull()
                docker.image(dockerImage).inside(dockerArgs) {
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
