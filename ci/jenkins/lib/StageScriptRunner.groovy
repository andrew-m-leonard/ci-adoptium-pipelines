/**
 * StageScriptRunner — vendor-overridable stage script resolution and execution.
 *
 * Resolution order:
 *   1. config-repo/vendor-scripts/<stem>.sh
 *   2. config-repo/vendor-scripts/<stem>.groovy
 *   3. config-repo/vendor-scripts/<stem>.py
 *   4. scripts/stages/<stem>.sh
 *   5. scripts/stages/<stem>.groovy
 *   6. scripts/stages/<stem>.py
 *   7. built-in no-op (returns 0)
 *
 * Usage in Jenkinsfile:
 *   def stageRunner = load('ci/jenkins/lib/StageScriptRunner.groovy')
 *   def exitCode = stageRunner.run('13-smoke-tests', config)
 *
 * Note: This file is a CpsScript itself — pipeline steps (echo, sh, env, load,
 * fileExists, etc.) are called directly without any delegation wrapper.
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

    // Ensure TARGET_DIR exists before the script runs (if set by the stage)
    if (env.TARGET_DIR) {
        sh "mkdir -p ${env.TARGET_DIR}"
    }

    switch (found.type) {
        case 'sh':
            return sh(script: "bash ${found.path}", returnStatus: true)
        case 'groovy':
            def script = load(found.path)
            return script(config) ?: 0
        case 'py':
            return sh(script: "python3 ${found.path}", returnStatus: true)
    }
}

return this
