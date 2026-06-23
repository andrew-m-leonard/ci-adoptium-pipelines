# TARGET_DIR and archiveArtifacts Consistency Guide

## Overview

This document describes the consistent usage of `TARGET_DIR` environment variable and corresponding `archiveArtifacts` patterns across all pipeline stages in the declarative pipeline.

## Design Principle

**Every stage that produces artifacts MUST:**
1. Set `TARGET_DIR` environment variable to specify where outputs are written
2. Use `archiveArtifacts` with a pattern that matches the `TARGET_DIR` location
3. Ensure the artifact pattern is derived from or consistent with `TARGET_DIR`

## Stage-by-Stage Breakdown

### 1. Initialize Stage

**Purpose:** Load configuration and set up pipeline metadata

**TARGET_DIR:** Not applicable (no build artifacts)

**archiveArtifacts:**
```groovy
archiveArtifacts artifacts: 'pipeline-config.json',
               fingerprint: true,
               allowEmptyArchive: false
```

**Notes:** Archives only the configuration file, not build outputs.

---

### 2. Build Stage

**Purpose:** Compile JDK and organize build artifacts

**TARGET_DIR:**
```groovy
env.TARGET_DIR = "${WORKSPACE}/workspace/target"
```

**archiveArtifacts:**
```groovy
archiveArtifacts artifacts: "${env.TARGET_DIR.replace(env.WORKSPACE + '/', '')}/**/*",
               fingerprint: true,
               allowEmptyArchive: false
```

**Consistency:** ✅ **CONSISTENT**
- The `archiveArtifacts` pattern is dynamically derived from `TARGET_DIR`
- Strips workspace prefix to get relative path: `workspace/target/**/*`
- All build outputs (tar.gz, zip, JSON, SBOM files) are in this directory

**Script Reference:** [`scripts/stages/02-build.sh`](../scripts/stages/02-build.sh)
- Line 534: Copies artifacts to `${TARGET_DIR}` using `organize_build_outputs()`

---

### 3. Internal Sign Stage

**Purpose:** Code sign binaries (Mac/Windows only)

**TARGET_DIR:** Not set (uses downstream job)

**archiveArtifacts:** None (handled by downstream job)

**Notes:** This stage triggers a downstream signing job and doesn't produce artifacts directly.

---

### 4. Verify Signing Stage

**Purpose:** Verify code signatures

**TARGET_DIR:** Not set (verification only)

**archiveArtifacts:** None

**Notes:** Verification stage doesn't produce artifacts, only validates existing signatures.

---

### 5. Validate SBOM Stage

**Purpose:** Validate Software Bill of Materials files

**TARGET_DIR:**
```groovy
env.TARGET_DIR = "${WORKSPACE}"
```

**archiveArtifacts:** None

**Consistency:** ✅ **CONSISTENT**
- This stage validates SBOM files but doesn't produce new artifacts
- `TARGET_DIR` points to workspace root where SBOM files were copied from Build stage
- No archiving needed as SBOM files were already archived in Build stage

**Script Reference:** [`scripts/stages/12-validate-sbom.sh`](../scripts/stages/12-validate-sbom.sh)
- Line 99: Validates SBOM files in `${TARGET_DIR}` (workspace root after copyArtifacts flattening)

---

### 6. Smoke Tests Stage

**Purpose:** Run basic functionality tests on built JDK

**TARGET_DIR:**
```groovy
env.TARGET_DIR = "${WORKSPACE}/workspace/target"
```

**archiveArtifacts:** None

**Consistency:** ✅ **CONSISTENT**
- Stage reads artifacts from `TARGET_DIR` but doesn't produce new artifacts
- Test results are logged but not archived separately
- Uses same `TARGET_DIR` as Build stage for consistency

**Script Reference:** [`scripts/stages/13-smoke-tests.sh`](../scripts/stages/13-smoke-tests.sh)
- Extracts and tests JDK from `${TARGET_DIR}`

---

### 7. Reproducible Compare Build Stage

**Purpose:** Compare local build against production binaries

**TARGET_DIR:**
```groovy
env.TARGET_DIR = "${WORKSPACE}/workspace/target"
```

**archiveArtifacts:**
```groovy
// Archive comparison results (output directory is fixed by repro_compare.sh)
// Note: This stage outputs to ${WORKSPACE}/reproducible-compare/ regardless of TARGET_DIR
archiveArtifacts artifacts: 'reproducible-compare/comparison-report.txt,reproducible-compare/reprotest.diff,reproducible-compare/reproducible_evidence.log,reproducible-compare/ReproduciblePercent',
               fingerprint: true,
               allowEmptyArchive: true
```

