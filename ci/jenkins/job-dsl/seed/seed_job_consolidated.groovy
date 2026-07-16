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
 *
 * Stage parameters are collated dynamically from scripts/stages/*.params.json (defaults)
 * merged with config-repo/vendor-scripts/*.params.json (vendor overrides) via
 * scripts/lib/collect-stage-params.py. The same collated list is emitted on both the
 * launch job and each platform build job so operators can set all params once on the
 * launch job and have them forwarded to every platform build automatically.
 */

import groovy.json.JsonSlurper

// ---------------------------------------------------------------------------
// Helper: resolve the Python interpreter available on this agent.
// Tries python3 first, falls back to python. Throws if neither is found.
// Defined in both Job DSL files since they cannot share loaded Groovy scripts.
//
// Uses 'sh -c "command -v ..."' so the probe goes through the shell's PATH
// (plain .execute() on the JVM may miss entries that only appear in the shell
// environment, e.g. pyenv shims, nix profiles, custom PATH in .bashrc).
// ---------------------------------------------------------------------------
def resolvePython() {
    for (candidate in ['python3', 'python']) {
        try {
            def probe = ['sh', '-c', "command -v ${candidate}"].execute()
            probe.waitFor()
            if (probe.exitValue() == 0) {
                return candidate
            }
        } catch (IOException ignored) {
            // candidate not on PATH — try next
        }
    }
    throw new RuntimeException(
        "No Python interpreter found on PATH. " +
        "Ensure python3 or python is installed on the Jenkins agent."
    )
}

// ---------------------------------------------------------------------------
// Helper: run collect-stage-params.py and return the parsed collated output.
// Uses --vendor-raw-base-url so no local config-repo checkout is needed at
// Job DSL evaluation time.
//
// Returns a Map with keys:
//   groups    — List of [ name, description, stageId, parameters: [...] ]
//   paramNames — List of all parameter name strings (for STAGE_PARAM_NAMES)
// ---------------------------------------------------------------------------
def fetchCollatedStageParams(String repoPath, String branch) {
    def rawBase = "https://raw.githubusercontent.com/${repoPath}/${branch}"
    def tmpOut  = File.createTempFile('collated-stage-params', '.json')
    tmpOut.deleteOnExit()

    def python = resolvePython()
    def cmd = [
        python, 'scripts/lib/collect-stage-params.py',
        '--default-stages-dir', 'scripts/stages',
        '--vendor-raw-base-url', rawBase,
        '--output', tmpOut.absolutePath
    ]
    def proc = cmd.execute()
    proc.waitFor()
    if (proc.exitValue() != 0) {
        println "WARNING: collect-stage-params.py failed (exit ${proc.exitValue()}) — " +
                "continuing with no collated stage params.\n${proc.err.text}"
        return [groups: [], paramNames: []]
    }
    println proc.out.text.trim()

    return new JsonSlurper().parseText(tmpOut.text)
}

