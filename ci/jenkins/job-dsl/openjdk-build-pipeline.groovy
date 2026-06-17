/**
 * Job DSL Script for OpenJDK Build Pipeline
 *
 * This script creates the declarative pipeline job for building OpenJDK.
 * It reads configuration from the ci-temurin-config repository to determine
 * which JDK versions are active and should have jobs created.
 *
 * Usage:
 *   1. Install Job DSL plugin in Jenkins
 *   2. Create a seed job that runs this script
 *   3. Run the seed job to create/update the pipeline job
 *
 * For throwaway Jenkins instances, this ensures all jobs are reproducible from code.
 */

import groovy.json.JsonSlurper

// Fetch Jenkins job configuration from ci-temurin-config repository
def configRepoUrl = 'https://github.com/adoptium/ci-temurin-config.git'
def configRepoBranch = 'main'
def configFile = 'jenkins_job_config.json'

// Read configuration from repository
def jenkinsConfig
try {
    def configUrl = "https://raw.githubusercontent.com/adoptium/ci-temurin-config/${configRepoBranch}/${configFile}"
    println "Loading Jenkins configuration from ${configUrl}"
    def configText = new URL(configUrl).text
    jenkinsConfig = new JsonSlurper().parseText(configText)
    println "✓ Successfully loaded Jenkins configuration"
    println "  Active JDK versions: ${jenkinsConfig.activeJdkVersions.findAll { it.enabled }.collect { it.version }.join(', ')}"
} catch (Exception e) {
    def errorMsg = """
ERROR: Failed to load Jenkins job configuration from ci-temurin-config repository!

Configuration URL: https://raw.githubusercontent.com/adoptium/ci-temurin-config/${configRepoBranch}/${configFile}
Error: ${e.message}

The jenkins_job_config.json file must exist in the ci-temurin-config repository.
This file defines which JDK versions are active and should have jobs created.

Please ensure:
1. The ci-temurin-config repository is accessible
2. The jenkins_job_config.json file exists at the root
3. The file contains valid JSON with activeJdkVersions array

Seed job cannot proceed without this configuration.
""".stripIndent()
    
    println errorMsg
    throw new RuntimeException(errorMsg)
}

// Base job configuration template
def jobConfig = [
    // Job identification
    name: 'openjdk-build-pipeline',
    displayName: 'OpenJDK Build Pipeline',
    description: '''
        Declarative pipeline for building, testing, and releasing OpenJDK binaries.
        This pipeline supports restart from any stage and tracks build state across restarts.
    '''.stripIndent().trim(),

    // Repository configuration (from jenkins_job_config.json)
    repoUrl: jenkinsConfig.repository.url,
    repoBranch: "*/${jenkinsConfig.repository.branch}",
    repoCredentialsId: jenkinsConfig.repository.credentialsId ?: '',

    // Jenkinsfile location (from jenkins_job_config.json)
    jenkinsfilePath: jenkinsConfig.repository.jenkinsfilePath,

    // Build configuration
    concurrentBuilds: false,
    quietPeriod: 5,
    logRotator: jenkinsConfig.jobConfiguration.logRotation,

    // Pipeline parameters
    parameters: [
        [
            type: 'string',
            name: 'JDK_VERSION',
            defaultValue: '21',
            description: 'JDK version to build (e.g., 8, 11, 17, 21)'
        ],
        [
            type: 'choice',
            name: 'PLATFORM',
            choices: jenkinsConfig.jobConfiguration.platformChoices,
            description: 'Target platform for the build'
        ],
        [
            type: 'string',
            name: 'BUILD_VARIANT',
            defaultValue: jenkinsConfig.jobConfiguration.defaultParameters.BUILD_VARIANT,
            description: 'Build variant (temurin, dragonwell, etc.)'
        ],
        [
            type: 'string',
            name: 'CONFIG_REPO_URL',
            defaultValue: jenkinsConfig.jobConfiguration.defaultParameters.CONFIG_REPO_URL,
            description: 'URL of the configuration repository'
        ],
        [
            type: 'string',
            name: 'CONFIG_REPO_BRANCH',
            defaultValue: jenkinsConfig.jobConfiguration.defaultParameters.CONFIG_REPO_BRANCH,
            description: 'Branch of the configuration repository'
        ],
        [
            type: 'booleanParam',
            name: 'CLEAN_WORKSPACE_AFTER_STAGE',
            defaultValue: jenkinsConfig.jobConfiguration.defaultParameters.CLEAN_WORKSPACE_AFTER_STAGE,
            description: 'Clean workspace after each stage completes'
        ],
        [
            type: 'booleanParam',
            name: 'RUN_TESTS',
            defaultValue: jenkinsConfig.jobConfiguration.defaultParameters.RUN_TESTS,
            description: 'Run test stages (smoke tests, AQA, TCK)'
        ],
        [
            type: 'booleanParam',
            name: 'SIGN_ARTIFACTS',
            defaultValue: jenkinsConfig.jobConfiguration.defaultParameters.SIGN_ARTIFACTS,
            description: 'Sign artifacts and installers'
        ],
        [
            type: 'booleanParam',
            name: 'PUBLISH_ARTIFACTS',
            defaultValue: jenkinsConfig.jobConfiguration.defaultParameters.PUBLISH_ARTIFACTS,
            description: 'Publish artifacts to release repository'
        ],
        [
            type: 'booleanParam',
            name: 'RUN_REPRODUCIBLE_COMPARE',
            defaultValue: jenkinsConfig.jobConfiguration.defaultParameters.RUN_REPRODUCIBLE_COMPARE,
            description: 'Run reproducible build comparison'
        ]
    ],

    // Triggers
    triggers: [
        // Uncomment to enable periodic builds
        // cron: 'H 2 * * *'  // Daily at 2 AM
    ]
]

