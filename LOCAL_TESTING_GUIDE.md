# Local Testing Guide for CI-Agnostic Shell Scripts

This guide shows you how to test the pipeline stage scripts locally without any CI system.

## Quick Start

### 1. Create Test Workspace

```bash
# Create test workspace
mkdir -p /tmp/openjdk-test
cd /tmp/openjdk-test

# Create required directories
mkdir -p inputs outputs
```

### 2. Create Configuration File

Create `pipeline-config.json` with your build configuration:

```bash
cat > /tmp/openjdk-test/pipeline-config.json <<'EOF'
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk21u",
    "TARGET_OS": "linux",
    "ARCHITECTURE": "x64",
    "VARIANT": "temurin",
    "BUILD_ARGS": "--create-sbom",
    "NODE_LABEL": "worker",
    "DOCKER_IMAGE": "",
    "DOCKER_ARGS": "",
    "USE_ADOPT_SHELL_SCRIPTS": true,
    "CLEAN_WORKSPACE": false,
    "ENABLE_TESTS": true,
    "ENABLE_INSTALLERS": true,
    "ENABLE_SIGNER": true,
    "SCM_REF": "master",
    "CI_REF": "",
    "BUILD_REF": "",
    "HELPER_REF": ""
  },
  "parameters": {
    "enableTests": true,
    "enableInstallers": true,
    "enableSigner": true,
    "enableTCK": false,
    "cleanWorkspace": false,

## Resuming from a Specific Stage

If a pipeline run fails at a particular stage, you can resume from that stage without re-running earlier stages:

### Available Stages

1. `initialize` - Generate configuration
2. `build` - Build the JDK
3. `sign` - Sign artifacts
4. `installer` - Create installers
5. `smoke-tests` - Run smoke tests

### Resume Examples

```bash
# Resume from smoke tests (after build completed successfully)
python3 run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --start-from-stage smoke-tests

# Re-run just the installer stage
python3 run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --start-from-stage installer \
    --no-tests

# Resume from sign stage onwards
python3 run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --start-from-stage sign
```

### How It Works

- `--start-from-stage <stage>` skips all stages before the specified stage
- The workspace and artifacts from previous stages must exist
- Configuration file (`pipeline-config.json`) must exist in workspace
- All stages use the shared `TARGET_DIR` for artifacts

### Common Use Cases

**1. Build failed, want to retry just the build:**
```bash
python3 run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --start-from-stage build
```

**2. Build succeeded, want to test different installer options:**
```bash
python3 run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --start-from-stage installer \
    --no-tests
```

**3. Everything built, just want to re-run tests:**
```bash
python3 run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --start-from-stage smoke-tests
```

### Important Notes

- ⚠️ **Workspace must exist**: The workspace directory must contain artifacts from previous stages
- ⚠️ **Configuration must exist**: `pipeline-config.json` must be present in the workspace
- ⚠️ **Artifacts must be present**: Each stage expects artifacts from previous stages in `TARGET_DIR`
- ✅ **No re-initialization**: Skipping `initialize` stage means using existing configuration
- ✅ **Fast iteration**: Useful for debugging specific stages without full rebuild

    "cleanWorkspaceAfter": false
  },
  "buildNumber": "local-123",
  "buildTag": "local-test-123",
  "jobName": "local-openjdk-build",
  "timestamp": 1234567890
}
EOF
```

### 3. Set Environment Variables

```bash
# Required environment variables
export WORKSPACE=/tmp/openjdk-test
export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
export BUILD_NUMBER=local-123
export OUTPUT_DIR=${WORKSPACE}/outputs
export INPUT_DIR=${WORKSPACE}/inputs

# Optional - for debugging
export DEBUG=true
```

### 4. Run a Stage Script

```bash
# Navigate to your scripts directory
cd /path/to/refactored_pipeline_examples

# Make scripts executable
chmod +x scripts/stages/*.sh
chmod +x scripts/lib/*.sh

# Run build stage
./scripts/stages/02-build.sh

# Check outputs
ls -la ${OUTPUT_DIR}/
cat ${WORKSPACE}/stage-metadata.json
```

## Configuration File Explained

### `buildConfig` Section

This section contains the build-specific configuration that would normally come from Jenkins job parameters or the IndividualBuildConfig class.

