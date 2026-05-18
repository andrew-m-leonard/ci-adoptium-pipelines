# Stage Input/Output Specification

This document explains how each pipeline stage expects inputs, outputs, and parameters.

## Overview

Each stage is a standalone shell script that communicates through:
1. **Environment Variables** - Configuration and directory paths
2. **File System** - Input artifacts, output artifacts, and metadata
3. **Exit Codes** - Success (0) or failure (non-zero)

## Common Environment Variables

All stages require these standard environment variables:

| Variable | Required | Description | Example |
|----------|----------|-------------|---------|
| `WORKSPACE` | Yes | Root workspace directory | `/Users/user/openjdk-build` |
| `CONFIG_FILE` | Yes | Path to pipeline-config.json | `${WORKSPACE}/pipeline-config.json` |
| `BUILD_NUMBER` | No | Build identifier | `123` or `local` |

## Stage 1: Initialize

**Script**: `scripts/lib/load-json-config.py`

### Inputs
- Command-line arguments (JDK version, variant, OS, architecture)
- JSON configuration files in `configurations/` directory

### Outputs
- `${WORKSPACE}/pipeline-config.json` - Generated pipeline configuration

### Environment Variables
None required (uses command-line arguments)

### Example
```bash
python3 scripts/lib/load-json-config.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --config-dir ./configurations \
    --output-dir /Users/user/openjdk-build
```

---

## Stage 2: Build

**Script**: `scripts/stages/02-build-corrected.sh`

### Required Environment Variables
| Variable | Description | Example |
|----------|-------------|---------|
| `WORKSPACE` | Root workspace directory | `/Users/user/openjdk-build` |
| `CONFIG_FILE` | Path to pipeline-config.json | `${WORKSPACE}/pipeline-config.json` |
| `OUTPUT_DIR` | **Where to place built artifacts** | `${WORKSPACE}/outputs` |
| `BUILD_NUMBER` | Build identifier (optional) | `local` |

### Inputs
- `${CONFIG_FILE}` - Pipeline configuration
- Boot JDK (must be in PATH or JAVA_HOME)
- Build tools (git, make, gcc, etc.)

### Outputs
- `${OUTPUT_DIR}/*.tar.gz` - Built JDK tar archives
- `${OUTPUT_DIR}/*.zip` - Built JDK zip archives (Windows)
- `${OUTPUT_DIR}/build-metadata.json` - Build metadata
- `${OUTPUT_DIR}/checksums.txt` - SHA256 checksums
- `${WORKSPACE}/stage-metadata.json` - Stage execution metadata

### What It Does
1. Clones temurin-build repository
2. Calls `build-farm/make-adopt-build-farm.sh` (the actual build script)
3. Copies built artifacts from temurin-build workspace to `${OUTPUT_DIR}`
4. Creates checksums and metadata

### Example
```bash
export WORKSPACE=/Users/user/openjdk-build
export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
export OUTPUT_DIR=${WORKSPACE}/outputs
export BUILD_NUMBER=local

./scripts/stages/02-build-corrected.sh
```

---

## Stage 3: Sign Artifacts

**Script**: `scripts/stages/06-sign.sh`

### Required Environment Variables
| Variable | Description | Example |
|----------|-------------|---------|
| `WORKSPACE` | Root workspace directory | `/Users/user/openjdk-build` |
| `CONFIG_FILE` | Path to pipeline-config.json | `${WORKSPACE}/pipeline-config.json` |
| `INPUT_DIR` | **Where to find artifacts to sign** | `${WORKSPACE}/outputs` |
| `OUTPUT_DIR` | **Where to place signed artifacts** | `${WORKSPACE}/signed` |
| `BUILD_NUMBER` | Build identifier (optional) | `local` |

### Inputs
- `${INPUT_DIR}/*.tar.gz` - Unsigned JDK archives
- `${INPUT_DIR}/*.zip` - Unsigned JDK archives
- `${CONFIG_FILE}` - Pipeline configuration
- Signing certificates (if configured)

### Outputs
- `${OUTPUT_DIR}/signed/*` - Signed artifacts
- `${OUTPUT_DIR}/checksums.txt` - SHA256 checksums of signed files
- `${WORKSPACE}/stage-metadata.json` - Stage execution metadata

### What It Does
1. Finds all tar.gz and zip files in `${INPUT_DIR}`
2. Signs them (or copies if signing not configured)
3. Places signed artifacts in `${OUTPUT_DIR}/signed/`
4. Creates checksums

### Example
```bash
export WORKSPACE=/Users/user/openjdk-build
export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
export INPUT_DIR=${WORKSPACE}/outputs      # Read from build stage output
export OUTPUT_DIR=${WORKSPACE}/signed      # Write signed artifacts here
export BUILD_NUMBER=local

./scripts/stages/06-sign.sh
```

---

## Stage 4: Build Installers

