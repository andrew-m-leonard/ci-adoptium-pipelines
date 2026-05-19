# Reproducible Build Comparison Integration

## Overview

This document describes the reproducible build comparison capability in the CI Adoptium Pipelines. The system validates build reproducibility by comparing locally built JDKs against production Adoptium binaries using the proven `temurin-build/tooling/reproducible/repro_compare.sh` tool.

**Key Principle**: The comparison logic is **CI-agnostic** - implemented in a single shell script (`scripts/stages/20-reproducible-compare.sh`) that works across all CI platforms and local execution.

---

## CI-Agnostic Architecture

### Core Component: Stage Script

**Location**: [`scripts/stages/20-reproducible-compare.sh`](../scripts/stages/20-reproducible-compare.sh)

This shell script provides the complete reproducible build comparison functionality and can be executed from any CI system or locally.

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
│     └─ Locally built binary (from TARGET_DIR)                   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  4. Run repro_compare.sh                                         │
│     └─ Byte-by-byte comparison after preprocessing              │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  5. Generate comparison report                                   │
│     ├─ comparison-report.txt (script output)                    │
│     ├─ reprotest.diff (differences found)                       │
│     ├─ reproducible_evidence.log (detailed log)                 │
│     └─ ReproduciblePercent (percentage metric)                  │
└─────────────────────────────────────────────────────────────────┘
```

### Required Environment Variables

The stage script requires these environment variables (set by CI orchestration):

| Variable | Description | Example |
|----------|-------------|---------|
| `WORKSPACE` | Stage workspace directory | `/path/to/stage_workspace` |
| `CONFIG_FILE` | Path to pipeline-config.json | `/path/to/pipeline-config.json` |
| `TARGET_DIR` | Directory containing built artifacts | `/path/to/artifacts` |
| `SCM_REF` | Git tag/ref for the build | `jdk-21.0.2+13` |
| `RELEASE` | Boolean: true for release, false for EA | `true` or `false` |

### Optional Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `BUILD_REPO_URL` | temurin-build repository URL | `https://github.com/adoptium/temurin-build.git` |
| `BUILD_REF` | temurin-build branch/tag | `master` |

### Output Files

The script creates these files in `${WORKSPACE}/reproducible-compare/`:

| File | Description | Source |
|------|-------------|--------|
| `comparison-report.txt` | Complete comparison output | Stage script |
| `reprotest.diff` | List of different files | repro_compare.sh |
| `reproducible_evidence.log` | Detailed comparison log | repro_compare.sh |
| `ReproduciblePercent` | Reproducibility percentage (0-100) | repro_compare.sh |

### Exit Codes

- **0**: Build is 100% reproducible (success)
- **Non-zero**: Differences detected (failure)

---

## CI-Specific Implementations

### Jenkins Pipeline

**File**: [`ci/jenkins/Jenkinsfile.declarative`](../ci/jenkins/Jenkinsfile.declarative)

#### Parameter

```groovy
booleanParam(
    name: 'REPRODUCIBLE_COMPARE_BUILD',
    defaultValue: false,
    description: 'Enable reproducible build comparison against production Adoptium binaries (requires SCM_REF to be set)'
)
```

#### Stage Definition

The `Reproducible Compare Build` stage is positioned after `Smoke Tests` and before `AQA Tests`.

**When Condition**:
```groovy
when {
    allOf {
        expression { params.REPRODUCIBLE_COMPARE_BUILD == true }
        expression { params.SCM_REF != null && params.SCM_REF != '' }
        expression { currentBuild.result == null || currentBuild.result == 'SUCCESS' }
    }
}
```

**Key Features**:
- Pre-stage workspace cleanup with `cleanWs()`
- Retrieves built JDK using `copyArtifacts`
- Executes `scripts/stages/20-reproducible-compare.sh`
- Archives comparison results as artifacts
- Sets build to `UNSTABLE` if comparison fails
- Displays comparison report and diff summary
- Post-stage cleanup (optional, controlled by `CLEAN_WORKSPACE_AFTER_STAGE`)

#### Usage Example

```groovy
// Jenkins job parameters:
JDK_VERSION = 'jdk21u'
VARIANT = 'temurin'
TARGET_OS = 'mac'
ARCHITECTURE = 'aarch64'
SCM_REF = 'jdk-21.0.2+13'
RELEASE = true
REPRODUCIBLE_COMPARE_BUILD = true
```

#### Output

Comparison artifacts are archived and available in Jenkins:
- `reproducible-compare/comparison-report.txt`
- `reproducible-compare/reprotest.diff`
- `reproducible-compare/reproducible_evidence.log`
- `reproducible-compare/ReproduciblePercent`

---

### Local Pipeline Runner

**File**: [`ci/local/run-pipeline.py`](../ci/local/run-pipeline.py)

#### Command Line Option

```bash
--compare-build              # Enable reproducible build comparison
--scm-ref <ref>             # SCM reference (REQUIRED with --compare-build)
```

