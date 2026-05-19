# Pipeline Runner Guide

The `run-pipeline.py` script allows you to run the complete OpenJDK build pipeline locally from the command line.

## Quick Start

```bash
cd ci-adoptium-pipelines

# Run full pipeline
./ci/local/run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64
```

## What It Does

The pipeline runner orchestrates all build stages automatically:

1. **Initialize**: Generates `pipeline-config.json` from JSON configuration files
2. **Build**: Builds OpenJDK using `make-adopt-build-farm.sh`
3. **Sign**: Signs the built artifacts (if enabled)
4. **Installer**: Creates installers (if enabled)
5. **Smoke Tests**: Runs basic smoke tests (if enabled)

## Command Line Options

### Required Parameters

| Parameter | Description | Choices |
|-----------|-------------|---------|
| `--jdk-version` | JDK version to build | jdk8u, jdk11u, jdk17u, jdk21u, jdk22u, jdk23u, jdk |
| `--variant` | Build variant | temurin, hotspot |
| `--target-os` | Target operating system | mac, linux, windows, aix |
| `--architecture` | Target architecture | aarch64, x64, x32, ppc64, s390x |

### Optional Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `--workspace` | Workspace directory | `~/openjdk-build` |
| `--build-number` | Build identifier | `local-YYYYMMDD-HHMMSS` |
| `--release` | Mark as release build | false |
| `--weekly` | Mark as weekly build | false |
| `--scm-ref` | OpenJDK source branch/tag | master |
| `--build-ref` | temurin-build branch/tag | master |
| `--build-repo-url` | temurin-build repository URL | https://github.com/adoptium/temurin-build.git |

### Workspace Control

| Parameter | Description |
|-----------|-------------|
| `--clean-workspace` | Remove existing workspace before starting (ensures clean build) |

**Workspace Behavior:**
- **Without `--clean-workspace`**: Reuses existing workspace if present (faster, but may have stale files)
- **With `--clean-workspace`**: Removes entire workspace directory before starting (clean build, slower)

### Stage Control

| Parameter | Description |
|-----------|-------------|
| `--skip-build` | Only generate configuration, don't build |
| `--no-tests` | Skip smoke tests |
| `--no-installers` | Skip installer creation |
| `--no-signer` | Skip artifact signing |

## Usage Examples

### Example 1: Full Build (Mac Apple Silicon)

```bash
./run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64
```

**Output:**
- Configuration: `~/openjdk-build/pipeline-config.json`
- JDK tarball: `~/openjdk-build/workspace/target/OpenJDK*.tar.gz`
- Build logs: `~/openjdk-build/workspace/build/logs/`

### Example 2: Clean Build (Remove Existing Workspace)

```bash
./run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --clean-workspace
```

Removes the existing workspace directory before starting, ensuring a completely clean build.

### Example 3: Build Only (No Tests or Installers)

```bash
./run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --no-tests \
    --no-installers
```

Runs only the build stage, skipping tests and installer creation.

### Example 4: Linux x64 Release Build

```bash
./run-pipeline.py \
    --jdk-version jdk17u \
    --variant temurin \
    --target-os linux \
    --architecture x64 \
    --release \
    --scm-ref jdk-17.0.10+7
```

Builds a release version of JDK 17 for Linux x64.

### Example 5: Custom Workspace

```bash
./run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --workspace ~/my-custom-build
```

Uses a custom workspace directory instead of the default.

### Example 6: Custom temurin-build Branch

```bash
./run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --build-ref develop
```

Uses the `develop` branch of temurin-build instead of `master`.

### Example 7: Fork of temurin-build

```bash
./run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --build-repo-url https://github.com/myorg/temurin-build.git \
    --build-ref my-feature-branch
```

Uses a forked repository and custom branch.

### Example 8: Configuration Only

```bash
./run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --skip-build
```

Only generates the configuration file without running the build.

## Pipeline Stages

### Stage 1: Initialize

Generates `pipeline-config.json` by:
1. Loading `jdkNN_pipeline_config.json`
2. Extracting platform-specific settings
3. Applying variant-specific overrides
4. Creating configuration file

**Output:** `${WORKSPACE}/pipeline-config.json`

### Stage 2: Build

Builds OpenJDK by:
1. Cloning temurin-build repository
2. Running `make-adopt-build-farm.sh`
3. Creating JDK tarball

**Output:** `${WORKSPACE}/workspace/target/OpenJDK*.tar.gz`

### Stage 3: Sign (Optional)

Signs artifacts if enabled and applicable.

**Output:** `${WORKSPACE}/signed/`

### Stage 4: Installer (Optional)

Creates platform-specific installers.

**Output:** `${WORKSPACE}/installers/`

### Stage 5: Smoke Tests (Optional)

Runs basic smoke tests on the built JDK.

**Output:** Test results in console

## Environment Variables

The pipeline runner sets these environment variables for each stage:

