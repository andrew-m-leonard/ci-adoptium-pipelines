# Shell Scripts Reference

Index of all files under `scripts/` — stage scripts and shared utility libraries. For the stage interface contract (environment variables, archive/restore flow) see [WORKSPACE_ARTIFACTS_ARCHITECTURE.md](./WORKSPACE_ARTIFACTS_ARCHITECTURE.md). For writing a new stage see [UNIVERSAL_STAGE_PATTERN.md](./UNIVERSAL_STAGE_PATTERN.md).

---

## Shared Libraries (`scripts/lib/`)

All stage scripts source these libraries at the top of their file.

### [`scripts/lib/logging-utils.sh`](../scripts/lib/logging-utils.sh)

Timestamped logging to stderr.

| Function | Purpose |
|---|---|
| `log_info msg` | `[INFO]` line to stderr |
| `log_warn msg` | `[WARN]` line to stderr |
| `log_error msg` | `[ERROR]` line to stderr |
| `log_debug msg` | `[DEBUG]` line — only emitted when `DEBUG=true` |
| `log_section msg` | Separator banner to stderr |

### [`scripts/lib/config-utils.sh`](../scripts/lib/config-utils.sh)

Environment validation and JSON config helpers.

| Function | Purpose |
|---|---|
| `require_env VAR` | Exit 1 if `VAR` is unset or empty |
| `require_file PATH` | Exit 1 if file does not exist |
| `require_dir PATH` | Exit 1 if directory does not exist |
| `load_config FILE` | `cat` the JSON file (requires `jq`) |
| `get_config_value FILE JQ_PATH [DEFAULT]` | Extract string value via `jq`; exit 1 if absent and no default |
| `get_config_bool FILE JQ_PATH [DEFAULT]` | Extract boolean value; returns `true`/`false` string |
| `validate_standard_environment` | Checks `WORKSPACE` and `CONFIG_FILE` are set and file exists; sets `TARGET_DIR` default to `${WORKSPACE}/target` if not already provided |

### [`scripts/lib/artifact-utils.sh`](../scripts/lib/artifact-utils.sh)

Artifact directory and file helpers.

| Function | Purpose |
|---|---|
| `create_stage_metadata STAGE STATUS` | Writes `${WORKSPACE}/stage-metadata.json` |
| `copy_artifacts SRC DEST` | `cp -r SRC/* DEST/` with existence checks |
| `verify_artifact PATH` | Exit-safe check that a file exists; logs size |
| `list_artifacts DIR` | `find DIR -type f` with `ls -lh` |
| `create_checksums DIR` | SHA256 checksums for all files in `DIR` → `DIR/checksums.txt` |
| `verify_checksums DIR` | Verify `DIR/checksums.txt` against contents |
| `prepare_output_dir [DIR]` | `mkdir -p DIR`; optionally cleans if `CLEAN_OUTPUT=true` |
| `determine_filename` | Derives the Adoptium artifact filename from config vars; exports `$FILENAME` |

### [`scripts/lib/workspace-cleanup.sh`](../scripts/lib/workspace-cleanup.sh)

Utility functions for cleaning build scratch directories inside `WORKSPACE`.

---

## Stage Scripts (`scripts/stages/`)

Stages are invoked by the pipeline runner via [`StageScriptRunner.groovy`](../ci/jenkins/lib/StageScriptRunner.groovy) (Jenkins) or [`ci/local/stage_resolver.py`](../ci/local/stage_resolver.py) (local). Both resolve vendor overrides from `config-repo/vendor-scripts/` first, then fall back to defaults here.

**Key:** `REAL` = has a full working implementation. `STUB` = logs a skip message and exits 0; must be overridden via `config-repo/vendor-scripts/` for real behaviour.

### Standard environment variables received by every stage

