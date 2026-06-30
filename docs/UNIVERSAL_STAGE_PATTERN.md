# Universal Stage Pattern

Every stage script in `scripts/stages/` follows this pattern. Adhering to it ensures the script works identically on Jenkins and locally.

## Shell Script Template

```bash
#!/bin/bash
# <NN>-<stage-name>.sh — <one-line description>
#
# Required Environment Variables:
#   WORKSPACE           - Stage working directory
#   CONFIG_FILE         - Path to pipeline-config.json
#   TARGET_DIR          - Where to write output artifacts
#   INPUT_ARTIFACTS_DIR - Where to read input artifacts from (if needed)
#   BUILD_NUMBER        - Build identifier (optional, defaults to 'local')
#
# Outputs:
#   ${TARGET_DIR}/**/*  - Stage output artifacts

set -euo pipefail

# Source shared utilities (paths relative to this script's location)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../lib/logging-utils.sh"
source "${SCRIPT_DIR}/../lib/config-utils.sh"
source "${SCRIPT_DIR}/../lib/artifact-utils.sh"

STAGE_NAME="<stage-name>"
BUILD_NUMBER="${BUILD_NUMBER:-local}"

main() {
    log_section "${STAGE_NAME} Stage - Start"

    # 1. Validate environment
    validate_standard_environment

    # 2. Read pipeline configuration
    local config
    config="$(load_config "${CONFIG_FILE}")"
    local java_to_build variant target_os architecture
    java_to_build="$(get_config_value "${config}" ".buildConfig.JAVA_TO_BUILD")"
    variant="$(get_config_value       "${config}" ".buildConfig.VARIANT")"
    target_os="$(get_config_value     "${config}" ".buildConfig.TARGET_OS")"
    architecture="$(get_config_value  "${config}" ".buildConfig.ARCHITECTURE")"

    log_info "Building: ${java_to_build} ${variant} ${target_os}-${architecture}"

    # 3. Prepare output directory
    prepare_output_dir "${TARGET_DIR}"

    # 4. Stage-specific logic
    do_work

    # 5. Create stage metadata
    create_stage_metadata "${STAGE_NAME}" "SUCCESS" "${TARGET_DIR}"

    log_section "${STAGE_NAME} Stage - Complete"
}

do_work() {
    # Read inputs from ${INPUT_ARTIFACTS_DIR}
    # Write outputs to ${TARGET_DIR}
    :
}

main "$@"
```

## Key Rules

| Rule | Reason |
|---|---|
| `set -euo pipefail` at the top | Any unhandled error exits immediately |
| Source all three lib files | Ensures consistent logging and config access |
| Call `validate_standard_environment` first | Fails fast if required vars are missing |
| Read from `${INPUT_ARTIFACTS_DIR}` | Standard input location set by the pipeline |
| Write to `${TARGET_DIR}` | Standard output location archived by Jenkins |
| Create stage metadata at end | `create_stage_metadata` writes `stage-metadata.json` |
| Exit 0 on success, non-zero on failure | The Jenkinsfile checks `stageRunner.run()` return value |

## Variable Conventions

```bash
# Input artifacts from previous stages
INPUT_ARTIFACTS_DIR="${INPUT_ARTIFACTS_DIR:?INPUT_ARTIFACTS_DIR must be set}"
CONFIG_FILE="${INPUT_ARTIFACTS_DIR}/pipeline-config.json"

# Output artifacts from this stage
TARGET_DIR="${TARGET_DIR:?TARGET_DIR must be set}"
mkdir -p "${TARGET_DIR}"
```

## Vendor Override

The `StageScriptRunner` checks `config-repo/vendor-scripts/<stem>.sh` before `scripts/stages/<stem>.sh`. A vendor script placed in the config repo replaces the default implementation entirely — it should follow the same interface contract so the surrounding Jenkins/local infrastructure continues to work.

## Adding the Stage to Jenkins

1. Add the stage entry to `Jenkinsfile.declarative` following the existing pattern:

```groovy
stage('My Stage') {
    agent { label getNodeLabel() }
    when {
        expression { env.CONFIG_SOME_CONDITION == 'true' }
    }
    steps {
        script {
            ensureLibsLoaded()
            pipelineHelper.executeStageWithTracking('My Stage') {
                def config = pipelineHelper.initializeStage(
                    'My Stage',
                    ['Build'],                        // prerequisites
                    'pipeline-config.json,**/*.tar.gz', // artifact filter
                    "${WORKSPACE}/stage_input_artifacts" // INPUT_ARTIFACTS_DIR
                )
                env.TARGET_DIR = "${WORKSPACE}/my_stage_output"

                def exitCode = stageRunner.run('NN-my-stage', config)
                if (exitCode != 0) { error("My Stage failed with exit code: ${exitCode}") }

                dir(env.TARGET_DIR) {
                    archiveArtifacts artifacts: '**/*', allowEmptyArchive: true
                }

                pipelineHelper.finalizeStage('My Stage')
            }
        }
    }
}
```

2. Set the prerequisite list to the stages that must have passed before this one runs.
3. Set the `artifactFilter` to exactly the files the stage script needs from previous stages.
4. Set `TARGET_DIR` to a unique directory name (avoids cross-stage artifact collisions).

## Related Documentation

- [`STAGE_IO_SPECIFICATION.md`](./STAGE_IO_SPECIFICATION.md) — per-stage input/output contracts
- [`ARTIFACT_DIRECTORY_PATTERN.md`](./ARTIFACT_DIRECTORY_PATTERN.md) — INPUT_ARTIFACTS_DIR vs TARGET_DIR
- [`ci/jenkins/lib/StageScriptRunner.groovy`](../ci/jenkins/lib/StageScriptRunner.groovy) — vendor override resolution
