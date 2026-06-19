/**
 * Seed Job DSL Script
 *
 * This is the bootstrap job that creates all other jobs from the Job DSL scripts.
 * Run this once to set up a new Jenkins instance with all required jobs.
 *
 * Usage:
 *   1. Create a new Freestyle job in Jenkins named "seed-job"
 *   2. Add "Process Job DSLs" build step
 *   3. Point it to this script
 *   4. Run the job to create all pipeline jobs
 */

// Repository configuration
def repoUrl = 'https://github.com/andrew-m-leonard/ci-adoptium-pipelines.git'
def repoBranch = '*/main'
def repoCredentialsId = '' // Leave empty for public repos

// Create the seed job itself (self-updating)
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
            // Process Job DSL scripts in specific order from seed subdirectory
            // 1. Load configuration first
            external('ci/jenkins/job-dsl/seed/load_config.groovy')
            // 2. Create launch orchestrator jobs
            external('ci/jenkins/job-dsl/seed/openjdk_launch_pipeline.groovy')
            // 3. Recreate seed job (self-updating)
            external('ci/jenkins/job-dsl/seed/seed_job.groovy')
            
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

// Create folders for organizing jobs
folder('openjdk-launch-pipelines') {
    displayName('OpenJDK Launch Pipelines')
    description('Launch orchestrator jobs that coordinate builds across multiple platforms')
}

folder('openjdk-builds') {
    displayName('OpenJDK Build Jobs')
    description('Platform-specific build pipeline jobs (created dynamically by launch jobs)')
}

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
        regex('openjdk-launch-pipelines/jdk\\d+-launch-build-pipelines')
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
        regex('openjdk-builds/jdk\\d+/jdk\\d+-[^-]+-build-pipeline')
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

// Made with Bob
