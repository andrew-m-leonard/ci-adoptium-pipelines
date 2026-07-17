/**
 * Consolidated Seed Job DSL Script
 *
 * Run by Jenkinsfile.seed (in this directory) via the jobDsl() step.
 * Do not run this directly as a Freestyle Job DSL step.
 *
 * What this script does:
 *   1. Reads adoptium_pipeline_config.json and jenkins_job_config.json from
 *      the vendor config repo checkout in the workspace root.
 *   2. Collates stage parameters from:
 *        pipelines/scripts/stages/*.params.json  (defaults, in workspace)
 *        vendor-scripts/*.params.json            (vendor overrides, in workspace)
 *   3. Creates Build_openjdk_launchers/ folder and one launch job per enabled
 *      JDK version, each carrying the full collated stage parameter set.
 *   4. Creates Build_openjdk/ folder and Jenkins views.
 *
 * Workspace layout (set up by Jenkinsfile.seed):
 *   <workspace>/
 *     adoptium_pipeline_config.json   — vendor config repo root (SCM checkout)
 *     jenkins_job_config.json         — vendor config repo root
 *     configurations/                 — per-version platform configs
 *     vendor-scripts/                 — vendor stage param overrides
 *     pipelines/                      — ci-adoptium-pipelines checkout
 *       scripts/stages/               — default *.params.json files
 *       ci/jenkins/job-dsl/
 *
 * Binding variables (passed via additionalParameters from Jenkinsfile.seed):
 *   CONFIG_REPO_URL    — vendor config repo URL (baked into generated launch jobs)
 *   CONFIG_REPO_BRANCH — vendor config repo branch (baked into generated launch jobs)
 *   PARAM_STEMS        — comma-separated list of default stage param stems
 *   VENDOR_STEMS       — comma-separated list of vendor override stems (may be empty)
 */

import groovy.json.JsonSlurper

// ---------------------------------------------------------------------------
// Helper: collate stage parameters.
//
// Uses readFileFromWorkspace(path) — the Job DSL native method for reading
// files from the current workspace. Much simpler than FilePath.
//
//   defaultStagesDir  — workspace-relative path to pipelines/scripts/stages/
//   vendorScriptsDir  — workspace-relative path to vendor-scripts/
//   vendorStemSet     — Set of stems that have a vendor override file
//
// Returns a Map with keys:
//   groups     — List of [ name, description, stageId, parameters: [...] ]
//   paramNames — List of all parameter name strings (for STAGE_PARAM_NAMES)
// ---------------------------------------------------------------------------
def collateStageParams(String defaultStagesDir,
                       String vendorScriptsDir,
                       Set    vendorStemSet) {
    def slurper       = new JsonSlurper()
    def outputGroups  = []
    def allParamNames = [:]

    // Stems were discovered in the caller using FilePath.list() — passed in as vendorStemSet.
    // Default stems are discovered the same way and passed via the PARAM_STEMS binding.
    def stems = (binding.variables.get('PARAM_STEMS').split(',') as List)
    println "  Discovered param stems: ${stems}"

    stems.each { stem ->
        def defaultData = slurper.parseText(
            readFileFromWorkspace("${defaultStagesDir}/${stem}.params.json")
        )

        def vendorData = null
        if (vendorStemSet.contains(stem)) {
            vendorData = slurper.parseText(
                readFileFromWorkspace("${vendorScriptsDir}/${stem}.params.json")
            )
            println "  [${stem}] vendor override loaded"
        } else {
            println "  [${stem}] no vendor override"
        }

        def defaultGroups = [:]
        def paramToGroup  = [:]
        defaultData.parameterGroups?.each { grp ->
            defaultGroups[grp.name] = [
                name:        grp.name,
                description: grp.description ?: '',
                stageId:     stem,
                parameters:  new ArrayList(grp.parameters ?: [])
            ]
            grp.parameters?.each { p -> paramToGroup[p.name] = grp.name }
        }

        if (vendorData) {
            def ignored = vendorData.ignoreDefaultParams ?: []
            if (ignored) {
                println "  [${stem}] ignoreDefaultParams: ${ignored}"
            }
            ignored.each { name ->
                def gname = paramToGroup[name]
                if (gname) {
                    defaultGroups[gname].parameters.removeAll { it.name == name }
                    println "  [${stem}]   suppressed '${name}' from group '${gname}'"
                    if (!defaultGroups[gname].parameters) {
                        defaultGroups.remove(gname)
                        println "  [${stem}]   group '${gname}' removed (no params remaining)"
                    }
                } else {
                    println "  [${stem}]   WARNING: ignoreDefaultParams '${name}' not found in any default group — ignored"
                }
            }
            vendorData.parameterGroups?.each { vgrp ->
                if (defaultGroups.containsKey(vgrp.name)) {
                    def existing = defaultGroups[vgrp.name].parameters.collectEntries { [it.name, it] }
                    vgrp.parameters?.each { vp ->
                        existing[vp.name] = vp
                        println "  [${stem}]   vendor added/overrode '${vp.name}' in group '${vgrp.name}'"
                    }
                    defaultGroups[vgrp.name].parameters = existing.values().toList()
                } else {
                    defaultGroups[vgrp.name] = [
                        name:        vgrp.name,
                        description: vgrp.description ?: '',
                        stageId:     stem,
                        parameters:  new ArrayList(vgrp.parameters ?: [])
                    ]
                    println "  [${stem}]   vendor added new group '${vgrp.name}': ${vgrp.parameters?.collect { it.name }}"
                }
            }
        }

        defaultGroups.values().each { grp ->
            def clean = grp.parameters.findAll { p ->
                if (allParamNames.containsKey(p.name)) {
                    println "WARNING: duplicate param '${p.name}' in ${stem}/${grp.name} — skipping"
                    return false
                }
                allParamNames[p.name] = "${stem}/${grp.name}"
                return true
            }
            if (clean) {
                println "  [${stem}] group '${grp.name}': ${clean.collect { it.name }}"
                outputGroups << [name: grp.name, description: grp.description,
                                 stageId: grp.stageId, parameters: clean]
            }
        }
    }

    def paramNames = allParamNames.keySet().toList()
    println "  Total collated params: ${paramNames}"
    return [groups: outputGroups, paramNames: paramNames]
}

