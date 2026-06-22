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

println "=" * 80
println "SEED JOB CONFIGURATION"
println "=" * 80
println "CONFIG_REPO_URL: ${configRepoUrl ?: '(empty)'}"
println "CONFIG_REPO_BRANCH: ${configRepoBranch ?: '(empty)'}"
println "=" * 80
println ""

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

// Default values for job configuration
def defaultBuildVariant = jenkinsConfig.defaultVariant ?: 'temurin'
def defaultBuildArgs = jenkinsConfig.defaultBuildArgs ?: '--create-jre-image --create-sbom'
def defaultPipelineTimeoutHours = jenkinsConfig.pipelineTimeoutHours ?: 8
def pipelineRepoUrl = 'https://github.com/andrew-m-leonard/ci-adoptium-pipelines.git'
def pipelineRepoBranch = 'main'
def pipelineRepoCredentialsId = '' // Leave empty for public repos

// Create the openjdk-launch-pipelines folder for launch orchestrator jobs
folder('openjdk-launch-pipelines') {
    displayName('OpenJDK Launch Pipelines')
    description('Launch orchestrator jobs that coordinate builds across multiple platforms')
}

// Create the openjdk-builds folder for platform-specific build jobs
folder('openjdk-builds') {
    displayName('OpenJDK Platform Builds')
    description('Platform-specific build pipeline jobs organized by JDK version (created dynamically by launch jobs)')
}