| Variable | Description | Example |
|----------|-------------|---------|
| `WORKSPACE` | Root workspace directory | `/Users/user/openjdk-build` |
| `CONFIG_FILE` | Path to pipeline config | `${WORKSPACE}/pipeline-config.json` |
| `BUILD_NUMBER` | Build identifier | `local-20260506-140000` |

## Output Structure

```
~/openjdk-build/                    # Workspace root
├── pipeline-config.json            # Generated configuration
├── temurin-build/                  # Cloned build scripts
├── workspace/
│   ├── build/                      # Build artifacts
│   │   └── logs/                   # Build logs
│   └── target/                     # Final outputs
│       ├── OpenJDK*.tar.gz         # JDK tarball
│       └── metadata/               # Build metadata
├── signed/                         # Signed artifacts (if enabled)
├── installers/                     # Installers (if enabled)
└── stage-metadata.json             # Stage execution info
```

## Troubleshooting

### Pipeline fails at initialize stage

**Problem:** Configuration file not found

**Solution:**
```bash
# Check if JSON config exists
ls -la configurations/jdk21u_pipeline_config.json

# If missing, convert from Groovy
./tools/convert-groovy-config-to-json.sh \
    ~/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations/jdk21u_pipeline_config.groovy \
    configurations/jdk21u_pipeline_config.json
```

### Pipeline fails at build stage

**Problem:** Missing dependencies

**Solution:** See [`REAL_BUILD_GUIDE.md`](REAL_BUILD_GUIDE.md) for required dependencies

### Workspace has stale files

**Problem:** Previous build artifacts interfering with new build

**Solution:**
```bash
# Clean workspace before building
./run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --clean-workspace
```

### Build takes too long

**Problem:** Full build can take 30-60 minutes

**Solution:** Use `--skip-build` to test configuration only, or `--no-tests --no-installers` to speed up

### Permission denied

**Problem:** Script not executable

**Solution:**
```bash
chmod +x run-pipeline.py
```

## Comparison with Manual Execution

### Manual (Multiple Commands)

```bash
# Step 1: Generate config
python3 scripts/lib/load-json-config.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --output-dir ~/openjdk-build

# Step 2: Set environment
export WORKSPACE=~/openjdk-build
export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
export BUILD_NUMBER=test-1

# Step 3: Run build
./scripts/stages/02-build-corrected.sh

# Step 4: Run tests
./scripts/stages/13-smoke-tests.sh
```

### Pipeline Runner (Single Command)

```bash
./run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64
```

## Advanced Usage

### Dry Run (Configuration Only)

```bash
./run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --skip-build
```

### Build Multiple Variants

```bash
# Build Temurin
./run-pipeline.py --jdk-version jdk21u --variant temurin --target-os mac --architecture aarch64
# Build HotSpot
./run-pipeline.py --jdk-version jdk21u --variant hotspot --target-os mac --architecture aarch64


# Build Hotspot
./run-pipeline.py --jdk-version jdk21u --variant hotspot --target-os mac --architecture aarch64
```

### Parallel Builds (Different Workspaces)

```bash
# Terminal 1: JDK 21
./run-pipeline.py --jdk-version jdk21u --variant temurin --target-os mac --architecture aarch64 --workspace ~/jdk21-build

# Terminal 2: JDK 17
./run-pipeline.py --jdk-version jdk17u --variant temurin --target-os mac --architecture aarch64 --workspace ~/jdk17-build
```

## Integration with CI/CD

The pipeline runner can be used in CI/CD systems:

### Jenkins

```groovy
stage('Build') {
    steps {
        sh """
            python3 run-pipeline.py \
                --jdk-version ${params.JDK_VERSION} \
                --variant ${params.VARIANT} \
                --target-os ${params.TARGET_OS} \
                --architecture ${params.ARCHITECTURE} \
                --workspace ${WORKSPACE}
        """
    }
}
```

### GitLab CI

```yaml
build:
  script:
    - python3 run-pipeline.py
        --jdk-version $JDK_VERSION
        --variant $VARIANT
        --target-os $TARGET_OS
        --architecture $ARCHITECTURE
        --workspace $CI_PROJECT_DIR/build
```

### GitHub Actions

```yaml
- name: Build OpenJDK
  run: |
    python3 run-pipeline.py \
      --jdk-version ${{ matrix.jdk-version }} \
      --variant ${{ matrix.variant }} \
      --target-os ${{ matrix.os }} \
      --architecture ${{ matrix.arch }} \
      --workspace ${{ github.workspace }}/build
```

## See Also

- [`QUICKSTART_MAC.md`](QUICKSTART_MAC.md) - Mac-specific quick start guide
- [`CONFIGURATION_GUIDE.md`](CONFIGURATION_GUIDE.md) - JSON configuration details
- [`REAL_BUILD_GUIDE.md`](REAL_BUILD_GUIDE.md) - Complete build documentation
- [`CI_AGNOSTIC_ARCHITECTURE.md`](CI_AGNOSTIC_ARCHITECTURE.md) - Architecture overview