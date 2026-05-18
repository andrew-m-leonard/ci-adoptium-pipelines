# Real OpenJDK Build Guide

This guide explains how to use the real build stage (`02-build-real.sh`) to build OpenJDK from source using the Adoptium temurin-build repository.

## Overview

The [`02-build-real.sh`](scripts/stages/02-build-real.sh:1) script performs a complete OpenJDK build by:

1. Cloning/updating the temurin-build repository
2. Setting up the build environment
3. Executing the build using `makejdk-any-platform.sh`
4. Extracting build metadata
5. Organizing and archiving build outputs

## Prerequisites

### System Requirements

**Minimum:**
- 8 GB RAM (16 GB recommended)
- 50 GB free disk space
- 4 CPU cores (8+ recommended)

**Build Time:**
- First build: 30-60 minutes (depending on hardware)
- Incremental builds: 5-15 minutes

### Required Tools

**All Platforms:**
- Git
- Make
- Bash 4.0+
- Boot JDK (N-1 version of what you're building)
  - Building JDK 21: Need JDK 20 or 21
  - Building JDK 17: Need JDK 16 or 17

**macOS:**
```bash
# Install Xcode Command Line Tools
xcode-select --install

# Install Homebrew
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Install required tools
brew install git make bash autoconf

# Install boot JDK
brew install openjdk@21
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get update
sudo apt-get install -y \
    git make gcc g++ \
    libx11-dev libxext-dev libxrender-dev libxrandr-dev libxtst-dev libxt-dev \
    libcups2-dev libfontconfig1-dev \
    libasound2-dev \
    openjdk-21-jdk
```

**Linux (RHEL/CentOS):**
```bash
sudo yum install -y \
    git make gcc gcc-c++ \
    libX11-devel libXext-devel libXrender-devel libXrandr-devel libXtst-devel libXt-devel \
    cups-devel fontconfig-devel \
    alsa-lib-devel \
    java-21-openjdk-devel
```

## Quick Start

### 1. Setup Configuration

Create a configuration file for your build:

```bash
cat > ~/openjdk-build/pipeline-config.json <<'EOF'
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk21u",
    "TARGET_OS": "mac",
    "ARCHITECTURE": "aarch64",
    "VARIANT": "temurin",
    "BUILD_ARGS": "--clean-git-repo",
    "SCM_REF": "master",
    "CLEAN_WORKSPACE": false
  },
  "parameters": {
    "cleanWorkspace": false
  },
  "buildNumber": "local-001",
  "jobName": "local-openjdk-build"
}
EOF
```

### 2. Set Environment Variables

```bash
export WORKSPACE=~/openjdk-build
export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
export BUILD_NUMBER=local-001
export OUTPUT_DIR=${WORKSPACE}/outputs
export DEBUG=true

# Ensure workspace exists
mkdir -p ${WORKSPACE} ${OUTPUT_DIR}
```

### 3. Run the Build

```bash
cd /Users/anleonar/workspace/bob/refactored_pipeline_examples
./scripts/stages/02-build-real.sh
```

## Configuration Options

### Java Versions

Supported values for `JAVA_TO_BUILD`:
- `jdk8u` - Java 8 (LTS)
- `jdk11u` - Java 11 (LTS)
- `jdk17u` - Java 17 (LTS)
- `jdk21u` - Java 21 (LTS)
- `jdk` - Latest development version

### Target OS

Supported values for `TARGET_OS`:
- `mac` / `macos` / `darwin` - macOS
- `linux` - Linux
- `windows` / `win` - Windows

### Architecture

Supported values for `ARCHITECTURE`:
- `aarch64` / `arm64` - ARM 64-bit (Apple Silicon, ARM servers)
- `x64` / `x86_64` / `amd64` - Intel/AMD 64-bit
- `x86` / `x32` - Intel/AMD 32-bit (legacy)
- `arm` - ARM 32-bit (legacy)

### Variant

Supported values for `VARIANT`:
- `temurin` - Eclipse Temurin (recommended)
- `hotspot` - OpenJDK HotSpot
- `temurin` - Eclipse Temurin (default)

### Build Arguments

Common `BUILD_ARGS` options (from `makejdk-any-platform.sh --help`):

```bash
# Clean git repository before build
--clean-git-repo

# Clean workspace before build
--clean-workspace

# Create debug image
--create-debug-image

# Create JRE image in addition to JDK
--create-jre-image

# Create SBOM (Software Bill of Materials)
--create-sbom

# Custom configure arguments
--configure-args "--with-native-debug-symbols=internal"

# Custom make arguments
--make-args "JOBS=8"

# Specify target file name
--target-file-name "MyJDK.tar.gz"

# Build specific JVM variant (server or client)
--jvm-variant server

# Specify number of processors for build
--processors 8

# Use specific boot JDK
--boot-jdk /path/to/jdk

# Build from specific tag
--tag jdk-21.0.1+12

# Build from specific branch
--branch master
```

**Important**: Options like `--clean-docker-build` and `--disable-test-image` are NOT valid. Use `--clean-git-repo` or `--clean-workspace` instead.

## Build Examples

### Example 1: macOS Apple Silicon (M1/M2/M3)

```json
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk21u",
    "TARGET_OS": "mac",
    "ARCHITECTURE": "aarch64",
    "VARIANT": "temurin",
    "BUILD_ARGS": "--clean-docker-build --disable-test-image",
    "SCM_REF": "master"
  }
}
```

### Example 2: Linux x86_64

```json
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk17u",
    "TARGET_OS": "linux",
    "ARCHITECTURE": "x64",
    "VARIANT": "temurin",
    "BUILD_ARGS": "--clean-docker-build --create-jre-image",
    "SCM_REF": "master"
  }
}
```

### Example 3: Windows x64

```json
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk11u",
    "TARGET_OS": "windows",
    "ARCHITECTURE": "x64",
    "VARIANT": "temurin",
    "BUILD_ARGS": "--clean-docker-build",
    "SCM_REF": "master"
  }
}
```

## Build Process Details

### What the Script Does

1. **Environment Setup** (1-2 minutes)
   - Validates required tools
   - Checks for boot JDK
   - Detects OS and architecture

2. **Repository Setup** (2-5 minutes)
   - Clones temurin-build repository (first time only)
   - Updates to specified SCM_REF
   - Verifies build scripts

3. **Workspace Preparation** (<1 minute)
   - Cleans previous build artifacts (if requested)
   - Creates output directories

4. **Build Execution** (30-60 minutes)
   - Runs `makejdk-any-platform.sh`
   - Compiles OpenJDK from source
   - Creates JDK image
   - Generates test images (if enabled)

5. **Metadata Extraction** (<1 minute)
   - Extracts version information
   - Creates build metadata JSON
   - Captures build configuration

6. **Output Organization** (1-2 minutes)
   - Finds built artifacts
   - Creates tar.gz archives
   - Copies to output directory
   - Generates checksums

### Build Outputs

After a successful build, you'll find:

```
${OUTPUT_DIR}/
├── OpenJDK-aarch64_mac_temurin.tar.gz    # Main JDK archive
├── build-metadata.json                    # Build information
├── checksums.txt                          # SHA256 checksums
└── release                                # JDK release file
```

## Troubleshooting

### Build Fails: "Boot JDK not found"

**Solution:** Install a boot JDK (N-1 version):

```bash
# macOS
brew install openjdk@21

# Linux
sudo apt-get install openjdk-21-jdk

# Set JAVA_HOME
export JAVA_HOME=$(/usr/libexec/java_home -v 21)  # macOS
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64  # Linux
```

### Build Fails: "Out of memory"

**Solution:** Reduce parallel jobs:

```json
{
  "BUILD_ARGS": "--make-args 'JOBS=2'"
}
```

### Build Fails: "Missing dependencies"

**Solution:** Install platform-specific dependencies (see Prerequisites section)

### Build Times Out

**Solution:** The script has a 4-hour timeout. For slower machines:

1. Use `--disable-test-image` to skip test builds
2. Reduce parallel jobs
3. Use incremental builds (set `cleanWorkspace: false`)

### Artifacts Not Found

**Solution:** Check build logs:

```bash
# View last 100 lines of build output
tail -n 100 ${WORKSPACE}/temurin-build/workspace/build.log

# Check for JDK image
find ${WORKSPACE}/temurin-build/workspace -name "jdk" -type d
```

## Advanced Usage

### Custom Build Script Location

If you have temurin-build already cloned elsewhere:

```bash
# Create symlink
ln -s /path/to/existing/temurin-build ${WORKSPACE}/temurin-build
```

### Building Multiple Versions

```bash
# Build JDK 17
export JAVA_TO_BUILD=jdk17u
export OUTPUT_DIR=${WORKSPACE}/outputs/jdk17
./scripts/stages/02-build-real.sh

# Build JDK 21
export JAVA_TO_BUILD=jdk21u
export OUTPUT_DIR=${WORKSPACE}/outputs/jdk21
./scripts/stages/02-build-real.sh
```

### Cross-Compilation

For cross-compilation (e.g., building ARM on x86), you'll need:

1. Cross-compilation toolchain
2. Target sysroot
3. Additional configure arguments

```json
{
  "BUILD_ARGS": "--configure-args '--openjdk-target=aarch64-linux-gnu --with-sysroot=/path/to/sysroot'"
}
```

## Performance Tips

### Faster Builds

1. **Use SSD storage** - Significantly faster than HDD
2. **Increase RAM** - More RAM = more parallel jobs
3. **Disable test images** - Add `--disable-test-image`
4. **Use ccache** - Install ccache for faster recompilation
5. **Incremental builds** - Set `cleanWorkspace: false`

### Disk Space Management

```bash
# Clean old builds
rm -rf ${WORKSPACE}/temurin-build/workspace/build

# Clean downloaded dependencies
rm -rf ${WORKSPACE}/temurin-build/workspace/target

# Keep only final artifacts
find ${OUTPUT_DIR} -name "*.tar.gz" -o -name "*.zip"
```

## Integration with CI/CD

### Jenkins

```groovy
stage('Build') {
    steps {
        sh '''
            export WORKSPACE=${WORKSPACE}
            export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
            export BUILD_NUMBER=${BUILD_NUMBER}
            ./scripts/stages/02-build-real.sh
        '''
    }
}
```

### GitLab CI

```yaml
build:
  script:
    - export WORKSPACE=${CI_PROJECT_DIR}
    - export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
    - export BUILD_NUMBER=${CI_PIPELINE_ID}
    - ./scripts/stages/02-build-real.sh
```

### GitHub Actions

```yaml
- name: Build OpenJDK
  run: |
    export WORKSPACE=${GITHUB_WORKSPACE}
    export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
    export BUILD_NUMBER=${GITHUB_RUN_NUMBER}
    ./scripts/stages/02-build-real.sh
```

## Next Steps

After building:

1. **Sign artifacts** - Use [`06-sign.sh`](scripts/stages/06-sign.sh:1)
2. **Build installers** - Use [`07-installer.sh`](scripts/stages/07-installer.sh:1)
3. **Run tests** - Use [`13-smoke-tests.sh`](scripts/stages/13-smoke-tests.sh:1)

## Resources

- [Adoptium temurin-build](https://github.com/adoptium/temurin-build)
- [OpenJDK Build Instructions](https://openjdk.org/groups/build/doc/building.html)
- [Adoptium Build Farm](https://ci.adoptium.net/)

## Support

For issues with:
- **This script**: Check logs in `${WORKSPACE}/temurin-build/workspace/`
- **temurin-build**: See [Adoptium Slack](https://adoptium.net/slack)
- **OpenJDK**: See [OpenJDK Mailing Lists](https://mail.openjdk.org/mailman/listinfo)