**Script**: `scripts/stages/07-installer.sh`

### Required Environment Variables
| Variable | Description | Example |
|----------|-------------|---------|
| `WORKSPACE` | Root workspace directory | `/Users/user/openjdk-build` |
| `CONFIG_FILE` | Path to pipeline-config.json | `${WORKSPACE}/pipeline-config.json` |
| `INPUT_DIR` | **Where to find signed JDK archives** | `${WORKSPACE}/signed` |
| `OUTPUT_DIR` | **Where to place installers** | `${WORKSPACE}/installers` |
| `BUILD_NUMBER` | Build identifier (optional) | `local` |

### Inputs
- `${INPUT_DIR}/*.tar.gz` - Signed JDK archives
- `${INPUT_DIR}/*.zip` - Signed JDK archives
- `${CONFIG_FILE}` - Pipeline configuration
- Platform-specific installer tools (WiX, pkgbuild, etc.)

### Outputs
- `${OUTPUT_DIR}/installers/*.msi` - Windows installers
- `${OUTPUT_DIR}/installers/*.pkg` - macOS installers
- `${OUTPUT_DIR}/installers/*.deb` - Debian packages
- `${OUTPUT_DIR}/installers/*.rpm` - RPM packages
- `${WORKSPACE}/stage-metadata.json` - Stage execution metadata

### What It Does
1. Extracts JDK from signed archives in `${INPUT_DIR}`
2. Builds platform-specific installers
3. Places installers in `${OUTPUT_DIR}/installers/`

### Example
```bash
export WORKSPACE=/Users/user/openjdk-build
export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
export INPUT_DIR=${WORKSPACE}/signed       # Read from sign stage output
export OUTPUT_DIR=${WORKSPACE}/installers  # Write installers here
export BUILD_NUMBER=local

./scripts/stages/07-installer.sh
```

---

## Stage 5: Smoke Tests

**Script**: `scripts/stages/13-smoke-tests.sh`

### Required Environment Variables
| Variable | Description | Example |
|----------|-------------|---------|
| `WORKSPACE` | Root workspace directory | `/Users/user/openjdk-build` |
| `CONFIG_FILE` | Path to pipeline-config.json | `${WORKSPACE}/pipeline-config.json` |
| `INPUT_DIR` | **Where to find JDK to test** | `${WORKSPACE}/outputs` |
| `OUTPUT_DIR` | **Where to place test results** | `${WORKSPACE}/test-results` |
| `BUILD_NUMBER` | Build identifier (optional) | `local` |

### Inputs
- `${INPUT_DIR}/*.tar.gz` - JDK archive to test
- `${CONFIG_FILE}` - Pipeline configuration

### Outputs
- `${OUTPUT_DIR}/test-results/*` - Test result files
- `${OUTPUT_DIR}/test-summary.txt` - Test summary
- `${WORKSPACE}/stage-metadata.json` - Stage execution metadata

### What It Does
1. Extracts JDK from archive in `${INPUT_DIR}`
2. Runs smoke tests (java -version, javac, etc.)
3. Saves test results to `${OUTPUT_DIR}`

### Example
```bash
export WORKSPACE=/Users/user/openjdk-build
export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
export INPUT_DIR=${WORKSPACE}/outputs      # Read from build stage output
export OUTPUT_DIR=${WORKSPACE}/test-results
export BUILD_NUMBER=local

./scripts/stages/13-smoke-tests.sh
```

---

## Pipeline Data Flow

```
Initialize Stage
    ↓ (creates)
pipeline-config.json
    ↓ (used by)
Build Stage
    ↓ (creates)
${WORKSPACE}/outputs/*.tar.gz
    ↓ (INPUT_DIR → OUTPUT_DIR)
Sign Stage
    ↓ (creates)
${WORKSPACE}/signed/*.tar.gz
    ↓ (INPUT_DIR → OUTPUT_DIR)
Installer Stage
    ↓ (creates)
${WORKSPACE}/installers/*.pkg
    
Smoke Tests Stage (parallel, uses outputs/)
    ↓ (creates)
${WORKSPACE}/test-results/*
```

## Directory Structure

After a complete pipeline run:

```
${WORKSPACE}/
├── pipeline-config.json          # Generated by initialize stage
├── stage-metadata.json           # Updated by each stage
├── outputs/                      # Build stage outputs
│   ├── OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.1_12.tar.gz
│   ├── build-metadata.json
│   └── checksums.txt
├── signed/                       # Sign stage outputs
│   ├── signed/
│   │   └── OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.1_12.tar.gz
│   └── checksums.txt
├── installers/                   # Installer stage outputs
│   └── installers/
│       └── OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.1_12.pkg
├── test-results/                 # Smoke test outputs
│   ├── test-summary.txt
│   └── smoke-test.log
└── temurin-build/                # Build repository (cloned by build stage)
    └── ...
```

## Key Points

