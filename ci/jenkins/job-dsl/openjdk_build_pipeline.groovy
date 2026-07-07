/**
 * Job DSL Script for Platform-Specific OpenJDK Build Pipeline Jobs
 *
 * This script creates platform-specific pipeline jobs for building OpenJDK.
 * It is called by the launch job with JDK_VERSION and PLATFORM parameters,
 * then loads the platform configuration to extract TARGET_OS, ARCHITECTURE, and VARIANT.
 *
 * Called by: Launch job (Jenkinsfile.launch)
 * Creates: jdk${version}-${platform}-build-pipeline jobs
 *
 * Required Parameters (from launch job):
 *   - JDK_VERSION: The JDK version (e.g., "21")
 *   - PLATFORM: The platform key (e.g., "x64Linux", "aarch64Mac")
 *   - CONFIG_REPO_URL: Configuration repository URL
 *   - CONFIG_REPO_BRANCH: Configuration repository branch
 *
 * The script loads jdk${version}_pipeline_config.json and extracts from the platform configuration:
 *   - TARGET_OS: Operating system (e.g., "linux", "mac")
 *   - ARCHITECTURE: CPU architecture (e.g., "x64", "aarch64")
 *   - VARIANT: Build variant (e.g., "temurin", "dragonwell")
 */

import groovy.json.JsonSlurper

// Get parameters from launch job
def jdkVersion = binding.variables.get('JDK_VERSION')
def platform = binding.variables.get('PLATFORM')
def configRepoUrl = binding.variables.get('CONFIG_REPO_URL')
def configRepoBranch = binding.variables.get('CONFIG_REPO_BRANCH')

// Validate required parameters
if (!jdkVersion) {
    throw new IllegalArgumentException("JDK_VERSION parameter is required")
}
if (!platform) {
    throw new IllegalArgumentException("PLATFORM parameter is required")
}
if (!configRepoUrl || configRepoUrl.trim().isEmpty()) {
    throw new IllegalArgumentException("CONFIG_REPO_URL parameter is required")
}
if (!configRepoBranch || configRepoBranch.trim().isEmpty()) {
    throw new IllegalArgumentException("CONFIG_REPO_BRANCH parameter is required")
}

