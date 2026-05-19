# Reproducible Build Comparison Integration

## Overview

This document describes the integration of reproducible build comparison into the CI Adoptium Pipelines. The system uses the existing `temurin-build/tooling/reproducible/repro_compare.sh` tool to validate that builds are reproducible by comparing locally built JDKs against production Adoptium binaries.

## Two Use Cases

### 1. Local Pipeline Runner (`--compare-build`)

The local pipeline runner (`ci/local/run-pipeline.py`) includes a `--compare-build` option that enables automatic reproducible build comparison as part of the pipeline execution.

**Usage**:
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

**What It Does**:
1. Builds the JDK locally using the specified parameters
2. Downloads the corresponding production Adoptium binary using the SCM reference
3. Runs `repro_compare.sh` to compare the two builds
4. Stores comparison results in the artifacts directory
5. Reports success/failure based on reproducibility

**Requirements**:
- `--scm-ref` must be specified (used to download production binary from Adoptium API)
- `--release` is recommended for comparing against official releases
- Stage script: `scripts/stages/20-reproducible-compare.sh`

**Output Location**:
```
${WORKSPACE}/artifacts/
├── reproducible-compare/
│   ├── reprotest.diff              # List of different files
│   ├── reproducible_evidence.log  # Detailed comparison log
│   └── ReproduciblePercent        # Percentage match metric
```

### 2. Migration Validation Workflow

During migration from the old pipeline to the new pipeline, reproducible comparison validates that both pipelines produce identical builds.

## What Changed

### 1. MIGRATION_PLAN.md Updates

**Section 1.2 - Conversion Tools**
- Changed "Build Comparator" from generic tool to specific reference to `repro_compare.sh`
- Updated to leverage existing proven tooling

**Section 2.1 - EPIC 1 Tasks**
- Changed Issue #1.3 from "Create build comparison framework" to "Integrate existing repro_compare.sh tool"

**New Appendix A - Build Comparison Tool Usage**
- Complete guide to using `repro_compare.sh`
- Platform-specific examples (Linux, macOS, Windows)
- Output file descriptions
- Jenkins integration example
- Success criteria definition
- Troubleshooting guide

### 2. GITHUB_EPICS_AND_ISSUES.md Updates

**Issue #1.3 - Complete Rewrite**
- **Old Title**: "Implement build artifact comparison tool"
- **New Title**: "Integrate repro_compare.sh for build validation"
- **Changed Focus**: From building new tool to integrating existing one
- **Updated Tasks**: 
  - Document usage
  - Create wrapper scripts
  - Integrate into Jenkins
  - Set up dashboards
  - Add alerting
- **Added Tool Capabilities Section**: Lists what repro_compare.sh provides
- **Reduced Effort**: From 1.5 weeks to 1 week (integration vs building)

### 3. MIGRATION_VISUAL_GUIDE.md Updates

**Comparison Workflow Section**
- **Renamed**: "Detailed Comparison Process" → "Detailed Comparison Process Using repro_compare.sh"
- **Updated Steps**: 
  - Step 2: Changed to "EXTRACT BUILDS" (explicit tar extraction)
  - Step 3: New "RUN repro_compare.sh" with detailed tool execution
  - Shows actual command syntax
  - Lists preprocessing steps performed by tool
  - Shows exit code handling

**New Appendix - repro_compare.sh Tool Usage**
- Tool overview and command structure
- Platform-specific examples (Linux, macOS, Windows)
- Output file descriptions with examples
- Jenkins integration code example
- Result interpretation guide
- Common expected differences
- Troubleshooting steps
- Success criteria for migration

## Key Benefits

### 1. Leverage Existing Tooling
- ✅ No need to build new comparison infrastructure
- ✅ Tool already proven in production
- ✅ Handles platform-specific preprocessing
- ✅ Supports all target platforms (Linux, macOS, Windows)

### 2. Reduced Development Time
- ✅ Issue #1.3 effort reduced from 1.5 weeks to 1 week
- ✅ Focus on integration rather than development
- ✅ Less code to maintain

### 3. Better Validation
- ✅ Byte-by-byte comparison after preprocessing
- ✅ Removes expected differences (timestamps, build IDs)
- ✅ Calculates reproducibility percentage
- ✅ Clear success criteria (100% reproducible)

### 4. Comprehensive Documentation
- ✅ Platform-specific usage examples
- ✅ Jenkins integration patterns
- ✅ Troubleshooting guides
- ✅ Success criteria clearly defined

## Tool Capabilities

The `repro_compare.sh` tool provides:

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
   - `ReproduciblePercent` - Percentage match metric
   - Exit code 0 = identical, non-zero = differences

## Success Criteria

For migration approval, builds must achieve:
- ✅ **100% ReproduciblePercent**
- ✅ **Exit code 0** (no differences)
- ✅ **Empty reprotest.diff**
- ✅ **All test results match**
- ✅ **Performance within acceptable range**

## Integration Points

### Local Pipeline Runner

**Stage Implementation**: `scripts/stages/20-reproducible-compare.sh`

