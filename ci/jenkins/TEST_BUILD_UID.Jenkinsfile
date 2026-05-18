// Test Jenkinsfile to verify BUILD_UID persistence across stages and restarts
//
// This tests BUILD_UID persistence using environment variables.
// No parameters needed - everything is tracked via environment variables that persist across restarts.
//
// Features:
// - BUILD_UID persistence across restarts
// - Stage result tracking (SUCCESS/FAILURE/SKIPPED)
// - Prerequisite validation (ensures all previous stages completed successfully)
//
// To test:
// 1. Run the pipeline normally - observe BUILD_UID in all stages
// 2. Click "Restart from Stage 2" - verify BUILD_UID is preserved and title updates
// 3. Click "Restart from Stage 3" - verify BUILD_UID is preserved and title updates
// 4. Try restarting from Stage 3 without Stage 2 - should fail validation

@NonCPS
def parseStageResults(String resultsStr) {
    if (!resultsStr) return [:]
    def results = [:]
    resultsStr.split('\\|\\|').each { entry ->
        def parts = entry.split('==', 2)
        if (parts.size() == 2) {
            results[parts[0]] = parts[1]
        }
    }
    return results
}

@NonCPS
def serializeStageResults(Map results) {
    return results.collect { k, v -> "${k}==${v}" }.join('||')
}

// Function to record stage result
def recordStageResult(String stageName, String result) {
    def results = parseStageResults(env.STAGE_RESULTS ?: '')
    results[stageName] = result
    env.STAGE_RESULTS = serializeStageResults(results)
    println "📝 Recorded stage result: ${stageName} = ${result}"
    println "📊 All stage results: ${results}"
}

// Function to validate prerequisites (all previous stages must be SUCCESS)
def validatePrerequisites(String currentStage, List<String> requiredStages) {
    if (!requiredStages || requiredStages.isEmpty()) {
        println "✅ No prerequisites required for ${currentStage}"
        return true
    }
    
    println "🔍 Validating prerequisites for ${currentStage}..."
    println "   Required stages: ${requiredStages}"
    
    def results = parseStageResults(env.STAGE_RESULTS ?: '')
    println "   Current stage results: ${results}"
    
    def missingStages = []
    def failedStages = []
    
    requiredStages.each { requiredStage ->
        def result = results[requiredStage]
        if (!result) {
            missingStages << requiredStage
        } else if (result != 'SUCCESS') {
            failedStages << "${requiredStage} (${result})"
        }
    }
    
    if (!missingStages.isEmpty() || !failedStages.isEmpty()) {
        def errorMsg = "❌ PREREQUISITE VALIDATION FAILED for ${currentStage}:\n"
        if (!missingStages.isEmpty()) {
            errorMsg += "   Missing stages (never ran): ${missingStages}\n"
        }
        if (!failedStages.isEmpty()) {
            errorMsg += "   Failed stages: ${failedStages}\n"
        }
        errorMsg += "\n⚠️ Cannot restart from ${currentStage} without all previous stages completing successfully!"
        errorMsg += "\n💡 Please restart from an earlier stage or run the full pipeline."
        
        println errorMsg
        error(errorMsg)
    }
    
    println "✅ All prerequisites validated successfully"
    return true
}

