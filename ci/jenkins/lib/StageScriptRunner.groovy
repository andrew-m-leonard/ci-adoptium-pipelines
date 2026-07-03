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

    // On Podman builds, podman exec -w fails with "crun: getcwd: No such file or
    // directory" regardless of whether the directory exists — a crun/userns
    // interaction issue with this Podman version.  bash -c quoting is also
    // unreliable across Jenkins' sh step shell layers.
    //
    // The only reliable approach: write a small wrapper script into the workspace
    // (which is bind-mounted at the same absolute path inside the container), then
    // run that file directly with podman exec.  No -w, no bash -c quoting needed.

    // Recreate the workspace path inside the container — initializeStage() calls
    // cleanWs() which wipes and recreates the workspace after the container starts,
    // invalidating any directory created at container startup.
    if (podmanId) {
        sh "podman exec '${podmanId}' mkdir -p '${podmanWs}'"
    }

    // Ensure TARGET_DIR exists inside the container before the script runs.
    if (env.TARGET_DIR) {
        if (podmanId) {
            def wrapperPath = "${podmanWs}/.podman-wrapper-${UUID.randomUUID()}.sh"
            writeFile file: wrapperPath, text: "#!/bin/bash\nexec mkdir -p '${env.TARGET_DIR}'\n"
            sh "chmod +x '${wrapperPath}'"
            sh "podman exec '${podmanId}' '${wrapperPath}'"
            sh "rm -f '${wrapperPath}'"
        } else {
            sh "mkdir -p ${env.TARGET_DIR}"
        }
    }

    switch (found.type) {
        case 'sh':
            // Write a wrapper into the workspace (bind-mounted at the same path
            // inside the container) that cds to the workspace then runs the script.
            // podman exec runs the wrapper file directly — no -w, no bash -c.
            if (podmanId) {
                def wrapperPath = "${podmanWs}/.podman-wrapper-${UUID.randomUUID()}.sh"
                writeFile file: wrapperPath, text: "#!/bin/bash\ncd '${podmanWs}'\nexec bash '${found.path}'\n"
                sh "chmod +x '${wrapperPath}'"
                def rc = sh(script: "podman exec '${podmanId}' '${wrapperPath}'", returnStatus: true)
                sh "rm -f '${wrapperPath}'"
                return rc
            }
            return sh(script: "bash ${found.path}", returnStatus: true)
        case 'groovy':
            // Groovy scripts execute in the Jenkins CPS engine on the host JVM —
            // they cannot be run inside a container via podman exec.
            // However, a Groovy script can issue its own podman exec calls using
            // the BUILD_PODMAN_CONTAINER_ID and BUILD_PODMAN_WORKSPACE env vars.
            // Write a wrapper script to BUILD_PODMAN_WORKSPACE and podman exec it.
            if (podmanId) {
                echo "⚠️  WARNING: Groovy stage script '${found.path}' is running on the host JVM " +
                     "while the build agent is a Podman container (BUILD_PODMAN_CONTAINER_ID=${podmanId}). " +
                     "Groovy scripts execute in the Jenkins CPS engine and cannot be dispatched automatically " +
                     "via podman exec. Any sh() calls inside this script will run on the host, not in the container. " +
                     "Options: " +
                     "(1) Convert to a .sh or .py script — these are dispatched into the container automatically. " +
                     "(2) Write a wrapper script to BUILD_PODMAN_WORKSPACE and exec it directly: " +
                     "writeFile(file: \"\${BUILD_PODMAN_WORKSPACE}/.wrapper.sh\", text: \"#!/bin/bash\\ncd '\${BUILD_PODMAN_WORKSPACE}'\\nyour-command\\n\"); " +
                     "sh(\"podman exec '\${BUILD_PODMAN_CONTAINER_ID}' '\${BUILD_PODMAN_WORKSPACE}/.wrapper.sh'\")"
            }
            def script = load(found.path)
            return script(config) ?: 0
        case 'py':
            // Dispatch python scripts the same way as shell scripts.
            if (podmanId) {
                def wrapperPath = "${podmanWs}/.podman-wrapper-${UUID.randomUUID()}.sh"
                writeFile file: wrapperPath, text: "#!/bin/bash\ncd '${podmanWs}'\nexec python3 '${found.path}'\n"
                sh "chmod +x '${wrapperPath}'"
                def rc = sh(script: "podman exec '${podmanId}' '${wrapperPath}'", returnStatus: true)
                sh "rm -f '${wrapperPath}'"
                return rc
            }
            return sh(script: "python3 ${found.path}", returnStatus: true)
    }
}

return this
