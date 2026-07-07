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
 *   generatePipelineConfig(configRepoPath='./config-repo')
 *     → calls scripts/lib/load-json-config.py (CI-agnostic) to produce
 *       pipeline-config.json from jdkNN_pipeline_config.json and
 *       adoptium_pipeline_config.json; sets CONFIG_* / AQA_REF / SMOKE_TESTS_PASSED
 *       env vars; returns the parsed pipelineConfig Map.
 *
 *   generateJenkinsConfig(configRepoPath='./config-repo')
 *     → calls ci/jenkins/lib/load-jenkins-json-config.py to produce a separate
 *       jenkins-config.json from jenkins_job_config.json; sets
 *       CONFIG_NODE_LABEL / CONFIG_STAGE_AGENT_LABELS env vars;
 *       returns the parsed jenkinsConfig Map.
 *
 *   summarizePipelineConfig(pipelineConfig, jenkinsConfig)
 *     → logs key build configuration values from both config objects
 */

/**
 * Generate pipeline-config.json from job parameters and the config repository.
 *
 * Loads jdkNN_pipeline_config.json and adoptium_pipeline_config.json only.
 * Does not touch jenkins_job_config.json — call generateJenkinsConfig() for that.
 *
 * Resolves BUILD_REF/AQA_REF from job params (if non-empty) or config repo defaults.
 * Sets CI-agnostic CONFIG_* environment variables for use in when{} blocks.
 *
 * @param configRepoPath  Path to the config repository root (e.g. './config-repo').
 *                        The configurations sub-directory and adoptium_pipeline_config.json
 *                        are resolved from this root automatically.
 * @return parsed pipeline-config.json as a Map (pipelineConfig)
 */
def generatePipelineConfig(String configRepoPath = './config-repo') {

    // Validate parameters
    if (params.RUN_REPRODUCIBLE_COMPARE && (!params.SCM_REF || params.SCM_REF.trim().isEmpty())) {
        error("RUN_REPRODUCIBLE_COMPARE requires SCM_REF to be specified. Please provide a valid SCM reference (e.g., jdk-21.0.12+6_adopt)")
    }

    // Load adoptium_pipeline_config.json for vendor defaults
    def adoptiumConfigFile = "${configRepoPath}/adoptium_pipeline_config.json"
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
    def configDir = "${configRepoPath}/configurations"
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

    // Execute CI-agnostic Python script — produces pipeline-config.json
    sh "python3 scripts/lib/load-json-config.py ${pythonArgs.join(' ')}"

    // Read the generated pipeline-config.json
    def pipelineConfig = readJSON(file: 'pipeline-config.json')

    // Set TCK enablement from parameter and persist
    pipelineConfig.parameters.enableTCK = params.ENABLE_TCK
    writeJSON(file: 'pipeline-config.json', json: pipelineConfig, pretty: 4)

    // Set CI-agnostic environment variables for use in when{} blocks
    env.CONFIG_VARIANT           = pipelineConfig.buildConfig.VARIANT
    env.CONFIG_TARGET_OS         = pipelineConfig.buildConfig.TARGET_OS
    env.CONFIG_ARCHITECTURE      = pipelineConfig.buildConfig.ARCHITECTURE
    env.CONFIG_JAVA_TO_BUILD     = pipelineConfig.buildConfig.JAVA_TO_BUILD
    env.CONFIG_NODE_LABEL        = pipelineConfig.buildConfig.NODE_LABEL ?: 'worker'
    env.CONFIG_BUILD_ARGS        = pipelineConfig.buildConfig.BUILD_ARGS ?: ''
    env.CONFIG_CONFIGURE_ARGS    = pipelineConfig.buildConfig.CONFIGURE_ARGS ?: ''
    env.CONFIG_BUILD_REF         = pipelineConfig.refs.buildRef ?: 'master'
    env.CONFIG_BUILD_REPO_URL    = pipelineConfig.refs.buildRepoUrl ?: ''
    env.CONFIG_CLEAN_WORKSPACE   = pipelineConfig.parameters.cleanWorkspaceAfterStage?.toString() ?: 'false'
    env.CONFIG_EA_BETA_BUILD     = pipelineConfig.parameters.eaBetaBuild?.toString() ?: 'false'
    env.CONFIG_COMPARE_BUILD     = pipelineConfig.parameters.compareBuild?.toString() ?: 'false'
    env.CONFIG_DOCKER_IMAGE      = pipelineConfig.buildConfig.DOCKER_IMAGE ?: ''
    env.CONFIG_DOCKER_REGISTRY   = pipelineConfig.buildConfig.DOCKER_REGISTRY ?: ''
    env.CONFIG_DOCKER_CREDENTIAL = pipelineConfig.buildConfig.DOCKER_CREDENTIAL ?: ''
    env.CONFIG_DOCKER_ARGS       = pipelineConfig.buildConfig.DOCKER_ARGS ?: ''
    env.CONFIG_PODMAN_ARGS       = pipelineConfig.buildConfig.PODMAN_ARGS ?: ''
    env.CONFIG_RUN_TESTS         = pipelineConfig.parameters.enableTests.toString()
    env.CONFIG_ENABLE_INSTALLERS = pipelineConfig.parameters.enableInstallers.toString()
    env.CONFIG_SIGN_ARTIFACTS    = pipelineConfig.parameters.enableSigner.toString()
    env.CONFIG_ENABLE_TCK        = pipelineConfig.parameters.enableTCK.toString()
    env.CONFIG_PUBLISH_ARTIFACTS = params.PUBLISH_ARTIFACTS.toString()
    env.AQA_REF                  = pipelineConfig.refs.aqaRef
    env.SMOKE_TESTS_PASSED       = 'false'

    return pipelineConfig
}

