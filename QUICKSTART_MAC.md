# Quick Start Guide for Mac (Apple Silicon)

This guide shows how to test the refactored OpenJDK build pipeline on **Mac with Apple Silicon (aarch64)**.

## Prerequisites

```bash
# Install required tools
brew install jq python3

# Verify installations
jq --version
python3 --version
```

## Step 1: Create Pipeline Configuration and Workspace

```bash
# Navigate to refactored pipeline examples
cd /Users/anleonar/workspace/bob/refactored_pipeline_examples

# Create workspace directory
mkdir -p ~/openjdk-test

# Generate pipeline configuration from JSON
python3 scripts/lib/load-json-config.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --config-dir ./configurations \
    --output-dir ~/openjdk-test

# Verify configuration was created
cat ~/openjdk-test/pipeline-config.json | jq .
```

**Expected output:**
```json
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk21u",
    "TARGET_OS": "mac",
    "ARCHITECTURE": "aarch64",
    "VARIANT": "temurin",
    "BUILD_ARGS": "--create-jre-image --create-sbom",
    "CONFIGURE_ARGS": "--enable-dtrace",
    "NODE_LABEL": "xcode15.0.1&&build&&mac&&aarch64"
  },
  "parameters": {
    "enableTests": true,
    "enableInstallers": true,
    "enableSigner": true
  }
}
```

## Step 2: Set Environment and Run Build Stage

```bash
# Set required environment variables
export WORKSPACE=~/openjdk-test
export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
export BUILD_NUMBER=mac-test-1

# Run the build stage
./scripts/stages/02-build-corrected.sh
```

**What this does:**
- Clones OpenJDK source code
- Clones temurin-build scripts
- Runs the actual OpenJDK build using `make-adopt-build-farm.sh`
- Creates JDK tarball in `${WORKSPACE}/workspace/target/`

**Build time:** ~30-60 minutes depending on your Mac

## Step 3: Run Smoke Tests

After the build completes successfully:

```bash
# Run smoke tests on the built JDK
./scripts/stages/13-smoke-tests.sh
```

**What this does:**
- Extracts the built JDK tarball
- Runs `java -version`
- Executes basic smoke tests
- Verifies the JDK works correctly

**Expected output:**
```
=== Running Smoke Tests ===
Found JDK tarball: OpenJDK21U-jdk_aarch64_mac_temurin_21.0.x_2024-xx-xx-xx-xx.tar.gz
Extracting JDK...
Testing java -version...
openjdk version "21.0.x" 2024-xx-xx
OpenJDK Runtime Environment Temurin-21.0.x+x (build 21.0.x+x)
OpenJDK 64-Bit Server VM Temurin-21.0.x+x (build 21.0.x+x, mixed mode)
✅ Smoke tests passed
```

## Troubleshooting

### Configuration not found
```bash
# Check if JSON config file exists
ls -la configurations/jdk21u_pipeline_config.json

# If missing, you may need to convert from Groovy
./tools/convert-groovy-config-to-json.sh \
    ~/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations/jdk21u_pipeline_config.groovy \
    configurations/jdk21u_pipeline_config.json
```

### Build fails
```bash
# Check build logs
tail -100 ${WORKSPACE}/workspace/build/logs/build.log

# Check if all dependencies are installed
# See REAL_BUILD_GUIDE.md for full dependency list
```

### Smoke tests fail
```bash
# Check if JDK was built
ls -la ${WORKSPACE}/workspace/target/*.tar.gz

# Manually test the JDK
tar -xzf ${WORKSPACE}/workspace/target/OpenJDK*.tar.gz
./jdk-*/Contents/Home/bin/java -version
```

## Testing Other Configurations

### JDK 17 with HotSpot
```bash
python3 scripts/lib/load-json-config.py \
    --jdk-version jdk17u \
    --variant hotspot \
    --target-os mac \
    --architecture aarch64 \
    --config-dir ./configurations \
    --output-dir ~/openjdk-test
```

### Release Build
```bash
python3 scripts/lib/load-json-config.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --release \
    --scm-ref jdk-21.0.2+13 \
    --config-dir ./configurations \
    --output-dir ~/openjdk-test
```

### Build Without Tests
```bash
python3 scripts/lib/load-json-config.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --no-tests \
    --config-dir ./configurations \
    --output-dir ~/openjdk-test
```

## Next Steps

- See [`REAL_BUILD_GUIDE.md`](REAL_BUILD_GUIDE.md) for complete build documentation
- See [`CONFIGURATION_GUIDE.md`](CONFIGURATION_GUIDE.md) for JSON configuration details
- See [`CI_AGNOSTIC_ARCHITECTURE.md`](CI_AGNOSTIC_ARCHITECTURE.md) for architecture overview