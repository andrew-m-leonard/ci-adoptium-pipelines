# Code/Config Separation Pattern

## Overview

The Adoptium CI Pipelines architecture maintains a strict separation between **pipeline code** (this repository) and **pipeline configuration** (a separate config repository such as `ci-temurin-config`). The pipeline code never contains build-specific settings; all such data lives in the config repo.

This separation enables:

- **Vendor independence** — different organizations maintain their own config repos while reusing the same pipeline code
- **Privacy** — vendors can keep their configs in private repos without forking the pipeline
- **Governance** — config and code are owned and reviewed separately
- **Testability** — swap configs at build time without touching code

---

## Config Repository Structure

The config repo is the single source of all externalized settings. It must contain:

```
<config-repo>/
├── adoptium_pipeline_config.json          # CI-agnostic defaults and repo pointers
├── jenkins_job_config.json                # Jenkins-specific job settings
├── configurations/
│   ├── jdk8u_pipeline_config.json         # Per-version platform build settings
│   ├── jdk11u_pipeline_config.json
│   ├── jdk17u_pipeline_config.json
│   ├── jdk21u_pipeline_config.json
│   └── ...
└── vendor-scripts/                        # Optional vendor-specific stage overrides
    ├── 02-build.sh                        # Overrides scripts/stages/02-build.sh
    └── ...
```

---

## Config Files in Detail

### 1. `adoptium_pipeline_config.json` — CI-agnostic pipeline defaults

**Location**: root of the config repo  
**Consumed by**: seed job, launch pipeline (`Jenkinsfile.launch`), build pipeline (`ConfigHelper.groovy`), local runner (`run-pipeline.py`)

This is the top-level config file that glues everything together. It tells the system:
- which JDK versions are active
- the default build variant and args
- where to find the per-version config files (prefix/suffix pattern)
- URLs and branch refs for `temurin-build` and `aqa-tests` repositories

**Example structure**:
```json
{
  "defaultVariant": "temurin",
  "defaultBuildArgs": "--create-jre-image --create-sbom",
  "defaultConfigureArgs": "",
  "defaultScmReference": "",
  "configFilePrefix": "configurations/",
  "configFileSuffix": "_pipeline_config.json",
  "activeJdkVersions": [
    { "version": "jdk21u", "enabled": true },
    { "version": "jdk17u", "enabled": true },
    { "version": "jdk11u", "enabled": false }
  ],
  "repository": {
    "url": "https://github.com/adoptium/ci-adoptium-pipelines.git",
    "branch": "main",
    "credentialsId": "",
    "buildRepoUrl": "https://github.com/adoptium/temurin-build.git",
    "buildBranch": "master",
    "aqaRepoUrl": "https://github.com/adoptium/aqa-tests.git",
    "aqaBranch": "master"
  }
}
```

**How it is loaded**:

| Consumer | Method |
|---|---|
| Seed job (`seed_job_consolidated.groovy`) | Fetched directly from `raw.githubusercontent.com` via HTTP during seed job execution |
| Build pipeline (`ConfigHelper.groovy`) | Read from `./config-repo/adoptium_pipeline_config.json` after Git sparse-checkout |
| Local runner (`run-pipeline.py`) | Read from `<workspace>/config-repo/adoptium_pipeline_config.json` after `git clone` |
| CLI tool (`load-adoptium-pipeline-config-json.py`) | Either local path (`--config-repo-dir`) or fetched from remote (`--config-repo-url`) |

---

### 2. `jenkins_job_config.json` — Jenkins-specific job settings

**Location**: root of the config repo  
**Consumed by**: seed job only (`seed_job_consolidated.groovy`)

Contains settings that are Jenkins-specific and only needed at job creation time. The build pipeline itself never reads this file.

