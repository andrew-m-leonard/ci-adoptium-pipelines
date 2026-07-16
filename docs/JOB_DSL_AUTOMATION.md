# Job DSL Automation

This document describes how Jenkins pipeline jobs are created and updated using Job DSL scripts that read configuration from a vendor-specific configuration repository.

## Overview

All Jenkins pipeline jobs are defined as code using Job DSL scripts. A seed job reads these scripts and creates/updates all pipeline jobs automatically. This ensures jobs are reproducible and version-controlled.

**Key points**:
- The configuration repository URL and branch are **mandatory parameters** — there are no defaults.
- The seed job is a **manually configured one-time setup** — it does not regenerate itself. This keeps the dual-SCM checkout configuration intact across runs.
- Vendor-specific stage parameter overrides are read from `config-repo/vendor-scripts/` which must be present in the workspace (see Step 3).

## Prerequisites

Your Jenkins instance must have:

1. **Job DSL Plugin** installed and configured
2. **Pipeline Plugin** installed
3. **Git Plugin** installed (with "Check out to a sub-directory" additional behaviour support, or **Multiple SCMs Plugin**)
4. **Script Security** configured to allow Job DSL scripts
5. Access to:
   - `https://github.com/adoptium/ci-adoptium-pipelines.git` (pipeline code)
   - Your vendor-specific configuration repository (e.g., `https://github.com/adoptium/ci-temurin-config.git`)

## Architecture

```
openjdk-build-seed-job (Freestyle, manually configured — does NOT self-regenerate)
  SCM checkout 1: ci-adoptium-pipelines  → workspace root
  SCM checkout 2: vendor config repo     → config-repo/
  → ci/jenkins/job-dsl/seed/seed_job_consolidated.groovy
       reads adoptium_pipeline_config.json  (active JDK versions, repo URLs)
       reads jenkins_job_config.json        (log rotation, default params)
       reads config-repo/vendor-scripts/    (vendor stage param overrides)
       creates Jenkins folders and views
       creates per-JDK launch jobs:
         Build_openjdk_launchers/
           ├── Build_openjdk21_launch   (Jenkinsfile.launch)
           ├── Build_openjdk17_launch
           └── ...

Build_openjdk21_launch (run manually or on schedule)
  → ci/jenkins/Jenkinsfile.launch
       reads configurations/jdk21_pipeline_config.json   (available platforms)
       if REGENERATE_JOBS=true (or build #1): calls jobDsl(openjdk_build_pipeline.groovy)
         → creates/updates platform build jobs:
             Build_openjdk/
               ├── Build_openjdk21_temurin_x86-64_linux
               ├── Build_openjdk21_temurin_aarch64_linux
               └── Build_openjdk21_temurin_aarch64_mac  ...
       triggers all selected platform builds in parallel
```

## Setup Instructions

### Step 1: Create the Seed Freestyle Job

In Jenkins, create a new **Freestyle project** named `openjdk-build-seed-job`.

> **Job naming**: generated platform build jobs follow the AQA-style convention
> `Build_openjdk<version>_<distro>_<arch>_<os>` under a `Build_openjdk/` folder.
> Launch orchestrators live under `Build_openjdk_launchers/`.
> See [BUILD_JOB_NAMING_CONVENTION.md](./BUILD_JOB_NAMING_CONVENTION.md) for details.

### Step 2: Add Job Parameters

Click **This project is parameterized** and add two String Parameters:

| Name | Default | Description |
|---|---|---|
| `CONFIG_REPO_URL` | *(empty)* | URL of the vendor config repository — **REQUIRED** |
| `CONFIG_REPO_BRANCH` | *(empty)* | Branch of the vendor config repository — **REQUIRED** |

Example values: `https://github.com/adoptium/ci-temurin-config.git` / `main`

### Step 3: Configure Source Code Management (two checkouts — both required)

The seed job needs two repositories checked out before the Job DSL step runs:

