/**
 * Configuration Loader for Job DSL Scripts
 *
 * This script loads the Jenkins job configuration from the configuration repository
 * and makes it available to other Job DSL scripts via the binding.
 *
 * Must be executed first before other Job DSL scripts.
 */

import groovy.json.JsonSlurper

// Get configuration repository details from seed job parameters
def configRepoUrl = binding.variables.get('CONFIG_REPO_URL')
def configRepoBranch = binding.variables.get('CONFIG_REPO_BRANCH')

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

// Read configuration from repository
def jenkinsConfig
try {
    def configUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/jenkins_job_config.json"
    println "Loading Jenkins configuration from ${configUrl}"
    println "  Repository: ${configRepoUrl}"
    println "  Branch: ${configRepoBranch}"
    
    def configText = new URL(configUrl).text
    jenkinsConfig = new JsonSlurper().parseText(configText)
    println "✓ Successfully loaded Jenkins configuration"
    println "  Active JDK versions: ${jenkinsConfig.activeJdkVersions.findAll { it.enabled }.collect { it.version }.join(', ')}"
} catch (Exception e) {
    def errorMsg = """
ERROR: Failed to load Jenkins job configuration from configuration repository!

Configuration Repository: ${configRepoUrl}
Branch: ${configRepoBranch}
Configuration URL: https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/jenkins_job_config.json
Error: ${e.message}

The jenkins_job_config.json file must exist in the configuration repository.
This file defines which JDK versions are active and should have jobs created.

Please ensure:
1. The configuration repository is accessible
2. The jenkins_job_config.json file exists at the root
3. The file contains valid JSON with activeJdkVersions array
4. The CONFIG_REPO_URL and CONFIG_REPO_BRANCH parameters are correct

Seed job cannot proceed without this configuration.
""".stripIndent()
    
    println errorMsg
    throw new RuntimeException(errorMsg)
}

// Make configuration available to other scripts via binding
binding.setVariable('jenkinsConfig', jenkinsConfig)
binding.setVariable('CONFIG_REPO_URL', configRepoUrl)
binding.setVariable('CONFIG_REPO_BRANCH', configRepoBranch)

println "✓ Configuration loaded and available to other Job DSL scripts"

// Made with Bob