1. **OUTPUT_DIR is NOT automatically set** - Each stage script expects it as an environment variable
2. **INPUT_DIR connects stages** - One stage's OUTPUT_DIR becomes the next stage's INPUT_DIR
3. **WORKSPACE is the anchor** - All paths are relative to WORKSPACE
4. **Stages are independent** - Each can run standalone if environment variables are set correctly
5. **No implicit defaults** - If OUTPUT_DIR or INPUT_DIR is not set, the stage will fail

## Current Issue

The `run-pipeline.py` script currently only sets:
- `WORKSPACE`
- `CONFIG_FILE`
- `BUILD_NUMBER`

But does NOT set:
- `OUTPUT_DIR` (required by build, sign, installer stages)
- `INPUT_DIR` (required by sign, installer, smoke-test stages)

This causes the sign stage to fail with:
```
[ERROR] Required directory not found: /Users/anleonar/openjdk-build/inputs
```

Because `INPUT_DIR` is undefined, and the script tries to validate `${INPUT_DIR}` which expands to empty string.

## Solution

The `run-pipeline.py` script needs to set these environment variables for each stage:

```python
# Build stage
env['OUTPUT_DIR'] = str(self.workspace / 'outputs')

# Sign stage
env['INPUT_DIR'] = str(self.workspace / 'outputs')
env['OUTPUT_DIR'] = str(self.workspace / 'signed')

# Installer stage
env['INPUT_DIR'] = str(self.workspace / 'signed')
env['OUTPUT_DIR'] = str(self.workspace / 'installers')

# Smoke test stage
env['INPUT_DIR'] = str(self.workspace / 'outputs')
env['OUTPUT_DIR'] = str(self.workspace / 'test-results')
```

## How Stages Actually Handle INPUT_DIR and OUTPUT_DIR

**IMPORTANT DISCOVERY**: All stages use the **same method** via [`config-utils.sh`](refactored_pipeline_examples/scripts/lib/config-utils.sh:100):

### The validate_standard_environment() Function

Every stage calls `validate_standard_environment()` which:

1. **Sets defaults if not provided**:
   ```bash
   export INPUT_DIR="${INPUT_DIR:-${WORKSPACE}/inputs}"
   export OUTPUT_DIR="${OUTPUT_DIR:-${WORKSPACE}/outputs}"
   ```

2. Stages that need INPUT_DIR then call `require_dir "${INPUT_DIR}"` to verify it exists
3. Stages that need OUTPUT_DIR then call `prepare_output_dir "${OUTPUT_DIR}"` to create it

### The Problem with Defaults

The **default values don't match the pipeline flow**:

| Stage | Default INPUT_DIR | Default OUTPUT_DIR | What Should Happen |
|-------|-------------------|-------------------|-------------------|
| Build | N/A | `${WORKSPACE}/outputs` | ✅ Correct - writes to outputs/ |
| Sign | `${WORKSPACE}/inputs` | `${WORKSPACE}/outputs` | ❌ Should read from outputs/, write to signed/ |
| Installer | `${WORKSPACE}/inputs` | `${WORKSPACE}/outputs` | ❌ Should read from signed/, write to installers/ |
| Smoke Tests | `${WORKSPACE}/inputs` | `${WORKSPACE}/outputs` | ❌ Should read from outputs/ for testing |

### Why the Sign Stage Failed

The sign stage failed because:
1. It uses the default `INPUT_DIR=${WORKSPACE}/inputs`
2. It calls `require_dir "${INPUT_DIR}"` which checks if `/Users/anleonar/openjdk-build/inputs` exists
3. That directory doesn't exist (build stage wrote to `outputs/`)
4. Error: `Required directory not found: /Users/anleonar/openjdk-build/inputs`

### The Correct Solution

The `run-pipeline.py` script must **override the defaults** by explicitly setting INPUT_DIR and OUTPUT_DIR for each stage:

```python
# Build stage - only needs OUTPUT_DIR
env['OUTPUT_DIR'] = str(self.workspace / 'outputs')

# Sign stage - reads from build, writes to separate directory
env['INPUT_DIR'] = str(self.workspace / 'outputs')   # Read from build output
env['OUTPUT_DIR'] = str(self.workspace / 'signed')   # Write to separate dir

# Installer stage - reads from sign, writes to separate directory
env['INPUT_DIR'] = str(self.workspace / 'signed')    # Read from sign output
env['OUTPUT_DIR'] = str(self.workspace / 'installers')

# Smoke test stage - reads from build output for testing
env['INPUT_DIR'] = str(self.workspace / 'outputs')   # Test the built JDK
env['OUTPUT_DIR'] = str(self.workspace / 'test-results')
```

This ensures:
- Each stage reads from the correct location
- Each stage writes to a separate directory
- No overwrites occur
- The pipeline flow is correct: build → sign → installer (with smoke tests reading from build)