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
- Verify `CONFIG_REPO_URL` and `CONFIG_REPO_BRANCH` parameters point to a valid config repo
- Ensure `jdk${version}_pipeline_config.json` exists under `configurations/` in the config repo
- Use the conversion tools in `tools/` if migrating from legacy Groovy configs

### Pipeline fails at build stage

**Problem:** Missing dependencies

**Solution:** Ensure required build tools (git, make, gcc, boot JDK) are installed on the agent

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
chmod +x scripts/stages/*.sh
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
./ci/local/run-pipeline.py --jdk-version jdk21u --variant temurin --target-os mac --architecture aarch64
# Build HotSpot
./ci/local/run-pipeline.py --jdk-version jdk21u --variant hotspot --target-os mac --architecture aarch64
```

### Parallel Builds (Different Workspaces)

```bash
# Terminal 1: JDK 21
./run-pipeline.py --jdk-version jdk21u --variant temurin --target-os mac --architecture aarch64 --workspace ~/jdk21-build

# Terminal 2: JDK 17
./run-pipeline.py --jdk-version jdk17u --variant temurin --target-os mac --architecture aarch64 --workspace ~/jdk17-build
```

## See Also

- [`CODE_CONFIG_SEPARATION.md`](CODE_CONFIG_SEPARATION.md) — JSON configuration details
- [`CI_AGNOSTIC_ARCHITECTURE.md`](CI_AGNOSTIC_ARCHITECTURE.md) — Architecture overview
- [`ci/local/README.md`](../ci/local/README.md) — Local runner full reference