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
 *   def stageRunner = load('ci/jenkins/lib/StageScriptRunner.groovy').init(this)
 *   def exitCode = stageRunner.run('13-smoke-tests', config)
 */

def steps  // Pipeline steps context (the Jenkinsfile's 'this')

def init(pipelineSteps) {
    this.steps = pipelineSteps
    return this
}

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

    def found = candidates.find { steps.fileExists(it.path) }

    if (!found) {
        steps.echo "ℹ️  No script found for '${scriptStem}' — stage is a no-op"
        return 0
    }

    steps.echo "▶ Running ${found.type.toUpperCase()} stage script: ${found.path}"

    // Ensure TARGET_DIR exists before the script runs (if set by the stage)
    if (steps.env.TARGET_DIR) {
        steps.sh "mkdir -p ${steps.env.TARGET_DIR}"
    }

    switch (found.type) {
        case 'sh':
            return steps.sh(script: "bash ${found.path}", returnStatus: true)
        case 'groovy':
            def script = steps.load(found.path)
            return script(config) ?: 0
        case 'py':
            return steps.sh(script: "python3 ${found.path}", returnStatus: true)
    }
}

return this
