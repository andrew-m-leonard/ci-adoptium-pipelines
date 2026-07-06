/**
 * ConfigHelper — pipeline configuration generation and display.
 *
 * Loaded with:
 *   def configHelper = load('ci/jenkins/lib/ConfigHelper.groovy')
 *
 * This file is a CpsScript. All pipeline steps (echo, sh, env, params, readJSON,
 * writeJSON, fileExists, error, etc.) are called directly — no 'steps.' prefix.
 *
 * Public API:
 *   generatePipelineConfig(configDir='./configurations')
 *     → calls scripts/lib/load-json-config.py to produce pipeline-config.json;
 *       sets CONFIG_VARIANT, CONFIG_TARGET_OS, CONFIG_ARCHITECTURE, CONFIG_JAVA_TO_BUILD,
 *       CONFIG_NODE_LABEL, CONFIG_BUILD_ARGS, CONFIG_RUN_TESTS, CONFIG_ENABLE_INSTALLERS,
 *       CONFIG_SIGN_ARTIFACTS, CONFIG_ENABLE_TCK, CONFIG_PUBLISH_ARTIFACTS, AQA_REF,
 *       SMOKE_TESTS_PASSED env vars; returns the parsed config Map
 *
 *   summarizePipelineConfig(config)
 *     → logs key build configuration values
 */

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
    if (params.RUN_REPRODUCIBLE_COMPARE && (!params.SCM_REF || params.SCM_REF.trim().isEmpty())) {
        error("RUN_REPRODUCIBLE_COMPARE requires SCM_REF to be specified. Please provide a valid SCM reference (e.g., jdk-21.0.12+6_adopt)")
    }

    // Load adoptium_pipeline_config.json for vendor defaults
    def adoptiumConfigFile = './config-repo/adoptium_pipeline_config.json'
    if (!fileExists(adoptiumConfigFile)) {
        error("adoptium_pipeline_config.json not found in config repo — this file is required")
    }
    def adoptiumConfig = readJSON(file: adoptiumConfigFile)
    echo "✓ Loaded adoptium_pipeline_config.json"
    def repoDefaults = adoptiumConfig.repository ?: [:]

    // Resolve BUILD_REF and AQA_REF: use job param if non-empty, otherwise config repo default
    def buildRef    = params.BUILD_REF?.trim() ?: repoDefaults.buildBranch
    def aqaRef      = params.AQA_REF?.trim()   ?: repoDefaults.aqaBranch
    def buildRepoUrl = repoDefaults.buildRepoUrl
    def aqaRepoUrl   = repoDefaults.aqaRepoUrl

    // Validate — these must be resolved from one source or the other
    if (!buildRef) {
        error("BUILD_REF is empty and repository.buildBranch is missing from adoptium_pipeline_config.json")
    }
    if (!aqaRef) {
        error("AQA_REF is empty and repository.aqaBranch is missing from adoptium_pipeline_config.json")
    }
    if (!buildRepoUrl) {
        error("repository.buildRepoUrl is missing from adoptium_pipeline_config.json")
    }
    if (!aqaRepoUrl) {
        error("repository.aqaRepoUrl is missing from adoptium_pipeline_config.json")
    }

    echo "  Build Ref     : ${buildRef}"
    echo "  AQA Ref       : ${aqaRef}"
    echo "  Build Repo URL: ${buildRepoUrl}"
    echo "  AQA Repo URL  : ${aqaRepoUrl}"

    // Build Python command arguments
    def pythonArgs = [
        "--jdk-version jdk${params.JDK_VERSION}",
        "--variant ${params.VARIANT}",
        "--target-os ${params.TARGET_OS}",
        "--architecture ${params.ARCHITECTURE}",
        "--config-dir ${configDir}",
        "--output-dir ."
    ]

    // Optional parameters
    if (params.RELEASE_TYPE) {
        def releaseType = params.RELEASE_TYPE.toUpperCase()
        def validReleaseTypes = ['NIGHTLY', 'WEEKLY', 'RELEASE']
        if (!validReleaseTypes.contains(releaseType)) {
            error("Invalid RELEASE_TYPE: '${params.RELEASE_TYPE}'. Must be one of: ${validReleaseTypes.join(', ')} (case-insensitive)")
        }
        pythonArgs.add("--release-type")
        pythonArgs.add(releaseType)
    }
    if (params.SCM_REF) {
        pythonArgs.add("--scm-ref ${params.SCM_REF}")
    }
    // Always pass resolved refs and repo URLs (required by load-json-config.py)
    pythonArgs.add("--build-ref ${buildRef}")
    pythonArgs.add("--aqa-ref ${aqaRef}")
    pythonArgs.add("--build-repo-url ${buildRepoUrl}")
    pythonArgs.add("--aqa-repo-url ${aqaRepoUrl}")
    if (!params.RUN_TESTS) {
        pythonArgs.add("--no-tests")
    }
    if (!params.ENABLE_INSTALLERS) {
        pythonArgs.add("--no-installers")
    }
    if (!params.SIGN_ARTIFACTS) {
        pythonArgs.add("--no-signer")
    }
    if (params.RELEASE_TYPE?.toUpperCase() == 'WEEKLY') {
        pythonArgs.add("--ea-beta-build")
    }

    // Pass jenkins_job_config.json so stageAgentLabels flow into pipeline-config.json
    def jobConfigFile = './config-repo/jenkins_job_config.json'
    if (fileExists(jobConfigFile)) {
        pythonArgs.add("--job-config ${jobConfigFile}")
    }

    // Execute Python script to generate configuration
    sh "python3 scripts/lib/load-json-config.py ${pythonArgs.join(' ')}"

    // Read and return the generated configuration
    def config = readJSON(file: 'pipeline-config.json')

    // Set TCK enablement from parameter
    config.parameters.enableTCK = params.ENABLE_TCK

    // Update pipeline-config.json with TCK setting
    writeJSON(file: 'pipeline-config.json', json: config, pretty: 4)

    // Set environment variables for use in when{} blocks
    env.CONFIG_VARIANT           = config.buildConfig.VARIANT
    env.CONFIG_TARGET_OS         = config.buildConfig.TARGET_OS
    env.CONFIG_ARCHITECTURE      = config.buildConfig.ARCHITECTURE
    env.CONFIG_JAVA_TO_BUILD     = config.buildConfig.JAVA_TO_BUILD
    env.CONFIG_NODE_LABEL        = config.buildConfig.NODE_LABEL ?: 'worker'
    env.CONFIG_BUILD_ARGS        = config.buildConfig.BUILD_ARGS ?: ''
    env.CONFIG_CONFIGURE_ARGS    = config.buildConfig.CONFIGURE_ARGS ?: ''
    env.CONFIG_BUILD_REF         = config.refs.buildRef ?: 'master'
    env.CONFIG_BUILD_REPO_URL    = config.refs.buildRepoUrl ?: ''
    env.CONFIG_CLEAN_WORKSPACE   = config.parameters.cleanWorkspaceAfterStage?.toString() ?: 'false'
    env.CONFIG_EA_BETA_BUILD     = config.parameters.eaBetaBuild?.toString() ?: 'false'
    env.CONFIG_COMPARE_BUILD     = config.parameters.compareBuild?.toString() ?: 'false'
    env.CONFIG_DOCKER_IMAGE      = config.buildConfig.DOCKER_IMAGE ?: ''
    env.CONFIG_DOCKER_REGISTRY   = config.buildConfig.DOCKER_REGISTRY ?: ''
    env.CONFIG_DOCKER_CREDENTIAL = config.buildConfig.DOCKER_CREDENTIAL ?: ''
    env.CONFIG_DOCKER_ARGS       = config.buildConfig.DOCKER_ARGS ?: ''
    env.CONFIG_PODMAN_ARGS       = config.buildConfig.PODMAN_ARGS ?: ''
    env.CONFIG_RUN_TESTS         = config.parameters.enableTests.toString()
    env.CONFIG_ENABLE_INSTALLERS = config.parameters.enableInstallers.toString()
    env.CONFIG_SIGN_ARTIFACTS    = config.parameters.enableSigner.toString()
    env.CONFIG_ENABLE_TCK        = config.parameters.enableTCK.toString()
    env.CONFIG_PUBLISH_ARTIFACTS = params.PUBLISH_ARTIFACTS.toString()
    env.AQA_REF                  = config.refs.aqaRef
    env.SMOKE_TESTS_PASSED       = 'false'

    // Serialise stageAgentLabels map so the Jenkinsfile can resolve per-stage
    // agent labels without re-reading the config file on every agent allocation.
    env.CONFIG_STAGE_AGENT_LABELS = groovy.json.JsonOutput.toJson(
        config.stageAgentLabels ?: [:]
    )

    return config
}

