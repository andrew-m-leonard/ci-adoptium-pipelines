/**
 * Job DSL Script for Platform-Specific OpenJDK Build Pipeline Jobs
 *
 * Called by the launch job (Jenkinsfile.launch) via jobDsl() step.
 * Reads all configuration via readFileFromWorkspace — no FilePath, no HTTP.
 *
 * Workspace layout when called from Jenkinsfile.launch:
 *   <workspace>/
 *     scripts/stages/              — ci-adoptium-pipelines default *.params.json
 *     config-repo/
 *       adoptium_pipeline_config.json
 *       jenkins_job_config.json
 *       configurations/jdk<N>_pipeline_config.json
 *       vendor-scripts/            — vendor override *.params.json
 *
 * Binding variables (additionalParameters from Jenkinsfile.launch):
 *   JDK_VERSION   — numeric version (e.g. "21")
 *   PLATFORM      — platform key from buildConfigurations (e.g. "x86-64_linux")
 *   PARAM_STEMS   — comma-separated default stage param stems
 *   VENDOR_STEMS  — comma-separated vendor override stems (may be empty)
 *
 * Creates: Build_openjdk/Build_openjdk<version>_<distro>_<arch>_<os>
 */

import groovy.json.JsonSlurper

// ---------------------------------------------------------------------------
// Helper: collate stage parameters using readFileFromWorkspace.
// ---------------------------------------------------------------------------
def collateStageParams(String defaultStagesDir,
                       String vendorScriptsDir,
                       Set    vendorStemSet) {
    def slurper        = new JsonSlurper()
    // outputGroupMap preserves insertion order and merges same-named groups across stages.
    // Key = group name, value = [ name, description, stageIds: List, parameters: List ]
    def outputGroupMap = [:] as LinkedHashMap
    def allParamNames  = [:]

    def stems = (binding.variables.get('PARAM_STEMS').split(',') as List)
    println "collateStageParams: stems = ${stems}"

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
        }

        // Build per-stem group map (name → group) before merging into outputGroupMap.
        def defaultGroups = [:]
        def paramToGroup  = [:]
        defaultData.parameterGroups?.each { grp ->
            defaultGroups[grp.name] = [
                name:        grp.name,
                description: grp.description ?: '',
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
                        parameters:  new ArrayList(vgrp.parameters ?: [])
                    ]
                    println "  [${stem}]   vendor added new group '${vgrp.name}': ${vgrp.parameters?.collect { it.name }}"
                }
            }
        }

        // Merge this stem's groups into the cross-stem outputGroupMap.
        // Same-named groups from different stems have their params merged into
        // a single entry so they share one separator in the Jenkins UI.
        defaultGroups.values().each { grp ->
            def clean = grp.parameters.findAll { p ->
                if (allParamNames.containsKey(p.name)) {
                    println "WARNING: duplicate param '${p.name}' in ${stem}/${grp.name} — skipping"
                    return false
                }
                allParamNames[p.name] = "${stem}/${grp.name}"
                return true
            }
            if (!clean) return

            println "  [${stem}] group '${grp.name}': ${clean.collect { it.name }}"

            if (outputGroupMap.containsKey(grp.name)) {
                // Merge into the existing cross-stem group entry.
                def existing = outputGroupMap[grp.name]
                existing.stageIds << stem
                existing.parameters.addAll(clean)
                println "  [${stem}] merged group '${grp.name}' into existing entry (stages: ${existing.stageIds})"
            } else {
                outputGroupMap[grp.name] = [
                    name:        grp.name,
                    description: grp.description,
                    stageIds:    [stem],
                    parameters:  new ArrayList(clean)
                ]
            }
        }
    }

    def outputGroups = outputGroupMap.values().toList()
    def paramNames   = allParamNames.keySet().toList()
    println "  Total collated params: ${paramNames}"
    return [groups: outputGroups, paramNames: paramNames]
}

// ============================================================================
// STEP 1: Validate binding variables
// ============================================================================

