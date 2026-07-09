# Job DSL Automation

This document describes how Jenkins pipeline jobs are created and updated using Job DSL scripts that read configuration from a vendor-specific configuration repository.

## Overview

All Jenkins pipeline jobs are defined as code using Job DSL scripts. A seed job reads these scripts and creates/updates all pipeline jobs automatically. This ensures jobs are reproducible and version-controlled.

**Key Feature**: The configuration repository URL and branch are **mandatory parameters** — there are no defaults. This ensures each vendor explicitly specifies their configuration source.

## Prerequisites

Your Jenkins instance must have:

1. **Job DSL Plugin** installed and configured
2. **Pipeline Plugin** installed
3. **Git Plugin** installed
4. **Script Security** configured to allow Job DSL scripts
5. Access to:
   - `https://github.com/adoptium/ci-adoptium-pipelines.git` (pipeline code)
   - Your vendor-specific configuration repository (e.g., `https://github.com/adoptium/ci-temurin-config.git`)

## Architecture

```
openjdk-build-seed-job (Freestyle, self-updating)
  → ci/jenkins/job-dsl/seed/seed_job_consolidated.groovy
       reads adoptium_pipeline_config.json from config repo  (active JDK versions, repo URLs)
       reads jenkins_job_config.json from config repo        (log rotation, default params)
       creates Jenkins views
       creates per-JDK launch jobs:
         Build_openjdk_launchers/
           ├── Build_openjdk21_launch   (Jenkinsfile.launch)
           ├── Build_openjdk17_launch
           └── ...

Build_openjdk21_launch (run manually or on schedule)
  → ci/jenkins/Jenkinsfile.launch
       git clone config-repo
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

### Step 1: Create Seed Job

1. In Jenkins, create a new **Freestyle project** named `openjdk-build-seed-job`

> **Job naming**: generated platform build jobs follow the AQA-style convention `Build_openjdk<version>_<distro>_<arch>_<os>` and are placed under a top-level `Build_openjdk/` folder. Launch orchestrators are placed under `Build_openjdk_launchers/`. See [BUILD_JOB_NAMING_CONVENTION.md](./BUILD_JOB_NAMING_CONVENTION.md) for full details.

2. **Add Parameters** (required):
   - Click "This project is parameterized"
   - Add **String Parameter**:
     - **Name**: `CONFIG_REPO_URL`
     - **Default Value**: Leave empty
     - **Description**: `URL of the configuration repository (REQUIRED)`
   - Add **String Parameter**:
     - **Name**: `CONFIG_REPO_BRANCH`
     - **Default Value**: Leave empty
     - **Description**: `Branch of the configuration repository (REQUIRED)`

3. Configure Source Code Management:
   - **SCM**: Git
   - **Repository URL**: `https://github.com/adoptium/ci-adoptium-pipelines.git`
   - **Branch**: `*/main`

4. Add Build Step: **Process Job DSLs**
   - **DSL Scripts**: `ci/jenkins/job-dsl/seed/seed_job_consolidated.groovy`
   - **Action for removed jobs**: Delete
   - **Action for removed views**: Delete
   - **Additional Classpath**: `ci/jenkins/job-dsl`

5. Save the job

> **Note**: After the first run the seed job manages itself — it recreates `openjdk-build-seed-job` with an SCM poll trigger (`H/15 * * * *`) so it automatically re-runs when the Job DSL scripts change.

### Step 2: Run Seed Job

1. Click "Build with Parameters" on the seed job
2. **Provide required parameters**:
   - **CONFIG_REPO_URL**: `https://github.com/adoptium/ci-temurin-config.git` (or your vendor config repo)
   - **CONFIG_REPO_BRANCH**: `main`
3. Click "Build"
4. The job will:
   - Fetch `adoptium_pipeline_config.json` and `jenkins_job_config.json` from the config repo
   - Create the `Build_openjdk_launchers/` and `Build_openjdk/` folders
   - Create one launch job per active JDK version under `Build_openjdk_launchers/`
   - Create two Jenkins views: **Build_openjdk_launchers** and **Build_openjdk**
   - Recreate itself (`openjdk-build-seed-job`) with the SCM poll trigger

### Step 3: Verify