// Create the pipeline job
pipelineJob(jobConfig.name) {
    displayName(jobConfig.displayName)
    description(jobConfig.description)

    // Disable concurrent builds if specified
    if (!jobConfig.concurrentBuilds) {
        concurrentBuild(false)
    }

    // Set quiet period
    quietPeriod(jobConfig.quietPeriod)

    // Add parameters
    parameters {
        jobConfig.parameters.each { param ->
            switch (param.type) {
                case 'string':
                    stringParam(param.name, param.defaultValue, param.description)
                    break
                case 'choice':
                    choiceParam(param.name, param.choices, param.description)
                    break
                case 'booleanParam':
                    booleanParam(param.name, param.defaultValue, param.description)
                    break
            }
        }
    }

    // Configure triggers
    if (jobConfig.triggers.cron) {
        triggers {
            cron(jobConfig.triggers.cron)
        }
    }

    // Configure pipeline from SCM
    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url(jobConfig.repoUrl)
                        if (jobConfig.repoCredentialsId) {
                            credentials(jobConfig.repoCredentialsId)
                        }
                    }
                    branch(jobConfig.repoBranch)
                    extensions {
                        cleanBeforeCheckout()
                    }
                }
            }
            scriptPath(jobConfig.jenkinsfilePath)
            lightweight(true)
        }
    }

    // Configure properties
    properties {
        // Discard old builds
        buildDiscarder {
            strategy {
                logRotator {
                    daysToKeepStr(jobConfig.logRotator.daysToKeep.toString())
                    numToKeepStr(jobConfig.logRotator.numToKeep.toString())
                    artifactDaysToKeepStr(jobConfig.logRotator.artifactDaysToKeep.toString())
                    artifactNumToKeepStr(jobConfig.logRotator.artifactNumToKeep.toString())
                }
            }
        }

        // Disable resume (we handle restarts via BUILD_UID)
        disableResume()
    }
}

// Create a folder for organizing related jobs (optional)
folder('openjdk-builds') {
    displayName('OpenJDK Builds')
    description('Folder containing all OpenJDK build pipeline jobs')
}

// Create jobs for all active JDK versions from configuration
println "\nCreating jobs for active JDK versions from jenkins_job_config.json:"
jenkinsConfig.activeJdkVersions.findAll { it.enabled }.each { versionInfo ->
    def version = versionInfo.version
    def fullVersion = versionInfo.fullVersion
    def isLts = versionInfo.lts
    
    println "  → JDK ${version} (${fullVersion})${isLts ? ' [LTS]' : ''}"
    def versionConfig = jobConfig.clone()
    versionConfig.name = "openjdk-builds/jdk${version}-build-pipeline"
    versionConfig.displayName = "JDK ${version} Build Pipeline${isLts ? ' (LTS)' : ''}"
    versionConfig.description = """
        Declarative pipeline for building JDK ${version} (${fullVersion}).
        ${isLts ? 'This is a Long Term Support (LTS) version.' : ''}
        
        Configuration: ${versionInfo.configFile}
        
        This pipeline supports restart from any stage and tracks build state across restarts.
    """.stripIndent().trim()
    versionConfig.parameters.find { it.name == 'JDK_VERSION' }.defaultValue = version

    pipelineJob(versionConfig.name) {
        displayName(versionConfig.displayName)
        description(versionConfig.description)

        if (!versionConfig.concurrentBuilds) {
            concurrentBuild(false)
        }

        quietPeriod(versionConfig.quietPeriod)

        parameters {
            versionConfig.parameters.each { param ->
                switch (param.type) {
                    case 'string':
                        stringParam(param.name, param.defaultValue, param.description)
                        break
                    case 'choice':
                        choiceParam(param.name, param.choices, param.description)
                        break
                    case 'booleanParam':
                        booleanParam(param.name, param.defaultValue, param.description)
                        break
                }
            }
        }

        definition {
            cpsScm {
                scm {
                    git {
                        remote {
                            url(versionConfig.repoUrl)
                            if (versionConfig.repoCredentialsId) {
                                credentials(versionConfig.repoCredentialsId)
                            }
                        }
                        branch(versionConfig.repoBranch)
                        extensions {
                            cleanBeforeCheckout()
                        }
                    }
                }
                scriptPath(versionConfig.jenkinsfilePath)
                lightweight(true)
            }
        }

        // Configure properties
        properties {
            // Discard old builds
            buildDiscarder {
                strategy {
                    logRotator {
                        daysToKeepStr(versionConfig.logRotator.daysToKeep.toString())
                        numToKeepStr(versionConfig.logRotator.numToKeep.toString())
                        artifactDaysToKeepStr(versionConfig.logRotator.artifactDaysToKeep.toString())
                        artifactNumToKeepStr(versionConfig.logRotator.artifactNumToKeep.toString())
                    }
                }
            }

            // Disable resume (we handle restarts via BUILD_UID)
            disableResume()
        }
    }
}

// Made with Bob