```json
{
  "buildConfig": {
    // Java version to build
    "JAVA_TO_BUILD": "jdk21u",        // Options: jdk8u, jdk11u, jdk17u, jdk21u, jdk, etc.
    
    // Target operating system
    "TARGET_OS": "linux",              // Options: linux, windows, mac, aix, alpine-linux
    
    // Target architecture
    "ARCHITECTURE": "x64",             // Options: x64, aarch64, arm, ppc64le, s390x, riscv64
    
    // Build variant
    "VARIANT": "temurin",              // Options: temurin, hotspot

    // Additional build arguments
    "BUILD_ARGS": "--create-sbom",     // Any additional args for make-adopt-build-farm.sh
    
    // Jenkins node label (not used locally)
    "NODE_LABEL": "worker",
    
    // Docker configuration (optional)
    "DOCKER_IMAGE": "",                // e.g., "adoptopenjdk/centos7_build_image"
    "DOCKER_ARGS": "",                 // e.g., "--cpus=4 --memory=8g"
    
    // Script configuration
    "USE_ADOPT_SHELL_SCRIPTS": true,   // Use adoptium/temurin-build scripts
    
    // Workspace management
    "CLEAN_WORKSPACE": false,          // Clean before build (set false for local testing)
    
    // Feature flags
    "ENABLE_TESTS": true,
    "ENABLE_INSTALLERS": true,
    "ENABLE_SIGNER": true,
    
    // Git references (optional overrides)
    "SCM_REF": "master",               // OpenJDK source branch/tag
    "CI_REF": "",                      // ci-jenkins-pipelines override
    "BUILD_REF": "",                   // temurin-build override
    "HELPER_REF": ""                   // jenkins-helper override
  }
}
```

### `parameters` Section

Pipeline-level parameters that control stage execution:

```json
{
  "parameters": {
    "enableTests": true,               // Run test stages
    "enableInstallers": true,          // Build installers
    "enableSigner": true,              // Sign artifacts
    "enableTCK": false,                // Run TCK tests (usually false for local)
    "cleanWorkspace": false,           // Clean workspace before build
    "cleanWorkspaceAfter": false       // Clean workspace after build
  }
}
```

### Metadata Section

Build identification and tracking:

```json
{
  "buildNumber": "local-123",          // Unique build identifier
  "buildTag": "local-test-123",        // Build tag
  "jobName": "local-openjdk-build",    // Job name
  "timestamp": 1234567890              // Unix timestamp
}
```

## Testing Different Scenarios

### Scenario 1: Test Build Stage Only

```bash
# Minimal config for build testing
cat > ${CONFIG_FILE} <<'EOF'
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk21u",
    "TARGET_OS": "linux",
    "ARCHITECTURE": "x64",
    "VARIANT": "temurin",
    "BUILD_ARGS": "",
    "USE_ADOPT_SHELL_SCRIPTS": true,
    "CLEAN_WORKSPACE": false
  },
  "parameters": {
    "cleanWorkspace": false
  },
  "buildNumber": "test-build",
  "jobName": "local-build-test"
}
EOF

# Run build
./scripts/stages/02-build.sh
```

### Scenario 2: Test Signing Stage

```bash
# First, you need build outputs in INPUT_DIR
# For testing, create a dummy artifact:
mkdir -p ${INPUT_DIR}
echo "Dummy JDK" > ${INPUT_DIR}/OpenJDK-jdk_x64_linux_temurin.tar.gz

# Config for signing
cat > ${CONFIG_FILE} <<'EOF'
{
  "buildConfig": {
    "VARIANT": "temurin",
    "TARGET_OS": "linux",
    "ARCHITECTURE": "x64"
  },
  "parameters": {
    "enableSigner": true
  },
  "buildNumber": "test-sign"
}
EOF

# Set INPUT_DIR to where artifacts are
export INPUT_DIR=${WORKSPACE}/inputs
export OUTPUT_DIR=${WORKSPACE}/signed

# Run signing
./scripts/stages/06-sign.sh
```

### Scenario 3: Test Smoke Tests

```bash
# Create a test JDK artifact (or use real one)
mkdir -p ${INPUT_DIR}

# For real testing, you'd have an actual JDK tar.gz
# For demo, create a minimal structure:
mkdir -p /tmp/test-jdk/jdk-21/bin
echo '#!/bin/bash' > /tmp/test-jdk/jdk-21/bin/java
echo 'echo "openjdk version \"21\" 2023-09-19"' >> /tmp/test-jdk/jdk-21/bin/java
chmod +x /tmp/test-jdk/jdk-21/bin/java

# Create tar.gz
cd /tmp/test-jdk
tar -czf ${INPUT_DIR}/OpenJDK-jdk_x64_linux_temurin.tar.gz jdk-21/

# Config for testing
cat > ${CONFIG_FILE} <<'EOF'
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk21u",
    "TARGET_OS": "linux",
    "ARCHITECTURE": "x64"
  },
  "parameters": {
    "enableTests": true
  },
  "buildNumber": "test-smoke"
}
EOF

# Run smoke tests
export INPUT_DIR=${WORKSPACE}/inputs
export OUTPUT_DIR=${WORKSPACE}/test-results
./scripts/stages/13-smoke-tests.sh
```

### Scenario 4: Test Windows Build

```bash
cat > ${CONFIG_FILE} <<'EOF'
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk21u",
    "TARGET_OS": "windows",
    "ARCHITECTURE": "x64",
    "VARIANT": "temurin",
    "BUILD_ARGS": "",
    "USE_ADOPT_SHELL_SCRIPTS": true
  },
  "parameters": {
    "enableSigner": true,
    "cleanWorkspace": false
  },
  "buildNumber": "test-windows"
}
EOF

./scripts/stages/02-build.sh
```

