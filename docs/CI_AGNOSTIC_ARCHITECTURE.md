# CI-Agnostic Pipeline Architecture

## Problem Statement

The original `openjdk_build_pipeline.groovy` implementation was a monolithic Jenkins Groovy script that tightly coupled all build logic to Jenkins. This created several critical challenges:

**Business Impact:**
- **Vendor Lock-in**: Complete dependency on Jenkins infrastructure and Groovy DSL
- **Migration Risk**: Moving to alternative CI systems (GitLab CI, GitHub Actions, etc.) would require a complete rewrite
- **Development Velocity**: Changes required Jenkins expertise and could only be tested in Jenkins environment
- **Operational Overhead**: No ability to run or debug pipeline stages locally

**Technical Debt:**
- Mixed concerns: orchestration logic intertwined with business logic
- Difficult to test: stage implementation logic embedded within Jenkins-specific Groovy code prevents unit testing, requires full Jenkins environment for any testing, and makes it impossible to validate stage logic independently or locally
- Poor maintainability: large monolithic script (~2000+ lines) with complex dependencies, no modularization of stages, making it difficult to understand, modify, or debug individual pipeline stages without affecting others
- Limited reusability: logic cannot be shared across different CI platforms

To address these issues and future-proof the build infrastructure, we need a **CI-agnostic architecture** that separates orchestration from implementation.

## Old Architecture (Before Refactoring)

The original `openjdk_build_pipeline.groovy` was a monolithic Jenkins Groovy script with all logic embedded:

```
┌─────────────────────────────────────────────────────────────┐
│ openjdk_build_pipeline.groovy (Monolithic Groovy Script)   │
│                                                             │
│ ┌─────────────────────────────────────────────────────┐   │
│ │ Pipeline Definition + Stage Logic (All in Groovy)   │   │
│ │                                                       │   │
│ │ • Stage: Build                                       │   │
│ │   └─> Groovy code calls make-adopt-build-farm.sh    │   │
│ │                                                       │   │
│ │ • Stage: Internal Sign                               │   │
│ │   └─> Groovy code calls downstream job               │   │
│ │                                                       │   │
│ │ • Stage: Sign                                        │   │
│ │   └─> Groovy code calls downstream job               │   │
│ │                                                       │   │
│ │ • Stage: Installer                                   │   │
│ │   └─> Groovy code calls downstream job               │   │
│ │                                                       │   │
│ │ • Stage: Smoke Tests                                 │   │
│ │   └─> Groovy code calls test scripts                │   │
│ │                                                       │   │
│ │ • Stage: AQA Tests                                   │   │
│ │   └─> Groovy code calls downstream job               │   │
│ └─────────────────────────────────────────────────────┘   │
│                                                             │
│ ⚠️  Problems:                                               │
│ • Tightly coupled to Jenkins                               │
│ • Cannot run locally without Jenkins                       │
│ • Cannot migrate to other CI systems                       │
│ • Difficult to test individual stages                      │
│ • Mixed concerns (orchestration + logic)                   │
└─────────────────────────────────────────────────────────────┘
```

**Key Issues:**
- **CI Lock-in**: All logic written in Jenkins Groovy DSL
- **No Local Testing**: Cannot run pipeline stages outside Jenkins
- **Maintenance Burden**: Changes require Jenkins expertise
- **Migration Barrier**: Moving to GitLab/GitHub Actions requires complete rewrite
- **Testing Difficulty**: Cannot unit test stage logic independently

## Solution: Separation of Concerns