def jdkVersion     = binding.variables.get('JDK_VERSION')
def platform       = binding.variables.get('PLATFORM')
def paramStemsRaw  = binding.variables.get('PARAM_STEMS')  ?: ''
def vendorStemsRaw = binding.variables.get('VENDOR_STEMS') ?: ''

if (!jdkVersion) throw new IllegalArgumentException("JDK_VERSION binding variable is required")
if (!platform)   throw new IllegalArgumentException("PLATFORM binding variable is required")
if (!paramStemsRaw?.trim()) {
    throw new RuntimeException(
        "PARAM_STEMS is empty — no *.params.json files found.\n" +
        "Ensure Jenkinsfile.launch passes PARAM_STEMS via additionalParameters."
    )
}

def vendorStemSet = vendorStemsRaw ? (vendorStemsRaw.split(',') as List).toSet() : [] as Set

println "=" * 80
println "openjdk_build_pipeline"
println "  JDK_VERSION  : ${jdkVersion}"
println "  PLATFORM     : ${platform}"
println "  PARAM_STEMS  : ${paramStemsRaw}"
println "  VENDOR_STEMS : ${vendorStemsRaw ?: '(none)'}"
println "=" * 80

// ============================================================================
// STEP 2: Load configuration via readFileFromWorkspace
// ============================================================================

def slurper = new JsonSlurper()

def pipelineConfig = slurper.parseText(readFileFromWorkspace('config-repo/adoptium_pipeline_config.json'))
println "✓ Loaded adoptium_pipeline_config.json"

def jenkinsConfig = slurper.parseText(readFileFromWorkspace('config-repo/jenkins_job_config.json'))
println "✓ Loaded jenkins_job_config.json"

def jdkConfig = slurper.parseText(readFileFromWorkspace("config-repo/configurations/jdk${jdkVersion}_pipeline_config.json"))

def platformConfig = jdkConfig.buildConfigurations[platform]
if (!platformConfig) {
    throw new IllegalArgumentException(
        "Platform '${platform}' not found in config-repo/configurations/jdk${jdkVersion}_pipeline_config.json"
    )
}

def architecture = platformConfig.arch
def targetOs     = platformConfig.os
def variant      = platformConfig.variant ?: pipelineConfig?.defaultVariant ?: 'temurin'

if (!architecture || !targetOs) {
    throw new IllegalArgumentException("Platform '${platform}' is missing 'arch' or 'os' fields")
}
println "✓ Platform: arch=${architecture}, os=${targetOs}, variant=${variant}"

def defaultParams   = jenkinsConfig?.jobConfiguration?.defaultParameters
def initializeLabel = jenkinsConfig?.stageAgentLabels?.get('Initialize') ?: 'ci.role.worker'

// ============================================================================
// STEP 3: Collate stage parameters
// ============================================================================

def collatedStageParams  = collateStageParams('scripts/stages', 'config-repo/vendor-scripts', vendorStemSet)
// Capture groups at script scope — configure{} runs with a different delegate
// and cannot reliably access variables defined inside pipelineJob{} closures.
def collatedParamGroups  = collatedStageParams.groups ?: []
println "✓ Collated ${collatedStageParams.paramNames?.size() ?: 0} stage parameter(s) " +
        "across ${collatedParamGroups.size()} group(s)"

// ============================================================================
// STEP 4: Create platform build job
// ============================================================================

folder('/Build_openjdk') {
    displayName('Build_openjdk')
    description('OpenJDK platform build pipeline jobs, AQA-style naming: Build_openjdk<version>_<distro>_<arch>_<os>')
}

def jobName = "/Build_openjdk/Build_openjdk${jdkVersion}_${variant}_${architecture}_${targetOs}"
println "Creating platform build job: ${jobName}"