// Function to initialize BUILD_UID and set build title (called once per build)
def initializeBuildContext(String stageName) {
    println "🔧 Checking build context from ${stageName}..."
    
    // Debug: Check what environment variables exist
    println "🔍 Debug - Environment variables:"
    println "   env.BUILD_UID: ${env.BUILD_UID}"
    println "   env.ORIGINAL_BUILD_NUMBER: ${env.ORIGINAL_BUILD_NUMBER}"
    println "   env.BUILD_CONTEXT_INITIALIZED: ${env.BUILD_CONTEXT_INITIALIZED}"
    println "   env.BUILD_NUMBER: ${env.BUILD_NUMBER}"
    
    // Check if already initialized in THIS build
    if (env.BUILD_CONTEXT_INITIALIZED == env.BUILD_NUMBER) {
        println "ℹ️ Build #${env.BUILD_NUMBER} already initialized, skipping..."
        return
    }
    
    // Determine if this is original run or restart
    // If BUILD_UID already exists, it's a restart (env vars persist across restarts)
    def isRestart = (env.BUILD_UID != null && env.BUILD_UID != '')
    
    if (isRestart) {
        // RESTART - BUILD_UID already exists from previous run
        println "♻️ RESTART detected (BUILD_UID already exists)"
        println "   Reusing BUILD_UID: ${env.BUILD_UID}"
        println "   Original build number: ${env.ORIGINAL_BUILD_NUMBER ?: 'Unknown'}"
        println "   Restarting from: ${stageName}"
        
        // If ORIGINAL_BUILD_NUMBER is not set, try to infer it
        if (!env.ORIGINAL_BUILD_NUMBER || env.ORIGINAL_BUILD_NUMBER == '') {
            env.ORIGINAL_BUILD_NUMBER = "${env.BUILD_NUMBER.toInteger() - 1}"
            println "   ⚠️ ORIGINAL_BUILD_NUMBER was not set, inferring as: ${env.ORIGINAL_BUILD_NUMBER}"
        }
        
        // Set build title for restart
        currentBuild.displayName = "Restart of #${env.ORIGINAL_BUILD_NUMBER} from ${stageName}"
        currentBuild.description = "BUILD_UID: ${env.BUILD_UID}\nRestart from: ${stageName}"
        println "📋 Build title: Restart of #${env.ORIGINAL_BUILD_NUMBER} from ${stageName}"
        
    } else {
        // ORIGINAL RUN - Generate new BUILD_UID
        println "🆕 ORIGINAL RUN detected (no BUILD_UID exists)"
        def timestamp = currentBuild.startTimeInMillis
        def random = UUID.randomUUID().toString().take(8)
        env.BUILD_UID = "${timestamp}-${random}"
        env.ORIGINAL_BUILD_NUMBER = env.BUILD_NUMBER
        
        println "   Generated BUILD_UID: ${env.BUILD_UID}"
        println "   Original build number: ${env.ORIGINAL_BUILD_NUMBER}"
        
        // Set build title for original run
        currentBuild.displayName = "#${env.BUILD_NUMBER}"
        currentBuild.description = "BUILD_UID: ${env.BUILD_UID}\nOriginal run"
        println "📋 Build title: #${env.BUILD_NUMBER} (Original run)"
    }
    
    // Mark as initialized for THIS build number
    env.BUILD_CONTEXT_INITIALIZED = env.BUILD_NUMBER
    println "✅ Build context initialized for build #${env.BUILD_NUMBER}"
}

