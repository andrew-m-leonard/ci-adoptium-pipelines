# repro_compare.sh Integration Summary

## Overview

This document summarizes the integration of the existing `temurin-build/tooling/reproducible/repro_compare.sh` tool into the pipeline migration validation workflow.

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

### Jenkins Pipeline
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

### Validation Workflow
1. Old pipeline builds JDK → archives artifact
2. New pipeline builds JDK → archives artifact
3. Comparison stage extracts both builds
4. `repro_compare.sh` compares builds
5. Results archived and displayed on dashboard
6. Migration proceeds only if 100% reproducible

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

## References

- **Tool Location**: `temurin-build/tooling/reproducible/repro_compare.sh`
- **Documentation**: `temurin-build/tooling/reproducible/README.md`
- **Migration Plan**: `refactored_pipeline_examples/MIGRATION_PLAN.md` (Appendix A)
- **Visual Guide**: `refactored_pipeline_examples/MIGRATION_VISUAL_GUIDE.md` (Appendix)
- **GitHub Issues**: `refactored_pipeline_examples/GITHUB_EPICS_AND_ISSUES.md` (Issue #1.3)

## Next Steps

1. ✅ Documentation updated with repro_compare.sh integration
2. ⏭️ Create wrapper scripts for automated comparison
3. ⏭️ Integrate into parallel execution Jenkins jobs
4. ⏭️ Set up comparison dashboard
5. ⏭️ Test with pilot platform (Linux x64)

---

*Document Version: 1.0*  
*Created: 2026-05-12*  
*Purpose: Track repro_compare.sh integration into migration workflow*