pipelineJob(jobName) {
    displayName("Build_openjdk${jdkVersion}_${variant}_${architecture}_${targetOs}")
    description("""
        Platform-specific build pipeline for OpenJDK ${jdkVersion} (${variant}) on ${architecture}/${targetOs}.
    """.stripIndent().trim())

    quietPeriod(5)

    parameters {
        stringParam('JDK_VERSION', jdkVersion,
            'JDK version number — fixed at job-generation time')
        stringParam('TARGET_OS', targetOs,
            'Target operating system — fixed at job-generation time')
        stringParam('ARCHITECTURE', architecture,
            'Target CPU architecture — fixed at job-generation time')
        stringParam('GROUP_UID', '',
            'Group identifier linking all platform builds from the same launch.')
        stringParam('INITIALIZE_LABEL', initializeLabel,
            'Agent label for the Initialize stage — from stageAgentLabels.Initialize in jenkins_job_config.json')
        stringParam('ACTIVE_NODE_TIMEOUT',
            (jenkinsConfig?.activeNodeTimeoutMinutes ?: 10).toString(),
            'Minutes to wait for an active agent before failing.')

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
        booleanParam('CLEAN_WORKSPACE_AFTER_STAGE',
            defaultParams?.CLEAN_WORKSPACE_AFTER_STAGE != null ? defaultParams.CLEAN_WORKSPACE_AFTER_STAGE : true,
            'Clean workspace after each stage completes')
        choiceParam('RELEASE_TYPE',
            ['NIGHTLY', 'WEEKLY', 'RELEASE'],
            'Type of release build')

        // Collated stage parameters
        collatedParamGroups.each { group ->
            group.parameters?.each { p ->
                if (p.type == 'boolean') {
                    booleanParam(p.name, p.default == true, p.description ?: '')
                } else {
                    stringParam(p.name, p.default ?: '', p.description ?: '')
                }
            }
        }

        // Config repo — used by PipelineHelper.initializeStage() on the build agent
        stringParam('CONFIG_REPO_URL', '',
            'Vendor config repo URL — forwarded by the launch job')
        stringParam('CONFIG_REPO_BRANCH', '',
            'Vendor config repo branch — forwarded by the launch job')
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

    configure { project ->
        // Use depthFirst().find() to locate the existing <parameterDefinitions>
        // node built by the parameters{} block. The / operator creates nodes
        // when not found, which puts separators in the wrong place in the XML.
        def paramDefs = project.depthFirst().find { it instanceof groovy.util.Node && it.name() == 'parameterDefinitions' }
        if (paramDefs) collatedParamGroups.each { group ->
            if (!group.parameters) return

            def detached = group.parameters.collect { p ->
                paramDefs.'*'.find { node -> node.'name'?.text() == p.name }
            }.findAll { it != null }
            detached.each { paramDefs.remove(it) }

            // stageIds is a List — join for the separator name (must be a valid XML node
            // name so use underscores) and for the human-readable section header.
            def stageLabel  = group.stageIds.join('_')
            def stageHeader = group.stageIds.size() == 1
                ? "stage: ${group.stageIds[0]}"
                : "stages: ${group.stageIds.join(', ')}"

            def sepNode = paramDefs.appendNode(
                'jenkins.plugins.parameter__separator.ParameterSeparatorDefinition'
            )
            sepNode.appendNode('name', "__sep_${stageLabel}_${group.name.replaceAll(/\W+/, '_')}")
            sepNode.appendNode('sectionHeader', "${group.name}  [${stageHeader}]")
            sepNode.appendNode('sectionHeaderStyle', '')
            if (group.description) {
                sepNode.appendNode('sectionDescription', group.description)
            }
            sepNode.appendNode('separatorStyle', '')

            detached.each { paramDefs.append(it) }
        }

        // ── copyArtifact permission ───────────────────────────────────────
        project / 'properties' / 'hudson.plugins.copyartifact.CopyArtifactPermissionProperty' {
            projectNameList {
                string('*')
            }
        }
    }
}

println "✓ Platform build job created: ${jobName}"