**Example structure**:
```json
{
  "pipelineTimeoutHours": 8,
  "jobConfiguration": {
    "defaultParameters": {
      "RUN_TESTS": false,
      "ENABLE_INSTALLERS": true,
      "SIGN_ARTIFACTS": false,
      "PUBLISH_ARTIFACTS": false,
      "RUN_REPRODUCIBLE_COMPARE": false
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

**How it is loaded**: Fetched from `raw.githubusercontent.com` during seed job execution alongside `adoptium_pipeline_config.json`. It is never read at runtime by the build pipeline or the local runner.

---

### 3. `configurations/jdkNN_pipeline_config.json` — Per-version platform build settings

**Location**: `configurations/` directory of the config repo (path determined by `configFilePrefix` / `configFileSuffix` in `adoptium_pipeline_config.json`)  
**Consumed by**: `scripts/lib/load-json-config.py` (invoked by `ConfigHelper.groovy` on Jenkins, or `run-pipeline.py` locally)

One file per JDK version. Describes every supported build platform and its platform-specific settings. The filename pattern is `<configFilePrefix><version><configFileSuffix>`, e.g. `configurations/jdk21u_pipeline_config.json`.

**Example structure**:
```json
{
  "openjdkVersion": "jdk21u",
  "buildConfigurations": {
    "aarch64Mac": {
      "os": "mac",
      "arch": "aarch64",
      "additionalNodeLabels": "xcode15.0.1",
      "configureArgs": "--enable-dtrace",
      "buildArgs": {
        "temurin": "--create-jre-image --create-sbom",
        "hotspot": "--create-jre-image"
      },
      "dockerImage": "",
      "test": {
        "nightly": ["sanity.openjdk"],
        "weekly": ["sanity.openjdk", "sanity.system"],
        "release": ["sanity.openjdk", "sanity.system", "extended.openjdk"]
      }
    },
    "x64Linux": {
      "os": "linux",
      "arch": "x64",
      "additionalNodeLabels": "",
      "dockerImage": "adoptopenjdk/centos7_build_image",
      "configureArgs": "--enable-unlimited-crypto",
      "buildArgs": "--create-jre-image --create-sbom",
      "test": {
        "nightly": ["sanity.openjdk", "sanity.system"],
        "weekly": ["sanity.openjdk", "sanity.system", "extended.system"]
      }
    }
  }
}
```

`buildArgs` and `configureArgs` can be either a plain string (applied to all variants) or a variant-keyed object (`{ "temurin": "...", "hotspot": "...", "default": "..." }`).

**How it is loaded**:

The file is located by [`scripts/lib/load-json-config.py`](../scripts/lib/load-json-config.py) which constructs the path as:

```
<config-dir>/<jdk-version>_pipeline_config.json
```

`--config-dir` defaults to `./configurations` and is set to `./config-repo/configurations` (Jenkins) or resolved from `configFilePrefix` in `adoptium_pipeline_config.json` (local runner).

The script extracts the entry for the requested platform key (e.g. `aarch64Mac`), merges it with runtime parameters, and writes the output to `pipeline-config.json`.

---

### 4. `pipeline-config.json` — Generated runtime config

**Location**: workspace root (Jenkins) or `<workspace>/pipeline-config.json` (local)  
**Generated by**: `scripts/lib/load-json-config.py` during the Initialize stage  
**Consumed by**: every subsequent pipeline stage via the `CONFIG_FILE` environment variable; also read by `PipelineHelper.groovy` and `StageScriptRunner.groovy` to gate stage execution

This file is not stored in any repository — it is generated fresh for each build by combining the per-version JSON config with the runtime parameters supplied to the job.

**Structure**:
```json
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk21u",
    "TARGET_OS": "mac",
    "ARCHITECTURE": "aarch64",
    "VARIANT": "temurin",
    "BUILD_ARGS": "--create-jre-image --create-sbom",
    "CONFIGURE_ARGS": "--enable-dtrace",
    "NODE_LABEL": "build&&mac&&aarch64",
    "DOCKER_IMAGE": "",
    "TEST_LIST": ["sanity.openjdk"]
  },
  "parameters": {
    "enableTests": true,
    "enableInstallers": true,
    "enableSigner": false,
    "cleanWorkspaceAfterStage": true,
    "eaBetaBuild": false,
    "compareBuild": false,
    "release": false
  },
  "refs": {
    "scmRef": "",
    "buildRef": "master",
    "buildRepoUrl": "https://github.com/adoptium/temurin-build.git",
    "aqaRef": "master",
    "aqaRepoUrl": "https://github.com/adoptium/aqa-tests.git"
  }
}
```

On Jenkins it is immediately archived as a build artifact so that subsequent stages (which each start with a clean workspace) can retrieve it via `copyArtifacts`.

---

### 5. `vendor-scripts/` — Optional stage overrides

**Location**: `vendor-scripts/` directory of the config repo  
**Consumed by**: `StageScriptRunner.groovy` (Jenkins) and `StageResolver` (local runner)

Vendors can place scripts here to replace any default stage script in `scripts/stages/`. Resolution order (first match wins):

| Priority | Jenkins (`StageScriptRunner.groovy`) | Local (`StageResolver`) |
|---|---|---|
| 1 | `config-repo/vendor-scripts/<stem>.sh` | `config-repo/vendor-scripts/<stem>.sh` |
| 2 | `config-repo/vendor-scripts/<stem>.groovy` | `config-repo/vendor-scripts/<stem>.py` |
| 3 | `config-repo/vendor-scripts/<stem>.py` | `scripts/stages/<stem>.sh` |
| 4 | `scripts/stages/<stem>.sh` | `scripts/stages/<stem>.py` |
| 5 | `scripts/stages/<stem>.groovy` | *(no-op)* |
| 6 | `scripts/stages/<stem>.py` | — |
| 7 | *(no-op)* | — |

> **Note**: `.groovy` scripts are Jenkins-only; the local runner does not support them.

---

## How Config Flows Through the Pipeline

### Jenkins path

```
Seed Job (seed_job_consolidated.groovy)
  │  reads adoptium_pipeline_config.json    ← HTTP from raw.githubusercontent.com
  │  reads jenkins_job_config.json          ← HTTP from raw.githubusercontent.com
  │
  └─► Creates launch jobs and build pipeline jobs
         (CONFIG_REPO_URL / CONFIG_REPO_BRANCH baked into job parameters)

