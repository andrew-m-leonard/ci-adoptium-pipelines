# Reproducible Build Comparison Integration

## Overview

This document describes the reproducible build comparison capability in the CI Adoptium Pipelines. The system validates build reproducibility by comparing locally built JDKs against production binaries.

**Key Principle**: The comparison logic is **CI-agnostic** — the stage contract (`scripts/stages/20-reproducible-compare.sh`) defines the interface; the vendor-specific implementation (comparison tooling, binary source, acceptance criteria) lives in the config repo.

**Temurin implementation**: `ci-temurin-config/vendor-scripts/20-reproducible-compare.sh` — downloads from `api.adoptium.net` and delegates to `temurin-build/tooling/reproducible/repro_compare.sh`.

---

## CI-Agnostic Architecture

### Core Component: Stage Script

**Default stub**: [`scripts/stages/20-reproducible-compare.sh`](../scripts/stages/20-reproducible-compare.sh) — no-op, exits 0.

**Temurin vendor override**: `ci-temurin-config/vendor-scripts/20-reproducible-compare.sh` — provides the full implementation. The vendor script is resolved at runtime via [`StageScriptRunner`](../ci/jenkins/lib/StageScriptRunner.groovy) (Jenkins) or [`stage_resolver.py`](../ci/local/stage_resolver.py) (local).

### How It Works

```
┌─────────────────────────────────────────────────────────────────┐
│  1. Clone temurin-build repository                               │
│     └─ Contains repro_compare.sh tool                           │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  2. Download production binary from Adoptium API                 │
│     └─ Uses SCM_REF to identify the exact version               │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  3. Unpack both binaries                                         │
│     ├─ Production binary (from Adoptium API)                    │
│     └─ Locally built binary (from INPUT_ARTIFACTS_DIR)          │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  4. Run repro_compare.sh                                         │
│     └─ Byte-by-byte comparison after preprocessing              │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  5. Copy results to TARGET_DIR                                   │
│     ├─ comparison-report.txt                                    │
│     ├─ reprotest.diff (if differences found)                    │
│     └─ ReproduciblePercent                                      │
└─────────────────────────────────────────────────────────────────┘
```

### Required Environment Variables

| Variable | Description | Example |
|---|---|---|
| `WORKSPACE` | Stage workspace directory | `stage_workspace/` |
| `CONFIG_FILE` | Path to `pipeline-config.json` | `stage_workspace/pipeline-config.json` |
| `INPUT_ARTIFACTS_DIR` | Directory containing built JDK artifacts from the Build stage | `stage_workspace/` |
| `TARGET_DIR` | Directory where this stage writes comparison result files | `stage_workspace/target/` |
| `SCM_REF` | Git tag/ref for the build | `jdk-21.0.2+13` |
| `RELEASE` | `true` for release builds, `false` for EA/nightly | `true` |

### Optional Environment Variables

| Variable | Description | Default |
|---|---|---|
| `BUILD_REPO_URL` | temurin-build repository URL | `https://github.com/adoptium/temurin-build.git` |
| `BUILD_REF` | temurin-build branch/tag | `master` |

### Output Files

The script uses `${WORKSPACE}/reproducible-compare/` as a scratch area during execution. At the end, it copies result files to `${TARGET_DIR}/` so they are available for archiving by the orchestration layer:

| File | Location | Description |
|---|---|---|
| `comparison-report.txt` | `${TARGET_DIR}/` | Complete `repro_compare.sh` output |
| `ReproduciblePercent` | `${TARGET_DIR}/` | Reproducibility percentage (0–100) |
| `reprotest.diff` | `${TARGET_DIR}/` | List of differing files (only present when differences found) |
| `reproducible_evidence.log` | `${WORKSPACE}/reproducible-compare/` only | Detailed comparison log (written by `repro_compare.sh` but **not** copied to `TARGET_DIR`) |

### Exit Codes

- **0**: Build is 100% reproducible
- **Non-zero**: Differences detected — pipeline marks build `UNSTABLE`, does not fail

---

## CI-Specific Implementations

### Jenkins Pipeline

**File**: [`ci/jenkins/Jenkinsfile.declarative`](../ci/jenkins/Jenkinsfile.declarative)

#### Enablement

The stage runs when all three conditions are true:

```groovy
when {
    allOf {
        expression { params.REPRODUCIBLE_COMPARE_BUILD == true }
        expression { params.SCM_REF != null && params.SCM_REF != '' }
        expression { currentBuild.result == null || currentBuild.result == 'SUCCESS' }
    }
}
```

#### Stage behaviour

1. `initializeStage()` — `cleanWs()`, checkout, `copyArtifacts` (filter: `pipeline-config.json,*.tar.gz,*.zip`) into `stage_input_artifacts/`
2. `env.TARGET_DIR = "${WORKSPACE}/reproducible_compare_output"`
3. `env.SCM_REF = params.SCM_REF`
4. `env.RELEASE = (params.RELEASE_TYPE == 'RELEASE') ? 'true' : 'false'`
5. `stageRunner.run('20-reproducible-compare', config)`
6. `archiveArtifacts artifacts: 'TARGET_DIR/**/*', allowEmptyArchive: true` — archives flat files from `reproducible_compare_output/`
7. Sets `currentBuild.result = 'UNSTABLE'` on non-zero exit code (does **not** call `error()`)
8. `finalizeStage()` — optional `cleanWs()`

