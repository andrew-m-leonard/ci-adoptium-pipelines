# Pluggable Stage Script Design

This document describes the vendor-overridable stage script mechanism used by
`ci-adoptium-pipelines`. It allows multiple vendors to share the same pipeline
code while supplying their own implementations of stages that differ between
organisations (e.g. code-signing), while still benefiting from a common set of
default implementations for stages that are universal (e.g. build, smoke tests).

---

## Core Idea: Resolution Order

A single Jenkinsfile helper function `runStageScript(stem, config)` resolves the
implementation to run using a fixed priority chain:

```
1. config-repo/vendor-scripts/<stem>.sh      ← vendor override (sh)
2. config-repo/vendor-scripts/<stem>.groovy  ← vendor override (groovy)
3. config-repo/vendor-scripts/<stem>.py      ← vendor override (python)
4. scripts/stages/<stem>.sh                  ← default (sh)
5. scripts/stages/<stem>.groovy              ← default (groovy)
6. scripts/stages/<stem>.py                  ← default (python)
7. built-in no-op stub                       ← safe fallback (logs and exits 0)
```

Vendor scripts live in **the config repo** under `vendor-scripts/`.  
Default scripts live in **this repo** under `scripts/stages/`.  
The Jenkinsfile never hard-codes a path — it always delegates to the resolver.

---

## Stage Script Name Convention

Script file names (stems) are derived from the stage number and a short
lower-cased hyphenated description:

| Stage Name             | Script Stem              | Default in this repo           |
|------------------------|--------------------------|--------------------------------|
| Build                  | `02-build`               | Full implementation (`.sh`)    |
| Internal Sign          | `03-internal-sign`       | No-op stub (`.sh`)             |
| Assemble               | `04-assemble`            | No-op stub (`.sh`)             |
| Sign Artifacts         | `06-sign`                | Full implementation (`.sh`)    |
| Build Installers       | `07-installer`           | Full implementation (`.sh`)    |
| Sign Installers        | `08-sign-installer`      | No-op stub (`.sh`)             |
| GPG Sign               | `09-gpg-sign`            | No-op stub (`.sh`)             |
| SBOM Sign              | `10-sbom-sign`           | No-op stub (`.sh`)             |
| Verify Signing         | `11-verify-signing`      | No-op stub (`.sh`)             |
| Validate SBOM          | `12-validate-sbom`       | Full implementation (`.sh`)    |
| Smoke Tests            | `13-smoke-tests`         | Full implementation (`.sh`)    |
| AQA Tests              | `14-aqa-tests`           | No-op stub (`.sh`)             |
| TCK Tests              | `15-tck-tests`           | No-op stub (`.sh`)             |
| Reproducible Compare   | `20-reproducible-compare`| Full implementation (`.sh`)    |

**Full implementation** — a working default used by all vendors unless overridden.  
**No-op stub** — an echo-only placeholder; vendors must supply an override for the
stage to do real work.

---

## The `runStageScript()` Helper

One new function in `Jenkinsfile.declarative` replaces all hard-coded
`load 'scripts/stages/...'` and `sh 'bash scripts/stages/...'` calls:

```groovy
/**
 * Resolve and execute a stage script.
 * Searches vendor-scripts/ (config-repo) first, then scripts/stages/ (this repo).
 * Supports .sh, .groovy, and .py implementations.
 *
 * @param scriptStem  e.g. '03-internal-sign'
 * @param config      pipeline-config map (passed to .groovy scripts;
 *                    .sh and .py scripts use environment variables)
 */
def runStageScript(String scriptStem, def config = null) {
    def searchPaths = [
        [path: "config-repo/vendor-scripts/${scriptStem}.sh",     type: 'sh'],
        [path: "config-repo/vendor-scripts/${scriptStem}.groovy", type: 'groovy'],
        [path: "config-repo/vendor-scripts/${scriptStem}.py",     type: 'py'],
        [path: "scripts/stages/${scriptStem}.sh",                 type: 'sh'],
        [path: "scripts/stages/${scriptStem}.groovy",             type: 'groovy'],
        [path: "scripts/stages/${scriptStem}.py",                 type: 'py'],
    ]

    def found = searchPaths.find { fileExists(it.path) }

    if (!found) {
        echo "ℹ️  No script found for '${scriptStem}' — stage is a no-op (stub)"
        return
    }

    echo "▶ Running ${found.type.toUpperCase()} stage script: ${found.path}"

    switch (found.type) {
        case 'sh':
            sh "bash ${found.path}"
            break
        case 'groovy':
            def script = load found.path
            script(config)
            break
        case 'py':
            sh "python3 ${found.path}"
            break
    }
}
```

