/**
 * Job DSL Script for Platform-Specific OpenJDK Build Pipeline Jobs
 *
 * This script creates platform-specific pipeline jobs for building OpenJDK.
 * It is called by the launch job with specific JDK_VERSION and PLATFORM parameters.
 *
 * Called by: Launch job (Jenkinsfile.launch)
 * Creates: jdk${version}-${platform}-build-pipeline jobs
 *
 * Required Parameters (from launch job):
 *   - JDK_VERSION: The JDK version (e.g., "21")
 *   - PLATFORM: The platform (e.g., "linux-x64")
 *   - CONFIG_REPO_URL: Configuration repository URL
 *   - CONFIG_REPO_BRANCH: Configuration repository branch
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

// Fetch Jenkins job configuration
def jenkinsConfig
try {
    def repoPath = configRepoUrl.replaceAll(/^https?:\/\/github\.com\//, '').replaceAll(/\.git$/, '')
    def configUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/jenkins_job_config.json"
    
    println "Loading Jenkins configuration from ${configUrl}"
    def configText = new URL(configUrl).text
    jenkinsConfig = new JsonSlurper().parseText(configText)
    println "✓ Successfully loaded Jenkins configuration"
    println "jenkinsConfig: ${jenkinsConfig}"
} catch (Exception e) {
    def errorMsg = """
ERROR: Failed to load Jenkins job configuration!

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

// Ensure the openjdk-builds folder exists
folder('openjdk-builds') {
    displayName('OpenJDK Build Jobs')
    description('Folder containing all OpenJDK build pipeline jobs')
}

// Create platform-specific build job
def jobName = "openjdk-builds/jdk${jdkVersion}-${platform}-build-pipeline"

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
        // Fixed parameters (set by launch job)
        stringParam('JDK_VERSION', jdkVersion, 'JDK version (fixed)')
        stringParam('PLATFORM', platform, 'Target platform (fixed)')
        
        // Configuration repository
        stringParam('CONFIG_REPO_URL', configRepoUrl,
            'URL of the configuration repository')
        stringParam('CONFIG_REPO_BRANCH', configRepoBranch,
            'Branch of the configuration repository')
        
        // Build configuration - with safe navigation and fallback defaults
        stringParam('BUILD_VARIANT',
            defaultParams?.BUILD_VARIANT ?: jenkinsConfig?.defaultVariant ?: 'temurin',
            'Build variant (temurin, dragonwell, etc.)')
        
        booleanParam('CLEAN_WORKSPACE_AFTER_STAGE',
            defaultParams?.CLEAN_WORKSPACE_AFTER_STAGE != null ? defaultParams.CLEAN_WORKSPACE_AFTER_STAGE : true,
            'Clean workspace after each stage completes')
        
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
        booleanParam('RELEASE',
            false,
            'Is this a release build?')
        
        booleanParam('WEEKLY',
            false,
            'Is this a weekly build?')
        
        booleanParam('ENABLE_TESTS',
            defaultParams?.RUN_TESTS != null ? defaultParams.RUN_TESTS : true,
            'Run AQA tests after build')
        
        booleanParam('ENABLE_INSTALLERS',
            true,
            'Build installers')
        
        booleanParam('ENABLE_SIGNER',
            defaultParams?.SIGN_ARTIFACTS != null ? defaultParams.SIGN_ARTIFACTS : false,
            'Sign artifacts')
        
        booleanParam('ENABLE_TCK',
            false,
            'Run TCK tests (Temurin only, release/weekly builds)')
        
        booleanParam('EA_BETA_BUILD',
            false,
            'Enable EA/Beta build (adds --with-version-opt=ea to configure args)')
        
        booleanParam('REPRODUCIBLE_COMPARE_BUILD',
            defaultParams?.RUN_REPRODUCIBLE_COMPARE != null ? defaultParams.RUN_REPRODUCIBLE_COMPARE : false,
            'Enable reproducible build comparison against production Adoptium binaries (requires SCM_REF to be set)')
        
        stringParam('SCM_REF',
            '',
            'Override OpenJDK source branch/tag (required for REPRODUCIBLE_COMPARE_BUILD)')
        
        stringParam('BUILD_REF',
            '',
            'Override temurin-build branch/tag')
        
        stringParam('HELPER_REF',
            '',
            'Override jenkins-helper branch/tag')
    }

    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url(jenkinsConfig.repository.url)
                        if (jenkinsConfig.repository.credentialsId) {
                            credentials(jenkinsConfig.repository.credentialsId)
                        }
                    }
                    branch("*/${jenkinsConfig.repository.branch}")
                    extensions {
                        cleanBeforeCheckout()
                    }
                }
            }
            scriptPath(jenkinsConfig.repository.jenkinsfilePath)
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
}

println "✓ Platform-specific build job created: ${jobName}"

// Made with Bob
