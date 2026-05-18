/**
 * Build Stage Script
 * 
 * Handles the core JDK compilation process including:
 * - Workspace cleanup
 * - Repository checkout
 * - Build execution (with or without Docker)
 * - Metadata extraction
 * - Build artifact preparation
 */

def call(Map config) {
    def buildConfig = config.buildConfig
    def buildTimeouts = config.buildTimeouts
    def repoHandler = config.repoHandler
    def useAdoptShellScripts = config.useAdoptShellScripts
    def enableSigner = config.enableSigner
    def buildConfigEnvVars = config.buildConfigEnvVars
    
    println "Starting build stage for ${buildConfig.TARGET_OS}/${buildConfig.ARCHITECTURE}"
    
    // Determine build paths
    def build_path = 'workspace/build/src/build'
    def openjdk_build_dir = "${WORKSPACE}/${build_path}"
    def openjdk_build_dir_arg = ""
    
    try {
        // Clean workspace if required
        if (config.cleanWorkspace) {
            cleanWorkspace(buildTimeouts.NODE_CLEAN_TIMEOUT)
        }
        
        // Always clean previous build output
        cleanBuildOutput(openjdk_build_dir, buildTimeouts.NODE_CLEAN_TIMEOUT)
        
        // Checkout repositories
        timeout(time: buildTimeouts.NODE_CHECKOUT_TIMEOUT, unit: 'HOURS') {
            if (useAdoptShellScripts) {
                repoHandler.checkoutAdoptPipelines(this)
            } else {
                repoHandler.setUserDefaultsJson(this, config.DEFAULTS_JSON)
                repoHandler.checkoutUserPipelines(this)
            }
            
            // Git clean with safe directory config for Windows Docker
            if (buildConfig.TARGET_OS == 'windows' && buildConfig.DOCKER_IMAGE) {
                bat(script: 'bash -c "git config --global safe.directory $(cygpath \'$\'{WORKSPACE})"')
            }
            sh('git clean -fdx')
            printGitRepoInfo()
        }
        
        // Execute build
        withEnv(buildConfigEnvVars) {
            timeout(time: buildTimeouts.BUILD_JDK_TIMEOUT, unit: 'HOURS') {
                // Update GitHub status for PR tester
                if (env.JOB_NAME.contains('pr-tester')) {
                    updateGithubCommitStatus('PENDING', 'Build Started')
                }
                
                if (useAdoptShellScripts) {
                    executeBuildWithAdoptScripts(
                        buildConfig,
                        repoHandler,
                        enableSigner,
                        openjdk_build_dir_arg,
                        build_path,
                        config
                    )
                } else {
                    executeBuildWithUserScripts(
                        buildConfig,
                        repoHandler,
                        openjdk_build_dir_arg,
                        config
                    )
                }
            }
            
            // Extract version information (unless doing internal signing)
            if (!needsInternalSigning(buildConfig, enableSigner)) {
                def versionOut = extractVersionInfo(buildConfig)
                config.versionInfo = parseVersionOutput(versionOut)
                writeMetadata(config.versionInfo, true)
            } else {
                println "Skipping metadata extraction - will be done after internal signing"
            }
        }
        
    } catch (FlowInterruptedException e) {
        if (env.JOB_NAME.contains('pr-tester')) {
            updateGithubCommitStatus('FAILED', 'Build FAILED')
        }
        throw new Exception("[ERROR] Build stage timeout reached. Exiting...")
    } finally {
        // Archive artifacts (unless doing internal signing)
        if (!needsInternalSigning(buildConfig, enableSigner)) {
            archiveBuildArtifacts(buildConfig, buildTimeouts.BUILD_ARCHIVE_TIMEOUT)
        }
    }
    
    return config
}

/**
 * Execute build using Adoptium shell scripts
 */
def executeBuildWithAdoptScripts(buildConfig, repoHandler, enableSigner, 
                                  openjdk_build_dir_arg, build_path, config) {
    println '[CHECKOUT] Checking out to adoptium/temurin-build...'
    repoHandler.checkoutAdoptBuild(this)
    printGitRepoInfo()
    
    // Check if internal signing is needed (Windows/Mac JDK11+)
    if (needsInternalSigning(buildConfig, enableSigner)) {
        println "Generating exploded build for internal signing"
        buildExplodedImage(openjdk_build_dir_arg, config.ADOPT_DEFAULTS_JSON)
        
        // Determine base path for signing
        def base_path = build_path
        if (openjdk_build_dir_arg == "") {
            base_path = sh(script: "ls -d ${build_path}/*", returnStdout: true).trim()
        }
        println "base_path for jmod signing = ${base_path}"
        
        // Stash files for signing
        def files_to_sign_list = getEclipseSigningFileList(base_path)
        stash name: 'jmods', includes: "${files_to_sign_list}"
        
        // Store base_path for later stages
        config.base_path = base_path
    } else {
        // Standard single-pass build
        def buildArgs = env.BUILD_ARGS ? "${env.BUILD_ARGS}${openjdk_build_dir_arg}" : openjdk_build_dir_arg
        withEnv(["BUILD_ARGS=${buildArgs}"]) {
            println "Executing single-pass build"
            sh("bash ./${config.ADOPT_DEFAULTS_JSON['scriptDirectories']['buildfarm']}")
        }
    }
    
    // Revert to pipelines checkout
    println '[CHECKOUT] Reverting to pipelines checkout...'
    if (env.JOB_NAME.contains('pr-tester')) {
        checkout scm
    } else {
        repoHandler.setUserDefaultsJson(this, config.DEFAULTS_JSON)
        repoHandler.checkoutUserPipelines(this)
    }
    printGitRepoInfo()
}