pipeline {
    agent any
    
    stages {
        stage('Stage 1: Initialize') {
            steps {
                script {
                    println "=" * 80
                    println "STAGE 1: Initialize"
                    println "=" * 80
                    
                    // Initialize build context (BUILD_UID + build title)
                    initializeBuildContext('Stage 1: Initialize')
                    
                    // No prerequisites for first stage
                    validatePrerequisites('Stage 1: Initialize', [])
                    
                    // Verify BUILD_UID is available
                    println "\n📝 BUILD_UID Status:"
                    println "   BUILD_UID: ${env.BUILD_UID}"
                    println "   Original Build: ${env.ORIGINAL_BUILD_NUMBER}"
                    
                    // Do some work
                    println "\n🔨 Doing some work..."
                    sleep 1
                    
                    println "\n" + "=" * 80
                }
            }
            post {
                success {
                    script {
                        recordStageResult('Stage 1: Initialize', 'SUCCESS')
                    }
                }
                unstable {
                    script {
                        recordStageResult('Stage 1: Initialize', 'UNSTABLE')
                    }
                }
                failure {
                    script {
                        recordStageResult('Stage 1: Initialize', 'FAILURE')
                    }
                }
                aborted {
                    script {
                        recordStageResult('Stage 1: Initialize', 'ABORTED')
                    }
                }
            }
        }
        
        stage('Stage 2: Process') {
            steps {
                script {
                    println "=" * 80
                    println "STAGE 2: Process"
                    println "=" * 80
                    
                    // Initialize build context (BUILD_UID + build title)
                    initializeBuildContext('Stage 2: Process')
                    
                    // Validate that Stage 1 completed successfully
                    validatePrerequisites('Stage 2: Process', ['Stage 1: Initialize'])
                    
                    // Verify BUILD_UID is available
                    println "\n📝 BUILD_UID Status:"
                    if (env.BUILD_UID) {
                        println "   ✅ BUILD_UID is available: ${env.BUILD_UID}"
                        println "   ✅ Original Build: ${env.ORIGINAL_BUILD_NUMBER}"
                    } else {
                        println "   ❌ BUILD_UID is NOT available!"
                    }
                    
                    // Do some work
                    println "\n🔨 Doing some work..."
                    sleep 1
                    
                    println "\n" + "=" * 80
                }
            }
            post {
                success {
                    script {
                        recordStageResult('Stage 2: Process', 'SUCCESS')
                    }
                }
                unstable {
                    script {
                        recordStageResult('Stage 2: Process', 'UNSTABLE')
                    }
                }
                failure {
                    script {
                        recordStageResult('Stage 2: Process', 'FAILURE')
                    }
                }
                aborted {
                    script {
                        recordStageResult('Stage 2: Process', 'ABORTED')
                    }
                }
            }
        }
        
        stage('Stage 3: Finalize') {
            steps {
                script {
                    println "=" * 80
                    println "STAGE 3: Finalize"
                    println "=" * 80
                    
                    // Initialize build context (BUILD_UID + build title)
                    initializeBuildContext('Stage 3: Finalize')
                    
                    // Validate that Stages 1 and 2 completed successfully
                    validatePrerequisites('Stage 3: Finalize', ['Stage 1: Initialize', 'Stage 2: Process'])
                    
                    // Verify BUILD_UID is available
                    println "\n📝 BUILD_UID Status:"
                    if (env.BUILD_UID) {
                        println "   ✅ BUILD_UID is available: ${env.BUILD_UID}"
                        println "   ✅ Original Build: ${env.ORIGINAL_BUILD_NUMBER}"
                    } else {
                        println "   ❌ BUILD_UID is NOT available!"
                    }
                    
                    // Final summary
                    println "\n📊 Final Summary:"
                    println "   Build Number: ${env.BUILD_NUMBER}"
                    println "   BUILD_UID: ${env.BUILD_UID}"
                    println "   Original Build Number: ${env.ORIGINAL_BUILD_NUMBER}"
                    
                    // Display all stage results
                    def results = parseStageResults(env.STAGE_RESULTS ?: '')
                    println "\n📊 Stage Results:"
                    results.each { stage, result ->
                        println "   ${stage}: ${result}"
                    }
                    
                    println "\n" + "=" * 80
                }
            }
            post {
                success {
                    script {
                        recordStageResult('Stage 3: Finalize', 'SUCCESS')
                    }
                }
                unstable {
                    script {
                        recordStageResult('Stage 3: Finalize', 'UNSTABLE')
                    }
                }
                failure {
                    script {
                        recordStageResult('Stage 3: Finalize', 'FAILURE')
                    }
                }
                aborted {
                    script {
                        recordStageResult('Stage 3: Finalize', 'ABORTED')
                    }
                }
            }
        }
    }
    
    post {
        always {
            script {
                println "\n" + "=" * 80
                println "POST: Pipeline Complete"
                println "=" * 80
                println "Build Number: ${env.BUILD_NUMBER}"
                println "BUILD_UID: ${env.BUILD_UID}"
                
                // Display final stage results
                def results = parseStageResults(env.STAGE_RESULTS ?: '')
                println "\n📊 Final Stage Results:"
                results.each { stage, result ->
                    def icon = result == 'SUCCESS' ? '✅' :
                               result == 'FAILURE' ? '❌' :
                               result == 'ABORTED' ? '🚫' :
                               result == 'UNSTABLE' ? '⚠️' : '❓'
                    println "   ${icon} ${stage}: ${result}"
                }
                
                println "=" * 80
            }
        }
    }
}

// Made with Bob