---

## Script Contracts

### Shell scripts (`.sh`)

Communicate entirely via environment variables. The Jenkinsfile sets the
relevant variables before calling `runStageScript()`. Common variables:

| Variable             | Description                                      |
|----------------------|--------------------------------------------------|
| `WORKSPACE`          | Stage workspace root                             |
| `CONFIG_FILE`        | Path to `pipeline-config.json`                   |
| `INPUT_ARTIFACTS_DIR`| Directory containing input artifacts             |
| `TARGET_DIR`         | Directory for this stage's output                |
| `BUILD_NUMBER`       | Jenkins build number                             |
| `BUILD_UID`          | Unique build identifier                          |
| `GROUP_UID`          | Group identifier (set by Launch pipeline)        |

### Groovy scripts (`.groovy`)

Must expose a `call(Map config)` function and end with `return this`:

```groovy
def call(Map config) {
    def buildConfig = config.buildConfig
    // ... implementation ...
}

return this
```

### Python scripts (`.py`)

Like shell scripts, communicate via environment variables. Must exit with
code `0` on success and non-zero on failure.

---

## No-op Stub Convention

For stages with no universal default (e.g. Internal Sign), the default script
is a minimal stub that logs clearly and exits 0. The build does **not** fail
when a stub runs — the `when {}` guard in the Jenkinsfile is the sole gate for
deciding whether a stage should run at all.

```bash
#!/bin/bash
# DEFAULT STUB: 03-internal-sign.sh
# This is a no-op placeholder. Override by placing a script at:
#   config-repo/vendor-scripts/03-internal-sign.{sh,groovy,py}
echo "ℹ️  Internal Sign: no vendor implementation configured — skipping"
```

---

## Config Repo Layout for Vendor Overrides

```
config-repo/                          ← checked out by Initialize stage
  configurations/
    jdk21_pipeline_config.json
  vendor-scripts/                     ← vendor override scripts go here
    03-internal-sign.sh               ← vendor's own signing impl
    09-gpg-sign.groovy                ← vendor's GPG sign (groovy)
    14-aqa-tests.sh                   ← vendor's AQA test runner
```

The `vendor-scripts/` path must be included in the sparse checkout performed
by the Initialize stage:

```groovy
extensions: [
    [$class: 'SparseCheckoutPaths',
     sparseCheckoutPaths: [
         [path: 'configurations/*'],
         [path: 'vendor-scripts/*']    // required for vendor overrides
     ]]
]
```

---

## How Stages Change in the Jenkinsfile

Before (hard-coded, mixed patterns):

```groovy
// groovy stage
def signScript = load 'scripts/stages/03-internal-sign.groovy'
signScript(config)

// sh stage
sh 'bash scripts/stages/12-validate-sbom.sh'
```

After (uniform, vendor-aware):

```groovy
runStageScript('03-internal-sign', config)

runStageScript('12-validate-sbom')
```

The Jenkinsfile no longer cares what the implementation language is.

---

## What Stays the Same

- `when {}` guards — unchanged. Stage enablement is still controlled by config
  flags and environment variables.
- Environment variable contract for `.sh` scripts — unchanged.
- `initializeStage()` / `finalizeStage()` / `executeStageWithTracking()` — unchanged.
- `pipeline-config.json` schema — no changes needed. The script resolver is
  purely filesystem-based.

---

## What a Vendor Does

To override **Internal Sign** for Temurin (Eclipse CBI codesign service):

1. Create `vendor-scripts/03-internal-sign.sh` in the config repo:

```bash
#!/bin/bash
# Temurin internal signing via Eclipse CBI codesign service
set -euo pipefail
# ... actual curl signing logic ...
```

2. No changes to `ci-adoptium-pipelines` are required. The next pipeline run
   picks up the vendor script automatically.

---

## Implementation Checklist

- [ ] Add `runStageScript()` helper to `Jenkinsfile.declarative` (above `pipeline {}`)
- [ ] Replace every `load '...'` + `script(config)` call with `runStageScript('<stem>', config)`
- [ ] Replace every `sh 'bash scripts/stages/...'` call with `runStageScript('<stem>')`
- [ ] Add `vendor-scripts/*` to sparse checkout paths in the Initialize stage
- [ ] Replace `scripts/stages/03-internal-sign.groovy` with a `03-internal-sign.sh` stub
- [ ] Add no-op stub `.sh` files for all other not-yet-implemented stages
