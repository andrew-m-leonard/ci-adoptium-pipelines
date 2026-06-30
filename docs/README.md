# CI Adoptium Pipelines — Documentation

This directory contains reference documentation for the Adoptium CI pipeline infrastructure.

## Quick Navigation

### I want to...

| Goal | Start here |
|---|---|
| Set up Jenkins from scratch | [JOB_DSL_AUTOMATION.md](./JOB_DSL_AUTOMATION.md) |
| Understand the overall design | [CI_AGNOSTIC_ARCHITECTURE.md](./CI_AGNOSTIC_ARCHITECTURE.md) |
| Configure a build | [CONFIGURATION_GUIDE.md](./CONFIGURATION_GUIDE.md) |
| Restart a failed stage | [RESTARTABILITY_GUIDE.md](./RESTARTABILITY_GUIDE.md) |
| Run the pipeline locally | [PIPELINE_RUNNER_GUIDE.md](./PIPELINE_RUNNER_GUIDE.md) |
| Add or modify a stage script | [STAGE_IO_SPECIFICATION.md](./STAGE_IO_SPECIFICATION.md) + [UNIVERSAL_STAGE_PATTERN.md](./UNIVERSAL_STAGE_PATTERN.md) |
| Understand BUILD_UID tracking | [BUILD_UID_INTEGRATION.md](./BUILD_UID_INTEGRATION.md) |
| Work with reproducible builds | [REPRODUCIBLE_COMPARE.md](./REPRODUCIBLE_COMPARE.md) |
| Convert legacy Groovy configs | [`tools/README.md`](../tools/README.md) |

---

## Architecture & Design

- **[CI_AGNOSTIC_ARCHITECTURE.md](./CI_AGNOSTIC_ARCHITECTURE.md)** — 3-layer design (Configuration / Shell Scripts / CI Orchestration), before/after comparison, portability rationale
- **[CODE_CONFIG_SEPARATION.md](./CODE_CONFIG_SEPARATION.md)** — Why pipeline code and vendor configuration live in separate repositories
- **[PIPELINE_ORCHESTRATION_ARCHITECTURE.md](./PIPELINE_ORCHESTRATION_ARCHITECTURE.md)** — Launch pipeline → build pipeline → stage script flow
- **[STAGE_IO_SPECIFICATION.md](./STAGE_IO_SPECIFICATION.md)** — Per-stage input/output contracts (environment variables, artifacts in/out)
- **[UNIVERSAL_STAGE_PATTERN.md](./UNIVERSAL_STAGE_PATTERN.md)** — How to write a new stage script following the standard pattern

## Configuration

- **[CONFIGURATION_GUIDE.md](./CONFIGURATION_GUIDE.md)** — Full reference for `pipeline-config.json`, `adoptium_pipeline_config.json`, and `jenkins_job_config.json`
- **[JENKINS_ENVIRONMENT_VARIABLES.md](./JENKINS_ENVIRONMENT_VARIABLES.md)** — All `CONFIG_*` and pipeline env vars set by `ConfigHelper` and `BuildUidHelper`
- **[PARAMETER_CONSOLIDATION.md](./PARAMETER_CONSOLIDATION.md)** — How job parameters are defined and flow into the pipeline

## Jenkins Integration

- **[JOB_DSL_AUTOMATION.md](./JOB_DSL_AUTOMATION.md)** — Complete seed job and Job DSL setup guide; how launch jobs and platform build jobs are created
- **[BUILD_UID_INTEGRATION.md](./BUILD_UID_INTEGRATION.md)** — `BUILD_UID` and `GROUP_UID` lifecycle; `BUILD_STAGE_RESULTS` serialisation; prerequisite validation
- **[JENKINS_RESTART_BEHAVIOR.md](./JENKINS_RESTART_BEHAVIOR.md)** — What happens when "Restart from Stage" is used; `BUILD_STAGE_RESULTS` across restarts; Rebuild vs Restart distinction
- **[RESTARTABILITY_GUIDE.md](./RESTARTABILITY_GUIDE.md)** — How every stage is designed to be independently restartable
- **[JENKINS_CLEANUP_REFACTORING.md](./JENKINS_CLEANUP_REFACTORING.md)** — Use of native `cleanWs()` per stage; pre/post cleanup strategy

## Workspace Management

