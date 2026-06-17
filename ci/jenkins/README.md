# Jenkins Integration

This directory contains Jenkins-specific pipeline definitions and Job DSL automation for the Adoptium build infrastructure.

## Files

### Jenkinsfile.declarative

Declarative Jenkins pipeline with the following features:

- **Stage-Level Restart**: Failed stages can be restarted individually
- **Code/Config Separation**: Pipeline code separated from vendor configurations
- **JSON Configuration**: Uses JSON config files from external repository
- **Modular Design**: Each stage calls CI-agnostic shell scripts
- **BUILD_UID Tracking**: Unique build identification across restarts

### job-dsl/

Job DSL scripts that automate Jenkins job creation:

- **`seed-job.groovy`**: Creates the seed job (self-updating)
- **`openjdk-build-pipeline.groovy`**: Creates all pipeline jobs dynamically

These scripts read configuration from the ci-temurin-config repository to determine which JDK versions are active.

### lib/

Shared Groovy libraries:

- **`BuildUidHelper.groovy`**: Helper functions for BUILD_UID tracking and stage result management

## Job DSL Automation

All Jenkins jobs are created automatically using Job DSL scripts. See [Job DSL Automation Guide](../../docs/JOB_DSL_AUTOMATION.md) for complete setup instructions.

### Quick Setup

1. **Prerequisites**: Ensure your Jenkins instance has:
   - Job DSL Plugin
   - Pipeline Plugin
   - Git Plugin
   - Script Security configured

2. **Create Seed Job**:
   - New Freestyle project named `seed-job`
   - SCM: Git → `https://github.com/adoptium/ci-adoptium-pipelines.git`
   - Build Step: Process Job DSLs → `ci/jenkins/job-dsl/*.groovy`

3. **Run Seed Job**:
   - Click "Build Now"
   - Jobs will be created for all active JDK versions (8, 11, 17, 21, 25, 26, 27)

### Configuration

Active JDK versions and job parameters are defined in:
**`ci-temurin-config/jenkins_job_config.json`**

To add/remove versions, edit this file and run the seed job.

## Jenkins Job Configuration

### SCM Setup

The seed job checks out this repository and runs the Job DSL scripts. The generated pipeline jobs are configured to:

```groovy
Pipeline script from SCM
  SCM: Git
    Repository URL: https://github.com/adoptium/ci-adoptium-pipelines.git
    Branch: main
    Script Path: ci/jenkins/Jenkinsfile.declarative
```

### Parameters

The pipeline accepts these parameters (configured via Job DSL):

**Build Configuration:**
- `JDK_VERSION`: JDK version to build (8, 11, 17, 21, 25, 26, 27)
- `PLATFORM`: Target platform (linux-x64, linux-aarch64, windows-x64, mac-x64, mac-aarch64, etc.)
- `BUILD_VARIANT`: Build variant (temurin, openj9, hotspot)

**Configuration Repository:**
- `CONFIG_REPO_URL`: Git repository URL containing JSON configurations
  - Default: `https://github.com/adoptium/ci-temurin-config.git`
- `CONFIG_REPO_BRANCH`: Branch to checkout from configuration repository
  - Default: `main`

**Feature Toggles:**
- `CLEAN_WORKSPACE_AFTER_STAGE`: Clean workspace after each stage
- `RUN_TESTS`: Run test stages
- `SIGN_ARTIFACTS`: Sign artifacts
- `PUBLISH_ARTIFACTS`: Publish to release repository
- `RUN_REPRODUCIBLE_COMPARE`: Run reproducible build comparison

## Pipeline Stages

The declarative pipeline orchestrates these stages:

1. **Initialize**: Generate pipeline configuration from JSON
2. **Build**: Compile OpenJDK from source
3. **Internal Sign**: Sign JMODs (if enabled)
4. **Assemble**: Create final JDK image
5. **Sign Artifacts**: Sign build artifacts (if enabled)
6. **Build Installers**: Create platform installers (if enabled)
7. **Sign Installers**: Sign installers (if enabled)
8. **GPG Sign**: GPG sign artifacts (if enabled)
9. **Verify Signing**: Verify all signatures
10. **Validate SBOM**: Validate Software Bill of Materials
11. **Smoke Tests**: Run basic validation tests
12. **Reproducible Compare**: Compare with previous build (if enabled)
13. **AQA Tests**: Run Adoptium Quality Assurance tests (if enabled)
14. **TCK Tests**: Run Technology Compatibility Kit tests (if enabled)

Each stage calls CI-agnostic shell scripts from the `scripts/stages/` directory.

## Configuration Repository

The pipeline fetches build configurations from an external repository specified by `CONFIG_REPO_URL`. This allows:

- **Vendor Independence**: Different vendors can maintain their own configurations
- **Version Control**: Configuration changes are tracked separately from code
- **Easy Updates**: Configuration updates don't require pipeline code changes

### Expected Repository Structure

```
ci-temurin-config/
├── jenkins_job_config.json          # Job DSL configuration
└── configurations/
    ├── jdk8u_pipeline_config.json
    ├── jdk11u_pipeline_config.json
    ├── jdk17u_pipeline_config.json
    ├── jdk21u_pipeline_config.json
    ├── jdk25u_pipeline_config.json
    ├── jdk26u_pipeline_config.json
    └── jdk27_pipeline_config.json
```

## Restart Capability

The declarative pipeline supports stage-level restart:

1. Navigate to the failed build in Jenkins
2. Click "Restart from Stage"
3. Select the stage to restart from
4. The pipeline resumes from that stage using BUILD_UID tracking

This saves hours of rebuild time when builds fail late in the pipeline.

## Local Testing

Before committing changes, test locally using:

```bash
cd /path/to/ci-adoptium-pipelines
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os mac \
  --architecture aarch64
```

See [Local Testing Guide](../local/README.md) for details.

## Troubleshooting

### Pipeline Fails to Checkout Configuration Repository

**Problem**: Pipeline fails with "Configuration directory not found"

**Solution**: Verify CONFIG_REPO_URL and CONFIG_REPO_BRANCH parameters are correct

### Stage Restart Fails

**Problem**: Restart fails with "Artifact not found"

**Solution**: Ensure previous stages completed successfully and archived artifacts

### Scripts Not Found

**Problem**: Pipeline fails with "scripts/stages/XX-stage.sh: not found"

**Solution**: Verify ci-adoptium-pipelines repository is checked out correctly via SCM

### Seed Job Fails

**Problem**: Seed job fails with "Configuration not found"

**Solution**: Ensure `jenkins_job_config.json` exists in ci-temurin-config repository

## Related Documentation

- [Job DSL Automation Guide](../../docs/JOB_DSL_AUTOMATION.md) - Complete Job DSL setup
- [BUILD_UID Integration](../../docs/BUILD_UID_INTEGRATION.md) - Pipeline restart safety
- [Jenkins Restart Behavior](../../docs/JENKINS_RESTART_BEHAVIOR.md) - Restart behavior details
- [Migration Guide](../../docs/MIGRATION_IMPLEMENTATION_GUIDE.md) - Migrating from old pipeline
- [CI Agnostic Architecture](../../docs/CI_AGNOSTIC_ARCHITECTURE.md) - Architecture overview