### Layer 1: CI Orchestration (CI-Specific)
- **Purpose**: Pipeline definition, stage sequencing, artifact management
- **Language**: CI-specific DSL (Jenkinsfile, .gitlab-ci.yml, .github/workflows/*.yml) or portable scripts (shell/Python)
- **Responsibility**:
  - Define stages and their order
  - Manage artifacts (archive/retrieve)
  - Handle conditional execution
  - Provide environment variables
- **Implementation Options**:
  - CI-native DSL for platform-specific features (Jenkins Groovy, GitLab CI YAML, GitHub Actions YAML)
  - Portable shell/Python scripts for maximum portability

### Layer 2: Stage Implementation (CI-Agnostic)
- **Purpose**: Actual build/test/sign logic
- **Language**: **Platform-agnostic Bash scripts** (portable across Linux, macOS, Windows/MSYS2)
- **Responsibility**:
  - Execute the actual work
  - Read inputs from standard locations
  - Write outputs to standard locations
  - Return exit codes for success/failure
- **Key Benefit**: Same scripts run identically on any CI platform or locally

### Layer 3: Build Tools (Already CI-Agnostic)
- **Purpose**: Core build functionality
- **Examples**: make-adopt-build-farm.sh, sign scripts, test runners
- **Already portable** - no changes needed

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: CI Orchestration (CI-Specific or Portable)        │
│ ┌─────────────┐  ┌──────────────┐  ┌──────────────┐       │
│ │ Jenkinsfile │  │ .gitlab-ci   │  │ GitHub       │       │
│ │ (Groovy)    │  │ .yml         │  │ Actions YAML │       │
│ │             │  │              │  │              │       │
│ │ OR          │  │ OR           │  │ OR           │       │
│ │ Shell/Python│  │ Shell/Python │  │ Shell/Python │       │
│ └──────┬──────┘  └──────┬───────┘  └──────┬───────┘       │
└────────┼─────────────────┼──────────────────┼──────────────┘
         │                 │                  │
         │ All call same   │ All call same    │ All call same
         │ bash scripts    │ bash scripts     │ bash scripts
         ▼                 ▼                  ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: Stage Scripts (PLATFORM-AGNOSTIC BASH)            │
│ ┌──────────────────────────────────────────────────────┐   │
│ │ scripts/stages/ (Portable Bash - Same Everywhere)   │   │
│ │ ├── 02-build.sh          ✓ Linux                    │   │
│ │ ├── 03-internal-sign.sh  ✓ macOS                    │   │
│ │ ├── 06-sign.sh           ✓ Windows/MSYS2            │   │
│ │ ├── 07-installer.sh      ✓ Any CI Platform          │   │
│ │ └── 13-smoke-tests.sh    ✓ Local Development        │   │
│ └──────────────────────────────────────────────────────┘   │
└────────┬────────────────────────────────────────────────────┘
         │ Calls existing
         │ build tools
         ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 3: Build Tools (Already CI-Agnostic)                 │
│ ┌──────────────────────────────────────────────────────┐   │
│ │ - make-adopt-build-farm.sh                           │   │
│ │ - sign-artifacts.sh                                  │   │
│ │ - build-installers.sh                                │   │
│ │ - run-tests.sh                                       │   │
│ └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Standard Interface Contract

### Input Contract
Each stage script expects:
1. **Configuration file**: `pipeline-config.json` in current directory
2. **Input artifacts**: In standard locations (e.g., `workspace/target/`)
3. **Environment variables**: Standard set defined below

### Output Contract
Each stage script produces:
1. **Exit code**: 0 = success, non-zero = failure
2. **Output artifacts**: In standard locations
3. **Metadata file**: `stage-metadata.json` with stage results
4. **Logs**: Written to stdout/stderr

### Standard Environment Variables

```bash
# Build identification
BUILD_NUMBER=123
BUILD_TAG=jenkins-build-123
JOB_NAME=jdk21u-linux-x64-temurin

# Workspace paths
WORKSPACE=/path/to/workspace
ARTIFACT_DIR=${WORKSPACE}/artifacts
INPUT_DIR=${WORKSPACE}/inputs
OUTPUT_DIR=${WORKSPACE}/outputs

# Configuration
CONFIG_FILE=${WORKSPACE}/pipeline-config.json
```

## Example: Build Stage

### Jenkins Implementation (Layer 1)
```groovy
stage('Build') {
    agent { label getNodeLabel() }
    steps {
        script {
            // Retrieve configuration
            copyArtifacts(filter: 'pipeline-config.json')

            // Set up environment
            env.WORKSPACE = pwd()
            env.CONFIG_FILE = "${env.WORKSPACE}/pipeline-config.json"

            // Execute CI-agnostic script
            sh './scripts/stages/02-build.sh'

            // Archive outputs (CI-specific)
            archiveArtifacts artifacts: 'outputs/**/*,stage-metadata.json'
        }
    }
}
```

### GitLab CI Implementation (Layer 1)
```yaml
build:
  stage: build
  script:
    # Set up environment
    - export WORKSPACE=$CI_PROJECT_DIR
    - export CONFIG_FILE=$WORKSPACE/pipeline-config.json

    # Execute CI-agnostic script
    - ./scripts/stages/02-build.sh

  artifacts:
    paths:
      - outputs/
      - stage-metadata.json
