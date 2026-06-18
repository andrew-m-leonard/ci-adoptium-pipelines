# Job DSL Directory Structure

This directory contains Jenkins Job DSL scripts for automated job creation.

## Directory Organization

```
ci/jenkins/job-dsl/
├── seed/                          # Seed job scripts (processed by seed job)
│   ├── load_config.groovy        # Loads jenkins_job_config.json from config repo
│   ├── openjdk_launch_pipeline.groovy  # Creates launch orchestrator jobs
│   └── seed_job.groovy           # Creates/updates the seed job itself
│
└── openjdk_build_pipeline.groovy # Dynamic job creation (called by launch jobs)
```

## Script Categories

### Seed Scripts (`seed/` directory)

These scripts are processed by the Jenkins seed job during its execution. They create the initial set of jobs and views.

**Files:**
- **`load_config.groovy`**: Loads configuration from the config repository and makes it available to other scripts
- **`openjdk_launch_pipeline.groovy`**: Creates launch orchestrator jobs for each active JDK version
- **`seed_job.groovy`**: Self-updating script that recreates the seed job itself

**Seed Job Configuration:**
- DSL Scripts: `ci/jenkins/job-dsl/seed/*.groovy`
- Processes all `.groovy` files in the `seed/` directory
- Requires `CONFIG_REPO_URL` and `CONFIG_REPO_BRANCH` parameters

### Dynamic Job Creation Scripts (root directory)

These scripts are NOT processed by the seed job. They are called dynamically by pipeline jobs using the `jobDsl` step.

**Files:**
- **`openjdk_build_pipeline.groovy`**: Creates platform-specific build jobs
  - Called by: Launch jobs (Jenkinsfile.launch)
  - When: `REGENERATE_JOBS=true` or when platform jobs don't exist
  - Requires: `JDK_VERSION`, `PLATFORM`, `CONFIG_REPO_URL`, `CONFIG_REPO_BRANCH` parameters

## Adding New Scripts

### For Seed Job Processing

If you need to add a new script that should be processed by the seed job:

1. Create the script in the `seed/` directory
2. It will automatically be picked up by the seed job's wildcard pattern
3. Ensure it doesn't require parameters that aren't available during seed job execution

### For Dynamic Job Creation

If you need to add a new script for dynamic job creation:

1. Create the script in the root `job-dsl/` directory (NOT in `seed/`)
2. Call it from a pipeline using the `jobDsl` step
3. Pass required parameters via the binding

Example:
```groovy
jobDsl {
    targets('ci/jenkins/job-dsl/your_script.groovy')
    additionalParameters([
        PARAM1: 'value1',
        PARAM2: 'value2'
    ])
}
```

## Important Notes

⚠️ **Do NOT add scripts to the `seed/` directory unless they should be processed by the seed job!**

Scripts in `seed/` are processed automatically and must not require runtime parameters that aren't available during seed job execution (only `CONFIG_REPO_URL` and `CONFIG_REPO_BRANCH` are available).

✅ **For dynamic job creation, keep scripts in the root `job-dsl/` directory.**

This separation ensures:
- Clear distinction between bootstrap and dynamic job creation
- Prevents accidental processing of dynamic scripts by the seed job
- Makes it obvious which scripts are for which purpose