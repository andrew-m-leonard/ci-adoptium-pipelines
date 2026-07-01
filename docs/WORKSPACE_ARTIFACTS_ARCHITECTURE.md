# Workspace and Artifacts Architecture

## Overview

The pipeline maintains a clear separation between **ephemeral workspace** (stage working directory, cleaned between stages) and **persistent artifacts** (cross-stage outputs that survive restarts). Both Jenkins and the local runner honour this contract, but the way the underlying directories are structured — and how the standard `WORKSPACE`, `TARGET_DIR`, and `INPUT_ARTIFACTS_DIR` environment variables are set — differs significantly between the two.

Understanding these differences is important when writing stage scripts that must work identically in both environments.

---

## Common Interface Contract

Every stage script receives the same three environment variables regardless of which CI system is running it. Their **meaning** is consistent; only their **physical paths** differ:

| Variable | Meaning | Jenkins value | Local runner value |
|---|---|---|---|
| `WORKSPACE` | Ephemeral scratch directory for this stage | Jenkins workspace root (cleaned by `cleanWs()`) | `<pipeline_workspace>/stage_workspace/` |
| `CONFIG_FILE` | Path to `pipeline-config.json` | `${INPUT_ARTIFACTS_DIR}/pipeline-config.json` | `<pipeline_workspace>/pipeline-config.json` |
| `INPUT_ARTIFACTS_DIR` | Directory containing artifacts from previous stages | `${WORKSPACE}/stage_input_artifacts/` | `<pipeline_workspace>/artifacts/` |
| `TARGET_DIR` | Directory where this stage writes its output artifacts | `${WORKSPACE}/<stage>_output/` (e.g. `build_output`) | `<pipeline_workspace>/artifacts/` |
| `BUILD_NUMBER` | Build identifier | Jenkins build number | `local-<YYYYMMDD-HHMMSS>` |

`validate_standard_environment()` in [`scripts/lib/config-utils.sh`](../scripts/lib/config-utils.sh) checks `WORKSPACE`, `CONFIG_FILE`, and sets the `TARGET_DIR` default to `${WORKSPACE}/workspace/target` if not already set by the orchestration layer.

---

## Jenkins Architecture

### Directory Layout (per stage)

Every stage begins with a fresh workspace — `cleanWs()` wipes the entire `WORKSPACE` root. The orchestration layer then re-populates it:

```
${WORKSPACE}/                         # Jenkins workspace root
├── ci/                               # ← checked out from ci-adoptium-pipelines (checkout scm)
│   └── jenkins/lib/
│       ├── PipelineHelper.groovy
│       └── ...
├── scripts/                          # ← checked out from ci-adoptium-pipelines
│   └── stages/, lib/
├── config-repo/                      # ← sparse-checkout: configurations/, vendor-scripts/,
│   ├── configurations/               #   adoptium_pipeline_config.json
│   ├── vendor-scripts/
│   └── adoptium_pipeline_config.json
└── stage_input_artifacts/            # ← copyArtifacts pulls here: pipeline-config.json + stage inputs
    ├── pipeline-config.json          #   (created by initializeStage when INPUT_ARTIFACTS_DIR is set)
    └── <previous stage outputs>

# Outputs:
${WORKSPACE}/<stage>_output/          # ← stage writes here (e.g. build_output/, smoke_test_output/)
                                      #   then archiveArtifacts uploads to Jenkins artifact store
```

### How `WORKSPACE` is set

Jenkins sets `WORKSPACE` automatically to the job's workspace directory. `PipelineHelper.initializeStage()` calls `cleanWs()` on it at the start of every stage, so each stage starts from a completely empty directory.

### How artifacts flow

```
Initialize stage
  ConfigHelper.generatePipelineConfig()
  → writes pipeline-config.json to WORKSPACE root
  → archiveArtifacts: pipeline-config.json

Build stage (and every subsequent stage)
  initializeStage():
    cleanWs()                          # wipe entire WORKSPACE
    checkout scm                       # restore scripts/
    sparse-checkout config-repo        # restore vendor-scripts/ + config files
    copyArtifacts(filter, target: stage_input_artifacts/)
      # retrieves: pipeline-config.json + prior stage outputs

  env.TARGET_DIR = "${WORKSPACE}/build_output"
  stageRunner.run('02-build', config)
    # stage script reads from INPUT_ARTIFACTS_DIR, writes to TARGET_DIR

  archiveArtifacts("${TARGET_DIR}/**/*")
    # uploads build_output/** to Jenkins artifact store

  finalizeStage()
    # optional cleanWs()
```

### Jenkins-specific notes

