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
            // Process all Job DSL scripts in the job-dsl directory
            external('ci/jenkins/job-dsl/*.groovy')

            // Remove jobs that are no longer defined in DSL
            removeAction('DELETE')

            // Remove views that are no longer defined in DSL
            removeViewAction('DELETE')

            // Additional seed job configuration
            additionalClasspath('ci/jenkins/job-dsl')
        }
    }

    publishers {
        // Send email notification on failure
        mailer('', false, true)
    }
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

// Create a view for JDK version-specific jobs
listView('JDK Version Builds') {
    description('Build jobs organized by JDK version')
    jobs {
        regex('openjdk-builds/jdk.*')
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