/**
 * Execute build using user's shell scripts
 */
def executeBuildWithUserScripts(buildConfig, repoHandler, openjdk_build_dir_arg, config) {
    println "[CHECKOUT] Checking out to user's temurin-build..."
    repoHandler.setUserDefaultsJson(this, config.DEFAULTS_JSON)
    repoHandler.checkoutUserBuild(this)
    printGitRepoInfo()
    
    def buildArgs = env.BUILD_ARGS ? "${env.BUILD_ARGS}${openjdk_build_dir_arg}" : openjdk_build_dir_arg
    withEnv(["BUILD_ARGS=${buildArgs}"]) {
        println "Executing build with user scripts"
        sh("bash ./${config.DEFAULTS_JSON['scriptDirectories']['buildfarm']}")
    }
    
    println '[CHECKOUT] Reverting to user pipelines checkout...'
    repoHandler.checkoutUserPipelines(this)
    printGitRepoInfo()
}

/**
 * Build exploded image for internal signing
 */
def buildExplodedImage(openjdk_build_dir_arg, adoptDefaultsJson) {
    def signBuildArgs = env.BUILD_ARGS ? 
        "${env.BUILD_ARGS} --make-exploded-image${openjdk_build_dir_arg}" : 
        "--make-exploded-image${openjdk_build_dir_arg}"
    
    withEnv(["BUILD_ARGS=${signBuildArgs}"]) {
        println 'Building exploded image for signing'
        sh("bash ./${adoptDefaultsJson['scriptDirectories']['buildfarm']}")
    }
}

/**
 * Check if internal signing is needed
 */
def needsInternalSigning(buildConfig, enableSigner) {
    return (buildConfig.TARGET_OS == 'mac' || buildConfig.TARGET_OS == 'windows') &&
           buildConfig.JAVA_TO_BUILD != 'jdk8u' &&
           enableSigner
}

/**
 * Extract version information
 */
def extractVersionInfo(buildConfig) {
    if (buildConfig.BUILD_ARGS.contains('--cross-compile')) {
        println "[WARNING] Cross-compiled build - retrieving version from downstream job"
        return readCrossCompiledVersionString()
    } else {
        return readFile('workspace/target/metadata/version.txt')
    }
}

/**
 * Clean workspace
 */
def cleanWorkspace(timeout) {
    timeout(time: timeout, unit: 'HOURS') {
        if (WORKSPACE != null && !WORKSPACE.isEmpty()) {
            println "Cleaning workspace: ${WORKSPACE}"
            
            // Clean non-hidden files
            sh("rm -rf ${WORKSPACE}/*")
            
            // Clean hidden files
            try {
                cleanWs notFailBuild: true, disableDeferredWipeout: true, deleteDirs: true
            } catch (e) {
                println "Warning: Failed to clean hidden files: ${e}"
            }
        }
    }
}

/**
 * Clean build output directory
 */
def cleanBuildOutput(openjdk_build_dir, timeout) {
    timeout(time: timeout, unit: 'HOURS') {
        if (WORKSPACE != null && !WORKSPACE.isEmpty()) {
            println "Removing build directory: ${openjdk_build_dir}"
            sh("rm -rf ${openjdk_build_dir}")
        }
    }
}

/**
 * Archive build artifacts
 */
def archiveBuildArtifacts(buildConfig, timeout) {
    timeout(time: timeout, unit: 'HOURS') {
        println "Archiving build artifacts"
        
        if (buildConfig.BUILD_ARGS.contains('--cross-compile')) {
            // Only archive metadata for cross-compiled builds
            archiveArtifacts artifacts: 'workspace/target/metadata/*', 
                           fingerprint: true, 
                           allowEmptyArchive: true
        } else {
            // Archive all artifacts
            archiveArtifacts artifacts: 'workspace/target/**/*', 
                           fingerprint: true, 
                           allowEmptyArchive: true
        }
    }
}

return this

// Made with Bob