**Checkout 1 — pipeline repo** (contains the DSL scripts):
- **Repository URL**: `https://github.com/adoptium/ci-adoptium-pipelines.git`
- **Branch**: `*/main`
- *(checked out to workspace root — no sub-directory)*

**Checkout 2 — vendor config repo** (provides vendor stage param overrides):
- **Repository URL**: `${CONFIG_REPO_URL}` ← references the job parameter
- **Branch**: `${CONFIG_REPO_BRANCH}` ← references the job parameter
- **Check out to sub-directory**: `config-repo`
- Add credentials if the config repo is private

> **The seed job will fail** with a clear error if `config-repo/vendor-scripts/` is not
> present when the Job DSL step runs. This is intentional — vendor param overrides are
> a required part of job generation.

Use one of the following methods to configure the dual checkout:

**Option A — Git plugin "Additional Behaviours"** *(recommended if available)*:

In the **Source Code Management** section, configure the pipeline repo as usual,
then add a second repository entry and apply the
**"Check out to a sub-directory"** additional behaviour set to `config-repo`.

**Option B — Multiple SCMs plugin**:

Change the SCM type to **Multiple SCMs**, add both Git entries as separate
SCM blocks, applying **"Check out to a sub-directory: config-repo"** to the
second entry.

### Step 4: Add Build Step

Add a **Process Job DSLs** build step:

| Field | Value |
|---|---|
| DSL Scripts | `ci/jenkins/job-dsl/seed/seed_job_consolidated.groovy` |
| Action for removed jobs | Delete |
| Action for removed views | Delete |
| Additional Classpath | `ci/jenkins/job-dsl` |

### Step 5: Save and Run

1. Save the job
2. Click **Build with Parameters**
3. Provide `CONFIG_REPO_URL` and `CONFIG_REPO_BRANCH`
4. Click **Build**

The job will:
- Read `adoptium_pipeline_config.json` and `jenkins_job_config.json` from the config repo
- Read vendor stage param overrides from `config-repo/vendor-scripts/`
- Create the `Build_openjdk_launchers/` and `Build_openjdk/` folders
- Create one launch job per enabled JDK version under `Build_openjdk_launchers/`
- Create `Build_openjdk_launchers` and `Build_openjdk` Jenkins views

### Step 6: Create Platform Build Jobs

Run a launch job (e.g. `Build_openjdk_launchers/Build_openjdk21_launch`) with `REGENERATE_JOBS=true`.
It will create platform jobs like:
- `Build_openjdk/Build_openjdk21_temurin_x86-64_linux`
- `Build_openjdk/Build_openjdk21_temurin_aarch64_mac`

