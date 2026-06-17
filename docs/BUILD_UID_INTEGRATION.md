# BUILD_UID Integration Documentation

## Overview

This document describes the BUILD_UID tracking system integrated into the Jenkins declarative pipeline. BUILD_UID provides unique build identification and stage result tracking that persists across pipeline restarts.

## Purpose

The BUILD_UID system enables:
1. **Unique Build Tracking**: Each build gets a unique identifier that persists across restarts
2. **Stage Result Tracking**: Records SUCCESS/FAILURE/UNSTABLE/ABORTED for each stage
3. **Prerequisite Validation**: Ensures stages run only after required previous stages complete successfully
4. **Restart Safety**: Maintains build context when restarting from any stage

## Implementation

### Helper Functions

The BUILD_UID helper functions are defined in a separate Groovy library file for better organization and reusability:

**File**: [`ci/jenkins/lib/BuildUidHelper.groovy`](../ci/jenkins/lib/BuildUidHelper.groovy)

#### Restart-Safe Loading

To handle Jenkins "Restart from Stage" functionality, each stage ensures the helper is loaded. Since the helper is a Groovy library file that must be loaded from the repository, the correct order is:

1. Clean workspace (`cleanWs()`)
2. Checkout repository (`checkout scm`)
3. Load helper if needed (`ensureBuildUidHelperLoaded()`)
4. Use helper functions

```groovy
// In Jenkinsfile.declarative
def ensureBuildUidHelperLoaded() {
    if (buildUidHelper == null) {
        echo "Loading BuildUidHelper library..."
        buildUidHelper = load 'ci/jenkins/lib/BuildUidHelper.groovy'
    }
    return buildUidHelper
}
```

Each stage follows this pattern:
```groovy
script {
    echo "=== Stage Name ==="

    // Pre-cleanup: Always clean workspace for restartability
    cleanWs()

    // Checkout ci-adoptium-pipelines repository
    checkout scm

    // Ensure helper is loaded (handles restart from stage)
    ensureBuildUidHelperLoaded()

    // Now safe to use buildUidHelper
    buildUidHelper.initializeBuildContext('Stage Name')
    buildUidHelper.validatePrerequisites('Stage Name', ['Required', 'Stages'])
}
```

This ensures that:
- **First run**: Helper loads in Initialize stage
- **Restart from any stage**: Repository is checked out, then helper loads automatically
- **No errors**: `buildUidHelper` is never null because repository is always available
- **Efficiency**: Only one checkout per stage (not duplicated)

#### `buildUidHelper.parseStageResults(String resultsStr)`
- Parses serialized stage results from environment variable
- Returns a Map of stage names to their results

#### `buildUidHelper.serializeStageResults(Map results)`
- Serializes stage results Map to string format
- Uses `||` as entry separator and `==` as key-value separator

#### `buildUidHelper.recordStageResult(String stageName, String result)`
- Records the result of a stage (SUCCESS/FAILURE/UNSTABLE/ABORTED)
- Updates `BUILD_STAGE_RESULTS` environment variable
- Persists across pipeline restarts

#### `buildUidHelper.validatePrerequisites(String currentStage, List<String> requiredStages)`
- Validates that all required stages completed successfully
- Throws error if any prerequisite failed or is missing
- Allows pipeline restart from any stage with proper validation

#### `buildUidHelper.initializeBuildContext(String stageName)`
- Generates or reuses BUILD_UID
- Loads existing stage results on restart
- Must be called at the start of each stage

### Stage Integration Pattern

Each stage follows this optimized pattern for restart safety:

```groovy
stage('Stage Name') {
    agent { label 'worker' }
    steps {
        script {
            echo "=== Stage Name ==="

            // Pre-cleanup: Always clean workspace for restartability
            cleanWs()

            // Checkout ci-adoptium-pipelines repository
            checkout scm

            // Ensure helper is loaded (handles restart from stage)
            ensureBuildUidHelperLoaded()

            // Initialize BUILD_UID and build context
            buildUidHelper.initializeBuildContext('Stage Name')

            // Validate prerequisites
            buildUidHelper.validatePrerequisites('Stage Name', ['Required', 'Stages'])

            // Stage logic here...

            echo "=== Stage Name Complete ==="
            echo "BUILD_UID: ${env.BUILD_UID}"
        }
    }
    post {
        success {
            script {
                buildUidHelper.recordStageResult('Stage Name', 'SUCCESS')
            }
        }
        unstable {
            script {
                buildUidHelper.recordStageResult('Stage Name', 'UNSTABLE')
            }
        }
        failure {
            script {
                buildUidHelper.recordStageResult('Stage Name', 'FAILURE')
            }
        }
        aborted {
            script {
                buildUidHelper.recordStageResult('Stage Name', 'ABORTED')
            }
        }
    }
}
```