// Fetch configuration files
def repoPath = configRepoUrl.replaceAll(/^https?:\/\/github\.com\//, '').replaceAll(/\.git$/, '')

// Load CI-agnostic pipeline configuration
def pipelineConfig
try {
    def configUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/adoptium_pipeline_config.json"
    println "Loading Adoptium pipeline configuration from ${configUrl}"
    def configText = new URL(configUrl).text
    pipelineConfig = new JsonSlurper().parseText(configText)
    println "✓ Successfully loaded adoptium_pipeline_config.json"
} catch (Exception e) {
    def errorMsg = """
ERROR: Failed to load adoptium_pipeline_config.json!

Configuration Repository: ${configRepoUrl}
Branch: ${configRepoBranch}
Error: ${e.message}

Ensure adoptium_pipeline_config.json exists and is accessible.
""".stripIndent()
    
    println errorMsg
    throw new RuntimeException(errorMsg)
}

// Load Jenkins-specific job configuration
def jenkinsConfig
try {
    def configUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/jenkins_job_config.json"
    println "Loading Jenkins job configuration from ${configUrl}"
    def configText = new URL(configUrl).text
    jenkinsConfig = new JsonSlurper().parseText(configText)
    println "✓ Successfully loaded jenkins_job_config.json"
} catch (Exception e) {
    def errorMsg = """
ERROR: Failed to load jenkins_job_config.json!

Configuration Repository: ${configRepoUrl}
Branch: ${configRepoBranch}
Error: ${e.message}

Ensure jenkins_job_config.json exists and is accessible.
""".stripIndent()
    
    println errorMsg
    throw new RuntimeException(errorMsg)
}

// Extract default parameters with safe navigation
def defaultParams = jenkinsConfig?.jobConfiguration?.defaultParameters

// Extract the Initialize stage label from stageAgentLabels so the Jenkinsfile
// can use it at pipeline-definition time (before CONFIG_STAGE_AGENT_LABELS is set).
// The Initialize stage is platform-independent so no {os}/{arch} substitution needed.
def initializeLabel = jenkinsConfig?.stageAgentLabels?.get('Initialize') ?: 'ci.role.worker'

// Fetch platform-specific configuration to get os, arch, and variant
def platformConfig
def architecture
def targetOs
def variant
try {
    def jdkConfigUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/configurations/jdk${jdkVersion}_pipeline_config.json"
    
    println "Loading platform configuration from ${jdkConfigUrl}"
    def jdkConfigText = new URL(jdkConfigUrl).text
    def jdkConfig = new JsonSlurper().parseText(jdkConfigText)
    
    // Get platform-specific configuration
    platformConfig = jdkConfig.buildConfigurations[platform]
    if (!platformConfig) {
        throw new IllegalArgumentException("Platform '${platform}' not found in configuration for JDK ${jdkVersion}")
    }
    
    // Extract os and arch from platform configuration
    architecture = platformConfig.arch
    targetOs = platformConfig.os
    
    if (!architecture || !targetOs) {
        throw new IllegalArgumentException("Platform '${platform}' configuration missing 'arch' or 'os' fields")
    }
    
    // Extract variant (default to 'temurin' if not specified)
    variant = platformConfig.variant ?: pipelineConfig?.defaultVariant ?: 'temurin'
    
    println "✓ Platform configuration loaded:"
    println "  Platform: ${platform}"
    println "  Architecture: ${architecture}"
    println "  OS: ${targetOs}"
    println "  Variant: ${variant}"
} catch (Exception e) {
    def errorMsg = """
ERROR: Failed to load platform configuration!

Configuration Repository: ${configRepoUrl}
Branch: ${configRepoBranch}
JDK Version: ${jdkVersion}
Platform: ${platform}
Error: ${e.message}

Ensure jdk${jdkVersion}_pipeline_config.json exists and contains configuration for platform '${platform}'.
""".stripIndent()
    
    println errorMsg
    throw new RuntimeException(errorMsg)
}

// Ensure the openjdk-builds folder and JDK version subfolder exist
// Use absolute paths (starting with /) to ensure folders are created at root level
folder('/openjdk-builds') {
    displayName('OpenJDK Platform Builds')
    description('Platform-specific build pipeline jobs organized by JDK version (created dynamically by launch jobs)')
}

folder("/openjdk-builds/jdk${jdkVersion}") {
    displayName("JDK ${jdkVersion}")
    description("Build pipeline jobs for JDK ${jdkVersion}")
}

// Create platform-specific build job
def jobName = "/openjdk-builds/jdk${jdkVersion}/jdk${jdkVersion}-${platform}-build-pipeline"

println "Creating platform-specific build job: ${jobName}"

pipelineJob(jobName) {
    displayName("JDK ${jdkVersion} ${platform} Build Pipeline")
    description("""
        Platform-specific build pipeline for JDK ${jdkVersion} on ${platform}.
        
        This job executes the complete build pipeline including:
        - Build
        - Test (if enabled)
        - Sign (if enabled)
        - Publish (if enabled)
        
        This pipeline supports restart from any stage and tracks build state across restarts.
    """.stripIndent().trim())

    quietPeriod(5)

    parameters {
        // Fixed parameters (set by launch job from platform configuration)
        stringParam('JDK_VERSION', jdkVersion, 'JDK version (fixed)')
        stringParam('TARGET_OS', targetOs, 'Target operating system (fixed)')
        stringParam('ARCHITECTURE', architecture, 'Target architecture (fixed)')
        
        // Configuration repository
        stringParam('CONFIG_REPO_URL', configRepoUrl,
            'URL of the configuration repository')
        stringParam('CONFIG_REPO_BRANCH', configRepoBranch,
            'Branch of the configuration repository')
        
        // Build configuration - with safe navigation and fallback defaults
        stringParam('VARIANT',
            variant,
            'Build variant (temurin, dragonwell, etc.)')
        
        booleanParam('RUN_TESTS',
            defaultParams?.RUN_TESTS != null ? defaultParams.RUN_TESTS : true,
            'Run test stages (smoke tests, AQA, TCK)')
        
        booleanParam('SIGN_ARTIFACTS',
            defaultParams?.SIGN_ARTIFACTS != null ? defaultParams.SIGN_ARTIFACTS : false,
            'Sign artifacts and installers')
        
        booleanParam('PUBLISH_ARTIFACTS',
            defaultParams?.PUBLISH_ARTIFACTS != null ? defaultParams.PUBLISH_ARTIFACTS : false,
            'Publish artifacts to release repository')
        
        booleanParam('RUN_REPRODUCIBLE_COMPARE',
            defaultParams?.RUN_REPRODUCIBLE_COMPARE != null ? defaultParams.RUN_REPRODUCIBLE_COMPARE : false,
            'Run reproducible build comparison')
        
        // Additional build parameters
        choiceParam('RELEASE_TYPE',
            ['NIGHTLY', 'WEEKLY', 'RELEASE'],
            'Type of release build (NIGHTLY=default nightly builds, WEEKLY=weekly EA beta builds, RELEASE=official releases)')

        stringParam('GROUP_UID', '',
            'Optional group identifier linking all platform builds from the same launch (set automatically by Launch pipeline)')

        stringParam('SCM_REF', '',
            'Git reference (tag/branch) for the JDK source code (e.g., jdk-21.0.12+6_adopt)')

        stringParam('BUILD_REF', '',
            'Git reference for the build scripts repository (empty = use config repo default)')

        stringParam('AQA_REF', '',
            'Git reference (tag/branch) for the aqa-tests repository (empty = use config repo default)')

        booleanParam('ENABLE_INSTALLERS',
            defaultParams?.ENABLE_INSTALLERS != null ? defaultParams.ENABLE_INSTALLERS : true,
            'Build installers')

        booleanParam('ENABLE_TCK',
            false,
            'Run TCK tests (Temurin only, release/weekly builds)')
        
        booleanParam('REPRODUCIBLE_COMPARE_BUILD',
            defaultParams?.RUN_REPRODUCIBLE_COMPARE != null ? defaultParams.RUN_REPRODUCIBLE_COMPARE : false,
            'Enable reproducible build comparison against production Adoptium binaries (requires SCM_REF to be set)')

        booleanParam('CLEAN_WORKSPACE_AFTER_STAGE',
            defaultParams?.CLEAN_WORKSPACE_AFTER_STAGE != null ? defaultParams.CLEAN_WORKSPACE_AFTER_STAGE : true,
            'Clean workspace after each stage completes')

        // Read from jenkins_job_config.json stageAgentLabels.Initialize at job-generation
        // time so the Jenkinsfile can select the correct agent for the Initialize stage
        // before CONFIG_STAGE_AGENT_LABELS is populated by that stage itself.
        // This value will not change unless jenkins_job_config.json is updated and the
        // job is regenerated via the seed job.
        stringParam('INITIALIZE_LABEL', initializeLabel,
            'Jenkins node label for the Initialize stage (fixed at job generation from stageAgentLabels.Initialize in jenkins_job_config.json)')
    }

    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url(pipelineConfig.repository.url)
                        if (pipelineConfig.repository.credentialsId) {
                            credentials(pipelineConfig.repository.credentialsId)
                        }
                    }
                    branch("*/${pipelineConfig.repository.branch}")
                    extensions {
                        cleanBeforeCheckout()
                    }
                }
            }
            scriptPath(jenkinsConfig.jenkinsfilePath)
            lightweight(true)
        }
    }

    properties {
        buildDiscarder {
            strategy {
                logRotator {
                    daysToKeepStr(jenkinsConfig.jobConfiguration.logRotation.daysToKeep.toString())
                    numToKeepStr(jenkinsConfig.jobConfiguration.logRotation.numToKeep.toString())
                    artifactDaysToKeepStr(jenkinsConfig.jobConfiguration.logRotation.artifactDaysToKeep.toString())
                    artifactNumToKeepStr(jenkinsConfig.jobConfiguration.logRotation.artifactNumToKeep.toString())
                }
            }
        }
        disableResume()
        disableConcurrentBuilds()
    }
    
    // Configure copyArtifact permission using configure block
    configure { project ->
        project / 'properties' / 'hudson.plugins.copyartifact.CopyArtifactPermissionProperty' {
            projectNameList {
                string('*')
            }
        }
    }
}

println "✓ Platform-specific build job created: ${jobName}"

// Made with Bob