```

### GitHub Actions Implementation (Layer 1)
```yaml
- name: Build
  env:
    WORKSPACE: ${{ github.workspace }}
    CONFIG_FILE: ${{ github.workspace }}/pipeline-config.json
  run: |
    ./scripts/stages/02-build.sh

- name: Archive artifacts
  uses: actions/upload-artifact@v3
  with:
    name: build-outputs
    path: |
      outputs/
      stage-metadata.json
```

### Shell Script Implementation (Layer 2 - CI-Agnostic)
```bash
#!/bin/bash
# scripts/stages/02-build.sh
# CI-agnostic build stage implementation

set -euo pipefail

echo "=== Build Stage ==="

# Load configuration
if [[ ! -f "${CONFIG_FILE}" ]]; then
    echo "ERROR: Configuration file not found: ${CONFIG_FILE}"
    exit 1
fi

# Parse configuration using jq
JAVA_TO_BUILD=$(jq -r '.buildConfig.JAVA_TO_BUILD' "${CONFIG_FILE}")
TARGET_OS=$(jq -r '.buildConfig.TARGET_OS' "${CONFIG_FILE}")
ARCHITECTURE=$(jq -r '.buildConfig.ARCHITECTURE' "${CONFIG_FILE}")
VARIANT=$(jq -r '.buildConfig.VARIANT' "${CONFIG_FILE}")

echo "Building: ${JAVA_TO_BUILD} ${ARCHITECTURE} ${TARGET_OS} ${VARIANT}"

# Create output directory
mkdir -p "${OUTPUT_DIR}"

# Execute build (calls existing build tools)
bash ./make-adopt-build-farm.sh