### Scenario 5: Test macOS Build

```bash
cat > ${CONFIG_FILE} <<'EOF'
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk21u",
    "TARGET_OS": "mac",
    "ARCHITECTURE": "aarch64",
    "VARIANT": "temurin",
    "BUILD_ARGS": "",
    "USE_ADOPT_SHELL_SCRIPTS": true
  },
  "parameters": {
    "enableSigner": true,
    "cleanWorkspace": false
  },
  "buildNumber": "test-macos"
}
EOF

./scripts/stages/02-build.sh
```

## Complete Test Workflow

Here's a complete workflow testing multiple stages:

```bash
#!/bin/bash
# complete-test.sh - Test multiple stages locally

set -e

# Setup
export WORKSPACE=/tmp/openjdk-test
export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
export BUILD_NUMBER=complete-test-123
export DEBUG=true

# Clean and create workspace
rm -rf ${WORKSPACE}
mkdir -p ${WORKSPACE}/{inputs,outputs,signed,installers,test-results}

# Create configuration
cat > ${CONFIG_FILE} <<'EOF'
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk21u",
    "TARGET_OS": "linux",
    "ARCHITECTURE": "x64",
    "VARIANT": "temurin",
    "BUILD_ARGS": "",
    "USE_ADOPT_SHELL_SCRIPTS": true,
    "CLEAN_WORKSPACE": false
  },
  "parameters": {
    "enableTests": true,
    "enableInstallers": true,
    "enableSigner": true,
    "cleanWorkspace": false
  },
  "buildNumber": "complete-test-123",
  "jobName": "local-complete-test"
}
EOF

echo "=== Configuration ==="
cat ${CONFIG_FILE}

# Stage 1: Build
echo ""
echo "=== Stage 1: Build ==="
export OUTPUT_DIR=${WORKSPACE}/outputs
./scripts/stages/02-build.sh

# Stage 2: Sign
echo ""
echo "=== Stage 2: Sign ==="
export INPUT_DIR=${WORKSPACE}/outputs
export OUTPUT_DIR=${WORKSPACE}/signed
./scripts/stages/06-sign.sh

# Stage 3: Installer
echo ""
echo "=== Stage 3: Installer ==="
export INPUT_DIR=${WORKSPACE}/signed
export OUTPUT_DIR=${WORKSPACE}/installers
./scripts/stages/07-installer.sh

# Stage 4: Smoke Tests
echo ""
echo "=== Stage 4: Smoke Tests ==="
export INPUT_DIR=${WORKSPACE}/outputs
export OUTPUT_DIR=${WORKSPACE}/test-results
./scripts/stages/13-smoke-tests.sh || echo "Tests may fail without real JDK"

echo ""
echo "=== Complete Test Finished ==="
echo "Check outputs in: ${WORKSPACE}"
```

## Debugging

### Enable Debug Logging

```bash
export DEBUG=true
./scripts/stages/02-build.sh
```

### Run with Bash Debug Mode

```bash
bash -x ./scripts/stages/02-build.sh
```

### Check Stage Metadata

```bash
cat ${WORKSPACE}/stage-metadata.json | jq .
```

### Verify Configuration Loading

```bash
# Test config loading
cat ${CONFIG_FILE} | jq '.buildConfig.JAVA_TO_BUILD'
```

## Common Issues

### Issue: "jq: command not found"

**Solution**: Install jq
```bash
# Ubuntu/Debian
sudo apt-get install jq

# macOS
brew install jq

# Red Hat/CentOS
sudo yum install jq
```

### Issue: "CONFIG_FILE not found"

**Solution**: Verify file exists and path is correct
```bash
ls -la ${CONFIG_FILE}
echo ${CONFIG_FILE}
```

### Issue: "Permission denied"

**Solution**: Make scripts executable
```bash
chmod +x scripts/stages/*.sh
chmod +x scripts/lib/*.sh
```

### Issue: Build fails with "make-adopt-build-farm.sh not found"

**Solution**: For local testing without actual build, you can create a mock script:
```bash
mkdir -p ${WORKSPACE}/build-farm
cat > ${WORKSPACE}/make-adopt-build-farm.sh <<'EOF'
#!/bin/bash
echo "Mock build script"
mkdir -p workspace/target/metadata
echo "21.0.1+12" > workspace/target/metadata/version.txt
echo "Mock JDK build" > workspace/target/OpenJDK.tar.gz
EOF
chmod +x ${WORKSPACE}/make-adopt-build-farm.sh
```

## Summary

To test locally, you need:

1. **Workspace directory** - `/tmp/openjdk-test` or similar
2. **Configuration file** - `pipeline-config.json` with build settings
3. **Environment variables** - `WORKSPACE`, `CONFIG_FILE`, `BUILD_NUMBER`, etc.
4. **Executable scripts** - `chmod +x scripts/**/*.sh`
5. **Dependencies** - `jq` for JSON parsing

The configuration file structure matches what Jenkins would provide, allowing you to test the exact same scripts that run in CI.