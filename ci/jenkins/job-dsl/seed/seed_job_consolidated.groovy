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
 */

import groovy.json.JsonSlurper

// ---------------------------------------------------------------------------
// Helper: collate stage parameters using FilePath for workspace access.
//
// java.io.File is blocked by the Job DSL sandbox, but hudson.FilePath
// (Jenkins internal API) is permitted and works on the actual workspace.
//
//   defaultStagesPath — FilePath pointing to pipelines/scripts/stages/
//   vendorScriptsPath — FilePath pointing to vendor-scripts/
//
// Returns a Map with keys:
//   groups     — List of [ name, description, stageId, parameters: [...] ]
//   paramNames — List of all parameter name strings (for STAGE_PARAM_NAMES)
// ---------------------------------------------------------------------------
def collateStageParams(hudson.FilePath defaultStagesPath,
                       hudson.FilePath vendorScriptsPath) {
    def slurper       = new JsonSlurper()
    def outputGroups  = []
    def allParamNames = [:]

    def stems = defaultStagesPath.list('*.params.json')
        .collect { it.name.replace('.params.json', '') }
        .sort()
    println "  Discovered param stems: ${stems}"

    stems.each { stem ->
        def defaultData = slurper.parseText(
            defaultStagesPath.child("${stem}.params.json").readToString()
        )

        def vendorData = null
        def vf = vendorScriptsPath.child("${stem}.params.json")
        if (vf.exists()) {
            vendorData = slurper.parseText(vf.readToString())
            println "  [${stem}] vendor override loaded"
        } else {
            println "  [${stem}] no vendor override"
        }

        // Build default group map: groupName → group
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
            // Apply ignoreDefaultParams
            vendorData.ignoreDefaultParams?.each { name ->
                def gname = paramToGroup[name]
                if (gname) {
                    defaultGroups[gname].parameters.removeAll { it.name == name }
                    if (!defaultGroups[gname].parameters) defaultGroups.remove(gname)
                }
            }
            // Merge vendor parameterGroups
            vendorData.parameterGroups?.each { vgrp ->
                if (defaultGroups.containsKey(vgrp.name)) {
                    def existing = defaultGroups[vgrp.name].parameters.collectEntries { [it.name, it] }
                    vgrp.parameters?.each { vp -> existing[vp.name] = vp }
                    defaultGroups[vgrp.name].parameters = existing.values().toList()
                } else {
                    defaultGroups[vgrp.name] = [
                        name:        vgrp.name,
                        description: vgrp.description ?: '',
                        stageId:     stem,
                        parameters:  new ArrayList(vgrp.parameters ?: [])
                    ]
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
    return [
        groups:     outputGroups,
        paramNames: paramNames
    ]
}

// ============================================================================
// STEP 1: Resolve workspace FilePaths
// ============================================================================

def wsFilePath    = hudson.model.Executor.currentExecutor().currentWorkspace
def defaultStages = wsFilePath.child('pipelines/scripts/stages')
def vendorScripts = wsFilePath.child('vendor-scripts')
def configRoot    = wsFilePath   // config repo is checked out to workspace root

println "=" * 80
println "SEED JOB — workspace: ${wsFilePath.remote}"
println "=" * 80
println "  defaultStages : ${defaultStages.remote} (exists=${defaultStages.exists()})"
println "  vendorScripts : ${vendorScripts.remote} (exists=${vendorScripts.exists()})"
println "=" * 80
println ""

if (!defaultStages.exists()) {
    throw new RuntimeException(
        "pipelines/scripts/stages/ not found at ${defaultStages.remote}\n" +
        "Ensure the 'Checkout pipeline repo' stage in Jenkinsfile.seed ran successfully."
    )
}
if (!vendorScripts.exists()) {
    throw new RuntimeException(
        "vendor-scripts/ not found at ${vendorScripts.remote}\n\n" +
        "Expected workspace layout (created by Jenkinsfile.seed):\n" +
        "  pipelines/      — ci-adoptium-pipelines checkout\n" +
        "  vendor-scripts/ — vendor config repo vendor-scripts/ directory\n" +
        "                    (the config repo is checked out to the workspace root\n" +
        "                     by the Pipeline SCM step in Jenkinsfile.seed)\n\n" +
        "See ci/jenkins/job-dsl/seed/Jenkinsfile.seed and docs/JOB_DSL_AUTOMATION.md."
    )
}

// ============================================================================
// STEP 2: Load Configuration from workspace
// ============================================================================

def slurper = new JsonSlurper()

// CI-agnostic pipeline configuration (adoptium_pipeline_config.json)
def pipelineConfigFile = configRoot.child('adoptium_pipeline_config.json')
if (!pipelineConfigFile.exists()) {
    throw new RuntimeException(
        "adoptium_pipeline_config.json not found at ${pipelineConfigFile.remote}\n" +
        "This file must exist in the vendor config repo root."
    )
}
def pipelineConfig = slurper.parseText(pipelineConfigFile.readToString())
println "✓ Loaded adoptium_pipeline_config.json"
println "  Active JDK versions: ${pipelineConfig.activeJdkVersions.findAll { it.enabled }.collect { it.version }.join(', ')}"

// Jenkins-specific job configuration (jenkins_job_config.json)
def jenkinsConfigFile = configRoot.child('jenkins_job_config.json')
if (!jenkinsConfigFile.exists()) {
    throw new RuntimeException(
        "jenkins_job_config.json not found at ${jenkinsConfigFile.remote}\n" +
        "This file must exist in the vendor config repo root."
    )
}
def jenkinsConfig = slurper.parseText(jenkinsConfigFile.readToString())
println "✓ Loaded jenkins_job_config.json\n"

// ============================================================================
// STEP 3: Collate stage parameters
// ============================================================================

def collatedStageParams = collateStageParams(defaultStages, vendorScripts)
println "✓ Collated ${collatedStageParams.paramNames?.size() ?: 0} stage parameter(s) " +
        "across ${collatedStageParams.groups?.size() ?: 0} group(s)\n"

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

    // Load platform list from the per-version config file in the workspace
    def platforms = []
    def jdkConfigFile = configRoot.child(configFile)
    if (jdkConfigFile.exists()) {
        def jdkConfig = slurper.parseText(jdkConfigFile.readToString())
        platforms = (jdkConfig.buildConfigurations?.keySet() as List)?.sort() ?: []
        println "    Available platforms: ${platforms.join(', ')}"
    } else {
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
            // ── Fixed / infrastructure ────────────────────────────────────
            stringParam('JDK_VERSION', version.replaceAll(/[^\d]/, ''),
                'JDK version number — fixed for this launch job')
            stringParam('GROUP_UID', '',
                'Group identifier for this launch run. Auto-generated if empty.')

            // ── Launch-job-only controls ──────────────────────────────────
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
            // Inlined (not a helper def) — Job DSL closure delegates prevent
            // top-level def methods being visible inside parameters{}.
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
        // Inlined — top-level def methods are not visible inside configure{} closures.
        if (collatedStageParams.groups) {
            configure { project ->
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