**Consistency:** ⚠️ **SPECIAL CASE**
- `TARGET_DIR` points to build artifacts (input)
- Output directory is hardcoded by `repro_compare.sh` to `${WORKSPACE}/reproducible-compare/`
- This is intentional: comparison results are separate from build artifacts
- The script creates its own output directory structure

**Script Reference:** [`scripts/stages/20-reproducible-compare.sh`](../scripts/stages/20-reproducible-compare.sh)
- Line 54: Creates `COMPARE_WORKSPACE="${WORKSPACE}/reproducible-compare"`
- Comparison results are written to this fixed location

---

### 8. AQA Tests Stage

**Purpose:** Run Adoptium Quality Assurance test suite

**TARGET_DIR:** Not set (uses downstream job)

**archiveArtifacts:** None (handled by downstream job)

**Notes:** Triggers downstream AQA test job which handles its own artifact management.

---

## Consistency Rules

### Rule 1: TARGET_DIR Must Match Script Output Location

Every stage script that writes artifacts MUST:
- Accept `TARGET_DIR` environment variable
- Write all outputs to `${TARGET_DIR}` or a documented subdirectory
- Document any exceptions (like reproducible-compare)

### Rule 2: archiveArtifacts Must Reference TARGET_DIR

When archiving artifacts, prefer:
```groovy
// GOOD: Dynamically derived from TARGET_DIR
archiveArtifacts artifacts: "${env.TARGET_DIR.replace(env.WORKSPACE + '/', '')}/**/*"

// ACCEPTABLE: Documented exception with clear reason
archiveArtifacts artifacts: 'reproducible-compare/...'  // Fixed by repro_compare.sh
```

Avoid:
```groovy
// BAD: Hardcoded path that doesn't match TARGET_DIR
env.TARGET_DIR = "${WORKSPACE}/workspace/target"
archiveArtifacts artifacts: 'build/output/**/*'  // MISMATCH!
```

### Rule 3: Document Exceptions

If a stage must use a different output directory than `TARGET_DIR`:
1. Add a comment explaining why
2. Document the fixed output location
3. Ensure the script creates the directory structure

Example:
```groovy
// Archive comparison results (output directory is fixed by repro_compare.sh)
// Note: This stage outputs to ${WORKSPACE}/reproducible-compare/ regardless of TARGET_DIR
archiveArtifacts artifacts: 'reproducible-compare/...'
```

## Verification Checklist

When adding or modifying a stage:

- [ ] Does the stage produce artifacts?
  - If NO: Skip remaining checks
  - If YES: Continue

- [ ] Is `TARGET_DIR` set in the stage?
  - Should point to where artifacts are written

- [ ] Does the stage script use `TARGET_DIR`?
  - Check script accepts and uses `${TARGET_DIR}`

- [ ] Does `archiveArtifacts` match `TARGET_DIR`?
  - Pattern should reference `TARGET_DIR` or be documented exception

- [ ] Are exceptions documented?
  - Add comment explaining any hardcoded paths

## Common Patterns

### Pattern 1: Standard Build Output
```groovy
env.TARGET_DIR = "${WORKSPACE}/workspace/target"
// ... run build script ...
archiveArtifacts artifacts: "${env.TARGET_DIR.replace(env.WORKSPACE + '/', '')}/**/*"
```

### Pattern 2: Validation/Test Stage (No New Artifacts)
```groovy
env.TARGET_DIR = "${WORKSPACE}/workspace/target"  // Input location
// ... run validation ...
// No archiveArtifacts needed
```

### Pattern 3: Fixed Output Directory (Documented Exception)
```groovy
env.TARGET_DIR = "${WORKSPACE}/workspace/target"  // Input location
// ... run comparison ...
// Archive from fixed location (documented in comment)
archiveArtifacts artifacts: 'reproducible-compare/...'
```

## Benefits of This Approach

1. **Predictability:** Developers know where to find artifacts
2. **Maintainability:** Easy to update output locations
3. **Testability:** Scripts can be tested locally with different TARGET_DIR values
4. **Clarity:** Exceptions are documented and justified
5. **Consistency:** All stages follow the same pattern

## Related Documentation

- [Migration Strategy](MIGRATION_STRATEGY.md) - Overall refactoring approach
- [Stage Scripts](../scripts/stages/) - Individual stage implementations
- [Jenkinsfile.declarative](../ci/jenkins/Jenkinsfile.declarative) - Pipeline definition