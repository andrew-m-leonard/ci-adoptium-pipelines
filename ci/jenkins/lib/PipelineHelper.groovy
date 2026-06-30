/**
 * PipelineHelper — shared stage lifecycle functions for the Jenkinsfile.
 *
 * Provides: initializeStage(), finalizeStage(), executeStageWithTracking(),
 *           ensureBuildDescriptionSet()
 *
 * Usage in Jenkinsfile:
 *   def pipelineHelper = load('ci/jenkins/lib/PipelineHelper.groovy').init(this)
 *   pipelineHelper.executeStageWithTracking('Build') { ... }
 */

def steps          // Pipeline steps context (the Jenkinsfile's 'this')
def buildUidHelper // Lazy-loaded by initializeStage()

def init(pipelineSteps) {
    this.steps = pipelineSteps
    return this
}

/**
 * Execute a stage body with automatic result tracking via BuildUidHelper.
 */
def executeStageWithTracking(String stageName, Closure body) {
    try {
        body()
        buildUidHelper.recordStageResult(stageName, 'SUCCESS')
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        buildUidHelper.recordStageResult(stageName, 'ABORTED')
        throw e
    } catch (Exception e) {
        def result = steps.currentBuild.result ?: 'FAILURE'
        buildUidHelper.recordStageResult(stageName, result)
        throw e
    }
}

/**
 * Common stage initialization: workspace cleanup, checkout, config-repo,
 * BUILD_UID setup, prerequisite validation, and artifact retrieval.
 *
 * Returns the parsed pipeline-config.json for non-Initialize stages,
 * or an empty map for the Initialize stage.
 */
def initializeStage(String stageName, List<String> prerequisites = [], String artifactFilter = 'pipeline-config.json', String inputArtifactsDir = null) {
    steps.echo "=== ${stageName} ==="

    // Pre-cleanup: Always clean workspace for restartability
    steps.cleanWs()

    // Checkout ci-adoptium-pipelines repository
    steps.checkout steps.scm

    // Checkout config repo (vendor-scripts, configurations, adoptium_pipeline_config.json)
    if (steps.params.CONFIG_REPO_URL) {
        steps.dir('config-repo') {
            steps.checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${steps.params.CONFIG_REPO_BRANCH}"]],
                userRemoteConfigs: [[
                    url: steps.params.CONFIG_REPO_URL
                ]],
                extensions: [
                    [$class: 'SparseCheckoutPaths',
                     sparseCheckoutPaths: [
                         [path: 'configurations/*'],
                         [path: 'vendor-scripts/*'],
                         [path: 'adoptium_pipeline_config.json']
                     ]]
                ]
            ])
        }
    }

    // Lazy-load BuildUidHelper (only once, persists across stages via the field)
    if (buildUidHelper == null) {
        steps.echo "Loading BuildUidHelper library..."
        buildUidHelper = steps.load('ci/jenkins/lib/BuildUidHelper.groovy')
    }

    // Initialize BUILD_UID and build context
    buildUidHelper.initializeBuildContext(stageName)

    // Validate prerequisites (skip for Initialize stage)
    if (stageName != 'Initialize') {
        buildUidHelper.validatePrerequisites(stageName, prerequisites)
    }

    // Retrieve artifacts if filter specified (skip for Initialize stage)
    if (artifactFilter && stageName != 'Initialize') {
        def targetDir = inputArtifactsDir ?: '.'

        if (inputArtifactsDir) {
            steps.sh "mkdir -p ${inputArtifactsDir}"
        }

        try {
            steps.copyArtifacts(
                projectName: steps.env.JOB_NAME,
                selector: steps.specific(steps.env.BUILD_NUMBER),
                filter: artifactFilter,
                target: targetDir,
                optional: false,
                fingerprintArtifacts: true
            )
            steps.echo "✅ Successfully copied artifacts to ${targetDir}: ${artifactFilter}"
        } catch (Exception e) {
            steps.error("Failed to copy artifacts '${artifactFilter}' from build ${steps.env.BUILD_NUMBER}: ${e.message}")
        }
    }

    // Return config for convenience (empty for Initialize stage)
    if (stageName == 'Initialize') {
        return [:]
    } else {
        steps.env.WORKSPACE        = "${steps.env.WORKSPACE}"
        steps.env.BUILD_NUMBER     = "${steps.env.BUILD_NUMBER}"
        steps.env.INPUT_ARTIFACTS_DIR = inputArtifactsDir ?: "${steps.env.WORKSPACE}"
        steps.env.CONFIG_FILE      = "${steps.env.INPUT_ARTIFACTS_DIR}/pipeline-config.json"

        def config = steps.readJSON(file: steps.env.CONFIG_FILE)
        ensureBuildDescriptionSet(config)
        return config
    }
}

/**
 * Common stage finalization: post-cleanup and completion message.
 */
def finalizeStage(String stageName) {
    if (steps.params.CLEAN_WORKSPACE_AFTER_STAGE) {
        steps.cleanWs()
    }
    steps.echo "=== ${stageName} Complete ==="
    steps.echo "BUILD_UID: ${steps.env.BUILD_UID}"
}

/**
 * Set build display name and description from config + BUILD_UID.
 */
def ensureBuildDescriptionSet(def config) {
    if (config == null || config.isEmpty()) {
        steps.error("ensureBuildDescriptionSet() requires a valid config object")
    }

    def displayName = "#${steps.currentBuild.number} - ${config.buildConfig.JAVA_TO_BUILD} ${config.buildConfig.VARIANT} ${config.buildConfig.TARGET_OS}-${config.buildConfig.ARCHITECTURE}"
    if (steps.params.SCM_REF) {
        displayName += " @ ${steps.params.SCM_REF}"
    }
    if (steps.params.RELEASE_TYPE && steps.params.RELEASE_TYPE != 'NIGHTLY') {
        displayName += " [${steps.params.RELEASE_TYPE}]"
    }

    def description = ""
    def isRestart = steps.env.BUILD_UID && steps.env.BUILD_UID != '' && steps.currentBuild.number > 1
    if (isRestart) {
        def originalBuildNumber = steps.currentBuild.number
        def checkBuild = steps.currentBuild.previousBuild
        while (checkBuild != null) {
            try {
                def prevBuildUid = checkBuild.getBuildVariables()?.get('BUILD_UID')
                if (prevBuildUid == steps.env.BUILD_UID) {
                    originalBuildNumber = checkBuild.number
                    checkBuild = checkBuild.previousBuild
                } else {
                    break
                }
            } catch (Exception e) {
                break
            }
        }
        if (originalBuildNumber != steps.currentBuild.number) {
            description = "Restart of #${originalBuildNumber} | "
        }
    }

    description += "BUILD_UID: ${steps.env.BUILD_UID} | GROUP_UID: ${steps.env.GROUP_UID}"

    if (steps.currentBuild.displayName != displayName) {
        steps.currentBuild.displayName = displayName
        steps.echo "Build Display Name: ${displayName}"
    }
    if (steps.currentBuild.description != description) {
        steps.currentBuild.description = description
        steps.echo "Build Description: ${description}"
    }
    steps.echo "Build UID: ${steps.env.BUILD_UID}"
}

return this