/**
 * Generate jenkins-config.json from jenkins_job_config.json.
 *
 * Must be called after generatePipelineConfig() so that pipeline-config.json
 * already exists (TARGET_OS and ARCHITECTURE are read from it to resolve label
 * placeholders).  Writes a separate jenkins-config.json — pipeline-config.json
 * is never modified.
 *
 * Updates env vars:
 *   CONFIG_NODE_LABEL         — resolved Build-stage node label (with additionalNodeLabels)
 *   CONFIG_STAGE_AGENT_LABELS — JSON map of all resolved stage labels
 *
 * @param configRepoPath  Path to the config repository root (contains jenkins_job_config.json).
 * @return parsed jenkins-config.json as a Map (jenkinsConfig)
 */
def generateJenkinsConfig(String configRepoPath = './config-repo') {
    sh "python3 ci/jenkins/lib/load-jenkins-json-config.py" +
       " --config-repo-path ${configRepoPath}" +
       " --pipeline-config  ./pipeline-config.json" +
       " --output           ./jenkins-config.json"

    def jenkinsConfig = readJSON(file: 'jenkins-config.json')

    // Update node label env var to the Jenkins schema-resolved Build label
    env.CONFIG_NODE_LABEL = jenkinsConfig.buildNodeLabel ?: env.CONFIG_NODE_LABEL ?: 'worker'

    // Serialise resolvedStageAgentLabels so the Jenkinsfile can resolve per-stage
    // agent labels without re-reading the config file on every agent allocation.
    env.CONFIG_STAGE_AGENT_LABELS = groovy.json.JsonOutput.toJson(
        jenkinsConfig.resolvedStageAgentLabels ?: jenkinsConfig.stageAgentLabels ?: [:]
    )

    return jenkinsConfig
}

/**
 * Display key configuration values in a consistent format.
 *
 * @param pipelineConfig  Map returned by generatePipelineConfig()
 * @param jenkinsConfig   Map returned by generateJenkinsConfig() (optional)
 */
def summarizePipelineConfig(def pipelineConfig, def jenkinsConfig = null) {
    echo "Build Configuration:"
    echo "  JDK Version: ${pipelineConfig.buildConfig.JAVA_TO_BUILD}"
    echo "  Variant: ${pipelineConfig.buildConfig.VARIANT}"
    echo "  OS: ${pipelineConfig.buildConfig.TARGET_OS}"
    echo "  Architecture: ${pipelineConfig.buildConfig.ARCHITECTURE}"
    echo "  Build Args: ${pipelineConfig.buildConfig.BUILD_ARGS}"
    echo "  Docker Image: ${pipelineConfig.buildConfig.DOCKER_IMAGE ?: '(none)'}"
    echo "  Docker Registry: ${pipelineConfig.buildConfig.DOCKER_REGISTRY ?: '(none)'}"
    echo "  Docker Credential: ${pipelineConfig.buildConfig.DOCKER_CREDENTIAL ?: '(none)'}"
    echo "  Docker Args: ${pipelineConfig.buildConfig.DOCKER_ARGS ?: '(none)'}"
    echo "  Podman Args: ${pipelineConfig.buildConfig.PODMAN_ARGS ?: '(none)'}"
    if (jenkinsConfig) {
        echo "Stage Agent Labels (resolved):"
        (jenkinsConfig.resolvedStageAgentLabels ?: [:]).each { stage, label ->
            echo "  ${stage}: ${label}"
        }
        echo "  Build Node Label: ${jenkinsConfig.buildNodeLabel ?: '(none)'}"
    }
}

return this
