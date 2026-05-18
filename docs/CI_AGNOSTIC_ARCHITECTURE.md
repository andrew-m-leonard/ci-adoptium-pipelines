# CI-Agnostic Pipeline Architecture

## Problem Statement

The current refactoring uses Jenkins Groovy scripts for stage implementation, which tightly couples the build logic to Jenkins. To support potential migration to other CI/CD systems (GitLab CI, GitHub Actions, etc.), we need a **CI-agnostic architecture**.

## Solution: Separation of Concerns

### Layer 1: CI Orchestration (CI-Specific)
- **Purpose**: Pipeline definition, stage sequencing, artifact management
- **Language**: CI-specific (Jenkinsfile, .gitlab-ci.yml, .github/workflows/*.yml)
- **Responsibility**: 
  - Define stages and their order
  - Manage artifacts (archive/retrieve)
  - Handle conditional execution
  - Provide environment variables

### Layer 2: Stage Implementation (CI-Agnostic)
- **Purpose**: Actual build/test/sign logic
- **Language**: Shell scripts (bash/sh)
- **Responsibility**:
  - Execute the actual work
  - Read inputs from standard locations
  - Write outputs to standard locations
  - Return exit codes for success/failure

### Layer 3: Build Tools (Already CI-Agnostic)
- **Purpose**: Core build functionality
- **Examples**: make-adopt-build-farm.sh, sign scripts, test runners
- **Already portable** - no changes needed

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: CI Orchestration (CI-Specific)                    │
│ ┌─────────────┐  ┌──────────────┐  ┌──────────────┐       │
│ │ Jenkinsfile │  │ .gitlab-ci   │  │ GitHub       │       │
│ │             │  │ .yml         │  │ Actions      │       │
│ └──────┬──────┘  └──────┬───────┘  └──────┬───────┘       │
└────────┼─────────────────┼──────────────────┼──────────────┘
         │                 │                  │
         │ Calls shell     │ Calls shell      │ Calls shell
         │ scripts         │ scripts          │ scripts
         ▼                 ▼                  ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: Stage Scripts (CI-Agnostic Shell Scripts)         │
│ ┌──────────────────────────────────────────────────────┐   │
│ │ scripts/stages/                                      │   │
│ │ ├── 02-build.sh                                      │   │
│ │ ├── 03-internal-sign.sh                              │   │
│ │ ├── 06-sign.sh                                       │   │
│ │ ├── 07-installer.sh                                  │   │
│ │ └── 13-smoke-tests.sh                                │   │
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

```
ci-jenkins-pipelines/
├── pipelines/
│   └── build/
│       └── common/
│           ├── Jenkinsfile.declarative          # Jenkins orchestration
│           ├── .gitlab-ci.yml                   # GitLab orchestration
│           ├── .github/
│           │   └── workflows/
│           │       └── build.yml                # GitHub Actions orchestration
│           └── scripts/
│               ├── stages/                      # CI-agnostic stage scripts
│               │   ├── 01-initialize.sh
│               │   ├── 02-build.sh
│               │   ├── 03-internal-sign.sh
│               │   ├── 04-assemble.sh
│               │   ├── 06-sign.sh
│               │   ├── 07-installer.sh
│               │   ├── 08-sign-installer.sh
│               │   ├── 09-gpg-sign.sh
│               │   ├── 10-sbom-sign.sh
│               │   ├── 11-verify-signing.sh
│               │   ├── 12-validate-sbom.sh
│               │   ├── 13-smoke-tests.sh
│               │   ├── 14-aqa-tests.sh
│               │   └── 15-tck-tests.sh
│               └── lib/                         # Shared utilities
│                   ├── artifact-utils.sh
│                   ├── config-utils.sh
│                   └── logging-utils.sh
```

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