println "Creating launch orchestrator jobs for active JDK versions:"
jenkinsConfig.activeJdkVersions.findAll { it.enabled }.each { versionInfo ->
    def version = versionInfo.version
    def configFile = "${jenkinsConfig.configFilePrefix ?: 'configurations/'}${version}${jenkinsConfig.configFileSuffix ?: '_pipeline_config.json'}"
    
    // Determine if LTS based on version number
    def versionNum = version.replaceAll(/[^\d]/, '').toInteger()
    def isLts = (versionNum == 8 || versionNum == 11 || versionNum == 17 || versionNum == 21)
    
    println "  → JDK ${version}${isLts ? ' [LTS]' : ''}"
    
    // Load platform configuration to get available platforms
    def platforms = []
    try {
        def repoPath = configRepoUrl.replaceAll(/^https?:\/\/github\.com\//, '').replaceAll(/\.git$/, '')
        def pipelineConfigUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/${configFile}"
        
        println "    Loading platforms from ${pipelineConfigUrl}"
        def pipelineConfigText = new URL(pipelineConfigUrl).text
        def pipelineConfig = new groovy.json.JsonSlurper().parseText(pipelineConfigText)
        
        platforms = pipelineConfig.buildConfigurations.keySet() as List
        platforms.sort()  // Sort alphabetically
        println "    Available platforms: ${platforms.join(', ')}"
    } catch (Exception e) {
        println "    WARNING: Could not load platform configuration: ${e.message}"
        println "    Using 'all' as default platform choice"
        platforms = ['all']
    }
    
    def jobName = "openjdk-launch-pipelines/${version}-launch-build-pipelines"
    
    pipelineJob(jobName) {
        displayName("${version} Launch Build Pipelines${isLts ? ' (LTS)' : ''}")
        description("""
            Launch orchestrator for JDK ${version} builds.
            ${isLts ? 'This is a Long Term Support (LTS) version.' : ''}
            
            This job:
            1. Reads platform configuration from: ${configFile}
            2. Optionally creates/updates platform-specific build jobs
            3. Launches builds for selected platforms in parallel
            4. Aggregates and reports results
            
            Platform-specific jobs created: openjdk-builds/${version}/${version}-\${platform}-build-pipeline
        """.stripIndent().trim())

        quietPeriod(5)

        parameters {
            // Configuration repository parameters (propagated from seed job)
            // These values are set when the seed job runs and should not normally be changed
            stringParam('CONFIG_REPO_URL', configRepoUrl ?: '',
                """URL of the configuration repository containing jenkins_job_config.json
                
                ⚠️  This value is automatically set by the seed job.
                Current value: ${configRepoUrl ?: '(NOT SET - Re-run seed job with CONFIG_REPO_URL parameter!)'}
                
                If this is empty, the seed job was not run with proper parameters.
                Re-run the seed job with CONFIG_REPO_URL and CONFIG_REPO_BRANCH parameters.""".stripIndent().trim())
            
            stringParam('CONFIG_REPO_BRANCH', configRepoBranch ?: '',
                """Branch of the configuration repository
                
                ⚠️  This value is automatically set by the seed job.
                Current value: ${configRepoBranch ?: '(NOT SET - Re-run seed job with CONFIG_REPO_BRANCH parameter!)'}
                
                If this is empty, the seed job was not run with proper parameters.
                Re-run the seed job with CONFIG_REPO_URL and CONFIG_REPO_BRANCH parameters.""".stripIndent().trim())
            
            // Pipeline configuration
            stringParam('PIPELINE_TIMEOUT_HOURS', defaultPipelineTimeoutHours.toString(),
                "Pipeline timeout in hours (default from config: ${defaultPipelineTimeoutHours})")
            
            // Job management
            booleanParam('REGENERATE_JOBS', false,
                'Force regeneration of platform-specific build jobs (use after config changes)')
            
            // Platform selection - dynamically populated from config with 'all' at the top
            choiceParam('PLATFORMS', ['all'] + platforms,
                'Select platform to build, or "all" for all available platforms')
            
            // Build configuration parameters (passed to platform jobs)
            stringParam('VARIANT', defaultBuildVariant,
                'Build variant (temurin, dragonwell, etc.)')
            
            stringParam('BUILD_ARGS', defaultBuildArgs,
                'Additional build arguments')
            
            booleanParam('CLEAN_WORKSPACE_AFTER_STAGE', true,
                'Clean workspace after each stage completes')
            
            booleanParam('RUN_TESTS', false,
                'Run test stages (smoke tests, AQA, TCK)')
            
            booleanParam('SIGN_ARTIFACTS', false,
                'Sign artifacts and installers')
            
            booleanParam('PUBLISH_ARTIFACTS', false,
                'Publish artifacts to release repository')
            
            booleanParam('RUN_REPRODUCIBLE_COMPARE', false,
                'Run reproducible build comparison')
        }

        definition {
            cpsScm {
                scm {
                    git {
                        remote {
                            url(pipelineRepoUrl)
                            if (pipelineRepoCredentialsId) {
                                credentials(pipelineRepoCredentialsId)
                            }
                        }
                        branch("*/${pipelineRepoBranch}")
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
    displayName('New OpenJDK Build CI - Seed Job - Job Generator')
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
        artifactDaysToKeep(-1)
        artifactNumToKeep(-1)
    }

    parameters {
        stringParam('CONFIG_REPO_URL', configRepoUrl ?: '',
            """⚠️  REQUIRED: URL of the configuration repository containing jenkins_job_config.json
            
            Example: https://github.com/adoptium/ci-temurin-config.git
            
            This parameter MUST be provided when running the seed job.
            The value will be propagated to all generated launch jobs.""".stripIndent().trim())
        
        stringParam('CONFIG_REPO_BRANCH', configRepoBranch ?: '',
            """⚠️  REQUIRED: Branch of the configuration repository
            
            Example: main
            
            This parameter MUST be provided when running the seed job.
            The value will be propagated to all generated launch jobs.""".stripIndent().trim())
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

// Create a view for launch orchestrator jobs
listView('JDK Pipeline Launchers') {
    description('Launch orchestrator jobs for coordinating platform builds')
    jobs {
        regex('openjdk-launch-pipelines/jdk\\d+-launch-build-pipelines|openjdk-launch-pipelines/\\d+-launch-build-pipelines')
    }
    recurse(true)  // Include jobs in folders
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
listView('JDK Build Platform Pipelines') {
    description('Platform-specific build jobs (created dynamically by launch jobs)')
    jobs {
        regex('openjdk-builds/jdk\\d+/jdk\\d+-[^-]+-build-pipeline|openjdk-builds/\\d+/\\d+-[^-]+-build-pipeline')
    }
    recurse(true)  // Include jobs in folders
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
