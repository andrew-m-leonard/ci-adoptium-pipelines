# Jenkins Integration

This directory contains Jenkins-specific pipeline definitions for the Adoptium build infrastructure.

## Files

### Jenkinsfile.declarative

Declarative Jenkins pipeline with the following features:

- **Stage-Level Restart**: Failed stages can be restarted individually
- **Code/Config Separation**: Pipeline code separated from vendor configurations
- **JSON Configuration**: Uses JSON config files from external repository
- **Modular Design**: Each stage calls CI-agnostic shell scripts
- **No Shared Libraries**: Removed dependency on Jenkins-specific shared libraries

## Jenkins Job Configuration

### SCM Setup

Configure the Jenkins job to checkout this repository:

```groovy
Pipeline script from SCM
  SCM: Git
    Repository URL: https://github.com/adoptium/ci-adoptium-pipelines.git
    Branch: main
    Script Path: ci/jenkins/Jenkinsfile.declarative
```

### Parameters

The pipeline accepts these parameters:

**Build Configuration:**
- `JDK_VERSION`: JDK version to build (jdk8u, jdk11u, jdk17u, jdk21u, etc.)
- `VARIANT`: Build variant (temurin, openj9, hotspot)
- `TARGET_OS`: Target operating system (mac, linux, windows, aix)
- `ARCHITECTURE`: Target architecture (aarch64, x64, x32, ppc64, s390x)

**Build Type:**
- `RELEASE`: Is this a release build?
- `WEEKLY`: Is this a weekly build?

**Feature Toggles:**
- `ENABLE_TESTS`: Run AQA tests after build
- `ENABLE_INSTALLERS`: Build installers
- `ENABLE_SIGNER`: Sign artifacts
- `ENABLE_TCK`: Run TCK tests (Temurin only, release/weekly builds)

**Configuration Repository:**
- `CONFIG_REPO_URL`: Git repository URL containing JSON configurations
  - Default: `https://github.com/adoptium/ci-temurin-config.git`
- `CONFIG_REPO_BRANCH`: Branch to checkout from configuration repository
  - Default: `main`

**Git References:**
- `SCM_REF`: Override OpenJDK source branch/tag
- `BUILD_REF`: Override temurin-build branch/tag
- `HELPER_REF`: Override jenkins-helper branch/tag

**Workspace:**
- `CLEAN_WORKSPACE`: Clean workspace before build

## Pipeline Stages

The declarative pipeline orchestrates these stages:

1. **Initialize**: Generate pipeline configuration from JSON
2. **Build**: Compile OpenJDK from source
3. **Sign**: Sign build artifacts (if enabled)
4. **Installer**: Create platform installers (if enabled)
5. **Smoke Tests**: Run basic validation tests (if enabled)

Each stage calls CI-agnostic shell scripts from the `scripts/stages/` directory.

## Configuration Repository

The pipeline fetches build configurations from an external repository specified by `CONFIG_REPO_URL`. This allows:

- **Vendor Independence**: Different vendors can maintain their own configurations
- **Version Control**: Configuration changes are tracked separately from code
- **Easy Updates**: Configuration updates don't require pipeline code changes

### Expected Repository Structure

```
ci-temurin-config/
└── configurations/
    ├── jdk8u_pipeline_config.json
    ├── jdk11u_pipeline_config.json
    ├── jdk17u_pipeline_config.json
    ├── jdk21u_pipeline_config.json
    └── ...
```

## Restart Capability

The declarative pipeline supports stage-level restart:

1. Navigate to the failed build in Jenkins
2. Click "Restart from Stage"
3. Select the stage to restart from
4. The pipeline resumes from that stage using archived artifacts

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

See [LOCAL_TESTING_GUIDE.md](../../LOCAL_TESTING_GUIDE.md) for details.

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

## Related Documentation

- [JENKINS_RESTART_BEHAVIOR.md](../../JENKINS_RESTART_BEHAVIOR.md) - Restart behavior details
- [CODE_CONFIG_SEPARATION.md](../../CODE_CONFIG_SEPARATION.md) - Code/config separation pattern
- [CI_AGNOSTIC_ARCHITECTURE.md](../../CI_AGNOSTIC_ARCHITECTURE.md) - Architecture overview