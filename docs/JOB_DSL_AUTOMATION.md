# Job DSL Automation

This document describes how to automate Jenkins job creation using Job DSL scripts that read configuration from a vendor-specific configuration repository.

## Overview

All Jenkins pipeline jobs are defined as code using Job DSL scripts. A seed job reads these scripts and creates/updates all pipeline jobs automatically. This ensures jobs are reproducible and version-controlled.

**Key Feature**: The configuration repository URL and branch are **mandatory parameters** - there are no defaults. This ensures each vendor explicitly specifies their configuration source.

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
┌─────────────────────────────────────────────────────────────┐
│ Jenkins Instance (managed by infrastructure team)           │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Seed Job (Freestyle)                                   │ │
│  │ - Parameters: CONFIG_REPO_URL, CONFIG_REPO_BRANCH     │ │
│  │ - Checks out ci-adoptium-pipelines                     │ │
│  │ - Runs Job DSL scripts from job-dsl/ directory        │ │
│  │ - Creates/updates all pipeline jobs                    │ │
│  └────────────────────────────────────────────────────────┘ │
│                           │                                  │
│                           ↓                                  │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Job DSL Script (openjdk-build-pipeline.groovy)        │ │
│  │ - Uses CONFIG_REPO_URL and CONFIG_REPO_BRANCH params  │ │
│  │ - Fetches jenkins_job_config.json from config repo    │ │
│  │ - Reads active JDK versions (8,11,17,21,25,26,27)     │ │
│  │ - Creates one pipeline job per active version          │ │
│  └────────────────────────────────────────────────────────┘ │
│                           │                                  │
│                           ↓                                  │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Generated Pipeline Jobs                                │ │
│  │ - openjdk-builds/jdk8-build-pipeline (LTS)            │ │
│  │ - openjdk-builds/jdk11-build-pipeline (LTS)           │ │
│  │ - openjdk-builds/jdk17-build-pipeline (LTS)           │ │
│  │ - openjdk-builds/jdk21-build-pipeline (LTS)           │ │
│  │ - openjdk-builds/jdk25-build-pipeline (LTS)           │ │
│  │ - openjdk-builds/jdk26-build-pipeline                 │ │
│  │ - openjdk-builds/jdk27-build-pipeline (LTS)           │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## Setup Instructions

### Step 1: Create Seed Job

1. In Jenkins, create a new **Freestyle project** named `seed-job`

2. **Add Parameters** (This is required):
   - Click "This project is parameterized"
   - Add **String Parameter**:
     - **Name**: `CONFIG_REPO_URL`
     - **Default Value**: Leave empty (no default)
     - **Description**: `URL of the configuration repository containing jenkins_job_config.json (REQUIRED)`
   - Add **String Parameter**:
     - **Name**: `CONFIG_REPO_BRANCH`
     - **Default Value**: Leave empty (no default)
     - **Description**: `Branch of the configuration repository (REQUIRED)`

3. Configure Source Code Management:
   - **SCM**: Git
   - **Repository URL**: `https://github.com/adoptium/ci-adoptium-pipelines.git`
   - **Branch**: `*/main`

4. Add Build Step: **Process Job DSLs**
   - **Look on Filesystem**: Unchecked
   - **DSL Scripts**: `ci/jenkins/job-dsl/*.groovy`
   - **Action for removed jobs**: Delete
   - **Action for removed views**: Delete

5. (Optional) Add Build Trigger: **Poll SCM**
   - **Schedule**: `H * * * *` (hourly)

6. Save the job

### Step 2: Run Seed Job

