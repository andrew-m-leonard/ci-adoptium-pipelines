# Universal Stage Pattern

Every stage requires four artefacts to be fully functional. This document provides the template for each. For the full schema reference and step-by-step guides, see [`docs/STAGE_DEFINITION_REFERENCE.md`](./STAGE_DEFINITION_REFERENCE.md).

## Four-Artefact Checklist

When adding or modifying a stage, ensure all four artefacts are present and consistent:

- [ ] **Shell script** — `scripts/stages/NN-stem.sh`
- [ ] **Params sidecar** — `scripts/stages/NN-stem.params.json`
- [ ] **Declarative stage block** — `ci/jenkins/Jenkinsfile.declarative`
- [ ] **Local runner entry** — `ci/local/run-pipeline.py`

---

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

## Params Sidecar Template

Every stage that introduces new parameters or has runtime gate conditions needs a `scripts/stages/NN-stem.params.json` sidecar. Minimal example:

```json
{
  "stageId": "NN-stem",
  "stageDisabled": false,
  "stageCondition": [],
  "description": "Parameters consumed by the NN-stem stage.",
  "parameterGroups": [
    {
      "name": "Stage Selections",
      "description": "Boolean flags that control which pipeline stages are enabled.",
      "parameters": [
        {
          "name": "ENABLE_NN_STEM",
          "type": "boolean",
          "default": false,
          "description": "Enable this stage. Disabled by default — enable for builds that require it."
        }
      ]
    }
  ]
}
```

**`stageDisabled` convention:** stages added to the core repo that are not intended for all vendors should ship with `"stageDisabled": true`. Vendors re-enable them via a `vendor-scripts/NN-stem.params.json` override with `"stageDisabled": false`.

**Gate-only file:** if the stage has no new parameters but should only run when certain conditions are met, omit `parameterGroups` and provide only `stageCondition`:

```json
{
  "stageId": "08-code-sign-installer",
  "stageDisabled": false,
  "stageCondition": [
    { "param": "ENABLE_INSTALLERS", "value": true },
    { "param": "SIGN_ARTIFACTS",    "value": true }
  ]
}
```

---

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

Add the stage block to `ci/jenkins/Jenkinsfile.declarative` in numeric order. Use `stageConditionMet()` in the `when{}` block — it reads the `stageCondition` list from the params sidecar at runtime:

```groovy
stage('My Stage') {
    agent {
        label getStageLabel('My Stage')
    }
    when {
        beforeAgent true
        expression { stageConditionMet('NN-my-stage') }
    }
    steps {
        script {
            ensureLibsLoaded()
            nodeAgentHelper.waitForActiveNode(getStageLabel('My Stage'), getActiveNodeTimeout())
            pipelineHelper.executeStageWithTracking('My Stage') {
                def config = pipelineHelper.initializeStage(
                    'My Stage',
                    ['Build'],                           // prerequisite stages
                    'pipeline-config.json,**/*.tar.gz'   // artifact filter
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

- `beforeAgent true` — prevents allocating a node for skipped stages (important for efficiency).
- `stageConditionMet('NN-my-stage')` — evaluates all `stageCondition` entries from the params sidecar. Returns `true` when no conditions are defined (unconditional stage).
- Set the prerequisite list to the stages that must have passed before this one runs.
- Set the artifact filter to exactly the files the stage script needs from previous stages.
- Set `TARGET_DIR` to a unique directory name (avoids cross-stage artifact collisions).

## Adding the Stage to the Local Runner

In `ci/local/run-pipeline.py`:

1. Add the stage name to `PipelineRunner.STAGES` in execution order.
2. Add a guarded call in `run()`:

```python
if 'my-stage' in self.stages_to_run and self._stage_condition_met('NN-my-stage'):
    self._run_stage('My Stage', 'NN-my-stage',
                    'pipeline-config.json,**/*.tar.gz')
```

`_stage_condition_met()` checks `stageDisabled` first, then evaluates all `stageCondition` entries against the current `_stage_param_values` and environment.

## Related Documentation

- [`docs/STAGE_DEFINITION_REFERENCE.md`](./STAGE_DEFINITION_REFERENCE.md) — full schema reference, ownership rules, disable/enable guides
- [`docs/CI_AGNOSTIC_ARCHITECTURE.md`](./CI_AGNOSTIC_ARCHITECTURE.md) — interface contract, per-stage summary, artifact flow
- [`ci/jenkins/lib/StageScriptRunner.groovy`](../ci/jenkins/lib/StageScriptRunner.groovy) — vendor override resolution
- [`scripts/lib/collect-stage-params.py`](../scripts/lib/collect-stage-params.py) — collation logic, `PRIORITY_GROUPS`, `stageCondition` validation
