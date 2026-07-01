# Pipeline Runner Guide

The `ci/local/run-pipeline.py` script runs the complete OpenJDK build pipeline locally from the command line. It mirrors Jenkins semantics: each stage runs in a clean `stage_workspace/`, copies inputs from `build_artifacts/`, and archives outputs back to `build_artifacts/` when done.

## Quick Start

```bash
cd ci-adoptium-pipelines

python3 ci/local/run-pipeline.py \
    --jdk-version jdk21 \
    --target-os mac \
    --architecture aarch64 \
    --config-repo-url https://github.com/adoptium/ci-temurin-config.git
```

The `--config-repo-url` default is already `https://github.com/adoptium/ci-temurin-config.git`, so for Temurin builds it can be omitted.

## What It Does

The pipeline runner orchestrates all build stages in sequence:

1. **Initialize** — clones the config repo; generates `pipeline-config.json`; archives it to `build_artifacts/`
2. **Build** — clones `temurin-build`; runs `make-adopt-build-farm.sh`; archives JDK tarballs to `build_artifacts/`
3. **Validate SBOM** — validates SBOM files (only when `--create-sbom` is in `BUILD_ARGS`)
4. **Sign Artifacts** — signs tarballs (only when `enableSigner=true`; stub unless vendor-overridden)
5. **Build Installers** — creates platform installers (only when `enableInstallers=true`; stub unless vendor-overridden)
6. **Smoke Tests** — extracts JDK and runs four basic checks (only when `enableTests=true`)
7. **Reproducible Compare** — downloads Adoptium production binary and compares (only when `--compare-build` is set)

Stages 3–7 are each gated and will silently skip if their condition is not met.

---

## Command Line Reference

### Required

| Parameter | Description | Format |
|---|---|---|
| `--jdk-version` | JDK version to build | `jdk<N>` — e.g. `jdk21`, `jdk17`, `jdk8` |
| `--target-os` | Target OS | `mac`, `linux`, `windows`, `aix` |
| `--architecture` | Target architecture | `aarch64`, `x64`, `x32`, `ppc64`, `s390x` |

Note: `--jdk-version` must match the pattern `jdk` followed by digits only (e.g. `jdk21`). Suffixes like `jdk21u` are not accepted.

### Configuration repo

| Parameter | Description | Default |
|---|---|---|
| `--config-repo-url` | URL of the config repo containing `configurations/`, `vendor-scripts/`, `adoptium_pipeline_config.json` | `https://github.com/adoptium/ci-temurin-config.git` |
| `--config-repo-branch` | Branch to clone | `main` |

The config repo provides: build/AQA repo URLs and branches, the default variant, and active JDK version list. CLI `--build-ref`, `--build-repo-url`, `--aqa-ref`, `--aqa-repo-url` override values from the repo.

### Build references (overrides)

These override values from `adoptium_pipeline_config.json`. If neither the CLI flag nor the config repo provides a value, the runner errors.

| Parameter | Description |
|---|---|
| `--build-ref` | `temurin-build` branch/tag |
| `--build-repo-url` | `temurin-build` repository URL |
| `--aqa-ref` | `aqa-tests` branch/tag |
| `--aqa-repo-url` | `aqa-tests` repository URL |

### Release type

| Parameter | Description |
|---|---|
| `--release-type` | `NIGHTLY` (default), `WEEKLY` (adds `--with-version-opt=ea`), or `RELEASE` (case-insensitive) |
| `--scm-ref` | OpenJDK source tag/ref (e.g. `jdk-21.0.2+13`). Required with `--compare-build`. |

### Workspace control

| Parameter | Description |
|---|---|
| `--workspace` | Root workspace directory (default: `~/openjdk-build`) |
| `--build-number` | Build identifier string (default: `local-YYYYMMDD-HHMMSS`) |
| `--clean-workspace` | Remove existing workspace before starting |

**Workspace rules** — the runner validates workspace state before any stage runs:
- **Workspace does not exist**: fresh build proceeds normally
- **Workspace exists + no flags**: ❌ error — use `--clean-workspace` or `--start-from-stage`
- **`--clean-workspace`**: removes entire `pipeline_workspace/` then creates fresh structure
- **`--start-from-stage` + workspace + `build_artifacts/` exist**: restart proceeds
- **`--clean-workspace` + `--start-from-stage`**: ❌ error — mutually exclusive

### Stage control

| Parameter | Description |
|---|---|
| `--start-from-stage` | Start from a specific stage, skipping earlier ones (requires existing workspace) |
| `--skip-build` | Generate configuration only; skip all subsequent stages |
| `--no-tests` | Disable smoke tests |
| `--no-installers` | Disable installer building |
| `--no-signer` | Disable artifact signing |
| `--compare-build` | Enable reproducible build comparison against Adoptium production binary (requires `--scm-ref`) |

Valid `--start-from-stage` values: `initialize`, `build`, `sign`, `installer`, `smoke-tests`, `reproducible-compare`

---

## Usage Examples

### Full build (macOS Apple Silicon)

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21 \
    --target-os mac \
    --architecture aarch64
```

### Clean build (remove existing workspace first)

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21 \
    --target-os mac \
    --architecture aarch64 \
    --clean-workspace
```

### Build only — no tests or installers

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21 \
    --target-os mac \
    --architecture aarch64 \
    --no-tests \
    --no-installers
```

### Configuration inspection only — no build

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21 \
    --target-os mac \
    --architecture aarch64 \
    --skip-build
```

Generates and prints `pipeline-config.json` without running any build stages.

