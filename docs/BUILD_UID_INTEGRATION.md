# BUILD_UID Integration

## Overview

BUILD_UID provides unique build identification and per-stage result tracking that persists across pipeline restarts. It is implemented in [`ci/jenkins/lib/BuildUidHelper.groovy`](../ci/jenkins/lib/BuildUidHelper.groovy) and loaded by [`PipelineHelper.groovy`](../ci/jenkins/lib/PipelineHelper.groovy) at the start of each stage.

## Purpose

1. **Unique build tracking** — each build gets a `BUILD_UID` that is reused on "Restart from Stage"
2. **Stage result tracking** — every stage outcome is recorded so prerequisites can be validated
3. **Prerequisite validation** — stages fail fast if required earlier stages did not pass
4. **GROUP_UID linking** — links all platform builds from the same launch run

## How It Works

`PipelineHelper.initializeStage()` is the entry point called by every stage. It internally calls `BuildUidHelper.initializeBuildContext()` which:

1. Generates `BUILD_UID` as `build-<timestamp>-<uuid8>` — or reuses the existing value if already set (restart case)
2. Resolves `GROUP_UID` — uses the `GROUP_UID` job parameter if supplied, else auto-generates one
3. Loads any existing `BUILD_STAGE_RESULTS` from the env var

After the stage body completes, `PipelineHelper.executeStageWithTracking()` calls `BuildUidHelper.recordStageResult()` to update `BUILD_STAGE_RESULTS`.

### Stage Integration Pattern (actual)

```groovy
stage('Smoke Tests') {
    agent { label 'worker' }
    steps {
        script {
            ensureLibsLoaded()
            pipelineHelper.executeStageWithTracking('Smoke Tests') {
                def config = pipelineHelper.initializeStage(
                    'Smoke Tests',
                    ['Build'],                             // prerequisites
                    'pipeline-config.json,*.tar.gz,*.zip', // artifact filter
                    "${WORKSPACE}/stage_input_artifacts"   // INPUT_ARTIFACTS_DIR
                )
                env.TARGET_DIR = "${WORKSPACE}/smoke_test_output"

                def exitCode = stageRunner.run('13-smoke-tests', config)
                env.SMOKE_TESTS_PASSED = (exitCode == 0).toString()
                if (exitCode != 0) {
                    currentBuild.result = 'UNSTABLE'
                }

                pipelineHelper.finalizeStage('Smoke Tests')
            }
        }
    }
}
```

`executeStageWithTracking` wraps the closure and records the result in `BUILD_STAGE_RESULTS` regardless of outcome (including exceptions).

## Stage Prerequisites

| Stage | Prerequisites |
|---|---|
| Initialize | _(none)_ |
| Build | Initialize |
| Internal Sign | Build |
| Assemble | Build |
| Sign Artifacts | Assemble |
| Build Installers | Build |
| Sign Installers | Build Installers |
| GPG Sign | Sign Artifacts |
| Verify Signing | GPG Sign |
| Validate SBOM | Build |
| Smoke Tests | Build |
| Reproducible Compare Build | Build |
| AQA Tests | Smoke Tests |
| TCK Tests | Smoke Tests |
| Publish Artifacts | Build |

## Environment Variables

### `BUILD_UID`
- Format: `build-<timestamp>-<random>`
- Example: `build-20260617-abc123`
- Persists across pipeline restarts
- Unique identifier for each build execution

### `BUILD_STAGE_RESULTS`
- Format: `Stage1==SUCCESS||Stage2==FAILURE||Stage3==UNSTABLE`
- Serialised by `serializeStageResults()`, parsed by `parseStageResults()`
- Persisted in the Jenkins env var across stage restarts
- Used by `validatePrerequisites()` at the start of every non-Initialize stage

## Usage Examples

### First Run
```
Initialize: BUILD_UID generated → build-20260617-abc123de
             GROUP_UID generated → group-20260617-f1e2d3c4
Build:       validates Initialize == SUCCESS ✅
Smoke Tests: validates Build == SUCCESS ✅
```

### Restart from Smoke Tests
```
Jenkins skips Initialize and Build (already completed).
Smoke Tests:
  - PipelineHelper.initializeStage() runs: cleanWs, checkout scm, config-repo clone
  - BuildUidHelper.initializeBuildContext() reuses BUILD_UID from env
  - validatePrerequisites() reads BUILD_STAGE_RESULTS — Build == SUCCESS ✅
  - stage logic runs
```

### Rebuild (not Restart) — error case
```
Smoke Tests:
  - BUILD_STAGE_RESULTS is empty (Rebuild doesn't carry env vars)
  - validatePrerequisites() detects empty results on non-Initialize stage
  - Fails with clear user error: "Use 'Restart from Stage', not 'Rebuild'"
```

## Benefits

1. **Traceability**: Every build has a unique identifier
2. **Restart Safety**: Can restart from any stage with proper validation and automatic helper loading
3. **Dependency Management**: Stages only run when prerequisites succeed
4. **Audit Trail**: Complete history of stage results
5. **Debugging**: Easy to track which stages succeeded/failed across restarts
6. **Modular Design**: Helper functions in separate library for reusability
7. **No Null Errors**: `ensureBuildUidHelperLoaded()` prevents null reference errors on restart

## Related Documentation

- [`ci/jenkins/lib/BuildUidHelper.groovy`](../ci/jenkins/lib/BuildUidHelper.groovy) — implementation
- [`ci/jenkins/lib/PipelineHelper.groovy`](../ci/jenkins/lib/PipelineHelper.groovy) — loads and calls BuildUidHelper
- [`ci/jenkins/Jenkinsfile.declarative`](../ci/jenkins/Jenkinsfile.declarative) — pipeline that uses these helpers
- [`ci/jenkins/README.md`](../ci/jenkins/README.md) — stage restart behaviour section