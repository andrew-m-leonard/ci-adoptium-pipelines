/**
 * Job DSL Script for OpenJDK Launch Pipeline Jobs
 *
 * This script creates "launch" orchestrator jobs that coordinate builds across
 * multiple platforms for a specific JDK version. Each launch job dynamically
 * creates platform-specific build jobs when run.
 *
 * Created by: Seed Job
 * Creates: jdk${version}-launch-build-pipelines jobs
 */

import groovy.json.JsonSlurper

// Get configuration repository details from seed job parameters
def configRepoUrl = binding.variables.get('CONFIG_REPO_URL')
def configRepoBranch = binding.variables.get('CONFIG_REPO_BRANCH')

// Validate required parameters
if (!configRepoUrl || configRepoUrl.trim().isEmpty()) {
    throw new IllegalArgumentException("CONFIG_REPO_URL parameter is required")
}
if (!configRepoBranch || configRepoBranch.trim().isEmpty()) {
    throw new IllegalArgumentException("CONFIG_REPO_BRANCH parameter is required")
}

// Get Jenkins configuration (already loaded by openjdk_build_pipeline.groovy)
def jenkinsConfig = binding.variables.get('jenkinsConfig')
if (!jenkinsConfig) {
    throw new IllegalStateException("jenkinsConfig not found in binding - ensure openjdk_build_pipeline.groovy runs first")
}

// Create launch jobs for all active JDK versions
println "\nCreating launch orchestrator jobs for active JDK versions:"
jenkinsConfig.activeJdkVersions.findAll { it.enabled }.each { versionInfo ->
    def version = versionInfo.version
    def fullVersion = versionInfo.fullVersion
    def isLts = versionInfo.lts
    def configFile = versionInfo.configFile
    
    println "  → JDK ${version} (${fullVersion})${isLts ? ' [LTS]' : ''}"
    
    def jobName = "openjdk-builds/jdk${version}-launch-build-pipelines"
    
    pipelineJob(jobName) {
        displayName("JDK ${version} Launch Build Pipelines${isLts ? ' (LTS)' : ''}")
        description("""
            Launch orchestrator for JDK ${version} (${fullVersion}) builds.
            ${isLts ? 'This is a Long Term Support (LTS) version.' : ''}
            
            This job:
            1. Reads platform configuration from: ${configFile}
            2. Optionally creates/updates platform-specific build jobs
            3. Launches builds for selected platforms in parallel
            4. Aggregates and reports results
            
            Platform-specific jobs created: jdk${version}-\${platform}-build-pipeline
        """.stripIndent().trim())

        quietPeriod(5)

        parameters {
            // Configuration repository parameters
            stringParam('CONFIG_REPO_URL', configRepoUrl, 
                'URL of the configuration repository containing jenkins_job_config.json')
            stringParam('CONFIG_REPO_BRANCH', configRepoBranch, 
                'Branch of the configuration repository')
            
            // Job management
            booleanParam('REGENERATE_JOBS', false, 
                'Force regeneration of platform-specific build jobs (use after config changes)')
            
            // Platform selection (will be populated dynamically from config)
            stringParam('PLATFORMS', 'all', 
                'Comma-separated list of platforms to build, or "all" for all available platforms')
            
            // Build configuration parameters (passed to platform jobs)
            stringParam('BUILD_VARIANT', jenkinsConfig.jobConfiguration.defaultParameters.BUILD_VARIANT,
                'Build variant (temurin, dragonwell, etc.)')
            
            booleanParam('CLEAN_WORKSPACE_AFTER_STAGE', 
                jenkinsConfig.jobConfiguration.defaultParameters.CLEAN_WORKSPACE_AFTER_STAGE,
                'Clean workspace after each stage completes')
            
            booleanParam('RUN_TESTS', jenkinsConfig.jobConfiguration.defaultParameters.RUN_TESTS,
                'Run test stages (smoke tests, AQA, TCK)')
            
            booleanParam('SIGN_ARTIFACTS', jenkinsConfig.jobConfiguration.defaultParameters.SIGN_ARTIFACTS,
                'Sign artifacts and installers')
            
            booleanParam('PUBLISH_ARTIFACTS', jenkinsConfig.jobConfiguration.defaultParameters.PUBLISH_ARTIFACTS,
                'Publish artifacts to release repository')
            
            booleanParam('RUN_REPRODUCIBLE_COMPARE', 
                jenkinsConfig.jobConfiguration.defaultParameters.RUN_REPRODUCIBLE_COMPARE,
                'Run reproducible build comparison')
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
                scriptPath('ci/jenkins/Jenkinsfile.launch')
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
        }
    }
}

println "✓ Launch orchestrator jobs created successfully"

// Made with Bob
