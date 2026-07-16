/**
 * Job DSL Script for Platform-Specific OpenJDK Build Pipeline Jobs
 *
 * This script creates platform-specific pipeline jobs for building OpenJDK.
 * It is called by the launch job with JDK_VERSION and PLATFORM parameters,
 * then loads the platform configuration to extract TARGET_OS, ARCHITECTURE, and VARIANT.
 *
 * Called by: Launch job (Jenkinsfile.launch)
 * Creates: Build_openjdk<version>_<distro>_<arch>_<os> jobs under /Build_openjdk/
 *
 * Naming convention (mirrors AQA Test job naming):
 *   Build_<version>_<distro>_<arch>_<os>
 *   e.g. Build_openjdk21_temurin_x86-64_linux
 *        Build_openjdk17_dragonwell_aarch64_mac
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
 *   - VARIANT/DISTRO: Build distro (e.g., "temurin", "dragonwell", "corretto")
 *
 * Stage parameters are collated dynamically from scripts/stages/*.params.json (defaults)
 * merged with config-repo/vendor-scripts/*.params.json (vendor overrides) via
 * scripts/lib/collect-stage-params.py. The collated list is emitted as job parameters
 * grouped by Jenkins Parameter Separators, plus a hidden STAGE_PARAM_NAMES meta-parameter
 * that Jenkinsfile.launch uses to forward all stage params to platform build jobs.
 */

import groovy.json.JsonSlurper

// ---------------------------------------------------------------------------
// Helper: run collect-stage-params.py and return the parsed collated output.
//
// Called once per job generation. Uses --vendor-raw-base-url so no local
// config-repo checkout is needed at Job DSL evaluation time.
//
// Runs via 'sh -c' so that the shell resolves python3/python on PATH — plain
// JVM Runtime.exec() does not perform shell PATH lookups and fails on agents
// where python3 is installed via pyenv, nix, or a non-standard prefix.
//
// Returns a Map with keys:
//   groups    — List of [ name, description, stageId, parameters: [...] ]
//   paramNames — List of all parameter name strings
// ---------------------------------------------------------------------------
def fetchCollatedStageParams(String repoPath, String branch) {
    def rawBase = "https://raw.githubusercontent.com/${repoPath}/${branch}"
    def tmpOut  = File.createTempFile('collated-stage-params', '.json')
    tmpOut.deleteOnExit()

    // Run via 'sh -c' with the job workspace as CWD so relative paths
    // (scripts/lib/python-runner.sh, scripts/stages/) resolve correctly.
    // The List.execute(envp, workDir) overload sets the subprocess working dir.
    def shellCmd = "scripts/lib/python-runner.sh scripts/lib/collect-stage-params.py" +
        " --default-stages-dir scripts/stages" +
        " --vendor-raw-base-url '${rawBase}'" +
        " --output '${tmpOut.absolutePath}'"

    def proc = ['sh', '-c', shellCmd].execute(null, new File('.'))
    proc.waitFor()
    if (proc.exitValue() != 0) {
        println "WARNING: collect-stage-params.py failed (exit ${proc.exitValue()}) — " +
                "continuing with no collated stage params.\n${proc.err.text}"
        return [groups: [], paramNames: []]
    }
    println proc.out.text.trim()

    return new JsonSlurper().parseText(tmpOut.text)
}

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

// Collate stage parameters from default *.params.json files merged with any
// vendor-scripts/*.params.json overrides from the config repo.
// Runs collect-stage-params.py at job-generation time against the config repo
// raw URL so no local checkout is required.
def collatedStageParams = fetchCollatedStageParams(repoPath, configRepoBranch)
println "✓ Collated ${collatedStageParams.paramNames?.size() ?: 0} stage parameter(s) " +
        "across ${collatedStageParams.groups?.size() ?: 0} group(s)"