| Variable | Value |
|---|---|
| `WORKSPACE` | Ephemeral stage scratch directory (cleaned before/after stage) |
| `CONFIG_FILE` | Path to `pipeline-config.json` inside `WORKSPACE` |
| `INPUT_ARTIFACTS_DIR` | Directory containing artifacts from previous stages |
| `TARGET_DIR` | Directory where this stage writes its output files |
| `BUILD_NUMBER` | Build number string |

---

### `02-build.sh` — Build JDK `REAL`

Clones `temurin-build`, invokes `build-farm/make-adopt-build-farm.sh`, copies outputs to `TARGET_DIR`.

**Inputs:** `CONFIG_FILE` (for all build parameters)
**Outputs:** `${TARGET_DIR}/*.tar.gz` or `*.zip`, `*.json` (SBOM if `--create-sbom`), `build-metadata.json`, `checksums.txt`
**Extra env:** none required beyond standard set

Key functions: `setup_build_environment`, `setup_temurin_build`, `setup_reproducible_build_padding` (fetches upstream SBOM to derive workspace path length for reproducible builds), `prepare_workspace`, `execute_build`, `extract_build_metadata`, `organize_build_outputs`

---

### `03-internal-sign.sh` — Internal JMOD Signing `STUB`

Signs individual JMOD files before assembly. Vendor-specific (Eclipse codesign node).

**Inputs:** `INPUT_ARTIFACTS_DIR` containing `jmods/` from Build
**Outputs:** `TARGET_DIR` containing signed jmods

---

### `04-assemble.sh` — Assemble `STUB`

Reassembles the JDK image from signed JMODs. Vendor-specific.

**Inputs:** `INPUT_ARTIFACTS_DIR` containing signed jmods
**Outputs:** `TARGET_DIR` containing assembled JDK tarballs

---

### `06-sign.sh` — Sign Artifacts `STUB`

Signs the final JDK tarballs/zips. Vendor-specific (code signing service).

**Inputs:** `INPUT_ARTIFACTS_DIR` containing `*.tar.gz`/`*.zip`
**Outputs:** `TARGET_DIR` containing signed artifacts

---

### `07-installer.sh` — Build Installers `STUB`

Creates platform-specific installers (`.msi`, `.pkg`, `.deb`, `.rpm`). Vendor-specific.

**Inputs:** `INPUT_ARTIFACTS_DIR` containing signed JDK tarballs
**Outputs:** `TARGET_DIR` containing installer packages

---

### `08-sign-installer.sh` — Sign Installers `STUB`

Signs the installer packages. Vendor-specific.

**Inputs:** `INPUT_ARTIFACTS_DIR` containing installers
**Outputs:** `TARGET_DIR` containing signed installers

---

### `09-gpg-sign.sh` — GPG Sign `STUB`

GPG-signs artifacts and metadata (Temurin variant only). Vendor-specific.

**Inputs:** `INPUT_ARTIFACTS_DIR` containing signed artifacts + installers
**Outputs:** `TARGET_DIR` containing `.sig`/`.asc` signature files

---

### `10-sbom-sign.sh` — SBOM JSF Sign `STUB`

Signs SBOM files in JSF format. Vendor-specific. Only called when `--create-sbom` is in `BUILD_ARGS`.

**Inputs:** `INPUT_ARTIFACTS_DIR` containing SBOM JSON files
**Outputs:** `TARGET_DIR` containing signed SBOM files

---

### `11-verify-signing.sh` — Verify Signing `STUB`

Verifies all GPG signatures. Vendor-specific.

**Inputs:** `INPUT_ARTIFACTS_DIR` containing signed artifacts + `.sig`/`.asc` files
**Outputs:** `TARGET_DIR` containing verification results

---

### `12-validate-sbom.sh` — Validate SBOM `REAL`

Clones `temurin-build`, invokes `tooling/validateSBOM.sh` against the SBOM JSON files produced by Build. Only runs when `--create-sbom` is present in `BUILD_ARGS`.

