/**
 * Smoke Test Stage Script
 * 
 * Runs quick validation tests to ensure the build is functional
 * before proceeding with full AQA test suite
 */

def call(Map config) {
    def buildConfig = config.buildConfig
    def filename = config.filename
    
    println "Running smoke tests for ${buildConfig.TARGET_OS}/${buildConfig.ARCHITECTURE}"
    
    def additionalTestLabel = buildConfig.ADDITIONAL_TEST_LABEL
    def testLabel = "${buildConfig.NODE_LABEL}"
    
    if (additionalTestLabel) {
        testLabel = "${testLabel}&&${additionalTestLabel}"
    }
    
    def jobParams = getSmokeTestJobParams(buildConfig, filename)
    
    def jobName = "Test_openjdk${buildConfig.JAVA_TO_BUILD}_hs_sanity.functional_${buildConfig.ARCHITECTURE}_${buildConfig.TARGET_OS}_Nightly"
    
    println "Triggering smoke test job: ${jobName}"
    println "Test label: ${testLabel}"
    
    def smokeTestJob = build(
        job: jobName,
        propagate: false,
        parameters: jobParams
    )
    
    def smokeTestResult = smokeTestJob.getResult()
    
    println "Smoke test result: ${smokeTestResult}"
    
    if (smokeTestResult != 'SUCCESS') {
        println "[WARNING] Smoke tests failed with result: ${smokeTestResult}"
        currentBuild.result = 'UNSTABLE'
        config.smokeTestsPassed = false
    } else {
        println "Smoke tests passed successfully"
        config.smokeTestsPassed = true
    }
    
    return config
}

/**
 * Get smoke test job parameters
 */
def getSmokeTestJobParams(buildConfig, filename) {
    def jobParams = []
    
    // Add standard parameters
    jobParams.add(string(name: 'UPSTREAM_JOB_NAME', value: env.JOB_NAME))
    jobParams.add(string(name: 'UPSTREAM_JOB_NUMBER', value: env.BUILD_NUMBER))
    jobParams.add(string(name: 'RELEASE_TAG', value: buildConfig.SCM_REF))
    
    // Add test-specific parameters
    if (buildConfig.ADDITIONAL_TEST_LABEL) {
        jobParams.add(string(name: 'LABEL_ADDITION', value: buildConfig.ADDITIONAL_TEST_LABEL))
    }
    
    // Add artifact information
    if (filename) {
        jobParams.add(string(name: 'BINARY_URL', value: "${env.BUILD_URL}artifact/workspace/target/${filename}"))
    }
    
    // Add platform information
    jobParams.add(string(name: 'PLATFORM', value: "${buildConfig.ARCHITECTURE}_${buildConfig.TARGET_OS}"))
    jobParams.add(string(name: 'JDK_VERSION', value: buildConfig.JAVA_TO_BUILD))
    jobParams.add(string(name: 'JDK_IMPL', value: buildConfig.VARIANT))
    
    return jobParams
}

return this

// Made with Bob