```bash
#!/bin/bash
# Stage 20: Reproducible Build Comparison
# Compares locally built JDK against production Adoptium binary

# Environment variables:
# - WORKSPACE: Stage workspace directory
# - TARGET_DIR: Artifacts directory (contains locally built JDK)
# - SCM_REF: Source code reference (e.g., jdk-21.0.2+13)
# - RELEASE: true/false
# - CONFIG_FILE: Pipeline configuration

# 1. Download production binary from Adoptium API using SCM_REF
# 2. Extract both builds
# 3. Run repro_compare.sh
# 4. Store results in TARGET_DIR/reproducible-compare/
```

**Command Line Usage**:
```bash
# Full build with reproducible comparison
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --scm-ref jdk-21.0.2+13 \
    --release \
    --compare-build

# Resume from comparison stage only
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --scm-ref jdk-21.0.2+13 \
    --start-from-stage reproducible-compare
```

### Jenkins Pipeline (Migration Validation)

```groovy
stage('Compare Builds') {
    steps {
        script {
            sh '''
                cd temurin-build/tooling/reproducible
                ./repro_compare.sh \
                  temurin ${OLD_BUILD_DIR}/jdk-${VERSION} \
                  temurin ${NEW_BUILD_DIR}/jdk-${VERSION} \
                  ${PLATFORM}
            '''
            archiveArtifacts artifacts: 'temurin-build/tooling/reproducible/reprotest.diff'
            archiveArtifacts artifacts: 'temurin-build/tooling/reproducible/reproducible_evidence.log'
        }
    }
}
```

### Migration Validation Workflow
1. Old pipeline builds JDK → archives artifact
2. New pipeline builds JDK → archives artifact
3. Comparison stage extracts both builds
4. `repro_compare.sh` compares builds
5. Results archived and displayed on dashboard
6. Migration proceeds only if 100% reproducible

### Local Testing Workflow
1. Local pipeline builds JDK with `--compare-build`
2. Stage downloads production binary from Adoptium API
3. `repro_compare.sh` compares local build vs production
4. Results stored in artifacts directory
5. Pipeline reports success/failure

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

## Command Line Reference

### Local Pipeline Runner

```bash
# Required for --compare-build
--compare-build              # Enable reproducible build comparison
--scm-ref <ref>             # SCM reference (e.g., jdk-21.0.2+13) - REQUIRED with --compare-build

# Example: Compare local build against production
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture aarch64 \
    --scm-ref jdk-21.0.2+13 \
    --release \
    --compare-build
```

### Stage Script

```bash
# Direct execution of comparison stage
export WORKSPACE=/path/to/stage_workspace
export TARGET_DIR=/path/to/artifacts
export CONFIG_FILE=/path/to/pipeline-config.json
export SCM_REF=jdk-21.0.2+13
export RELEASE=true

./scripts/stages/20-reproducible-compare.sh
```

## References

- **Tool Location**: `temurin-build/tooling/reproducible/repro_compare.sh`
- **Documentation**: `temurin-build/tooling/reproducible/README.md`
- **Stage Script**: `scripts/stages/20-reproducible-compare.sh`
- **Local Runner**: `ci/local/run-pipeline.py` (see `--compare-build` option)
- **Migration Plan**: `MIGRATION_PLAN.md` (Appendix A)
- **Visual Guide**: `MIGRATION_VISUAL_GUIDE.md` (Appendix)
- **GitHub Issues**: `GITHUB_EPICS_AND_ISSUES.md` (Issue #1.3)

## Implementation Status

### Local Pipeline Runner (✅ Complete)
1. ✅ `--compare-build` option added to `ci/local/run-pipeline.py`
2. ✅ Stage script `scripts/stages/20-reproducible-compare.sh` implemented
3. ✅ Integration with Adoptium API for downloading production binaries
4. ✅ Automatic comparison as part of pipeline execution
5. ✅ Results stored in artifacts directory
6. ✅ Stage restartability support (`--start-from-stage reproducible-compare`)

### Jenkins Pipeline (✅ Complete)
1. ✅ `REPRODUCIBLE_COMPARE_BUILD` boolean parameter added to `ci/jenkins/Jenkinsfile.declarative`
2. ✅ `Reproducible Compare Build` stage added (after Smoke Tests stage)
3. ✅ Integration with Adoptium API via `20-reproducible-compare.sh` script
4. ✅ Archive comparison results as artifacts (comparison-report.txt, reprotest.diff, etc.)
5. ✅ Conditional execution based on `REPRODUCIBLE_COMPARE_BUILD` parameter
6. ✅ Requires `SCM_REF` parameter to be set
7. ✅ Sets build to UNSTABLE if comparison fails (exit code != 0)

### Future Enhancements
8. ⏭️ Comparison dashboard for tracking reproducibility metrics
9. ⏭️ Automated alerting for reproducibility failures
10. ⏭️ Historical trend analysis

---

*Document Version: 2.0*
*Last Updated: 2026-05-19*
*Purpose: Document reproducible build comparison integration in CI Adoptium Pipelines*