1. Click "Build with Parameters" on the seed job
2. **Provide required parameters**:
   - **CONFIG_REPO_URL**: `https://github.com/adoptium/ci-temurin-config.git` (or your vendor's config repo)
   - **CONFIG_REPO_BRANCH**: `main` (or your vendor's branch)
3. Click "Build"
4. The job will:
   - Fetch `jenkins_job_config.json` from your configuration repository
   - Create jobs for all active JDK versions
   - Create the `openjdk-builds` folder

### Step 3: Verify

Check that the following jobs were created:
- `openjdk-builds/jdk8-build-pipeline`
- `openjdk-builds/jdk11-build-pipeline`
- `openjdk-builds/jdk17-build-pipeline`
- `openjdk-builds/jdk21-build-pipeline`
- `openjdk-builds/jdk25-build-pipeline`
- `openjdk-builds/jdk26-build-pipeline`
- `openjdk-builds/jdk27-build-pipeline`

## Configuration

### Active JDK Versions

Active versions are defined in your configuration repository's `jenkins_job_config.json` file.

Example from Adoptium's configuration ([`ci-temurin-config/jenkins_job_config.json`](https://github.com/adoptium/ci-temurin-config/blob/main/jenkins_job_config.json)):

```json
{
  "activeJdkVersions": [
    {
      "version": "8",
      "fullVersion": "jdk8u",
      "enabled": true,
      "lts": true,
      "configFile": "configurations/jdk8u_pipeline_config.json"
    },
    ...
  ]
}
```

### Adding/Removing Versions

1. Edit `jenkins_job_config.json` in ci-temurin-config repository
2. Set `"enabled": false` to disable a version
3. Add new entry to enable a new version
4. Commit and push changes
5. Run the seed job (or wait for automatic poll)

### Job Parameters

Default parameters are also defined in `jenkins_job_config.json`:

```json
{
  "jobConfiguration": {
    "defaultParameters": {
      "CONFIG_REPO_URL": "https://github.com/adoptium/ci-temurin-config.git",
      "CONFIG_REPO_BRANCH": "main",
      "BUILD_VARIANT": "temurin",
      "CLEAN_WORKSPACE_AFTER_STAGE": true,
      "RUN_TESTS": true,
      "SIGN_ARTIFACTS": true,
      "PUBLISH_ARTIFACTS": false,
      "RUN_REPRODUCIBLE_COMPARE": false
    },
    "platformChoices": [
      "linux-x64",
      "linux-aarch64",
      ...
    ]
  }
}
```

## Job DSL Scripts

### Location

Job DSL scripts are in [`ci/jenkins/job-dsl/`](../ci/jenkins/job-dsl/):

- **`seed-job.groovy`**: Creates the seed job itself (self-updating)
- **`openjdk-build-pipeline.groovy`**: Creates all pipeline jobs

### How It Works

The `openjdk-build-pipeline.groovy` script:

1. Fetches `jenkins_job_config.json` from ci-temurin-config
2. Parses the JSON to get active JDK versions
3. For each enabled version:
   - Creates a pipeline job in the `openjdk-builds` folder
   - Configures parameters from the JSON
   - Points to `ci/jenkins/Jenkinsfile.declarative`
   - Sets up build discarder (log rotation)
   - Adds LTS badge if applicable

### Error Handling

If `jenkins_job_config.json` cannot be fetched, the seed job will **fail** with a clear error message:

```
ERROR: Failed to load Jenkins job configuration from ci-temurin-config repository!

Configuration URL: https://raw.githubusercontent.com/adoptium/ci-temurin-config/main/jenkins_job_config.json
Error: <error details>

The jenkins_job_config.json file must exist in the ci-temurin-config repository.
```

This ensures jobs are never created with incorrect or missing configuration.

## Maintenance

### Updating Job Configuration

To change job parameters, platforms, or log rotation:

1. Edit `jenkins_job_config.json` in ci-temurin-config
2. Commit and push
3. Run seed job
4. All jobs will be updated with new configuration

### Updating Pipeline Code

To change the pipeline itself:

1. Edit `ci/jenkins/Jenkinsfile.declarative` in ci-adoptium-pipelines
2. Commit and push
3. No seed job run needed - jobs will use new code on next build

### Updating Job DSL Scripts

To change how jobs are created:

1. Edit scripts in `ci/jenkins/job-dsl/` in ci-adoptium-pipelines
2. Commit and push
3. Run seed job
4. Jobs will be recreated with new DSL logic

## Troubleshooting

### Seed Job Fails with "Configuration Not Found"

**Cause**: `jenkins_job_config.json` doesn't exist in ci-temurin-config

**Solution**: Ensure the file exists at the repository root

### Seed Job Fails with "Script Security"

**Cause**: Jenkins Script Security blocking Job DSL operations

**Solution**: In Jenkins, go to Manage Jenkins → In-process Script Approval and approve the required signatures

### Jobs Not Created

**Cause**: Version has `"enabled": false` in configuration

**Solution**: Set `"enabled": true` in `jenkins_job_config.json`

### Jobs Not Updated After Config Change

**Cause**: Seed job hasn't run since config change

**Solution**: Manually run the seed job or wait for automatic poll

## Benefits

✅ **Version Controlled**: All job definitions in Git
✅ **Reproducible**: Recreate all jobs by running seed job
✅ **Centralized Config**: Single source of truth in ci-temurin-config
✅ **Automatic Updates**: Seed job polls for changes
✅ **No Manual Setup**: No clicking through Jenkins UI
✅ **Vendor Independent**: Each vendor maintains their own config repo

## Related Documentation

- [BUILD_UID Integration](BUILD_UID_INTEGRATION.md) - Pipeline restart safety
- [Jenkins Restart Behavior](JENKINS_RESTART_BEHAVIOR.md) - How restarts work
- [Migration Guide](MIGRATION_IMPLEMENTATION_GUIDE.md) - Migrating from old pipeline