// Ensure the top-level Build_openjdk folder exists.
// All build jobs (both launch orchestrators and platform builds) live here,
// mirroring the single top-level folder used by AQA test jobs.
folder('/Build_openjdk') {
    displayName('Build_openjdk')
    description('OpenJDK platform build pipeline jobs, named using the AQA-style Build_<version>_<distro>_<arch>_<os> convention')
}

// Build the AQA-style job name:
//   Build_openjdk<version>_<distro>_<arch>_<os>
// arch values follow AQA conventions (x86-64, aarch64, ppc64le, s390x, ppc64, aarch32, x86-32)
// os   values follow AQA conventions (linux, mac, windows, aix, solaris)
def archName = architecture   // already in AQA arch format from platform config (e.g. "x86-64", "aarch64")
def osName   = targetOs       // already in AQA os format from platform config  (e.g. "linux", "mac")
def jobName  = "/Build_openjdk/Build_openjdk${jdkVersion}_${variant}_${archName}_${osName}"

println "Creating platform-specific build job: ${jobName}"

pipelineJob(jobName) {
    displayName("Build_openjdk${jdkVersion}_${variant}_${archName}_${osName}")
    description("""
        Platform-specific build pipeline for OpenJDK ${jdkVersion} (${variant}) on ${archName}/${osName}.

        Job name follows AQA-style convention:
          Build_openjdk<version>_<distro>_<arch>_<os>

        This job executes the complete build pipeline including:
        - Build
        - Test (if enabled)
        - Sign (if enabled)
        - Publish (if enabled)

        This pipeline supports restart from any stage and tracks build state across restarts.
    """.stripIndent().trim())

    quietPeriod(5)

    parameters {
        // ── Fixed / infrastructure ────────────────────────────────────────
        // These are set at job-generation time from platform config and are
        // not visible as operator-editable fields on the "Build with Parameters"
        // form because their values are fixed for the lifetime of this job.
        stringParam('JDK_VERSION', jdkVersion,
            'JDK version number — fixed at job-generation time from platform config')
        stringParam('TARGET_OS', targetOs,
            'Target operating system — fixed at job-generation time from platform config')
        stringParam('ARCHITECTURE', architecture,
            'Target CPU architecture — fixed at job-generation time from platform config')
        stringParam('CONFIG_REPO_URL', configRepoUrl,
            'URL of the configuration repository (set by seed job)')
        stringParam('CONFIG_REPO_BRANCH', configRepoBranch,
            'Branch of the configuration repository (set by seed job)')
        stringParam('GROUP_UID', '',
            'Group identifier linking all platform builds from the same launch. Auto-generated by the launch job if empty.')
        stringParam('INITIALIZE_LABEL', initializeLabel,
            'Agent label for the Initialize stage — fixed at job-generation time from stageAgentLabels.Initialize in jenkins_job_config.json')
        stringParam('ACTIVE_NODE_TIMEOUT',
            (jenkinsConfig?.activeNodeTimeoutMinutes ?: 10).toString(),
            'Minutes to wait for an active agent before failing. Set from activeNodeTimeoutMinutes in jenkins_job_config.json.')

        // ── Pipeline gate flags ───────────────────────────────────────────
        // Control which stages run. These are pipeline-level decisions that
        // span all stage scripts and are not stage-script-specific.
        booleanParam('RUN_TESTS',
            defaultParams?.RUN_TESTS != null ? defaultParams.RUN_TESTS : true,
            'Run test stages (smoke tests, AQA, TCK)')
        booleanParam('SIGN_ARTIFACTS',
            defaultParams?.SIGN_ARTIFACTS != null ? defaultParams.SIGN_ARTIFACTS : false,
            'Sign artifacts and installers')
        booleanParam('PUBLISH_ARTIFACTS',
            defaultParams?.PUBLISH_ARTIFACTS != null ? defaultParams.PUBLISH_ARTIFACTS : false,
            'Publish artifacts to release repository')
        booleanParam('ENABLE_INSTALLERS',
            defaultParams?.ENABLE_INSTALLERS != null ? defaultParams.ENABLE_INSTALLERS : true,
            'Build platform-specific installers')
        booleanParam('ENABLE_TCK',
            false,
            'Run TCK tests (Temurin only, release/weekly builds)')
        booleanParam('RUN_REPRODUCIBLE_COMPARE',
            defaultParams?.RUN_REPRODUCIBLE_COMPARE != null ? defaultParams.RUN_REPRODUCIBLE_COMPARE : false,
            'Run reproducible build comparison against a production Adoptium binary')
        booleanParam('REPRODUCIBLE_COMPARE_BUILD',
            defaultParams?.RUN_REPRODUCIBLE_COMPARE != null ? defaultParams.RUN_REPRODUCIBLE_COMPARE : false,
            'Enable reproducible build comparison (requires SCM_REF to be set)')
        booleanParam('CLEAN_WORKSPACE_AFTER_STAGE',
            defaultParams?.CLEAN_WORKSPACE_AFTER_STAGE != null ? defaultParams.CLEAN_WORKSPACE_AFTER_STAGE : true,
            'Clean workspace after each stage completes')
        choiceParam('RELEASE_TYPE',
            ['NIGHTLY', 'WEEKLY', 'RELEASE'],
            'Type of release build (NIGHTLY = default nightly, WEEKLY = EA beta, RELEASE = official)')

        // ── Collated stage parameters ─────────────────────────────────────
        // Dynamically generated from scripts/stages/*.params.json (defaults)
        // merged with config-repo/vendor-scripts/*.params.json (vendor overrides)
        // via scripts/lib/collect-stage-params.py at job-generation time.
        //
        // Parameters are grouped by parameterGroup using Jenkins Parameter
        // Separator plugin entries injected via the configure block below.
        // The flat STAGE_PARAM_NAMES meta-param lets Jenkinsfile.launch forward
        // every stage param to platform builds without hardcoding their names.

        // Hidden meta-param: comma-separated list of all collated stage param names.
        // Used by Jenkinsfile.launch to forward them all dynamically.
        stringParam('STAGE_PARAM_NAMES',
            (collatedStageParams.paramNames ?: []).join(','),
            'Collated stage parameter names — set at job-generation time, do not edit manually')

        // Emit each collated stage parameter
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
    
    // Configure block: inject Parameter Separator entries for each collated stage
    // parameter group, and set copyArtifact permission.
    //
    // Jenkins Parameter Separator plugin (io.jenkins.plugins.parameter_separator):
    //   Each separator is inserted into the parameter definitions list immediately
    //   before the first parameter of its group, giving operators a clear visual
    //   heading and description on the "Build with Parameters" form.
    configure { project ->
        // ── Parameter Separators ─────────────────────────────────────────
        // Build an ordered list of parameter definition XML nodes:
        //   existing fixed/gate params  (already added by parameters{} block)
        //   then for each collated group:
        //     <separator name="__sep_<group>" sectionHeader="<group>" ... />
        //     <param name="PARAM_1" ... />
        //     <param name="PARAM_2" ... />
        //
        // The Job DSL parameters{} block inserts plain <hudson.model.*Parameter>
        // nodes into the XML. The configure block can append separator nodes to
        // the same parameterDefinitions list.
        if (collatedStageParams.groups) {
            def paramDefs = project / 'properties'
                / 'hudson.model.ParametersDefinitionProperty'
                / 'parameterDefinitions'

            collatedStageParams.groups.each { group ->
                if (!group.parameters) return

                // Insert a separator node before the group's parameters
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

                // Move each group param node to immediately after its separator.
                // The parameters{} block already added them to paramDefs — we just
                // need to re-order them so they sit after the separator.
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

        // ── copyArtifact permission ──────────────────────────────────────
        project / 'properties' / 'hudson.plugins.copyartifact.CopyArtifactPermissionProperty' {
            projectNameList {
                string('*')
            }
        }
    }
}

println "✓ Platform-specific build job created: ${jobName}"

// Made with Bob
