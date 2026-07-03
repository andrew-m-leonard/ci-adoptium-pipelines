/**
 * StageScriptRunner — vendor-overridable stage script resolution and execution.
 *
 * Loaded with:
 *   def stageRunner = load('ci/jenkins/lib/StageScriptRunner.groovy')
 *
 * This file is a CpsScript. All pipeline steps (echo, sh, env, load, fileExists,
 * etc.) are called directly — no 'steps.' prefix, no init(this) delegation.
 *
 * Resolution order for run(stem):
 *   1. config-repo/vendor-scripts/<stem>.sh     ← vendor override (shell)
 *   2. config-repo/vendor-scripts/<stem>.groovy ← vendor override (Groovy)
 *   3. config-repo/vendor-scripts/<stem>.py     ← vendor override (Python)
 *   4. scripts/stages/<stem>.sh                 ← default (shell)
 *   5. scripts/stages/<stem>.groovy             ← default (Groovy)
 *   6. scripts/stages/<stem>.py                 ← default (Python)
 *   7. no-op → returns 0
 *
 * .groovy scripts receive the config Map as their call() argument.
 * .sh and .py scripts receive config via environment variables set by initializeStage().
 *
 * Public API:
 *   run(scriptStem, config=null) → int exit code (0 = success)
 */

/**
 * Resolve and execute a stage script, returning an exit code.
 *
 * @param scriptStem  Script stem e.g. '13-smoke-tests'
 * @param config      Pipeline config map — forwarded to .groovy scripts as the call() argument.
 *                    .sh and .py scripts receive config via environment variables.
 * @return int exit code — 0 = success, non-zero = failure.
 */
def run(String scriptStem, def config = null) {
    def candidates = [
        [path: "config-repo/vendor-scripts/${scriptStem}.sh",     type: 'sh'],
        [path: "config-repo/vendor-scripts/${scriptStem}.groovy", type: 'groovy'],
        [path: "config-repo/vendor-scripts/${scriptStem}.py",     type: 'py'],
        [path: "scripts/stages/${scriptStem}.sh",                 type: 'sh'],
        [path: "scripts/stages/${scriptStem}.groovy",             type: 'groovy'],
        [path: "scripts/stages/${scriptStem}.py",                 type: 'py'],
    ]

    def found = candidates.find { fileExists(it.path) }

    if (!found) {
        echo "ℹ️  No script found for '${scriptStem}' — stage is a no-op"
        return 0
    }

    echo "▶ Running ${found.type.toUpperCase()} stage script: ${found.path}"

    def podmanId = env.BUILD_PODMAN_CONTAINER_ID?.trim()
    def podmanWs = env.BUILD_PODMAN_WORKSPACE?.trim()

    // Ensure TARGET_DIR exists before the script runs (if set by the stage).
    // On Podman builds this runs inside the container via podman exec so the
    // directory exists in the containerised view of the workspace.
    if (env.TARGET_DIR) {
        if (podmanId) {
            sh "podman exec -w '${podmanWs}' '${podmanId}' mkdir -p '${env.TARGET_DIR}'"
        } else {
            sh "mkdir -p ${env.TARGET_DIR}"
        }
    }

    switch (found.type) {
        case 'sh':
            // On Podman builds dispatch via podman exec so the script runs inside
            // the container started by runInPodmanContainer().  -w sets the working
            // directory so WORKSPACE-relative paths in the script resolve correctly.
            if (podmanId) {
                return sh(script: "podman exec -w '${podmanWs}' '${podmanId}' bash '${found.path}'", returnStatus: true)
            }
            return sh(script: "bash ${found.path}", returnStatus: true)
        case 'groovy':
            // Groovy scripts execute in the Jenkins CPS engine on the host JVM —
            // they cannot be run inside a container via podman exec.
            // However, a Groovy script can issue its own podman exec calls using
            // the BUILD_PODMAN_CONTAINER_ID and BUILD_PODMAN_WORKSPACE env vars.
            if (podmanId) {
                echo "⚠️  WARNING: Groovy stage script '${found.path}' is running on the host JVM " +
                     "while the build agent is a Podman container (BUILD_PODMAN_CONTAINER_ID=${podmanId}). " +
                     "Groovy scripts execute in the Jenkins CPS engine and cannot be dispatched automatically " +
                     "via podman exec. Any sh() calls inside this script will run on the host, not in the container. " +
                     "Options: " +
                     "(1) Convert to a .sh or .py script — these are dispatched into the container automatically. " +
                     "(2) Issue podman exec calls directly from within the Groovy script using the available " +
                     "environment variables: BUILD_PODMAN_CONTAINER_ID='${podmanId}' and " +
                     "BUILD_PODMAN_WORKSPACE='${podmanWs}'. " +
                     "Example: sh(\"podman exec -w '\${BUILD_PODMAN_WORKSPACE}' '\${BUILD_PODMAN_CONTAINER_ID}' bash -c 'your-command'\")"
            }
            def script = load(found.path)
            return script(config) ?: 0
        case 'py':
            // Python scripts are dispatched via podman exec on Podman builds,
            // same as shell scripts, so they run inside the container environment.
            if (podmanId) {
                return sh(script: "podman exec -w '${podmanWs}' '${podmanId}' python3 '${found.path}'", returnStatus: true)
            }
            return sh(script: "python3 ${found.path}", returnStatus: true)
    }
}

return this