**Inputs:** `INPUT_ARTIFACTS_DIR` containing `*sbom*.json`
**Outputs:** `TARGET_DIR` containing validation results
**Extra env (optional):** `TEMURIN_BUILD_REPO`, `TEMURIN_BUILD_BRANCH`

---

### `13-smoke-tests.sh` — Smoke Tests `REAL`

Extracts the built JDK into `WORKSPACE/jdk-test/` and runs four quick checks.

**Inputs:** `INPUT_ARTIFACTS_DIR` containing `*jdk_*.tar.gz` or `*jdk_*.zip`
**Outputs:** `${TARGET_DIR}/java-version.txt`, `hello-compile.txt`, `hello-run.txt`, `system-properties.txt`, `class-loading.txt`, `test-metadata.json`

Tests performed:
1. `java -version` executes successfully
2. HelloWorld.java compiles and runs (`javac` + `java`)
3. `-XshowSettings:properties` returns `java.version`, `java.home`, `os.name`
4. `-cp lib -version` verifies class loading

Key functions: `find_jdk_artifact`, `extract_jdk`, `run_smoke_tests`, `test_java_version`, `test_hello_world`, `test_system_properties`, `test_class_loading`, `create_test_metadata`

---

### `14-aqa-tests.sh` — AQA Tests `STUB`

Runs the full AQA test suite. Vendor-specific (AQA infrastructure required).

**Inputs:** `INPUT_ARTIFACTS_DIR` containing JDK tarballs + metadata
**Outputs:** `TARGET_DIR` containing AQA test reports

---

### `15-tck-tests.sh` — TCK Tests `STUB`

Runs the TCK (Technology Compatibility Kit). Vendor-specific. Skipped for `jdk8u/s390x/linux`.

**Inputs:** `INPUT_ARTIFACTS_DIR` containing JDK tarballs + metadata
**Outputs:** `TARGET_DIR` containing TCK results

---

### `16-publish.sh` — Publish Artifacts `STUB`

Publishes artifacts to a release repository. Vendor-specific.

**Inputs:** `INPUT_ARTIFACTS_DIR` containing all final artifacts
**Outputs:** `TARGET_DIR` containing publish receipts/logs

---

### `20-reproducible-compare.sh` — Reproducible Build Comparison `REAL`

Downloads the corresponding Adoptium production binary via the Adoptium API, then compares it against the locally built JDK using `temurin-build/tooling/reproducible/repro_compare.sh`.

**Inputs:** `INPUT_ARTIFACTS_DIR` containing built JDK tarballs
**Outputs:** `${TARGET_DIR}/comparison-report.txt`, `reprotest.diff`, `ReproduciblePercent`, `reproducible_evidence.log`
**Extra env:** `SCM_REF` (required), `RELEASE` (`true`/`false`), `BUILD_REPO_URL` (optional), `BUILD_REF` (optional)

Exit codes: `0` = 100% reproducible, non-zero = differences found (pipeline marks build UNSTABLE, does not fail)

---

## Vendor Override Pattern

Any `STUB` stage (and any `REAL` stage) can be overridden by placing a replacement script in the config repo:

```
config-repo/
└── vendor-scripts/
    ├── 06-sign.sh          ← overrides scripts/stages/06-sign.sh
    ├── 07-installer.sh     ← overrides scripts/stages/07-installer.sh
    └── ...
```

Resolution order (first match wins):
1. `config-repo/vendor-scripts/<stem>.sh`
2. `config-repo/vendor-scripts/<stem>.py`
3. `scripts/stages/<stem>.sh`
4. `scripts/stages/<stem>.py`
5. built-in no-op (logs skip, returns 0)

See [`ci/jenkins/lib/StageScriptRunner.groovy`](../ci/jenkins/lib/StageScriptRunner.groovy) (Jenkins) and [`ci/local/stage_resolver.py`](../ci/local/stage_resolver.py) (local) for the implementation.