- **[WORKSPACE_ARTIFACTS_ARCHITECTURE.md](./WORKSPACE_ARTIFACTS_ARCHITECTURE.md)** — Jenkins workspace layout; how artifacts flow between stages via `copyArtifacts` + `archiveArtifacts`
- **[WORKSPACE_CLEANUP.md](./WORKSPACE_CLEANUP.md)** — Cleanup strategy, `CLEAN_WORKSPACE_AFTER_STAGE` parameter
- **[WORKSPACE_VALIDATION_PATTERN.md](./WORKSPACE_VALIDATION_PATTERN.md)** — BUILD_UID-based workspace integrity checks
- **[LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md](./LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md)** — Local runner two-directory layout (`stage_workspace/` + `artifacts/`)

## Stage Implementation Details

- **[SHELL_SCRIPTS_SUMMARY.md](./SHELL_SCRIPTS_SUMMARY.md)** — Index of all `scripts/stages/` and `scripts/lib/` files with purpose and key variables
- **[STAGE_INPUT_STRATEGY.md](./STAGE_INPUT_STRATEGY.md)** — How `StageScriptRunner` resolves vendor-override scripts vs default implementations
- **[TARGET_DIR_ARTIFACT_CONSISTENCY.md](./TARGET_DIR_ARTIFACT_CONSISTENCY.md)** — `TARGET_DIR` convention across stages
- **[ARTIFACT_DIRECTORY_PATTERN.md](./ARTIFACT_DIRECTORY_PATTERN.md)** — Artifact naming and directory structure conventions

## Pipeline Features

- **[REPRODUCIBLE_COMPARE.md](./REPRODUCIBLE_COMPARE.md)** — Stage 20 reproducible build comparison: what it does, when it runs, how to interpret results
- **[REPRO_COMPARE_INTEGRATION.md](./REPRO_COMPARE_INTEGRATION.md)** — Integration details: `REPRODUCIBLE_COMPARE_BUILD` parameter, `SCM_REF` requirement, `TARGET_DIR` outputs
- **[REPRODUCIBLE_BUILD_PATH_PADDING.md](./REPRODUCIBLE_BUILD_PATH_PADDING.md)** — Automatic workspace path padding to match upstream build paths (macOS LC_UUID issue)
- **[PATH_PADDING_IMPLEMENTATION_SUMMARY.md](./PATH_PADDING_IMPLEMENTATION_SUMMARY.md)** — Quick reference for path padding: when it triggers, how it works
- **[BUILD_MONITORING_TRACEABILITY.md](./BUILD_MONITORING_TRACEABILITY.md)** — `BUILD_UID` / `GROUP_UID` in build display names and descriptions; linking platform builds to a launch run

## Local Execution

- **[PIPELINE_RUNNER_GUIDE.md](./PIPELINE_RUNNER_GUIDE.md)** — `ci/local/run-pipeline.py` full reference: arguments, stage names, workspace layout, examples
- **[TEST_BUILD_UID_GUIDE.md](./TEST_BUILD_UID_GUIDE.md)** — How to test BUILD_UID and prerequisite validation logic locally and in Jenkins

## Tools

- **[Tools README](../tools/README.md)** — Legacy Groovy-to-JSON configuration conversion tools: `convert-groovy-to-json.py`, `convert-all-legacy-groovy-configs.py`

## Migration & Planning (Historical)

These documents capture decisions and plans made during the original pipeline refactoring. They are retained for historical context.

- [MIGRATION_PLAN.md](./MIGRATION_PLAN.md) — Original phased migration strategy
- [MIGRATION_STRATEGY.md](./MIGRATION_STRATEGY.md) — Design choices and trade-offs
- [MIGRATION_IMPLEMENTATION_GUIDE.md](./MIGRATION_IMPLEMENTATION_GUIDE.md) — Step-by-step migration instructions
- [MIGRATION_VISUAL_GUIDE.md](./MIGRATION_VISUAL_GUIDE.md) — Timeline and flow diagrams
- [GITHUB_EPICS_AND_ISSUES.md](./GITHUB_EPICS_AND_ISSUES.md) — Original GitHub epics and issue tracking
- [PARAMETER_CONSISTENCY_UPDATE.md](./PARAMETER_CONSISTENCY_UPDATE.md) — Parameter naming standardisation history
- [RELEASE_TYPE_PARAMETER_MIGRATION.md](./RELEASE_TYPE_PARAMETER_MIGRATION.md) — `RELEASE_TYPE` parameter history
- [TARGET_DIR_REFACTORING.md](./TARGET_DIR_REFACTORING.md) — `TARGET_DIR` refactoring history

---

See [`../README.md`](../README.md) for the project overview and quick-start.
