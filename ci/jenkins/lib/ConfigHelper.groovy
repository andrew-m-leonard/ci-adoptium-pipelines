/**
 * ConfigHelper — pipeline configuration generation and display.
 *
 * Provides: generatePipelineConfig(), summarizePipelineConfig()
 *
 * Usage in Jenkinsfile:
 *   def configHelper = load('ci/jenkins/lib/ConfigHelper.groovy').init(this)
 *   def config = configHelper.generatePipelineConfig('./config-repo/configurations')
 */

def steps  // Pipeline steps context (the Jenkinsfile's 'this')

def init(pipelineSteps) {
    this.steps = pipelineSteps
    return this
}

/**
 * Generate pipeline-config.json from parameters and adoptium_pipeline_config.json.
 *
 * Resolves BUILD_REF/AQA_REF from job params (if non-empty) or config repo defaults.
 * Sets environment variables for use in when{} blocks.
 *
 * @param configDir  Path to the configurations directory (e.g. './config-repo/configurations')
 * @return parsed pipeline-config.json as a Map
 */
def generatePipelineConfig(String configDir = './configurations') {

    // Validate parameters
    if (steps.params.RUN_REPRODUCIBLE_COMPARE && (!steps.params.SCM_REF || steps.params.SCM_REF.trim().isEmpty())) {
        steps.error("RUN_REPRODUCIBLE_COMPARE requires SCM_REF to be specified. Please provide a valid SCM reference (e.g., jdk-21.0.12+6_adopt)")
    }

    // Load adoptium_pipeline_config.json for vendor defaults
    def adoptiumConfigFile = './config-repo/adoptium_pipeline_config.json'
    if (!steps.fileExists(adoptiumConfigFile)) {
        steps.error("adoptium_pipeline_config.json not found in config repo — this file is required")
    }
    def adoptiumConfig = steps.readJSON(file: adoptiumConfigFile)
    steps.echo "✓ Loaded adoptium_pipeline_config.json"
    def repoDefaults = adoptiumConfig.repository ?: [:]

    // Resolve BUILD_REF and AQA_REF: use job param if non-empty, otherwise config repo default
    def buildRef = steps.params.BUILD_REF?.trim() ?: repoDefaults.buildBranch
    def aqaRef = steps.params.AQA_REF?.trim() ?: repoDefaults.aqaBranch
    def buildRepoUrl = repoDefaults.buildRepoUrl
    def aqaRepoUrl = repoDefaults.aqaRepoUrl

    // Validate — these must be resolved from one source or the other
    if (!buildRef) {
        steps.error("BUILD_REF is empty and repository.buildBranch is missing from adoptium_pipeline_config.json")
    }
    if (!aqaRef) {
        steps.error("AQA_REF is empty and repository.aqaBranch is missing from adoptium_pipeline_config.json")
    }
    if (!buildRepoUrl) {
        steps.error("repository.buildRepoUrl is missing from adoptium_pipeline_config.json")
    }
    if (!aqaRepoUrl) {
        steps.error("repository.aqaRepoUrl is missing from adoptium_pipeline_config.json")
    }

    steps.echo "  Build Ref     : ${buildRef}"
    steps.echo "  AQA Ref       : ${aqaRef}"
    steps.echo "  Build Repo URL: ${buildRepoUrl}"
    steps.echo "  AQA Repo URL  : ${aqaRepoUrl}"

    // Build Python command arguments
    def pythonArgs = [
        "--jdk-version jdk${steps.params.JDK_VERSION}",
        "--variant ${steps.params.VARIANT}",
        "--target-os ${steps.params.TARGET_OS}",
        "--architecture ${steps.params.ARCHITECTURE}",
        "--config-dir ${configDir}",
        "--output-dir ."
    ]

    // Optional parameters
    if (steps.params.RELEASE_TYPE) {
        def releaseType = steps.params.RELEASE_TYPE.toUpperCase()
        def validReleaseTypes = ['NIGHTLY', 'WEEKLY', 'RELEASE']
        if (!validReleaseTypes.contains(releaseType)) {
            steps.error("Invalid RELEASE_TYPE: '${steps.params.RELEASE_TYPE}'. Must be one of: ${validReleaseTypes.join(', ')} (case-insensitive)")
        }
        pythonArgs.add("--release-type")
        pythonArgs.add(releaseType)
    }
    if (steps.params.SCM_REF) {
        pythonArgs.add("--scm-ref ${steps.params.SCM_REF}")
    }
    // Always pass resolved refs and repo URLs (required by load-json-config.py)
    pythonArgs.add("--build-ref ${buildRef}")
    pythonArgs.add("--aqa-ref ${aqaRef}")
    pythonArgs.add("--build-repo-url ${buildRepoUrl}")
    pythonArgs.add("--aqa-repo-url ${aqaRepoUrl}")
    if (!steps.params.RUN_TESTS) {
        pythonArgs.add("--no-tests")
    }
    if (!steps.params.ENABLE_INSTALLERS) {
        pythonArgs.add("--no-installers")
    }
    if (!steps.params.SIGN_ARTIFACTS) {
        pythonArgs.add("--no-signer")
    }
    if (steps.params.RELEASE_TYPE?.toUpperCase() == 'WEEKLY') {
        pythonArgs.add("--ea-beta-build")
    }

    // Execute Python script to generate configuration
    steps.sh "python3 scripts/lib/load-json-config.py ${pythonArgs.join(' ')}"

    // Read and return the generated configuration
    def config = steps.readJSON(file: 'pipeline-config.json')

    // Set TCK enablement from parameter
    config.parameters.enableTCK = steps.params.ENABLE_TCK

    // Update pipeline-config.json with TCK setting
    steps.writeJSON(file: 'pipeline-config.json', json: config, pretty: 4)

    // Set environment variables for use in when{} blocks
    steps.env.CONFIG_VARIANT = config.buildConfig.VARIANT
    steps.env.CONFIG_TARGET_OS = config.buildConfig.TARGET_OS
    steps.env.CONFIG_ARCHITECTURE = config.buildConfig.ARCHITECTURE
    steps.env.CONFIG_JAVA_TO_BUILD = config.buildConfig.JAVA_TO_BUILD
    steps.env.CONFIG_NODE_LABEL = config.buildConfig.NODE_LABEL ?: 'worker'
    steps.env.CONFIG_BUILD_ARGS = config.buildConfig.BUILD_ARGS ?: ''
    steps.env.CONFIG_RUN_TESTS = config.parameters.enableTests.toString()
    steps.env.CONFIG_ENABLE_INSTALLERS = config.parameters.enableInstallers.toString()
    steps.env.CONFIG_SIGN_ARTIFACTS = config.parameters.enableSigner.toString()
    steps.env.CONFIG_ENABLE_TCK = config.parameters.enableTCK.toString()
    steps.env.CONFIG_PUBLISH_ARTIFACTS = steps.params.PUBLISH_ARTIFACTS.toString()
    steps.env.AQA_REF = config.refs.aqaRef
    steps.env.SMOKE_TESTS_PASSED = 'false'

    return config
}

/**
 * Display key configuration values in a consistent format.
 */
def summarizePipelineConfig(config) {
    steps.echo "Build Configuration:"
    steps.echo "  JDK Version: ${config.buildConfig.JAVA_TO_BUILD}"
    steps.echo "  Variant: ${config.buildConfig.VARIANT}"
    steps.echo "  OS: ${config.buildConfig.TARGET_OS}"
    steps.echo "  Architecture: ${config.buildConfig.ARCHITECTURE}"
    steps.echo "  Node Label: ${config.buildConfig.NODE_LABEL}"
    steps.echo "  Build Args: ${config.buildConfig.BUILD_ARGS}"
    steps.echo "  Tests: ${config.buildConfig.TEST_LIST}"
}

return this