Platform jobs are also automatically created on the first run of each launch job (build #1).

## Configuration

### Active JDK Versions

Active versions are defined in `adoptium_pipeline_config.json` in the config repo:

```json
{
  "activeJdkVersions": [
    { "version": "jdk21", "enabled": true },
    { "version": "jdk17", "enabled": true },
    { "version": "jdk11", "enabled": false }
  ],
  "configFilePrefix": "configurations/",
  "configFileSuffix": "_pipeline_config.json"
}
```

The seed reads enabled entries and creates one launch job per version. Config file path per version: `configFilePrefix + version + configFileSuffix`.

### Stage Parameters

Stage parameters are collated at seed-job time from two sources:

1. **Default params** — `scripts/stages/*.params.json` in this pipeline repo (workspace)
2. **Vendor overrides** — `config-repo/vendor-scripts/*.params.json` from the checked-out config repo

Vendor files can add new parameters, replace defaults, or suppress defaults via `ignoreDefaultParams`. The collated set is baked into every launch job and platform build job as Jenkins parameters, with a hidden `STAGE_PARAM_NAMES` meta-parameter that `Jenkinsfile.launch` uses to forward all stage params to platform builds automatically.

See `scripts/stages/*.params.json` and the vendor config repo's `vendor-scripts/` directory for the parameter definitions.

### Job Parameters

Default parameter values for platform build jobs come from `jenkins_job_config.json`:

```json
{
  "pipelineTimeoutHours": 8,
  "jobConfiguration": {
    "defaultParameters": {
      "RUN_TESTS": false,
      "ENABLE_INSTALLERS": true,
      "SIGN_ARTIFACTS": false,
      "PUBLISH_ARTIFACTS": false,
      "RUN_REPRODUCIBLE_COMPARE": false,
      "CLEAN_WORKSPACE_AFTER_STAGE": true
    },
    "logRotation": {
      "daysToKeep": 30,
      "numToKeep": 50,
      "artifactDaysToKeep": 7,
      "artifactNumToKeep": 10
    }
  }
}
```

## Maintenance

### Re-running the Seed Job

Run the seed job any time to pick up changes to:
- Active JDK versions (`adoptium_pipeline_config.json`)
- Default parameters or log rotation (`jenkins_job_config.json`)
- Stage parameter definitions (`scripts/stages/*.params.json` or `vendor-scripts/*.params.json`)
- Job DSL script changes (`ci/jenkins/job-dsl/`)

The seed job reads both checkouts fresh on every run — no stale state.

### Adding/Removing JDK Versions

1. Edit `adoptium_pipeline_config.json` in the config repo — set `"enabled": false` or add a new entry
2. Commit and push
3. Re-run the seed job

### Updating Platform Jobs

1. Edit `jenkins_job_config.json` in the config repo or the Job DSL scripts
2. Commit and push
3. Re-run the seed job, then run each launch job with `REGENERATE_JOBS=true`

### Updating Pipeline Code

Changes to `Jenkinsfile` files or stage scripts in `ci-adoptium-pipelines` take effect on the next platform build run — no seed job run needed.

## Troubleshooting

### Seed Job Fails with "CONFIG_REPO_URL not provided"

**Cause**: Seed job run without parameters.

**Fix**: Use **Build with Parameters** and supply both `CONFIG_REPO_URL` and `CONFIG_REPO_BRANCH`.

### Seed Job Fails with "config-repo/vendor-scripts not found"

**Cause**: The vendor config repo was not checked out into `config-repo/` before the Job DSL step ran. This is a mandatory step.

**Fix**: Follow Step 3 above to configure the dual SCM checkout. Verify that after the SCM phase, `config-repo/vendor-scripts/` exists in the workspace. Check that `${CONFIG_REPO_URL}` and `${CONFIG_REPO_BRANCH}` are correctly expanding from the job parameters.

### Seed Job Fails with "Failed to load config"

**Cause**: `adoptium_pipeline_config.json` or `jenkins_job_config.json` cannot be fetched from the config repo.

**Fix**: Verify the files exist at the repo root, the URL format is `https://github.com/<owner>/<repo>.git`, and the branch name is correct.

### Seed Job Fails with "Script Security"

**Cause**: Jenkins Script Security is blocking Job DSL operations.

**Fix**: Go to **Manage Jenkins → In-process Script Approval** and approve the required signatures.

### Launch Job Doesn't Show All Platforms

**Cause**: The `PLATFORMS` choice parameter is populated at seed-job time. Platforms added to the config after the last seed run will not appear.

**Fix**: Re-run the seed job.

### Platform Jobs Not Created / Out of Date

**Cause**: `REGENERATE_JOBS` was not set, or this is not build #1.

**Fix**: Run the launch job with `REGENERATE_JOBS=true`.

## Related Documentation

- [BUILD_JOB_NAMING_CONVENTION.md](./BUILD_JOB_NAMING_CONVENTION.md) — Job naming schema and folder layout
- [BUILD_UID Integration](BUILD_UID_INTEGRATION.md) — Pipeline restart safety
- [ci/jenkins/README.md](../ci/jenkins/README.md) — Jenkins integration overview
- [CODE_CONFIG_SEPARATION.md](./CODE_CONFIG_SEPARATION.md) — Config repo JSON reference
