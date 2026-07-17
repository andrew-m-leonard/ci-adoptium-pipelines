/**
 * Job DSL Script for Platform-Specific OpenJDK Build Pipeline Jobs
 *
 * Called by the launch job (Jenkinsfile.launch) via jobDsl() step.
 * Reads all configuration directly from the workspace — no HTTP fetching.
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
 * Required binding variables (additionalParameters from Jenkinsfile.launch):
 *   JDK_VERSION — numeric version (e.g. "21")
 *   PLATFORM    — platform key from buildConfigurations (e.g. "x86-64_linux")
 *
 * Creates: Build_openjdk/Build_openjdk<version>_<distro>_<arch>_<os>
 */

import groovy.json.JsonSlurper

// ---------------------------------------------------------------------------
// Helper: collate stage parameters using FilePath for workspace access.
// ---------------------------------------------------------------------------
def collateStageParams(hudson.FilePath defaultStagesPath,
                       hudson.FilePath vendorScriptsPath) {
    def slurper       = new JsonSlurper()
    def outputGroups  = []
    def allParamNames = [:]

    def stems = defaultStagesPath.list('*.params.json')
        .collect { it.name.replace('.params.json', '') }
        .sort()
    println "collateStageParams: discovered stems = ${stems}"

    stems.each { stem ->
        def defaultData = slurper.parseText(
            defaultStagesPath.child("${stem}.params.json").readToString()
        )

        def vendorData = null
        def vf = vendorScriptsPath.child("${stem}.params.json")
        if (vf.exists()) {
            vendorData = slurper.parseText(vf.readToString())
            println "  [${stem}] vendor override loaded"
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
            vendorData.ignoreDefaultParams?.each { name ->
                def gname = paramToGroup[name]
                if (gname) {
                    defaultGroups[gname].parameters.removeAll { it.name == name }
                    if (!defaultGroups[gname].parameters) defaultGroups.remove(gname)
                }
            }
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
            if (clean) outputGroups << [name: grp.name, description: grp.description,
                                        stageId: grp.stageId, parameters: clean]
        }
    }

    return [groups: outputGroups, paramNames: allParamNames.keySet().toList()]
}

// ============================================================================
// STEP 1: Resolve workspace and binding variables
// ============================================================================

def jdkVersion = binding.variables.get('JDK_VERSION')
def platform   = binding.variables.get('PLATFORM')

if (!jdkVersion) throw new IllegalArgumentException("JDK_VERSION binding variable is required")
if (!platform)   throw new IllegalArgumentException("PLATFORM binding variable is required")

// WORKSPACE is passed in via additionalParameters from Jenkinsfile.launch.
def wsFilePath    = new hudson.FilePath(new File(WORKSPACE))
def defaultStages = wsFilePath.child('scripts/stages')
def configRoot    = wsFilePath.child('config-repo')
def vendorScripts = configRoot.child('vendor-scripts')

println "=" * 80
println "openjdk_build_pipeline — workspace: ${wsFilePath.remote}"
println "  JDK_VERSION   : ${jdkVersion}"
println "  PLATFORM      : ${platform}"
println "  defaultStages : ${defaultStages.remote} (exists=${defaultStages.exists()})"
println "  configRoot    : ${configRoot.remote}    (exists=${configRoot.exists()})"
println "  vendorScripts : ${vendorScripts.remote} (exists=${vendorScripts.exists()})"
println "=" * 80

if (!configRoot.exists()) {
    throw new RuntimeException(
        "config-repo/ not found at ${configRoot.remote}\n" +
        "Jenkinsfile.launch must check out the vendor config repo into config-repo/ " +
        "before calling the jobDsl() step."
    )
}
if (!vendorScripts.exists()) {
    throw new RuntimeException(
        "config-repo/vendor-scripts/ not found at ${vendorScripts.remote}\n" +
        "Ensure the vendor config repo contains a vendor-scripts/ directory."
    )
}

// ============================================================================
// STEP 2: Load configuration from workspace
// ============================================================================

def slurper = new JsonSlurper()

def pipelineConfigFile = configRoot.child('adoptium_pipeline_config.json')
if (!pipelineConfigFile.exists()) {
    throw new RuntimeException("adoptium_pipeline_config.json not found at ${pipelineConfigFile.remote}")
}
def pipelineConfig = slurper.parseText(pipelineConfigFile.readToString())
println "✓ Loaded adoptium_pipeline_config.json"

def jenkinsConfigFile = configRoot.child('jenkins_job_config.json')
if (!jenkinsConfigFile.exists()) {
    throw new RuntimeException("jenkins_job_config.json not found at ${jenkinsConfigFile.remote}")
}
def jenkinsConfig = slurper.parseText(jenkinsConfigFile.readToString())
println "✓ Loaded jenkins_job_config.json"

def jdkConfigFile = configRoot.child("configurations/jdk${jdkVersion}_pipeline_config.json")
if (!jdkConfigFile.exists()) {
    throw new RuntimeException(
        "configurations/jdk${jdkVersion}_pipeline_config.json not found at ${jdkConfigFile.remote}"
    )
}
def jdkConfig = slurper.parseText(jdkConfigFile.readToString())

def platformConfig = jdkConfig.buildConfigurations[platform]
if (!platformConfig) {
    throw new IllegalArgumentException(
        "Platform '${platform}' not found in configurations/jdk${jdkVersion}_pipeline_config.json"
    )
}

def architecture = platformConfig.arch
def targetOs     = platformConfig.os
def variant      = platformConfig.variant ?: pipelineConfig?.defaultVariant ?: 'temurin'

if (!architecture || !targetOs) {
    throw new IllegalArgumentException(
        "Platform '${platform}' configuration is missing 'arch' or 'os' fields"
    )
}
println "✓ Platform config: arch=${architecture}, os=${targetOs}, variant=${variant}"

def defaultParams      = jenkinsConfig?.jobConfiguration?.defaultParameters
def initializeLabel    = jenkinsConfig?.stageAgentLabels?.get('Initialize') ?: 'ci.role.worker'

// ============================================================================
// STEP 3: Collate stage parameters
// ============================================================================

def collatedStageParams = collateStageParams(defaultStages, vendorScripts)
println "✓ Collated ${collatedStageParams.paramNames?.size() ?: 0} stage parameter(s) " +
        "across ${collatedStageParams.groups?.size() ?: 0} group(s)"

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
        Job name: Build_openjdk<version>_<distro>_<arch>_<os>
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
            'Group identifier linking all platform builds from the same launch. Auto-generated by the launch job if empty.')
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
        if (collatedStageParams.groups) {
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

        project / 'properties' / 'hudson.plugins.copyartifact.CopyArtifactPermissionProperty' {
            projectNameList {
                string('*')
            }
        }
    }
}

println "✓ Platform build job created: ${jobName}"