- `WORKSPACE` is cleaned and **fully reconstructed** on every stage allocation (including `checkout scm` + config-repo sparse-checkout). Stages may run on different agents.
- `INPUT_ARTIFACTS_DIR` is always a **sub-directory** of `WORKSPACE` (`stage_input_artifacts/`). Stage scripts must not assume it is the same directory as `TARGET_DIR`.
- `TARGET_DIR` is a **dedicated per-stage sub-directory** of `WORKSPACE` (e.g. `build_output`, `smoke_test_output`). After the stage, its contents are archived and the directory can be discarded.
- Artifacts never touch the local filesystem between stages — they travel via `archiveArtifacts` → Jenkins artifact store → `copyArtifacts`.

---

## Local Runner Architecture

### Directory Layout

The local runner uses a **persistent root** (`pipeline_workspace`) that survives across stages, containing two purpose-specific sub-directories:

```
<pipeline_workspace>/                 # Root — persists for the life of the pipeline run
│                                     # (default: ~/openjdk-build)
├── pipeline-config.json              # Generated by Initialize; never moved or cleaned
├── config-repo/                      # git clone of the config repo (Initialize only)
│   ├── configurations/
│   ├── vendor-scripts/
│   └── adoptium_pipeline_config.json
│
├── stage_workspace/                  # Ephemeral — cleaned BEFORE every stage
│   └── (stage working files,         # and optionally AFTER (cleanWorkspaceAfterStage)
│       cloned repos, build scratch)
│
└── artifacts/                        # Persistent — never auto-cleaned
    └── (all stage outputs:           # both INPUT_ARTIFACTS_DIR and TARGET_DIR
        *.tar.gz, *.zip, metadata/,   # point here for all post-Initialize stages
        test results, etc.)
```

### How `WORKSPACE` is set

`run-pipeline.py` sets `env['WORKSPACE'] = str(self.stage_workspace)` for every stage. Stage scripts therefore see `WORKSPACE` pointing to `<pipeline_workspace>/stage_workspace/` — **not** the pipeline root.

`pipeline-config.json` lives at the pipeline root (`<pipeline_workspace>/pipeline-config.json`), not inside `stage_workspace/`. The `CONFIG_FILE` env var is set to this root-level path directly.

### How artifacts flow

```
Initialize stage
  workspace_mgr.cleanup_stage_workspace('pre')   # clean stage_workspace/
  git clone config-repo → pipeline_workspace/config-repo/
  load-json-config.py → writes pipeline_workspace/pipeline-config.json
  workspace_mgr.cleanup_stage_workspace('post')  # optional

Build stage (and every subsequent stage)
  workspace_mgr.cleanup_stage_workspace('pre')   # clean stage_workspace/
  env['WORKSPACE']           = stage_workspace/
  env['CONFIG_FILE']         = pipeline_workspace/pipeline-config.json
  env['INPUT_ARTIFACTS_DIR'] = pipeline_workspace/artifacts/
  env['TARGET_DIR']          = pipeline_workspace/artifacts/

  StageResolver.run('02-build', env)
    # stage script reads from artifacts/ (INPUT_ARTIFACTS_DIR)
    # stage script writes to artifacts/ (TARGET_DIR)
    # temporary work done inside stage_workspace/ (WORKSPACE)

  workspace_mgr.cleanup_stage_workspace('post')  # optional
```

### Local-runner-specific notes

- `WORKSPACE` points to `stage_workspace/` — the ephemeral scratch area. Stage scripts using `${WORKSPACE}` for temporary files get a clean directory on every run.
- `INPUT_ARTIFACTS_DIR` and `TARGET_DIR` **both point to the same directory** (`artifacts/`). Outputs from previous stages are already present when the next stage starts; no copy step is needed.
- `pipeline-config.json` lives at the pipeline root, **outside** `stage_workspace/`. It is never cleaned by the pre/post stage cleanup.
- `config-repo/` is cloned once during Initialize and left at the pipeline root. It is not re-cloned for subsequent stages (unlike Jenkins which sparse-checks out on every stage).
- The `artifacts/` directory is never automatically cleaned. To start fresh, use `--clean-workspace` which removes the entire `pipeline_workspace`.

---

## Side-by-Side Comparison