Check that the following jobs were created (exact versions depend on `adoptium_pipeline_config.json`):
- `Build_openjdk_launchers/Build_openjdk21_launch`
- `Build_openjdk_launchers/Build_openjdk17_launch`
- `Build_openjdk_launchers/Build_openjdk11_launch`

### Step 4: Create Platform Build Jobs

Run a launch job (e.g. `Build_openjdk21_launch`) with `REGENERATE_JOBS=true`. It will create platform build jobs like:
- `Build_openjdk/Build_openjdk21_temurin_x86-64_linux`
- `Build_openjdk/Build_openjdk21_temurin_aarch64_mac`

Platform jobs are also automatically created on the first run of each launch job (build #1).

## Configuration

### Active JDK Versions

Active versions are defined in `adoptium_pipeline_config.json` in the config repo (not `jenkins_job_config.json`):

```json
{
  "activeJdkVersions": [
    { "version": "21u", "enabled": true },
    { "version": "17u", "enabled": true },
    { "version": "11u", "enabled": false }
  ],
  "configFilePrefix": "configurations/",
  "configFileSuffix": "_pipeline_config.json"
}
```

The seed reads the enabled entries and creates one launch job per version. The config file path for each version is constructed as `configFilePrefix + version + configFileSuffix`.

### Adding/Removing Versions

1. Edit `adoptium_pipeline_config.json` in the config repo
2. Set `"enabled": false` to disable a version; add a new entry to enable one
3. Commit and push
4. Run the seed job (or wait for the automatic `H/15 * * * *` poll)

### Job Parameters

Default parameter values for platform build jobs come from `jenkins_job_config.json`:

```json
{
  "pipelineTimeoutHours": 8,
  "jenkinsfilePath": "ci/jenkins/Jenkinsfile.declarative",
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

`jenkinsfilePath` controls which Jenkinsfile the platform build jobs use. `logRotation` is applied to all generated jobs.

## Job DSL Scripts

### Location

Job DSL scripts are in [`ci/jenkins/job-dsl/`](../ci/jenkins/job-dsl/):

- **`seed/seed_job_consolidated.groovy`** — run by the seed job; reads both config files, creates launch jobs, views, and recreates the seed job itself
- **`seed/load_config.groovy`** — standalone config loader (used when scripts are split; the consolidated script includes equivalent logic inline)
- **`openjdk_build_pipeline.groovy`** — called by each launch job via `jobDsl()` to create one platform build job

### How It Works

**`seed_job_consolidated.groovy`**:
1. Validates `CONFIG_REPO_URL` and `CONFIG_REPO_BRANCH` parameters (fails immediately if empty)
2. Fetches `adoptium_pipeline_config.json` via `raw.githubusercontent.com` — provides active JDK versions, pipeline repo URL/branch, default build args
3. Fetches `jenkins_job_config.json` via `raw.githubusercontent.com` — provides log rotation and default parameter values
4. For each enabled JDK version in `activeJdkVersions`: loads the per-version platform config to discover available platforms, then creates `Build_openjdk_launchers/Build_openjdk${version}_launch` with a `PLATFORMS` choice parameter pre-populated from the config
5. Creates the `Build_openjdk_launchers/` and `Build_openjdk/` folders
6. Creates the `Build_openjdk_launchers` and `Build_openjdk` views
7. Recreates `openjdk-build-seed-job` with an SCM poll trigger pointing at `ci-adoptium-pipelines`

**`openjdk_build_pipeline.groovy`** (called by each launch job via `jobDsl()`):
1. Receives `JDK_VERSION`, `PLATFORM`, `CONFIG_REPO_URL`, `CONFIG_REPO_BRANCH` as binding variables
2. Fetches both config files from `raw.githubusercontent.com`
3. Fetches `configurations/jdk${JDK_VERSION}_pipeline_config.json` and extracts `arch`, `os`, and `variant` for the requested platform key
4. Creates `Build_openjdk/Build_openjdk${JDK_VERSION}_${variant}_${arch}_${os}` with:
   - Fixed params: `JDK_VERSION`, `TARGET_OS`, `ARCHITECTURE`
   - Variable params sourced from `jenkins_job_config.json` defaults: `RUN_TESTS`, `ENABLE_INSTALLERS`, `SIGN_ARTIFACTS`, `PUBLISH_ARTIFACTS`, `RUN_REPRODUCIBLE_COMPARE`, `CLEAN_WORKSPACE_AFTER_STAGE`, `ENABLE_TCK`
   - Other params: `VARIANT`, `SCM_REF`, `BUILD_REF`, `AQA_REF`, `RELEASE_TYPE`, `GROUP_UID`, `CONFIG_REPO_URL`, `CONFIG_REPO_BRANCH`
   - `scriptPath` read from `jenkins_job_config.json`'s `jenkinsfilePath` field
   - `disableResume()`, `disableConcurrentBuilds()`, `CopyArtifactPermissionProperty('*')`

### Error Handling

Both config files are required. If either cannot be fetched, the seed job fails with a clear error identifying the URL and error:

```
ERROR: Failed to load adoptium_pipeline_config.json from configuration repository!

Configuration Repository: https://github.com/adoptium/ci-temurin-config.git
Branch: main
Configuration URL: https://raw.githubusercontent.com/adoptium/ci-temurin-config/main/adoptium_pipeline_config.json
Error: <error details>
```

A separate error is thrown if `jenkins_job_config.json` is unreachable.

> **Note**: The raw.githubusercontent.com URL derivation only works for `https://github.com/` URLs. Private repos served from GitHub Enterprise or other hosting require credentials or an alternative fetch approach.

## Maintenance

### Updating Job Configuration

To change default parameters, log rotation, or the Jenkinsfile path:

1. Edit `jenkins_job_config.json` in the config repo
2. Commit and push
3. Run the seed job, then run each launch job with `REGENERATE_JOBS=true`

### Updating Active JDK Versions

To add or remove a version:

1. Edit `adoptium_pipeline_config.json` in the config repo
2. Commit and push
3. Run the seed job — launch jobs will be created/removed accordingly

### Updating Pipeline Code

To change the pipeline itself (`Jenkinsfile.declarative`):

1. Edit in `ci-adoptium-pipelines`
2. Commit and push
3. No seed job run needed — platform build jobs pick up the new code on their next run

### Updating Job DSL Scripts

To change how jobs are created:

1. Edit scripts in `ci/jenkins/job-dsl/` in `ci-adoptium-pipelines`
2. Commit and push
3. The seed job's SCM poll will detect the change and re-run automatically within ~15 minutes (or run it manually)

## Troubleshooting

### Seed Job Fails with "CONFIG_REPO_URL not provided"

**Cause**: Seed job run without parameters.

**Solution**: Use "Build with Parameters" and supply both `CONFIG_REPO_URL` and `CONFIG_REPO_BRANCH`.

### Seed Job Fails with "Failed to load config"

**Cause**: Config file doesn't exist in the config repo, the repo URL is wrong, or the branch doesn't exist.

**Solution**: Verify the file exists at the repo root, check the URL format (`https://github.com/<owner>/<repo>.git`), and confirm the branch name.

### Seed Job Fails with "Script Security"

**Cause**: Jenkins Script Security blocking Job DSL operations.

**Solution**: Go to **Manage Jenkins → In-process Script Approval** and approve the required signatures.

### Launch Job Doesn't Show All Platforms

**Cause**: The `PLATFORMS` choice parameter is populated at seed-job time from the per-version config. If platforms were added to the config after the seed last ran, the choice list will be stale.

**Solution**: Re-run the seed job to regenerate the launch job with an updated platform list.

### Platform Jobs Not Created / Out of Date

**Cause**: `REGENERATE_JOBS` was not set, or this is not build #1.

**Solution**: Run the launch job with `REGENERATE_JOBS=true`.

### Jobs Not Updated After Config Change

**Cause**: Seed job and/or launch job have not re-run since the config change.

**Solution**: Run the seed job (for launch job changes) or the launch job with `REGENERATE_JOBS=true` (for platform job changes).

## Related Documentation

- [BUILD_JOB_NAMING_CONVENTION.md](./BUILD_JOB_NAMING_CONVENTION.md) — Job naming schema and folder layout
- [BUILD_UID Integration](BUILD_UID_INTEGRATION.md) — Pipeline restart safety
- [ci/jenkins/README.md](../ci/jenkins/README.md) — Jenkins integration overview
- [CODE_CONFIG_SEPARATION.md](./CODE_CONFIG_SEPARATION.md) — Config repo JSON reference