// ---------------------------------------------------------------------------
// Helper: emit collated stage parameter definitions into a parameters{} block.
// Call this inside a parameters { } closure — it emits stringParam and
// booleanParam calls for every entry in collatedStageParams.
// ---------------------------------------------------------------------------
def emitCollatedParams(def collatedStageParams) {
    // Hidden meta-param forwarded to platform builds by Jenkinsfile.launch
    stringParam('STAGE_PARAM_NAMES',
        (collatedStageParams.paramNames ?: []).join(','),
        'Collated stage parameter names — set at job-generation time, do not edit manually')

    collatedStageParams.groups?.each { group ->
        group.parameters?.each { p ->
            if (p.type == 'boolean') {
                booleanParam(p.name, p.default == true, p.description ?: '')
            } else {
                stringParam(p.name, p.default ?: '', p.description ?: '')
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helper: inject Parameter Separator nodes for each collated group into the
// job's parameterDefinitions XML via a configure{} block.
// ---------------------------------------------------------------------------
def injectParamSeparators(def project, def collatedStageParams) {
    if (!collatedStageParams.groups) return

    def paramDefs = project / 'properties'
        / 'hudson.model.ParametersDefinitionProperty'
        / 'parameterDefinitions'

    collatedStageParams.groups.each { group ->
        if (!group.parameters) return

        def sepNode = paramDefs.appendNode(
            'io.jenkins.plugins.parameter__separator.ParameterSeparatorDefinition'
        )
        sepNode.appendNode('name', "__sep_${group.stageId}_${group.name.replaceAll(/\W+/, '_')}")
        sepNode.appendNode('sectionHeader', "${group.name}  [stage: ${group.stageId}]")
        sepNode.appendNode('sectionHeaderStyle', '')
        if (group.description) {
            sepNode.appendNode('sectionDescription', group.description)
        }
        sepNode.appendNode('separatorStyle', '')

        group.parameters.each { p ->
            def paramNode = paramDefs.'*'.find { node ->
                node.'name'?.text() == p.name
            }
            if (paramNode) {
                paramDefs.remove(paramNode)
                paramDefs.append(paramNode)
            }
        }
    }
}

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

// -------------------------------------------------------------------------
// Load CI-agnostic pipeline configuration (adoptium_pipeline_config.json)
// -------------------------------------------------------------------------
def pipelineConfig
try {
    def configUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/adoptium_pipeline_config.json"
    println "Loading Adoptium pipeline configuration from ${configUrl}"
    println "  Repository: ${configRepoUrl}"
    println "  Branch: ${configRepoBranch}"
    
    def configText = new URL(configUrl).text
    pipelineConfig = new JsonSlurper().parseText(configText)
    println "✓ Successfully loaded adoptium_pipeline_config.json"
    println "  Active JDK versions: ${pipelineConfig.activeJdkVersions.findAll { it.enabled }.collect { it.version }.join(', ')}"
} catch (Exception e) {
    def errorMsg = """
ERROR: Failed to load adoptium_pipeline_config.json from configuration repository!

Configuration Repository: ${configRepoUrl}
Branch: ${configRepoBranch}
Configuration URL: https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/adoptium_pipeline_config.json
Error: ${e.message}

The adoptium_pipeline_config.json file must exist in the configuration repository.
This file defines CI-agnostic settings: active JDK versions, default build args, repository info.

Please ensure:
1. The configuration repository is accessible
2. The adoptium_pipeline_config.json file exists at the root
3. The file contains valid JSON with activeJdkVersions array
4. The CONFIG_REPO_URL and CONFIG_REPO_BRANCH parameters are correct

Seed job cannot proceed without this configuration.
""".stripIndent()
    
    println errorMsg
    throw new RuntimeException(errorMsg)
}

// -------------------------------------------------------------------------
// Load Jenkins-specific job configuration (jenkins_job_config.json)
// -------------------------------------------------------------------------
def jenkinsConfig
try {
    def configUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/jenkins_job_config.json"
    println "Loading Jenkins job configuration from ${configUrl}"
    
    def configText = new URL(configUrl).text
    jenkinsConfig = new JsonSlurper().parseText(configText)
    println "✓ Successfully loaded jenkins_job_config.json"
} catch (Exception e) {
    def errorMsg = """
ERROR: Failed to load jenkins_job_config.json from configuration repository!

Configuration Repository: ${configRepoUrl}
Branch: ${configRepoBranch}
Configuration URL: https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/jenkins_job_config.json
Error: ${e.message}

The jenkins_job_config.json file must exist in the configuration repository.
This file defines Jenkins-specific settings: job parameters, log rotation, Jenkinsfile path.
""".stripIndent()
    
    println errorMsg
    throw new RuntimeException(errorMsg)
}

println "✓ Configuration loaded successfully\n"

// ============================================================================
// STEP 2: Create Launch Orchestrator Jobs
// ============================================================================

// Default values from CI-agnostic pipeline config
def defaultBuildVariant = pipelineConfig.defaultVariant ?: 'temurin'
def defaultBuildArgs = pipelineConfig.defaultBuildArgs ?: '--create-jre-image --create-sbom'
def pipelineRepoUrl = pipelineConfig.repository?.url ?: 'https://github.com/andrew-m-leonard/ci-adoptium-pipelines.git'
def pipelineRepoBranch = pipelineConfig.repository?.branch ?: 'main'
def pipelineRepoCredentialsId = pipelineConfig.repository?.credentialsId ?: ''

// Default values from Jenkins-specific config
def defaultPipelineTimeoutHours = jenkinsConfig.pipelineTimeoutHours ?: 8
def defaultParams = jenkinsConfig.jobConfiguration?.defaultParameters ?: [:]

// Top-level folder for launch orchestrator jobs
folder('Build_openjdk_launchers') {
    displayName('Build_openjdk_launchers')
    description('Launch orchestrator jobs that trigger platform-specific builds across all selected platforms for a given JDK version')
}

// Top-level folder for platform build jobs (AQA-style naming)
folder('Build_openjdk') {
    displayName('Build_openjdk')
    description('OpenJDK platform build pipeline jobs, named using the AQA-style Build_openjdk<version>_<distro>_<arch>_<os> convention')
}

// Collate stage parameters once — shared across all launch job versions.
// The same collated set is used for every JDK version's launch job so that
// all launch jobs present an identical stage-parameter surface to operators.
def collatedStageParams = fetchCollatedStageParams(repoPath, configRepoBranch)
println "✓ Collated ${collatedStageParams.paramNames?.size() ?: 0} stage parameter(s) " +
        "across ${collatedStageParams.groups?.size() ?: 0} group(s) for launch jobs"

println "Creating launch orchestrator jobs for active JDK versions:"
pipelineConfig.activeJdkVersions.findAll { it.enabled }.each { versionInfo ->
    def version = versionInfo.version
    def configFile = "${pipelineConfig.configFilePrefix ?: 'configurations/'}${version}${pipelineConfig.configFileSuffix ?: '_pipeline_config.json'}"
    
    // Determine if LTS based on version number
    // LTS versions: 8, 11, then every 4 versions from 17 onwards (17, 21, 25, 29, 33, ...)
    def versionNum = version.replaceAll(/[^\d]/, '').toInteger()
    def isLts = (versionNum == 8 || versionNum == 11 || (versionNum >= 17 && (versionNum - 17) % 4 == 0))
    
    println "  → JDK ${version}${isLts ? ' [LTS]' : ''}"
    
    // Load platform configuration to get available platforms
    def platforms = []
    try {
        def jdkConfigUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/${configFile}"
        
        println "    Loading platforms from ${jdkConfigUrl}"
        def jdkConfigText = new URL(jdkConfigUrl).text
        def jdkConfig = new groovy.json.JsonSlurper().parseText(jdkConfigText)
        
        platforms = jdkConfig.buildConfigurations.keySet() as List
        platforms.sort()  // Sort alphabetically
        println "    Available platforms: ${platforms.join(', ')}"
    } catch (Exception e) {
        println "    WARNING: Could not load platform configuration: ${e.message}"
        println "    Using 'all' as default platform choice"
        platforms = ['all']
    }
    
    // Launch orchestrator job lives in the Build_openjdk_launchers folder.
    // Name pattern: Build_openjdk_launchers/Build_openjdk<version>_launch
    def jobName = "Build_openjdk_launchers/Build_openjdk${version.replaceAll(/[^\d]/, '')}_launch"

    pipelineJob(jobName) {
        displayName("Build_openjdk${version.replaceAll(/[^\d]/, '')}_launch${isLts ? ' (LTS)' : ''}")
        description("""
            Launch orchestrator for JDK ${version} builds.
            ${isLts ? 'This is a Long Term Support (LTS) version.' : ''}

            This job:
            1. Reads platform configuration from: ${configFile}
            2. Optionally creates/updates platform-specific build jobs
            3. Launches builds for selected platforms in parallel
            4. Aggregates and reports results

            Platform-specific jobs created under Build_openjdk/ follow the AQA-style naming:
              Build_openjdk${version.replaceAll(/[^\d]/, '')}_<distro>_<arch>_<os>

            Stage parameters are collated from scripts/stages/*.params.json and any
            vendor-scripts/*.params.json overrides in the config repo. All collated
            params are forwarded automatically to every platform build job launched.
        """.stripIndent().trim())

        quietPeriod(5)

        parameters {
            // ── Fixed / infrastructure ────────────────────────────────────
            stringParam('JDK_VERSION', version.replaceAll(/[^\d]/, ''),
                'JDK version number — fixed for this launch job')
            stringParam('CONFIG_REPO_URL', configRepoUrl ?: '',
                """URL of the configuration repository (set by seed job).
                ⚠️  Current value: ${configRepoUrl ?: '(NOT SET — re-run seed job with CONFIG_REPO_URL)'}""".stripIndent().trim())
            stringParam('CONFIG_REPO_BRANCH', configRepoBranch ?: '',
                """Branch of the configuration repository (set by seed job).
                ⚠️  Current value: ${configRepoBranch ?: '(NOT SET — re-run seed job with CONFIG_REPO_BRANCH)'}""".stripIndent().trim())
            stringParam('GROUP_UID', '',
                'Group identifier for this launch run. Auto-generated if empty.')

            // ── Launch-job-only controls ──────────────────────────────────
            // These are not forwarded to platform build jobs.
            booleanParam('REGENERATE_JOBS', false,
                'Force regeneration of platform-specific build jobs (use after config changes)')
            choiceParam('PLATFORMS', ['all'] + platforms,
                'Select platform to build, or "all" for all available platforms')

            // ── Pipeline gate flags ───────────────────────────────────────
            stringParam('BUILD_ARGS', defaultBuildArgs,
                'Additional build arguments passed to the build stage')
            choiceParam('RELEASE_TYPE',
                ['NIGHTLY', 'WEEKLY', 'RELEASE'],
                'Type of release build (NIGHTLY = default nightly, WEEKLY = EA beta, RELEASE = official)')
            booleanParam('RUN_TESTS',
                defaultParams?.RUN_TESTS != null ? defaultParams.RUN_TESTS : false,
                'Run test stages (smoke tests, AQA, TCK)')
            booleanParam('ENABLE_INSTALLERS',
                defaultParams?.ENABLE_INSTALLERS != null ? defaultParams.ENABLE_INSTALLERS : true,
                'Build platform-specific installers')
            booleanParam('SIGN_ARTIFACTS',
                defaultParams?.SIGN_ARTIFACTS != null ? defaultParams.SIGN_ARTIFACTS : false,
                'Sign artifacts and installers')
            booleanParam('PUBLISH_ARTIFACTS',
                defaultParams?.PUBLISH_ARTIFACTS != null ? defaultParams.PUBLISH_ARTIFACTS : false,
                'Publish artifacts to release repository')
            booleanParam('RUN_REPRODUCIBLE_COMPARE',
                defaultParams?.RUN_REPRODUCIBLE_COMPARE != null ? defaultParams.RUN_REPRODUCIBLE_COMPARE : false,
                'Run reproducible build comparison against a production Adoptium binary')

            // ── Collated stage parameters ─────────────────────────────────
            // Same set as the platform build jobs. Set values here once and
            // Jenkinsfile.launch forwards them to every platform build job.
            emitCollatedParams(collatedStageParams)
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

        configure { project ->
            injectParamSeparators(project, collatedStageParams)
        }
    }
}

println "✓ Launch orchestrator jobs created successfully\n"

// ============================================================================
// STEP 3: Create Seed Job (Self-Updating)
// ============================================================================

// Repository configuration (from CI-agnostic pipeline config)
def repoUrl = pipelineConfig.repository?.url ?: 'https://github.com/andrew-m-leonard/ci-adoptium-pipelines.git'
def repoBranch = "*/${pipelineConfig.repository?.branch ?: 'main'}"
def repoCredentialsId = pipelineConfig.repository?.credentialsId ?: ''

freeStyleJob('openjdk-build-seed-job') {
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
        // Poll SCM every 15 mins to detect changes in Job DSL scripts
        scm('H/15 * * * *')
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
listView('Build_openjdk_launchers') {
    description('Launch orchestrator jobs for coordinating platform builds (Build_openjdk<version>_launch)')
    jobs {
        regex('Build_openjdk_launchers/Build_openjdk\\d+_launch')
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
listView('Build_openjdk') {
    description('Platform-specific build jobs — AQA-style naming: Build_openjdk<version>_<distro>_<arch>_<os>')
    jobs {
        regex('Build_openjdk/Build_openjdk\\d+_[^_]+_[^_]+_[^_]+')
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
