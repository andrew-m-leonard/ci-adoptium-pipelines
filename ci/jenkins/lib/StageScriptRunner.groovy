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

    def containerId = env.BUILD_DOCKER_CONTAINER_ID?.trim()
    def containerWs = env.BUILD_DOCKER_WORKSPACE?.trim()

    // Ensure TARGET_DIR exists before the script runs (if set by the stage).
    // On Podman builds this must run inside the container so the directory
    // exists within the containerised filesystem view of the workspace.
    if (env.TARGET_DIR) {
        if (containerId) {
            sh "docker exec -w '${containerWs}' ${containerId} mkdir -p '${env.TARGET_DIR}'"
        } else {
            sh "mkdir -p ${env.TARGET_DIR}"
        }
    }

    switch (found.type) {
        case 'sh':
            // If running inside a Podman container started by DockerAgentHelper,
            // execute the script inside that container via docker exec.
            // -w sets the working directory to the workspace so relative paths
            // and WORKSPACE env var usage inside the script resolve correctly.
            // BUILD_DOCKER_CONTAINER_ID is empty on Docker/.inside() builds.
            if (containerId) {
                return sh(script: "docker exec -w '${containerWs}' ${containerId} bash '${found.path}'", returnStatus: true)
            }
            return sh(script: "bash ${found.path}", returnStatus: true)
        case 'groovy':
            def script = load(found.path)
            return script(config) ?: 0
        case 'py':
            return sh(script: "python3 ${found.path}", returnStatus: true)
    }
}

return this