# Copy outputs to standard location
cp -r workspace/target/* "${OUTPUT_DIR}/"

# Create stage metadata
cat > stage-metadata.json <<EOF
{
  "stage": "build",
  "status": "success",
  "timestamp": $(date +%s),
  "version": "$(cat workspace/target/metadata/version.txt)",
  "artifacts": [
    "$(ls ${OUTPUT_DIR}/*.tar.gz | xargs basename)"
  ]
}
EOF

echo "=== Build Complete ==="
exit 0
```

## Directory Structure

### ci-adoptium-pipelines Repository (Pipeline Code)
```
ci-adoptium-pipelines/
├── ci/                                          # CI-specific orchestration
│   ├── jenkins/
│   │   ├── Jenkinsfile.declarative             # Jenkins declarative pipeline
│   │   ├── TEST_BUILD_UID.Jenkinsfile          # Test build UID pipeline
│   │   └── README.md
│   ├── local/
│   │   ├── run-pipeline.py                     # Local pipeline runner
│   │   ├── workspace_manager.py                # Workspace management module
│   │   └── README.md
│   └── README.md
├── scripts/                                     # CI-agnostic stage scripts
│   ├── stages/
│   │   ├── 01-initialize.sh                    # Generate configuration
│   │   ├── 02-build.sh                         # Build OpenJDK
│   │   ├── 02-build.groovy                     # (Legacy Groovy version)
│   │   ├── 03-internal-sign.groovy             # (Legacy Groovy version)
│   │   ├── 06-sign.sh                          # Sign artifacts
│   │   ├── 07-installer.sh                     # Build installers
│   │   ├── 13-smoke-tests.sh                   # Run smoke tests
│   │   ├── 13-smoke-tests.groovy               # (Legacy Groovy version)
│   │   └── 20-reproducible-compare.sh          # Reproducible build comparison
│   └── lib/                                     # Shared utilities
│       ├── artifact-utils.sh                    # Artifact management
│       ├── config-utils.sh                      # Configuration utilities
│       ├── load-json-config.py                  # JSON config loader
│       ├── logging-utils.sh                     # Logging utilities
│       └── workspace-cleanup.sh                 # Workspace cleanup
├── docs/                                        # Documentation
│   ├── CI_AGNOSTIC_ARCHITECTURE.md             # This file
│   ├── JENKINS_CLEANUP_REFACTORING.md
│   ├── LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md
│   ├── RESTARTABILITY_GUIDE.md
│   └── (other documentation files)
├── tools/                                       # Helper tools
│   └── convert-groovy-config-to-json.sh
├── CONTRIBUTING.md
└── README.md
```

### ci-temurin-config Repository (Vendor Configurations - Separate Repo)
```
ci-temurin-config/
├── configurations/                              # JSON configuration files
│   ├── jdk8u_pipeline_config.json
│   ├── jdk11u_pipeline_config.json
│   ├── jdk17u_pipeline_config.json
│   ├── jdk21u_pipeline_config.json
│   ├── jdk22u_pipeline_config.json
│   ├── jdk23u_pipeline_config.json
│   └── jdk_pipeline_config.json
├── .gitignore
└── README.md
```

**Note**: The configuration repository is cloned at runtime by the pipeline. This separation allows:
- Pipeline code (ci-adoptium-pipelines) to be vendor-agnostic
- Vendor-specific configurations (ci-temurin-config) to be maintained separately
- Different vendors to maintain their own configuration repositories

## Shell Script Template

```bash
#!/bin/bash
# scripts/stages/XX-stage-name.sh
# CI-agnostic implementation of [Stage Name]

set -euo pipefail

# Source shared utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../lib/logging-utils.sh"
source "${SCRIPT_DIR}/../lib/config-utils.sh"
source "${SCRIPT_DIR}/../lib/artifact-utils.sh"

# Stage name for logging
STAGE_NAME="Stage Name"

log_info "=== ${STAGE_NAME} Stage ==="

# Validate environment
validate_environment() {
    require_env "WORKSPACE"
    require_env "CONFIG_FILE"
    require_file "${CONFIG_FILE}"
}

# Main stage logic
main() {
    validate_environment

    # Load configuration
    local config=$(load_config "${CONFIG_FILE}")

    # Get required values
    local java_version=$(get_config_value "${config}" ".buildConfig.JAVA_TO_BUILD")

    log_info "Processing ${java_version}"

    # Create output directory
    mkdir -p "${OUTPUT_DIR:-${WORKSPACE}/outputs}"

    # Do the actual work
    perform_stage_work

    # Create stage metadata
    create_stage_metadata "success"

    log_info "=== ${STAGE_NAME} Complete ==="
}

# Execute main function
main "$@"
exit $?
```

## Shared Utilities

### logging-utils.sh
```bash
#!/bin/bash
# Logging utilities

log_info() {
    echo "[INFO] $(date '+%Y-%m-%d %H:%M:%S') - $*"
}

log_error() {
    echo "[ERROR] $(date '+%Y-%m-%d %H:%M:%S') - $*" >&2
}

log_warn() {
    echo "[WARN] $(date '+%Y-%m-%d %H:%M:%S') - $*"
}
```

### config-utils.sh
```bash
#!/bin/bash
# Configuration utilities

require_env() {
    local var_name=$1
    if [[ -z "${!var_name:-}" ]]; then
        log_error "Required environment variable not set: ${var_name}"
        exit 1
    fi
}

require_file() {
    local file_path=$1
    if [[ ! -f "${file_path}" ]]; then
        log_error "Required file not found: ${file_path}"
        exit 1
    fi
}

load_config() {
    local config_file=$1
    cat "${config_file}"
}

get_config_value() {
    local config=$1
    local json_path=$2
    echo "${config}" | jq -r "${json_path}"
}
```

### artifact-utils.sh
```bash
#!/bin/bash
# Artifact management utilities

create_stage_metadata() {
    local status=$1
    local metadata_file="${WORKSPACE}/stage-metadata.json"

    cat > "${metadata_file}" <<EOF
{
  "stage": "${STAGE_NAME}",
  "status": "${status}",
  "timestamp": $(date +%s),
  "build_number": "${BUILD_NUMBER:-unknown}",
  "workspace": "${WORKSPACE}"
}
EOF

    log_info "Created stage metadata: ${metadata_file}"
}

copy_artifacts() {
    local source=$1
    local dest=$2

    log_info "Copying artifacts from ${source} to ${dest}"
    mkdir -p "${dest}"
    cp -r "${source}"/* "${dest}/"
}
```

## Benefits of This Architecture

### 1. **CI Portability**
- Shell scripts work on any CI system
- Only orchestration layer needs rewriting for new CI
- Core logic remains unchanged

### 2. **Local Testing**
- Can run stages locally without CI system
- Easier debugging and development
- Faster iteration

### 3. **Maintainability**
- Clear separation of concerns
- Shell scripts are simpler than Groovy
- Standard Unix tools (jq, bash)

### 4. **Consistency**
- Same scripts run in any CI
- Reduces CI-specific bugs
- Easier to validate behavior

### 5. **Future-Proof**
- Not locked into Jenkins
- Can evaluate other CI systems
- Migration path is clear

## Migration Strategy

### Phase 1: Create Shell Scripts
1. Convert existing Groovy stage scripts to shell scripts
2. Add shared utility libraries
3. Test locally

### Phase 2: Update Jenkins Pipeline
1. Modify Jenkinsfile to call shell scripts
2. Keep artifact management in Jenkins
3. Test in parallel with old pipeline

### Phase 3: Add Alternative CI
1. Create GitLab CI or GitHub Actions config
2. Use same shell scripts
3. Validate outputs match Jenkins

### Phase 4: Deprecate Old Pipeline
1. Switch all builds to new pipeline
2. Archive old scripted pipeline
3. Document new architecture

## Testing Strategy

### Local Testing
```bash
# Set up environment
export WORKSPACE=/tmp/test-build
export CONFIG_FILE=${WORKSPACE}/pipeline-config.json
export BUILD_NUMBER=test-123

# Create test configuration
cat > ${CONFIG_FILE} <<EOF
{
  "buildConfig": {
    "JAVA_TO_BUILD": "jdk21u",
    "TARGET_OS": "linux",
    "ARCHITECTURE": "x64",
    "VARIANT": "temurin"
  }
}
EOF

# Run stage script
./scripts/stages/02-build.sh

# Verify outputs
ls -la ${WORKSPACE}/outputs/
cat ${WORKSPACE}/stage-metadata.json
```

### CI Testing
- Run in Jenkins with new architecture
- Run in GitLab CI (if available)
- Compare outputs and timing
- Validate artifact integrity

## Implementation Checklist

- [ ] Create shared utility scripts (logging, config, artifacts)
- [ ] Convert build stage to shell script
- [ ] Convert signing stages to shell scripts
- [ ] Convert test stages to shell scripts
- [ ] Update Jenkins pipeline to call shell scripts
- [ ] Test locally
- [ ] Test in Jenkins
- [ ] Create GitLab CI example
- [ ] Create GitHub Actions example
- [ ] Document migration guide
- [ ] Update team on new architecture

## Summary

**Key Principle**: Separate CI orchestration from build logic

- **CI Layer**: Manages stages, artifacts, conditions (CI-specific)
- **Script Layer**: Does the actual work (CI-agnostic shell scripts)
- **Tool Layer**: Existing build tools (already portable)

This architecture ensures the OpenJDK build pipeline can run on any CI/CD system with minimal changes.