/**
 * Consolidated Seed Job DSL Script
 *
 * This is the bootstrap job that creates all other jobs from the Job DSL scripts.
 * Run this once to set up a new Jenkins instance with all required jobs.
 *
 * This consolidated version includes all logic in a single file to avoid
 * binding issues between separate external() script calls.
 *
 * Usage:
 *   1. Create a new Freestyle job in Jenkins named "seed-job"
 *   2. Add "Process Job DSLs" build step
 *   3. Point it to this script
 *   4. Run the job to create all pipeline jobs
 */

import groovy.json.JsonSlurper

// ============================================================================
// STEP 1: Load Configuration
// ============================================================================

// Get configuration repository details from seed job parameters
def configRepoUrl = binding.variables.get('CONFIG_REPO_URL')
def configRepoBranch = binding.variables.get('CONFIG_REPO_BRANCH')

// Validate required parameters
if (!configRepoUrl || configRepoUrl.trim().isEmpty()) {
    throw new IllegalArgumentException("""
ERROR: CONFIG_REPO_URL parameter is required but not provided!

The seed job must be configured with the following parameters:
- CONFIG_REPO_URL: URL of the configuration repository (e.g., https://github.com/adoptium/ci-temurin-config.git)
- CONFIG_REPO_BRANCH: Branch of the configuration repository (e.g., main)

Please configure the seed job with these parameters and try again.
""".stripIndent())
}

if (!configRepoBranch || configRepoBranch.trim().isEmpty()) {
    throw new IllegalArgumentException("""
ERROR: CONFIG_REPO_BRANCH parameter is required but not provided!

The seed job must be configured with the following parameters:
- CONFIG_REPO_URL: URL of the configuration repository (e.g., https://github.com/adoptium/ci-temurin-config.git)
- CONFIG_REPO_BRANCH: Branch of the configuration repository (e.g., main)

Please configure the seed job with these parameters and try again.
""".stripIndent())
}

// Extract GitHub owner/repo from URL for raw.githubusercontent.com access
def repoPath = configRepoUrl.replaceAll(/^https?:\/\/github\.com\//, '').replaceAll(/\.git$/, '')

// Read configuration from repository
def jenkinsConfig
try {
    def configUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/jenkins_job_config.json"
    println "Loading Jenkins configuration from ${configUrl}"
    println "  Repository: ${configRepoUrl}"
    println "  Branch: ${configRepoBranch}"
    
    def configText = new URL(configUrl).text
    jenkinsConfig = new JsonSlurper().parseText(configText)
    println "✓ Successfully loaded Jenkins configuration"
    println "  Active JDK versions: ${jenkinsConfig.activeJdkVersions.findAll { it.enabled }.collect { it.version }.join(', ')}"
} catch (Exception e) {
    def errorMsg = """
ERROR: Failed to load Jenkins job configuration from configuration repository!

Configuration Repository: ${configRepoUrl}
Branch: ${configRepoBranch}
Configuration URL: https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/jenkins_job_config.json
Error: ${e.message}

The jenkins_job_config.json file must exist in the configuration repository.
This file defines which JDK versions are active and should have jobs created.

Please ensure:
1. The configuration repository is accessible
2. The jenkins_job_config.json file exists at the root
3. The file contains valid JSON with activeJdkVersions array
4. The CONFIG_REPO_URL and CONFIG_REPO_BRANCH parameters are correct

Seed job cannot proceed without this configuration.
""".stripIndent()
    
    println errorMsg
    throw new RuntimeException(errorMsg)
}

println "✓ Configuration loaded successfully\n"

// ============================================================================
// STEP 2: Create Launch Orchestrator Jobs
// ============================================================================

println "Creating launch orchestrator jobs for active JDK versions:"
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

println "✓ Launch orchestrator jobs created successfully\n"

// ============================================================================
// STEP 3: Create Seed Job (Self-Updating)
// ============================================================================

// Repository configuration
def repoUrl = 'https://github.com/andrew-m-leonard/ci-adoptium-pipelines.git'
def repoBranch = '*/main'
def repoCredentialsId = '' // Leave empty for public repos

freeStyleJob('seed-job') {
    displayName('Seed Job - Job Generator')
    description('''
        This job generates all other Jenkins jobs from Job DSL scripts.
        Run this job to create or update all pipeline jobs.

        This job is self-updating - it will recreate itself from the DSL script.

        Required Parameters:
        - CONFIG_REPO_URL: URL of the configuration repository (e.g., https://github.com/adoptium/ci-temurin-config.git)
        - CONFIG_REPO_BRANCH: Branch of the configuration repository (e.g., main)
    '''.stripIndent().trim())

    logRotator {
        daysToKeep(30)
        numToKeep(50)
    }

    parameters {
        stringParam('CONFIG_REPO_URL', '', 'URL of the configuration repository containing jenkins_job_config.json (REQUIRED)')
        stringParam('CONFIG_REPO_BRANCH', '', 'Branch of the configuration repository (REQUIRED)')
    }

    scm {
        git {
            remote {
                url(repoUrl)
                if (repoCredentialsId) {
                    credentials(repoCredentialsId)
                }
            }
            branch(repoBranch)
            extensions {
                cleanBeforeCheckout()
            }
        }
    }

    triggers {
        // Poll SCM every hour to detect changes in Job DSL scripts
        scm('H * * * *')
    }

    steps {
        dsl {
            // Process the consolidated seed job script
            external('ci/jenkins/job-dsl/seed/seed_job_consolidated.groovy')
            
            // NOTE: Scripts in ci/jenkins/job-dsl/ (not in seed/) are for dynamic job creation
            // openjdk_build_pipeline.groovy is called by launch jobs via jobDsl step
            // when REGENERATE_JOBS=true or when platform jobs don't exist yet.

            // Remove jobs that are no longer defined in DSL
            removeAction('DELETE')

            // Remove views that are no longer defined in DSL
            removeViewAction('DELETE')

            // Additional classpath for helper classes (if needed)
            additionalClasspath('ci/jenkins/job-dsl')
        }
    }

    publishers {
        // Send email notification on failure
        mailer('', false, true)
    }
}

println "✓ Seed job created successfully\n"

// ============================================================================
// STEP 4: Create Views
// ============================================================================

// Create a view to organize all generated jobs
listView('All Pipeline Jobs') {
    description('All OpenJDK build pipeline jobs')
    jobs {
        regex('.*pipeline.*')
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

// Create a view for launch orchestrator jobs
listView('JDK Launch Jobs') {
    description('Launch orchestrator jobs for coordinating platform builds')
    jobs {
        regex('.*/jdk\\d+-launch-build-pipelines')
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

// Create a view for platform-specific build jobs
listView('Platform Build Jobs') {
    description('Platform-specific build jobs (created dynamically by launch jobs)')
    jobs {
        regex('.*/jdk\\d+-[^-]+-build-pipeline')
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

println "✓ Views created successfully\n"
println "=" * 80
println "Seed job execution complete!"
println "=" * 80

// Made with Bob