| Aspect | Jenkins | Local runner |
|---|---|---|
| `WORKSPACE` points to | Jenkins workspace root | `<pipeline_workspace>/stage_workspace/` |
| `CONFIG_FILE` points to | `${INPUT_ARTIFACTS_DIR}/pipeline-config.json` | `<pipeline_workspace>/pipeline-config.json` |
| `INPUT_ARTIFACTS_DIR` | `${WORKSPACE}/stage_input_artifacts/` | `<pipeline_workspace>/artifacts/` |
| `TARGET_DIR` | `${WORKSPACE}/<stage>_output/` (unique per stage) | `<pipeline_workspace>/artifacts/` (shared) |
| `INPUT_ARTIFACTS_DIR` == `TARGET_DIR`? | **No** — separate directories | **Yes** — same `artifacts/` directory |
| Ephemeral area cleaned | `cleanWs()` wipes entire `WORKSPACE` | `stage_workspace/` wiped by `WorkspaceManager` |
| config-repo checkout | Sparse-checkout on **every stage** | `git clone` **once** at Initialize |
| `scripts/` availability | Re-checked-out via `checkout scm` on every stage | Permanent on disk (runner's own directory) |
| Artifact transfer between stages | `archiveArtifacts` → Jenkins store → `copyArtifacts` | Direct file presence in `artifacts/` |
| Pre-stage cleanup scope | Entire `WORKSPACE` (all checked-out files gone) | Only `stage_workspace/` (artifacts/ and config-repo/ untouched) |
| Post-stage cleanup | `cleanWs()` in `finalizeStage()` if `CLEAN_WORKSPACE_AFTER_STAGE=true` | `shutil.rmtree(stage_workspace)` if `cleanWorkspaceAfterStage=true` |

---

## Writing Stage Scripts That Work in Both Environments

Because `INPUT_ARTIFACTS_DIR` and `TARGET_DIR` have different physical paths on each system but are always provided via environment variables, stage scripts should:

1. **Never hardcode paths** — always use `${WORKSPACE}`, `${CONFIG_FILE}`, `${INPUT_ARTIFACTS_DIR}`, `${TARGET_DIR}`
2. **Write all outputs to `${TARGET_DIR}`** — the orchestration layer handles persistence (archive vs direct write)
3. **Read all inputs from `${INPUT_ARTIFACTS_DIR}`** — do not assume they are already in `${WORKSPACE}`
4. **Use `${WORKSPACE}` only for ephemeral scratch** — it will be empty at stage start on both systems
5. **Call `validate_standard_environment`** — it verifies `WORKSPACE` and `CONFIG_FILE` are set and sets the `TARGET_DIR` default

```bash
# Correct — works on both Jenkins and local
source "${SCRIPT_DIR}/../lib/config-utils.sh"
validate_standard_environment

local config_file="${CONFIG_FILE}"
local input_dir="${INPUT_ARTIFACTS_DIR}"
local output_dir="${TARGET_DIR}"
local scratch="${WORKSPACE}/my-stage-scratch"

mkdir -p "${scratch}" "${output_dir}"
# read inputs from ${input_dir}/...
# write outputs to ${output_dir}/...
# temp files under ${scratch}/... (will be cleaned before next stage)
```

---

## Troubleshooting

### Stage script can't find `pipeline-config.json`

Check `CONFIG_FILE` is set correctly. On Jenkins it is set by `PipelineHelper.initializeStage()` to `${INPUT_ARTIFACTS_DIR}/pipeline-config.json`. Locally it is set by `run-pipeline.py` to `<pipeline_workspace>/pipeline-config.json`. Never assume `CONFIG_FILE` is inside `WORKSPACE`.

### Stage outputs not found by next stage (Jenkins)

The stage must `archiveArtifacts` its `TARGET_DIR` output, and the next stage's `artifactFilter` in `initializeStage()` must include the relevant glob. Missing a file from the filter means `copyArtifacts` won't retrieve it.

### Stage outputs not found by next stage (Local)

`TARGET_DIR` and `INPUT_ARTIFACTS_DIR` both point to `artifacts/`. If outputs are missing, the stage script may have written to `WORKSPACE` (ephemeral) instead of `TARGET_DIR` (persistent). Check the script writes to `${TARGET_DIR}`.

### Disk space full (local)

`artifacts/` is never auto-cleaned. Use `--clean-workspace` on the next run, or manually remove `<pipeline_workspace>/artifacts/`. Individual files can be deleted without affecting `stage_workspace/` or `pipeline-config.json`.

### `cleanWs()` or workspace cleanup not happening (Jenkins)

`finalizeStage()` only calls `cleanWs()` when the `CLEAN_WORKSPACE_AFTER_STAGE` job parameter is `true`. The pre-stage `cleanWs()` inside `initializeStage()` always runs unconditionally.

## Related Documentation

- [LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md](./LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md) — local runner workspace validation rules and error messages
- [CI_AGNOSTIC_ARCHITECTURE.md](./CI_AGNOSTIC_ARCHITECTURE.md) — artifact flow diagram (Jenkins `copyArtifacts` ↔ `archiveArtifacts`)
- [UNIVERSAL_STAGE_PATTERN.md](./UNIVERSAL_STAGE_PATTERN.md) — stage script template using these env vars
- [`scripts/lib/config-utils.sh`](../scripts/lib/config-utils.sh) — `validate_standard_environment()` implementation
- [`ci/local/workspace_manager.py`](../ci/local/workspace_manager.py) — `WorkspaceManager` implementation