// ============================================================================
// STEP 1: Validate binding variables
// ============================================================================

// WORKSPACE, PARAM_STEMS, VENDOR_STEMS, CONFIG_REPO_URL and CONFIG_REPO_BRANCH
// are all passed in via additionalParameters from Jenkinsfile.seed.
// PARAM_STEMS and VENDOR_STEMS are comma-separated stem lists pre-computed by
// Jenkinsfile.seed using shell glob, since Job DSL file-listing is awkward.

def configRepoUrl    = binding.variables.get('CONFIG_REPO_URL')    ?: ''
def configRepoBranch = binding.variables.get('CONFIG_REPO_BRANCH') ?: ''

if (!configRepoUrl?.trim()) {
    throw new RuntimeException(
        "CONFIG_REPO_URL is required but was not provided.\n" +
        "Set it as a parameter on the seed job (see docs/JOB_DSL_AUTOMATION.md)."
    )
}
if (!configRepoBranch?.trim()) {
    throw new RuntimeException(
        "CONFIG_REPO_BRANCH is required but was not provided.\n" +
        "Set it as a parameter on the seed job (see docs/JOB_DSL_AUTOMATION.md)."
    )
}

def paramStemsRaw  = binding.variables.get('PARAM_STEMS')  ?: ''
def vendorStemsRaw = binding.variables.get('VENDOR_STEMS') ?: ''

if (!paramStemsRaw?.trim()) {
    throw new RuntimeException(
        "No *.params.json files found under pipelines/scripts/stages/\n" +
        "Ensure the 'Checkout pipeline repo' stage in Jenkinsfile.seed completed successfully."
    )
}

def vendorStemSet = vendorStemsRaw ? (vendorStemsRaw.split(',') as List).toSet() : [] as Set

println "=" * 80
println "SEED JOB"
println "  CONFIG_REPO_URL    : ${configRepoUrl}"
println "  CONFIG_REPO_BRANCH : ${configRepoBranch}"
println "  PARAM_STEMS        : ${paramStemsRaw}"
println "  VENDOR_STEMS       : ${vendorStemsRaw ?: '(none)'}"
println "=" * 80
println ""

// ============================================================================
// STEP 2: Load configuration using readFileFromWorkspace
// ============================================================================

def slurper = new JsonSlurper()

def pipelineConfig = slurper.parseText(readFileFromWorkspace('adoptium_pipeline_config.json'))
println "✓ Loaded adoptium_pipeline_config.json"
println "  Active JDK versions: ${pipelineConfig.activeJdkVersions.findAll { it.enabled }.collect { it.version }.join(', ')}"

def jenkinsConfig = slurper.parseText(readFileFromWorkspace('jenkins_job_config.json'))
println "✓ Loaded jenkins_job_config.json\n"

// ============================================================================
// STEP 3: Collate stage parameters
// ============================================================================

def collatedStageParams = collateStageParams(
    'pipelines/scripts/stages',
    'vendor-scripts',
    vendorStemSet
)
// Capture groups at script scope — configure{} runs with a different delegate
// and cannot reliably access variables defined inside pipelineJob{} closures.
def collatedParamGroups = collatedStageParams.groups ?: []
println "✓ Collated ${collatedStageParams.paramNames?.size() ?: 0} stage parameter(s) " +
        "across ${collatedParamGroups.size()} group(s)\n"

// ============================================================================
// STEP 4: Create folders
// ============================================================================

folder('Build_openjdk_launchers') {
    displayName('Build_openjdk_launchers')
    description('Launch orchestrator jobs that trigger platform-specific builds across all selected platforms for a given JDK version')
}