Launch Job (Jenkinsfile.launch)
  │  git clone config-repo → dir('config-repo')
  │  reads config-repo/configurations/jdkNN_pipeline_config.json
  │                                         ← selects available platforms
  └─► Creates or updates platform build jobs in parallel

Build Pipeline (Jenkinsfile.declarative)
  │
  ├─ Initialize stage (PipelineHelper + ConfigHelper)
  │     git sparse-checkout config-repo (configurations/*, vendor-scripts/*, adoptium_pipeline_config.json)
  │     reads config-repo/adoptium_pipeline_config.json   ← buildRef, aqaRef defaults
  │     runs scripts/lib/load-json-config.py              ← merges params + per-version JSON
  │     writes pipeline-config.json
  │     archives pipeline-config.json as build artifact
  │
  └─ Every subsequent stage (PipelineHelper.initializeStage)
        clean workspace
        git sparse-checkout (for scripts and vendor overrides)
        copyArtifacts pipeline-config.json from Initialize
        sets CONFIG_FILE env var → stage scripts read it via config-utils.sh
```

### Local runner path

```
run-pipeline.py
  │
  ├─ stage_initialize()
  │     git clone --depth 1 config-repo  (or use local path)
  │     reads config-repo/adoptium_pipeline_config.json   ← variant, buildRef, aqaRef defaults
  │     runs scripts/lib/load-json-config.py              ← merges CLI args + per-version JSON
  │     writes <workspace>/pipeline-config.json
  │
  └─ Every subsequent stage
        StageResolver.run(stem, env)
          checks pipeline-config.json parameters to gate stage
          resolves vendor-scripts/ then scripts/stages/ for the script to run
          sets CONFIG_FILE env var pointing to pipeline-config.json
```

---

## Config vs Parameter Priority

Several values can come from either the config repo or from job parameters. The priority is:

| Value | Priority 1 (wins) | Priority 2 (fallback) |
|---|---|---|
| `BUILD_REF` | `BUILD_REF` job param (non-empty) | `repository.buildBranch` in `adoptium_pipeline_config.json` |
| `AQA_REF` | `AQA_REF` job param (non-empty) | `repository.aqaBranch` in `adoptium_pipeline_config.json` |
| `BUILD_REPO_URL` | — | `repository.buildRepoUrl` in `adoptium_pipeline_config.json` |
| `AQA_REPO_URL` | — | `repository.aqaRepoUrl` in `adoptium_pipeline_config.json` |
| `VARIANT` | — | `defaultVariant` in `adoptium_pipeline_config.json` |
| Platform build/configure args | variant key in per-version JSON | `default` key in per-version JSON |

If a required field cannot be resolved from either source, the pipeline fails early with a clear error message.

---

## Use Cases

### Adoptium official builds

- Config repo: `github.com/adoptium/ci-temurin-config` (public)
- Pipeline code: `github.com/adoptium/ci-adoptium-pipelines` (public)
- Job parameter: `CONFIG_REPO_URL=https://github.com/adoptium/ci-temurin-config.git`

### Vendor-specific builds

- Config repo: `github.com/acme-corp/openjdk-configs` (private)
- Pipeline code: `github.com/adoptium/ci-adoptium-pipelines` (public, unmodified)
- Job parameter: `CONFIG_REPO_URL=https://github.com/acme-corp/openjdk-configs.git`
- Jenkins credential configured to access the private repo

### Testing a config change

Point the job at a feature branch of the config repo:

```
CONFIG_REPO_URL:    https://github.com/adoptium/ci-temurin-config.git
CONFIG_REPO_BRANCH: feature/jdk25-platforms
```

### Local development

```bash
# From the ci-adoptium-pipelines root
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --target-os mac \
  --architecture aarch64 \
  --config-repo-url https://github.com/adoptium/ci-temurin-config.git

# Or with a local config repo checkout
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --target-os linux \
  --architecture x64 \
  --config-repo-url /path/to/local/ci-temurin-config
```

---

## Security Considerations

`ci-adoptium-pipelines` is a public repository and must **never** contain:
- Credentials, API keys, or tokens
- Internal URLs or endpoints
- Vendor-specific or proprietary settings

Sensitive settings belong in the config repo:
- Use a **private** config repo for internal URLs, signing config, etc.
- Store actual secrets in Jenkins credentials, not in JSON files — reference them by credential ID from `jenkins_job_config.json` or pipeline parameters.

---

## Related Documentation

- [CONFIGURATION_GUIDE.md](CONFIGURATION_GUIDE.md) — per-version config file schema reference
- [CI_AGNOSTIC_ARCHITECTURE.md](CI_AGNOSTIC_ARCHITECTURE.md) — overall architecture overview
- [PIPELINE_ORCHESTRATION_ARCHITECTURE.md](PIPELINE_ORCHESTRATION_ARCHITECTURE.md) — job hierarchy and launch pipeline
- [JOB_DSL_AUTOMATION.md](JOB_DSL_AUTOMATION.md) — seed job and job creation
