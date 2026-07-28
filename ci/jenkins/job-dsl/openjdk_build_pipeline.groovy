/**
 * Job DSL Script for Platform-Specific OpenJDK Build Pipeline Jobs
 *
 * Called by the launch job (Jenkinsfile.launch) via jobDsl() step.
 * Reads all configuration via readFileFromWorkspace — no FilePath, no HTTP.
 *
 * Workspace layout when called from Jenkinsfile.launch:
 *   <workspace>/
 *     collated-stage-params.json   — written by 'Collate Stage Parameters' stage
 *     config-repo/
 *       adoptium_pipeline_config.json
 *       jenkins_job_config.json
 *       configurations/jdk<N>_pipeline_config.json
 *
 * Binding variables (additionalParameters from Jenkinsfile.launch):
 *   JDK_VERSION          — numeric version (e.g. "21")
 *   PLATFORM             — platform key from buildConfigurations (e.g. "x86-64_linux")
 *   COLLATED_PARAMS_JSON — JSON string produced by collect-stage-params.py, with
 *                          cross-stem group merge already applied by Jenkinsfile.launch
 *
 * Creates: Build_openjdk/Build_openjdk<version>_<distro>_<arch>_<os>
 */

import groovy.json.JsonSlurper

// ============================================================================
// STEP 1: Validate binding variables
// ============================================================================

def jdkVersion          = binding.variables.get('JDK_VERSION')
def platform            = binding.variables.get('PLATFORM')
def collatedParamsJson  = binding.variables.get('COLLATED_PARAMS_JSON') ?: ''

if (!jdkVersion) throw new IllegalArgumentException("JDK_VERSION binding variable is required")
if (!platform)   throw new IllegalArgumentException("PLATFORM binding variable is required")
if (!collatedParamsJson?.trim()) {
    throw new RuntimeException(
        "COLLATED_PARAMS_JSON is empty.\n" +
        "Ensure the 'Collate Stage Parameters' stage in Jenkinsfile.launch completed successfully."
    )
}

println "=" * 80
println "openjdk_build_pipeline"
println "  JDK_VERSION : ${jdkVersion}"
println "  PLATFORM    : ${platform}"
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
// STEP 3: Build collated param groups from pre-computed JSON
// ============================================================================

// COLLATED_PARAMS_JSON was produced by collect-stage-params.py and the
// cross-stem group merge was applied in Jenkinsfile.launch's
// 'Collate Stage Parameters' stage. Parse it and re-apply the merge here
// to obtain the stageIds list structure that configure{} needs.
def rawGroups = new JsonSlurper().parseText(collatedParamsJson).groups ?: []

def mergedGroupMap = [:] as LinkedHashMap
rawGroups.each { grp ->
    def gname = grp.name
    if (mergedGroupMap.containsKey(gname)) {
        mergedGroupMap[gname].stageIds << grp.stageId
        mergedGroupMap[gname].parameters.addAll(grp.parameters ?: [])
    } else {
        mergedGroupMap[gname] = [
            name:        gname,
            description: grp.description ?: '',
            stageIds:    [grp.stageId],
            parameters:  new ArrayList(grp.parameters ?: [])
        ]
    }
}

// Capture at script scope — configure{} runs with a different delegate.
def collatedParamGroups = mergedGroupMap.values().toList()
println "✓ Received ${rawGroups.size()} raw group(s), merged to ${collatedParamGroups.size()} group(s)"

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