folder('Build_openjdk') {
    displayName('Build_openjdk')
    description('OpenJDK platform build pipeline jobs, named using the AQA-style Build_openjdk<version>_<distro>_<arch>_<os> convention')
}

// ============================================================================
// STEP 5: Create Launch Orchestrator Jobs
// ============================================================================

def defaultBuildArgs          = pipelineConfig.defaultBuildArgs ?: '--create-jre-image --create-sbom'
def pipelineRepoUrl           = pipelineConfig.repository?.url ?: 'https://github.com/adoptium/ci-adoptium-pipelines.git'
def pipelineRepoBranch        = pipelineConfig.repository?.branch ?: 'main'
def pipelineRepoCredentialsId = pipelineConfig.repository?.credentialsId ?: ''
def defaultParams             = jenkinsConfig.jobConfiguration?.defaultParameters ?: [:]

println "Creating launch orchestrator jobs for active JDK versions:"
pipelineConfig.activeJdkVersions.findAll { it.enabled }.each { versionInfo ->
    def version    = versionInfo.version
    def configFile = "${pipelineConfig.configFilePrefix ?: 'configurations/'}${version}${pipelineConfig.configFileSuffix ?: '_pipeline_config.json'}"

    def versionNum = version.replaceAll(/[^\d]/, '').toInteger()
    def isLts      = (versionNum == 8 || versionNum == 11 || (versionNum >= 17 && (versionNum - 17) % 4 == 0))

    println "  → JDK ${version}${isLts ? ' [LTS]' : ''}"

    // Load platform list from the per-version config file
    def platforms = []
    try {
        def jdkConfig = slurper.parseText(readFileFromWorkspace(configFile))
        platforms = (jdkConfig.buildConfigurations?.keySet() as List)?.sort() ?: []
        println "    Available platforms: ${platforms.join(', ')}"
    } catch (Exception e) {
        println "    WARNING: ${configFile} not found — using 'all' as default platform choice"
        platforms = ['all']
    }

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

            Stage parameters are collated from scripts/stages/*.params.json and any
            vendor-scripts/*.params.json overrides in the config repo. All collated
            params are forwarded automatically to every platform build job launched.
        """.stripIndent().trim())

        quietPeriod(5)

        parameters {
            stringParam('JDK_VERSION', version.replaceAll(/[^\d]/, ''),
                'JDK version number — fixed for this launch job')
            stringParam('GROUP_UID', '',
                'Group identifier for this launch run. Auto-generated if empty.')

            booleanParam('REGENERATE_JOBS', false,
                'Force regeneration of platform-specific build jobs (use after config changes)')
            choiceParam('PLATFORMS', ['all'] + platforms,
                'Select platform to build, or "all" for all available platforms')

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

            // Inlined (not a helper def) — Job DSL closure delegates prevent
            // top-level def methods being visible inside parameters{}.
            collatedParamGroups.each { group ->
                group.parameters?.each { p ->
                    if (p.type == 'boolean') {
                        booleanParam(p.name, p.default == true, p.description ?: '')
                    } else {
                        stringParam(p.name, p.default ?: '', p.description ?: '')
                    }
                }
            }

            // Config repo — used by Jenkinsfile.launch to checkout config repo at runtime
            stringParam('CONFIG_REPO_URL', configRepoUrl,
                'Vendor config repo URL — baked in at job-generation time')
            stringParam('CONFIG_REPO_BRANCH', configRepoBranch,
                'Vendor config repo branch — baked in at job-generation time')
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

        // Inject Parameter Separator nodes for each collated group.
        // configure{} must be at the top level of the job block — wrapping it
        // in an if() causes Job DSL to silently ignore it.
        // Strategy: detach each group's param nodes, then re-append separator +
        // params in order so separators appear immediately before their group.
        configure { project ->
            def paramDefs = project / 'properties'
                / 'hudson.model.ParametersDefinitionProperty'
                / 'parameterDefinitions'

            collatedParamGroups.each { group ->
                if (!group.parameters) return

                def detached = group.parameters.collect { p ->
                    paramDefs.'*'.find { node -> node.'name'?.text() == p.name }
                }.findAll { it != null }
                detached.each { paramDefs.remove(it) }

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

                detached.each { paramDefs.append(it) }
            }
        }
    }
}

println "✓ Launch orchestrator jobs created successfully\n"

// ============================================================================
// STEP 6: Create Views
// ============================================================================

listView('Build_openjdk_launchers') {
    description('Launch orchestrator jobs for coordinating platform builds (Build_openjdk<version>_launch)')
    jobs {
        regex('Build_openjdk_launchers/Build_openjdk\\d+_launch')
    }
    recurse(true)
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

listView('Build_openjdk') {
    description('Platform-specific build jobs — AQA-style naming: Build_openjdk<version>_<distro>_<arch>_<os>')
    jobs {
        regex('Build_openjdk/Build_openjdk\\d+_[^_]+_[^_]+_[^_]+')
    }
    recurse(true)
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