### Release build with SCM ref (Linux x64)

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk17 \
    --target-os linux \
    --architecture x64 \
    --release-type RELEASE \
    --scm-ref jdk-17.0.10+7
```

### Weekly (EA beta) build

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21 \
    --target-os mac \
    --architecture aarch64 \
    --release-type WEEKLY
```

Adds `--with-version-opt=ea` to configure args.

### Custom temurin-build branch

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21 \
    --target-os mac \
    --architecture aarch64 \
    --build-ref develop
```

### Custom workspace directory

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21 \
    --target-os mac \
    --architecture aarch64 \
    --workspace ~/my-jdk21-build
```

### Restart from a specific stage

```bash
# After a failed sign stage, re-run from sign onwards
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21 \
    --target-os mac \
    --architecture aarch64 \
    --start-from-stage sign
```

The workspace and `build_artifacts/` from the previous run must exist.

### Reproducible build comparison

```bash
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21 \
    --target-os mac \
    --architecture aarch64 \
    --scm-ref jdk-21.0.2+13 \
    --release-type RELEASE \
    --compare-build
```

Downloads the matching Adoptium production binary and compares it byte-by-byte against the locally built JDK. See [REPRO_COMPARE_INTEGRATION.md](./REPRO_COMPARE_INTEGRATION.md).

### Parallel builds (different workspaces)

```bash
# Terminal 1: JDK 21
python3 ci/local/run-pipeline.py --jdk-version jdk21 --target-os mac --architecture aarch64 \
    --workspace ~/jdk21-build

# Terminal 2: JDK 17
python3 ci/local/run-pipeline.py --jdk-version jdk17 --target-os mac --architecture aarch64 \
    --workspace ~/jdk17-build
```

---

## Workspace Layout

```
~/openjdk-build/                    # pipeline_workspace (--workspace)
│
├── pipeline-config.json            # Generated by Initialize
│                                   # (immediately archived to build_artifacts/)
│
├── config-repo/                    # Cloned once at Initialize
│   ├── configurations/
│   ├── vendor-scripts/
│   └── adoptium_pipeline_config.json
│
├── stage_workspace/                # Ephemeral — wiped before each stage
│   ├── pipeline-config.json        # Restored from build_artifacts/ before each stage
│   ├── *.tar.gz, *.zip …           # Other stage inputs (restored from build_artifacts/)
│   └── target/                     # Stage writes outputs here (TARGET_DIR)
│
└── build_artifacts/                # Durable archive store — persists across stages
    ├── pipeline-config.json
    ├── OpenJDK*.tar.gz             # Built JDK (after Build stage)
    ├── *.json                      # SBOM, metadata
    └── …                          # Signed artifacts, installer outputs, test results
```

All final artifacts are in `build_artifacts/` after the pipeline completes.

---

## Environment Variables Set Per Stage

| Variable | Value |
|---|---|
| `WORKSPACE` | `stage_workspace/` — ephemeral scratch dir for this stage |
| `CONFIG_FILE` | `stage_workspace/pipeline-config.json` — restored from `build_artifacts/` |
| `INPUT_ARTIFACTS_DIR` | `stage_workspace/` — inputs copied in from `build_artifacts/` |
| `TARGET_DIR` | `stage_workspace/target/` — stage writes outputs here |
| `BUILD_NUMBER` | `local-YYYYMMDD-HHMMSS` (or `--build-number` value) |
| `PIPELINE_ROOT` | Root of the `ci-adoptium-pipelines` checkout |

---

## Troubleshooting

### "Workspace already exists" error on fresh build

The runner refuses to overwrite an existing workspace without an explicit instruction. Use `--clean-workspace` to remove it first, or `--start-from-stage` to continue from where it left off.

### "build_artifacts/ does not exist" on restart

The workspace was created by an older version of the local runner (which used `artifacts/` instead of `build_artifacts/`). Run with `--clean-workspace` to start fresh.

### Initialize fails — configuration not found

- Verify `--config-repo-url` points to a reachable repository
- Confirm the repo contains `configurations/` and `adoptium_pipeline_config.json`
- Check that `configFilePrefix` in `adoptium_pipeline_config.json` matches the actual config directory name
- Use `tools/` to convert legacy Groovy configs if migrating

### Build fails — missing dependencies

Ensure the following are installed on the build machine: `git`, `make`, `gcc`/`clang`, a boot JDK (N−1 version of the target JDK). The boot JDK must be in `PATH` or `JAVA_HOME` must be set.

### Build time

A full JDK build typically takes 30–60 minutes. Use `--skip-build` to test configuration generation only, or `--no-tests --no-installers` to get just the JDK tarball.

### Script not executable

```bash
chmod +x ci/local/run-pipeline.py
chmod +x scripts/stages/*.sh scripts/lib/*.sh
```

---

## See Also

- [WORKSPACE_ARTIFACTS_ARCHITECTURE.md](./WORKSPACE_ARTIFACTS_ARCHITECTURE.md) — workspace layout, archive/restore semantics, validation rules
- [CODE_CONFIG_SEPARATION.md](./CODE_CONFIG_SEPARATION.md) — config repo structure and `pipeline-config.json` schema
- [REPRO_COMPARE_INTEGRATION.md](./REPRO_COMPARE_INTEGRATION.md) — reproducible build comparison details
- [CI_AGNOSTIC_ARCHITECTURE.md](./CI_AGNOSTIC_ARCHITECTURE.md) — overall pipeline architecture
- [`ci/local/README.md`](../ci/local/README.md) — local runner module README