Jenkins does **not** display the comparison report inline — results are available via the archived artifacts link.

#### Jenkins archived artifact paths

Jenkins archives the contents of `reproducible_compare_output/` flat:
- `comparison-report.txt`
- `ReproduciblePercent`
- `reprotest.diff` (when differences found)

---

### Local Pipeline Runner

**File**: [`ci/local/run-pipeline.py`](../ci/local/run-pipeline.py)

#### Command line options

```bash
--compare-build              # Enable reproducible build comparison
--scm-ref <ref>              # SCM reference (REQUIRED with --compare-build)
--release-type RELEASE       # Set to RELEASE for release builds (default: NIGHTLY → RELEASE=false)
```

#### Usage example

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21u \
    --target-os mac \
    --architecture aarch64 \
    --scm-ref jdk-21.0.2+13 \
    --release-type RELEASE \
    --compare-build
```

#### Stage execution

`stage_reproducible_compare()`:

1. `cleanup_stage_workspace('pre')` — wipes `stage_workspace/`
2. `restore_stage_inputs('Reproducible Compare', 'pipeline-config.json,**/*.tar.gz,**/*.zip')` — copies from `build_artifacts/`
3. Sets env: `WORKSPACE=stage_workspace/`, `TARGET_DIR=stage_workspace/target/`, `INPUT_ARTIFACTS_DIR=stage_workspace/`, `SCM_REF`, `RELEASE`
4. `StageResolver.run('20-reproducible-compare', env)`
5. Reads result files from `stage_workspace/target/`
6. Displays inline: reproducibility percentage, comparison report (on failure), first 50 lines of `reprotest.diff` (on failure)
7. `archive_stage_outputs('Reproducible Compare')` — copies `stage_workspace/target/**` → `build_artifacts/`
8. **Does not raise** if comparison fails — prints warning and raises `CalledProcessError` to fail the stage

#### Restart support

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21u \
    --target-os mac \
    --architecture aarch64 \
    --scm-ref jdk-21.0.2+13 \
    --start-from-stage reproducible-compare
```

#### Output example (success)

```
Comparison exit code: 0
✅ SUCCESS: Build is 100% reproducible
   Reproducibility: 100%

📁 Comparison artifacts saved to: ~/openjdk-build/stage_workspace/target
   - comparison-report.txt
   - ReproduciblePercent
```

#### Output example (failure)

```
Comparison exit code: 1
❌ FAILED: Reproducible build comparison failed (exit code: 1)

📄 Comparison Report:
[comparison output...]

📄 Differences (reprotest.diff):
[first 50 lines of diff...]

   Reproducibility: 87%

⚠️  Stage failed due to reproducibility issues
```

---

## Tool Capabilities

The underlying `repro_compare.sh` tool provides:

1. **File Structure Comparison**: Verifies same files exist in both builds
2. **File Count Validation**: Ensures no missing or extra files
3. **Binary Comparison**: Byte-by-byte comparison after preprocessing
4. **Platform-Specific Preprocessing**:
   - Removes build timestamps
   - Removes build IDs and UUIDs
   - Removes absolute paths in debug info
   - Normalises platform-specific metadata
5. **Detailed Reporting**:
   - `reprotest.diff` — lists differing files
   - `reproducible_evidence.log` — detailed comparison log (in scratch workspace, not archived)
   - `ReproduciblePercent` — percentage match metric (0–100)
   - Exit code 0 = identical, non-zero = differences

---

## Success Criteria

For a build to be considered reproducible:

- ✅ **Exit code 0** (no differences detected by `repro_compare.sh`)
- ✅ **100% ReproduciblePercent**
- ✅ **Empty or absent `reprotest.diff`**

---

## Platform Support

| Platform | `repro_compare.sh` OS identifier | Status |
|---|---|---|
| Linux x64 | `Linux` | Supported |
| Linux aarch64 | `Linux` | Supported |
| Linux ppc64le | `Linux` | Supported |
| Linux s390x | `Linux` | Supported |
| macOS x64 | `Darwin` | Supported |
| macOS aarch64 | `Darwin` | Supported |
| Windows x64 | `CYGWIN` | Supported |
| Windows x86-32 | `CYGWIN` | Supported |
| AIX ppc64 | `AIX` | Mapped but upstream tool support may vary |

---

## References

- **Stage Script**: [`scripts/stages/20-reproducible-compare.sh`](../scripts/stages/20-reproducible-compare.sh)
- **Comparison Tool**: `temurin-build/tooling/reproducible/repro_compare.sh`
- **Jenkins Implementation**: [`ci/jenkins/Jenkinsfile.declarative`](../ci/jenkins/Jenkinsfile.declarative) (`Reproducible Compare Build` stage)
- **Local Runner**: [`ci/local/run-pipeline.py`](../ci/local/run-pipeline.py) (`stage_reproducible_compare()`)