## Integrated Stages

All 14 stages now have BUILD_UID tracking:

| Stage | Prerequisites | Location |
|-------|--------------|----------|
| Initialize | None | Jenkinsfile.declarative |
| Build | Initialize | Jenkinsfile.declarative |
| Internal Sign | Build | Jenkinsfile.declarative |
| Assemble | Build | Jenkinsfile.declarative |
| Sign Artifacts | Assemble | Jenkinsfile.declarative |
| Build Installers | Sign Artifacts | Jenkinsfile.declarative |
| Sign Installers | Build Installers | Jenkinsfile.declarative |
| GPG Sign | Sign Artifacts | Jenkinsfile.declarative |
| Verify Signing | GPG Sign | Jenkinsfile.declarative |
| Validate SBOM | Build | Jenkinsfile.declarative |
| Smoke Tests | Build | Jenkinsfile.declarative |
| Reproducible Compare Build | Build | Jenkinsfile.declarative |
| AQA Tests | Smoke Tests | Jenkinsfile.declarative |
| TCK Tests | Smoke Tests | Jenkinsfile.declarative |

## Environment Variables

### `BUILD_UID`
- Format: `build-<timestamp>-<random>`
- Example: `build-20260617-abc123`
- Persists across pipeline restarts
- Unique identifier for each build execution

### `BUILD_STAGE_RESULTS`
- Format: `Stage1==SUCCESS||Stage2==FAILURE||Stage3==UNSTABLE`
- Tracks completion status of all stages
- Updated after each stage completes
- Used for prerequisite validation on restart

## Usage Examples

### First Run
```
Initialize: BUILD_UID generated → build-20260617-abc123
Build: Validates Initialize SUCCESS
Smoke Tests: Validates Build SUCCESS
```

### Restart from Smoke Tests
```
Initialize: Skipped (already completed)
Build: Skipped (already completed)
Smoke Tests:
  - ensureBuildUidHelperLoaded() loads the helper library
  - Reuses BUILD_UID: build-20260617-abc123
  - Validates Build SUCCESS from BUILD_STAGE_RESULTS
  - Executes stage logic
```

**Key Point**: When restarting from a stage, Jenkins skips all previous stages including Initialize. The `ensureBuildUidHelperLoaded()` function ensures the helper library is loaded even when Initialize is skipped.

### Restart After Failure
```
Build: FAILED
Smoke Tests: Cannot run - prerequisite Build FAILED
User fixes issue and restarts from Build:
Build:
  - Reuses BUILD_UID
  - Re-executes (overwrites previous FAILURE)
  - Records new result
Smoke Tests: Now runs with Build SUCCESS
```

## Benefits

1. **Traceability**: Every build has a unique identifier
2. **Restart Safety**: Can restart from any stage with proper validation and automatic helper loading
3. **Dependency Management**: Stages only run when prerequisites succeed
4. **Audit Trail**: Complete history of stage results
5. **Debugging**: Easy to track which stages succeeded/failed across restarts
6. **Modular Design**: Helper functions in separate library for reusability
7. **No Null Errors**: `ensureBuildUidHelperLoaded()` prevents null reference errors on restart

## Testing

To test BUILD_UID integration:

1. **First Run**: Execute full pipeline and verify BUILD_UID is generated
2. **Restart Test**: Restart from middle stage and verify BUILD_UID is reused
3. **Prerequisite Test**: Restart from stage after failed prerequisite and verify error
4. **Result Tracking**: Check BUILD_STAGE_RESULTS contains all completed stages

## File Structure

```
ci/jenkins/
├── Jenkinsfile.declarative          # Main pipeline file
├── lib/
│   └── BuildUidHelper.groovy        # BUILD_UID helper functions
└── TEST_BUILD_UID.Jenkinsfile       # Original prototype
```

## Related Documentation

- [`Jenkinsfile.declarative`](../ci/jenkins/Jenkinsfile.declarative) - Main pipeline file
- [`BuildUidHelper.groovy`](../ci/jenkins/lib/BuildUidHelper.groovy) - Helper functions library
- [`TEST_BUILD_UID.Jenkinsfile`](../ci/jenkins/TEST_BUILD_UID.Jenkinsfile) - Original prototype
- [`WORKSPACE_CLEANUP_REFACTORING.md`](WORKSPACE_CLEANUP_REFACTORING.md) - Cleanup strategy

## Migration Notes

This BUILD_UID system was integrated on 2026-06-17 as part of the pipeline restartability improvements. All stages now support:
- Unique build tracking
- Stage result persistence
- Prerequisite validation
- Safe restart from any point

The integration maintains backward compatibility while adding robust tracking capabilities.