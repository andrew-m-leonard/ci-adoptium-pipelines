/**
 * Configuration Loader for Job DSL Scripts
 *
 * This script loads both configuration files from the configuration repository
 * and makes them available to other Job DSL scripts via the binding:
 *
 *   - adoptium_pipeline_config.json  (CI-agnostic: JDK versions, defaults, repo)
 *   - jenkins_job_config.json        (Jenkins-specific: job params, log rotation)
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

// -------------------------------------------------------------------------
// Load CI-agnostic pipeline configuration (adoptium_pipeline_config.json)
// -------------------------------------------------------------------------
def pipelineConfig
try {
    def configUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/adoptium_pipeline_config.json"
    println "Loading Adoptium pipeline configuration from ${configUrl}"
    
    def configText = new URL(configUrl).text
    pipelineConfig = new JsonSlurper().parseText(configText)
    println "✓ Successfully loaded adoptium_pipeline_config.json"
    println "  Active JDK versions: ${pipelineConfig.activeJdkVersions.findAll { it.enabled }.collect { it.version }.join(', ')}"
} catch (Exception e) {
    def errorMsg = """
ERROR: Failed to load adoptium_pipeline_config.json from configuration repository!

Configuration Repository: ${configRepoUrl}
Branch: ${configRepoBranch}
Configuration URL: https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/adoptium_pipeline_config.json
Error: ${e.message}

The adoptium_pipeline_config.json file must exist in the configuration repository.
This file defines CI-agnostic settings: active JDK versions, default build args, repository info.

Please ensure:
1. The configuration repository is accessible
2. The adoptium_pipeline_config.json file exists at the root
3. The file contains valid JSON with activeJdkVersions array
4. The CONFIG_REPO_URL and CONFIG_REPO_BRANCH parameters are correct

Seed job cannot proceed without this configuration.
""".stripIndent()
    
    println errorMsg
    throw new RuntimeException(errorMsg)
}

// -------------------------------------------------------------------------
// Load Jenkins-specific job configuration (jenkins_job_config.json)
// -------------------------------------------------------------------------
def jenkinsConfig
try {
    def configUrl = "https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/jenkins_job_config.json"
    println "Loading Jenkins job configuration from ${configUrl}"
    
    def configText = new URL(configUrl).text
    jenkinsConfig = new JsonSlurper().parseText(configText)
    println "✓ Successfully loaded jenkins_job_config.json"
} catch (Exception e) {
    def errorMsg = """
ERROR: Failed to load jenkins_job_config.json from configuration repository!

Configuration Repository: ${configRepoUrl}
Branch: ${configRepoBranch}
Configuration URL: https://raw.githubusercontent.com/${repoPath}/${configRepoBranch}/jenkins_job_config.json
Error: ${e.message}

The jenkins_job_config.json file must exist in the configuration repository.
This file defines Jenkins-specific settings: job parameters, log rotation, Jenkinsfile path.
""".stripIndent()
    
    println errorMsg
    throw new RuntimeException(errorMsg)
}

// Make configuration available to other scripts via binding
binding.setVariable('pipelineConfig', pipelineConfig)
binding.setVariable('jenkinsConfig', jenkinsConfig)
binding.setVariable('CONFIG_REPO_URL', configRepoUrl)
binding.setVariable('CONFIG_REPO_BRANCH', configRepoBranch)

println "✓ Configuration loaded and available to other Job DSL scripts"
