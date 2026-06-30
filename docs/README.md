# CI Adoptium Pipelines — Documentation

This directory contains reference documentation for the Adoptium CI pipeline infrastructure.

## Quick Navigation

### I want to...

| Goal | Start here |
|---|---|
| Set up Jenkins from scratch | [JOB_DSL_AUTOMATION.md](./JOB_DSL_AUTOMATION.md) |
| Understand the overall design | [CI_AGNOSTIC_ARCHITECTURE.md](./CI_AGNOSTIC_ARCHITECTURE.md) |
| Configure a build | [CONFIGURATION_GUIDE.md](./CONFIGURATION_GUIDE.md) |
| Restart a failed stage | [BUILD_UID_INTEGRATION.md](./BUILD_UID_INTEGRATION.md) |
| Run the pipeline locally | [PIPELINE_RUNNER_GUIDE.md](./PIPELINE_RUNNER_GUIDE.md) |
| Add or modify a stage script | [STAGE_IO_SPECIFICATION.md](./STAGE_IO_SPECIFICATION.md) + [UNIVERSAL_STAGE_PATTERN.md](./UNIVERSAL_STAGE_PATTERN.md) |
| Understand BUILD_UID tracking | [BUILD_UID_INTEGRATION.md](./BUILD_UID_INTEGRATION.md) |
| Work with reproducible builds | [REPRO_COMPARE_INTEGRATION.md](./REPRO_COMPARE_INTEGRATION.md) |
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

## Jenkins Integration

- **[JOB_DSL_AUTOMATION.md](./JOB_DSL_AUTOMATION.md)** — Complete seed job and Job DSL setup guide; how launch jobs and platform build jobs are created
- **[BUILD_UID_INTEGRATION.md](./BUILD_UID_INTEGRATION.md)** — `BUILD_UID` and `GROUP_UID` lifecycle; `BUILD_STAGE_RESULTS` serialisation; prerequisite validation

## Workspace Management

- **[WORKSPACE_ARTIFACTS_ARCHITECTURE.md](./WORKSPACE_ARTIFACTS_ARCHITECTURE.md)** — Jenkins workspace layout; how artifacts flow between stages via `copyArtifacts` + `archiveArtifacts`
- **[LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md](./LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md)** — Local runner two-directory layout (`stage_workspace/` + `artifacts/`)

## Stage Implementation Details

- **[SHELL_SCRIPTS_SUMMARY.md](./SHELL_SCRIPTS_SUMMARY.md)** — Index of all `scripts/stages/` and `scripts/lib/` files with purpose and key variables
- **[ARTIFACT_DIRECTORY_PATTERN.md](./ARTIFACT_DIRECTORY_PATTERN.md)** — Artifact naming and directory structure conventions

## Pipeline Features

- **[REPRO_COMPARE_INTEGRATION.md](./REPRO_COMPARE_INTEGRATION.md)** — Integration details: `REPRODUCIBLE_COMPARE_BUILD` parameter, `SCM_REF` requirement, `TARGET_DIR` outputs

## Local Execution

- **[PIPELINE_RUNNER_GUIDE.md](./PIPELINE_RUNNER_GUIDE.md)** — `ci/local/run-pipeline.py` full reference: arguments, stage names, workspace layout, examples

## Tools

- **[Tools README](../tools/README.md)** — Legacy Groovy-to-JSON configuration conversion tools: `convert-groovy-to-json.py`, `convert-all-legacy-groovy-configs.py`

## Historical

These documents capture decisions, refactoring work, and intermediate designs from the original pipeline implementation. Retained for reference.

- [JENKINS_ENVIRONMENT_VARIABLES.md](./JENKINS_ENVIRONMENT_VARIABLES.md) — `CONFIG_*` and pipeline env vars (superseded by [`ci/jenkins/README.md`](../ci/jenkins/README.md) parameters table)
- [PARAMETER_CONSOLIDATION.md](./PARAMETER_CONSOLIDATION.md) — How job parameters were consolidated
- [JENKINS_RESTART_BEHAVIOR.md](./JENKINS_RESTART_BEHAVIOR.md) — Restart from Stage mechanics (superseded by [`BUILD_UID_INTEGRATION.md`](./BUILD_UID_INTEGRATION.md))
- [RESTARTABILITY_GUIDE.md](./RESTARTABILITY_GUIDE.md) — Stage restartability design (superseded by [`BUILD_UID_INTEGRATION.md`](./BUILD_UID_INTEGRATION.md))
- [JENKINS_CLEANUP_REFACTORING.md](./JENKINS_CLEANUP_REFACTORING.md) — Refactoring to native `cleanWs()`
- [WORKSPACE_CLEANUP.md](./WORKSPACE_CLEANUP.md) — Earlier workspace cleanup strategy
- [WORKSPACE_VALIDATION_PATTERN.md](./WORKSPACE_VALIDATION_PATTERN.md) — BUILD_UID workspace integrity check design
- [STAGE_INPUT_STRATEGY.md](./STAGE_INPUT_STRATEGY.md) — Earlier vendor-override resolution design
- [TARGET_DIR_ARTIFACT_CONSISTENCY.md](./TARGET_DIR_ARTIFACT_CONSISTENCY.md) — `TARGET_DIR` consistency work
- [REPRODUCIBLE_COMPARE.md](./REPRODUCIBLE_COMPARE.md) — Earlier reproducible compare notes
- [REPRODUCIBLE_BUILD_PATH_PADDING.md](./REPRODUCIBLE_BUILD_PATH_PADDING.md) — Path padding for macOS LC_UUID
- [PATH_PADDING_IMPLEMENTATION_SUMMARY.md](./PATH_PADDING_IMPLEMENTATION_SUMMARY.md) — Path padding quick reference
- [BUILD_MONITORING_TRACEABILITY.md](./BUILD_MONITORING_TRACEABILITY.md) — BUILD_UID / GROUP_UID traceability design
- [TEST_BUILD_UID_GUIDE.md](./TEST_BUILD_UID_GUIDE.md) — Testing BUILD_UID logic
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
