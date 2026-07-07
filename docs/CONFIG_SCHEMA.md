# Configuration File Schema Reference

This document defines the valid schema for all JSON configuration files used by the Adoptium pipeline.
The configuration is split across three file types with clearly separated concerns.

---

## Overview

| File | Location | Purpose |
|---|---|---|
| `adoptium_pipeline_config.json` | repo root | CI-agnostic: active versions, build defaults, repository references |
| `jenkins_job_config.json` | repo root | Jenkins-specific: Jenkinsfile path, timeout, job parameters, log rotation |
| `configurations/jdkNN_pipeline_config.json` | `configurations/` | Per-version: platform build matrix |

---

## `adoptium_pipeline_config.json`

CI-agnostic top-level configuration. Contains the list of active JDK versions, default build settings, and
repository references that apply regardless of which CI system runs the pipeline.

```json
{
  "activeJdkVersions": [
    { "version": "jdk21", "enabled": true },
    { "version": "jdk17", "enabled": false }
  ],
  "defaultBuildArgs": "--create-jre-image --create-sbom",
  "defaultConfigureArgs": "",
  "defaultVariant": "temurin",
  "configFilePrefix": "configurations/",
  "configFileSuffix": "_pipeline_config.json",
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

### Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `activeJdkVersions` | array of objects | ✅ | Ordered list of JDK versions known to this config repo |
| `activeJdkVersions[].version` | string | ✅ | JDK version identifier, e.g. `"jdk21"`, `"jdk8"` |
| `activeJdkVersions[].enabled` | boolean | ✅ | Whether this version's pipeline should be active (`true`) or skipped (`false`) |
| `defaultBuildArgs` | string | ✅ | Default `--build-args` passed to every platform build unless overridden |
| `defaultConfigureArgs` | string | ✅ | Default `--configure-args` passed to every platform build unless overridden. Empty string means none |
| `defaultVariant` | string | ✅ | Default JVM variant (e.g. `"temurin"`) used when no per-platform variant is specified |
| `configFilePrefix` | string | ✅ | Path prefix prepended to version name when locating per-version config files. Typically `"configurations/"` |
| `configFileSuffix` | string | ✅ | Path suffix appended to version name. Typically `"_pipeline_config.json"` |
| `repository` | object | ✅ | Repository references for pipeline code and build tooling |
| `repository.url` | string | ✅ | Git URL of the CI pipeline repository |
| `repository.branch` | string | ✅ | Branch of the CI pipeline repository to check out |
| `repository.credentialsId` | string | ✅ | Jenkins credentials ID for pipeline repo checkout. Empty string for public repos |
| `repository.buildRepoUrl` | string | ✅ | Git URL of the temurin-build repository |
| `repository.buildBranch` | string | ✅ | Branch of the temurin-build repository |
| `repository.aqaRepoUrl` | string | ✅ | Git URL of the aqa-tests repository |
| `repository.aqaBranch` | string | ✅ | Branch of the aqa-tests repository |

---

## `jenkins_job_config.json`

Jenkins-specific configuration. Contains settings that are only meaningful in a Jenkins context:
where to find the Jenkinsfile, pipeline timeout, default job parameter values, and log rotation policy.

```json
{
  "jenkinsfilePath": "ci/jenkins/Jenkinsfile.declarative",
  "pipelineTimeoutHours": 8,
  "jobConfiguration": {
    "defaultParameters": {
      "VARIANT": "temurin",
      "CLEAN_WORKSPACE_AFTER_STAGE": true,
      "RUN_TESTS": true,
      "ENABLE_INSTALLERS": true,
      "SIGN_ARTIFACTS": true,
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

### Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `jenkinsfilePath` | string | ✅ | Relative path within the pipeline repo to the Jenkinsfile |
| `pipelineTimeoutHours` | integer | ✅ | Maximum wall-clock hours a pipeline run is allowed before Jenkins aborts it |
| `jobConfiguration` | object | ✅ | Jenkins job settings |
| `jobConfiguration.defaultParameters` | object | ✅ | Default values for Jenkins build parameters (can be overridden at trigger time) |
| `jobConfiguration.defaultParameters.VARIANT` | string | ✅ | JVM variant to build, e.g. `"temurin"` |
| `jobConfiguration.defaultParameters.CLEAN_WORKSPACE_AFTER_STAGE` | boolean | ✅ | Whether to clean the workspace after each stage completes |
| `jobConfiguration.defaultParameters.RUN_TESTS` | boolean | ✅ | Whether to run the test stage |
| `jobConfiguration.defaultParameters.ENABLE_INSTALLERS` | boolean | ✅ | Whether to build installer packages |
| `jobConfiguration.defaultParameters.SIGN_ARTIFACTS` | boolean | ✅ | Whether to sign build artifacts |
| `jobConfiguration.defaultParameters.PUBLISH_ARTIFACTS` | boolean | ✅ | Whether to publish artifacts to a remote location |
| `jobConfiguration.defaultParameters.RUN_REPRODUCIBLE_COMPARE` | boolean | ✅ | Whether to run the reproducible-build comparison stage |
| `jobConfiguration.logRotation` | object | ✅ | Jenkins log/artifact retention policy |
| `jobConfiguration.logRotation.daysToKeep` | integer | ✅ | Number of days to retain build logs |
| `jobConfiguration.logRotation.numToKeep` | integer | ✅ | Maximum number of build records to retain |
| `jobConfiguration.logRotation.artifactDaysToKeep` | integer | ✅ | Number of days to retain build artifacts |
| `jobConfiguration.logRotation.artifactNumToKeep` | integer | ✅ | Maximum number of builds whose artifacts are retained |

---

## `configurations/jdkNN_pipeline_config.json`

Per-version platform build matrix. One file per JDK version, named `jdk8_pipeline_config.json`,
`jdk21_pipeline_config.json`, etc.

```json
{
  "version": "jdk21",
  "openjdkVersion": "jdk21u",
  "enabled": true,
  "targetConfigurations": ["x86-64_linux", "x86-64_mac", "x86-64_windows"],
  "buildConfigurations": {
    "x86-64_linux": {
      "os": "linux",
      "arch": "x64",
      "dockerImage": "adoptopenjdk/centos7_build_image",
      "dockerArgs": "--platform linux/amd64",
      "podmanArgs": "",
      "dockerFile": { "openj9": "pipelines/build/dockerFiles/cuda.dockerfile" },
      "dockerRegistry": "https://adoptium.azurecr.io",
      "dockerCredential": "bbb9fa70-a1de-4853-b564-5f02193329ac",
      "crossCompile": "aarch64",
      "additionalNodeLabels": "sw.os.centos.7",
      "additionalTestLabels": { "temurin": "!sw.tool.glibc.2_12" },
      "cleanWorkspaceAfterBuild": true,
      "configureArgs": { "temurin": "--enable-dtrace" },
      "buildArgs": { "temurin": "--create-jre-image --create-sbom" },
      "additionalSmokeTestNodeLabels": ""
    }
  }
}
```

### Top-level fields

| Field | Type | Required | Description |
|---|---|---|---|
| `version` | string | ✅ | JDK version identifier without `u` suffix, e.g. `"jdk21"`, `"jdk8"` |
| `openjdkVersion` | string | ✅ | OpenJDK source stream identifier, typically with `u` suffix, e.g. `"jdk21u"`. Used to locate the upstream source |
| `enabled` | boolean | ✅ | Whether this version is active. Must match the corresponding entry in `adoptium_pipeline_config.json` |
| `targetConfigurations` | array of strings | ✅ | Ordered list of platform keys from `buildConfigurations` that should be built. Must be a subset of the keys in `buildConfigurations` |
| `buildConfigurations` | object | ✅ | Map of platform name → platform build configuration. Key follows the aqa-tests `PLATFORM_MAP` naming convention: `{arch}_{os}` with hyphens for compound words (e.g. `"x86-64_linux"`, `"aarch64_mac"`). See `LABEL_SCHEMA.md` for the full mapping table |

### `buildConfigurations` platform entry fields

Each key in `buildConfigurations` is a platform name (e.g. `"x64Linux"`) whose value is a platform
configuration object with the following fields.

#### Polymorphic fields

Several fields accept either a plain **string** (applied to all variants) or an **object** whose keys
are variant names (`"temurin"`, `"openj9"`, `"hotspot"`, `"dragonwell"`, etc.) with per-variant string
values. This is noted in the Type column as `string | variant-object`.

#### Required fields

| Field | Type | Required | Description |
|---|---|---|---|
| `os` | string | ✅ | Target operating system. Known values: `"linux"`, `"mac"`, `"windows"`, `"aix"`, `"solaris"`, `"alpine-linux"` |
| `arch` | string | ✅ | Target CPU architecture. Known values: `"x64"`, `"aarch64"`, `"ppc64"`, `"ppc64le"`, `"s390x"`, `"arm"` (32-bit), `"x86-32"`, `"riscv64"`, `"sparcv9"` |
| `additionalSmokeTestNodeLabels` | string | ✅ | Jenkins node label expression used to select agents for the SmokeTest stage. Empty string means no additional constraint |

#### Optional fields

| Field | Type | Description |
|---|---|---|
| `dockerImage` | `string \| variant-object` | Docker image used for the build. When a variant-object, each variant key maps to a different image. Omit for bare-metal builds |
| `dockerArgs` | string | Extra arguments passed to `docker run`, e.g. `"--platform linux/arm/v7"` |
| `podmanArgs` | string | Extra arguments passed to `podman run` when Podman is used instead of Docker |
| `dockerFile` | variant-object | Per-variant path to a custom Dockerfile, relative to the build repo root. Only present when a non-default image build is required |
| `dockerRegistry` | string | Registry URL used to pull the `dockerImage`, e.g. `"https://adoptium.azurecr.io"` |
| `dockerCredential` | string | Jenkins credentials ID used to authenticate with `dockerRegistry` |
| `crossCompile` | string | Host architecture used as the cross-compilation toolchain host. E.g. `"aarch64"` when building arm32, `"x64"` when cross-compiling aarch64 Windows, `"qemustatic"` for RISC-V via QEMU |
| `additionalNodeLabels` | `string \| variant-object` | Extra Jenkins node label expression ANDed onto the base agent selector. Values **must** use the `sw.*` / `hw.*` / `ci.*` label schema (e.g. `sw.tool.xcode15.0.1`, `sw.os.windows.2022&&sw.tool.vs2022`). String applies to all variants; variant-object allows per-variant labels. See `LABEL_SCHEMA.md` for the full token migration table |
| `additionalTestLabels` | `string \| variant-object` | Additional AQA test node label constraints. String applies to all variants; variant-object allows per-variant expressions (e.g. exclusions like `"!sw.tool.glibc.2_12"`) |
| `cleanWorkspaceAfterBuild` | boolean | When `true`, the workspace is wiped after a successful build. Typically set on resource-constrained agents (e.g. AIX). Defaults to `false` when absent |
| `configureArgs` | `string \| variant-object` | Arguments appended to the OpenJDK `configure` invocation. String applies to all variants; variant-object allows per-variant arguments |
| `buildArgs` | `string \| variant-object` | Arguments passed to the temurin-build `makejdk-any-platform.sh` script. String applies to all variants; variant-object allows per-variant arguments |

---

## Polymorphic field detail

Many fields in `buildConfigurations` entries accept either a plain string or a variant-keyed object.
The pipeline resolves the effective value by looking up the active variant key; if not found it falls
back to the plain string form.

**String form** — applies to all variants:
```json
"configureArgs": "--enable-dtrace"
```

**Variant-object form** — per-variant values:
```json
"configureArgs": {
  "temurin": "--enable-dtrace",
  "openj9":  "--enable-dtrace --with-jvm-variants=openj9"
}
```

Fields that support both forms: `dockerImage`, `additionalNodeLabels`, `additionalTestLabels`,
`configureArgs`, `buildArgs`, `dockerFile`.

---

## Platform naming convention

Platform keys in `buildConfigurations` follow the aqa-tests `PLATFORM_MAP` convention:
`{arch}_{os}` using hyphens within compound segments (not camelCase).

> **Note:** the `os` and `arch` *field values* inside each entry retain their
> existing temurin-build identifiers and do **not** change.  Only the map *key*
> uses the new format.  See [`LABEL_SCHEMA.md`](./LABEL_SCHEMA.md) for the full
> mapping from old camelCase keys to new keys.

| Platform key         | `arch` field value | `os` field value |
|----------------------|--------------------|------------------|
| `x86-64_linux`       | `x64`              | `linux`          |
| `x86-64_mac`         | `x64`              | `mac`            |
| `x86-64_windows`     | `x64`              | `windows`        |
| `x86-32_windows`     | `x86-32`           | `windows`        |
| `x86-64_alpine-linux`| `x64`              | `alpine-linux`   |
| `aarch64_linux`      | `aarch64`          | `linux`          |
| `aarch64_mac`        | `aarch64`          | `mac`            |
| `aarch64_windows`    | `aarch64`          | `windows`        |
| `aarch64_alpine-linux`| `aarch64`         | `alpine-linux`   |
| `arm_linux`          | `arm`              | `linux`          |
| `ppc64_aix`          | `ppc64`            | `aix`            |
| `ppc64le_linux`      | `ppc64le`          | `linux`          |
| `s390x_linux`        | `s390x`            | `linux`          |
| `riscv64_linux`      | `riscv64`          | `linux`          |
| `sparcv9_solaris`    | `sparcv9`          | `solaris`        |
| `x86-64_solaris`     | `x64`              | `solaris`        |

---

## Config file resolution

The pipeline resolves per-version config files using fields from `adoptium_pipeline_config.json`:

```
{configFilePrefix}{version}{configFileSuffix}
→ configurations/jdk21_pipeline_config.json
```

Only versions listed in `activeJdkVersions` with `"enabled": true` are processed.