/**
 * Display key configuration values in a consistent format.
 */
def summarizePipelineConfig(config) {
    echo "Build Configuration:"
    echo "  JDK Version: ${config.buildConfig.JAVA_TO_BUILD}"
    echo "  Variant: ${config.buildConfig.VARIANT}"
    echo "  OS: ${config.buildConfig.TARGET_OS}"
    echo "  Architecture: ${config.buildConfig.ARCHITECTURE}"
    echo "  Node Label: ${config.buildConfig.NODE_LABEL}"
    echo "  Build Args: ${config.buildConfig.BUILD_ARGS}"
    echo "  Docker Image: ${config.buildConfig.DOCKER_IMAGE ?: '(none)'}"
    echo "  Docker Registry: ${config.buildConfig.DOCKER_REGISTRY ?: '(none)'}"
    echo "  Docker Credential: ${config.buildConfig.DOCKER_CREDENTIAL ?: '(none)'}"
    echo "  Docker Args: ${config.buildConfig.DOCKER_ARGS ?: '(none)'}"
    echo "  Podman Args: ${config.buildConfig.PODMAN_ARGS ?: '(none)'}"
    echo "Stage Agent Labels:"
    def os   = config.buildConfig.TARGET_OS
    def arch = config.buildConfig.ARCHITECTURE
    (config.stageAgentLabels ?: [:]).each { stage, template ->
        def resolved = template.replace('{os}', os).replace('{arch}', arch)
        echo "  ${stage}: ${template}  →  ${resolved}"
    }
}

return this