#### Usage Example

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --scm-ref jdk-21.0.2+13 \
    --release \
    --compare-build
```

#### Stage Execution

The `stage_reproducible_compare()` method:
1. Sets up environment variables
2. Executes `scripts/stages/20-reproducible-compare.sh`
3. Captures exit code
4. Checks for output files
5. Displays results with reproducibility percentage
6. Shows comparison report and diff on failure
7. Lists all comparison artifacts
8. Raises exception if comparison fails

#### Output Example

**Success**:
```
Comparison exit code: 0
✅ SUCCESS: Build is 100% reproducible
   Reproducibility: 100%

📁 Comparison artifacts saved to: /path/to/stage_workspace/reproducible-compare
   - comparison-report.txt
   - reprotest.diff
   - ReproduciblePercent
   - reproducible_evidence.log
```

**Failure**:
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

#### Restart Support

```bash
# Resume from comparison stage only
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --scm-ref jdk-21.0.2+13 \
    --start-from-stage reproducible-compare
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
   - Normalizes platform-specific metadata
5. **Detailed Reporting**:
   - `reprotest.diff` - Lists different files
   - `reproducible_evidence.log` - Detailed comparison log
   - `ReproduciblePercent` - Percentage match metric (0-100)
   - Exit code 0 = identical, non-zero = differences

---

## Success Criteria

For a build to be considered reproducible:

- ✅ **Exit code 0** (no differences detected)
- ✅ **100% ReproduciblePercent**
- ✅ **Empty reprotest.diff** (no files differ)
- ✅ **All test results match**
- ✅ **Performance within acceptable range**

---

## Platform Support

| Platform | Tool Support | Status |
|----------|-------------|--------|
| Linux x64 | ✅ Supported | Ready |
| Linux aarch64 | ✅ Supported | Ready |
| Linux ppc64le | ✅ Supported | Ready |
| Linux s390x | ✅ Supported | Ready |
| macOS x64 | ✅ Supported (Darwin) | Ready |
| macOS aarch64 | ✅ Supported (Darwin) | Ready |
| Windows x64 | ✅ Supported (CYGWIN) | Ready |
| Windows x86-32 | ✅ Supported (CYGWIN) | Ready |
| AIX ppc64 | ⚠️ Check support | TBD |

---

## Migration Validation Workflow

During migration from old pipeline to new pipeline:

1. **Old pipeline** builds JDK → archives artifact
2. **New pipeline** builds JDK → archives artifact
3. **Comparison stage** extracts both builds
4. **repro_compare.sh** compares builds
5. **Results** archived and displayed on dashboard
6. **Migration proceeds** only if 100% reproducible

---

## References

- **Stage Script**: [`scripts/stages/20-reproducible-compare.sh`](../scripts/stages/20-reproducible-compare.sh)
- **Comparison Tool**: `temurin-build/tooling/reproducible/repro_compare.sh`
- **Tool Documentation**: `temurin-build/tooling/reproducible/README.md`
- **Jenkins Implementation**: [`ci/jenkins/Jenkinsfile.declarative`](../ci/jenkins/Jenkinsfile.declarative)
- **Local Runner**: [`ci/local/run-pipeline.py`](../ci/local/run-pipeline.py)
- **Migration Plan**: [`MIGRATION_PLAN.md`](./MIGRATION_PLAN.md)
- **Visual Guide**: [`MIGRATION_VISUAL_GUIDE.md`](./MIGRATION_VISUAL_GUIDE.md)

---

## Implementation Status

### Core (CI-Agnostic) ✅ Complete
1. ✅ Stage script `scripts/stages/20-reproducible-compare.sh` implemented
2. ✅ Integration with Adoptium API for downloading production binaries
3. ✅ Integration with `repro_compare.sh` tool
4. ✅ Comprehensive output files and reporting
5. ✅ Platform-specific binary handling (tar.gz, zip)

### Jenkins Pipeline ✅ Complete
1. ✅ `REPRODUCIBLE_COMPARE_BUILD` boolean parameter
2. ✅ `Reproducible Compare Build` stage (after Smoke Tests)
3. ✅ Conditional execution based on parameter
4. ✅ Archive comparison results as artifacts
5. ✅ Set build to UNSTABLE on failure
6. ✅ Display comparison report and diff summary

### Local Pipeline Runner ✅ Complete
1. ✅ `--compare-build` command line option
2. ✅ Stage execution with environment setup
3. ✅ Output file checking and validation
4. ✅ Detailed success/failure reporting
5. ✅ Reproducibility percentage display
6. ✅ Stage restartability support

### Future Enhancements
7. ⏭️ Comparison dashboard for tracking reproducibility metrics
8. ⏭️ Automated alerting for reproducibility failures
9. ⏭️ Historical trend analysis
10. ⏭️ GitLab CI implementation
11. ⏭️ GitHub Actions implementation

---

*Document Version: 3.0*  
*Last Updated: 2026-05-19*  
*Purpose: Document CI-agnostic reproducible build comparison in CI Adoptium Pipelines*