#!/bin/bash
# CI-agnostic Build Stage Implementation
#
# This script replicates the build stage from openjdk_build_pipeline.groovy
# It calls build-farm/make-adopt-build-farm.sh exactly as the Jenkins pipeline does.
# This ensures reliable, reproducible builds without relying on caches.
#
# Prerequisites:
#   - temurin-build repository cloned in workspace
#   - Required build tools installed (make, gcc, etc.)
#   - Boot JDK installed
#
# Required Environment Variables:
#   WORKSPACE     - Root workspace directory
#   CONFIG_FILE   - Path to pipeline-config.json
#   TARGET_DIR    - Directory for build artifacts (reads/writes here)
#   BUILD_NUMBER  - Build number (optional, defaults to 'local')
#
# Outputs:
#   ${TARGET_DIR}/**/*           - Built JDK artifacts
#   stage-metadata.json          - Stage execution metadata

set -euo pipefail

# Source shared utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../lib/logging-utils.sh"
source "${SCRIPT_DIR}/../lib/config-utils.sh"
source "${SCRIPT_DIR}/../lib/artifact-utils.sh"

# Stage configuration
STAGE_NAME="build"
BUILD_NUMBER="${BUILD_NUMBER:-local}"

# Main execution
main() {
    log_section "Build Stage - Start"
    
    # Validate environment
    validate_standard_environment
    
    # Load configuration
    log_info "Loading configuration from ${CONFIG_FILE}"
    
    # Extract build configuration (pass file path directly)
    local java_to_build=$(get_config_value "${CONFIG_FILE}" ".buildConfig.JAVA_TO_BUILD")
    local target_os=$(get_config_value "${CONFIG_FILE}" ".buildConfig.TARGET_OS")
    local architecture=$(get_config_value "${CONFIG_FILE}" ".buildConfig.ARCHITECTURE")
    local variant=$(get_config_value "${CONFIG_FILE}" ".buildConfig.VARIANT")
    local build_args=$(get_config_value "${CONFIG_FILE}" ".buildConfig.BUILD_ARGS" "")
    local configure_args=$(get_config_value "${CONFIG_FILE}" ".buildConfig.CONFIGURE_ARGS" "")
    local scm_ref=$(get_config_value "${CONFIG_FILE}" ".refs.scmRef" "master")
    local build_ref=$(get_config_value "${CONFIG_FILE}" ".refs.buildRef" "master")
    local build_repo_url=$(get_config_value "${CONFIG_FILE}" ".refs.buildRepoUrl" "https://github.com/adoptium/temurin-build.git")
    local clean_workspace=$(get_config_bool "${CONFIG_FILE}" ".parameters.cleanWorkspace" "false")
    local ea_beta_build=$(get_config_bool "${CONFIG_FILE}" ".parameters.eaBetaBuild" "false")
    
    # If EA/Beta build is enabled, append --with-version-opt=ea to configure args
    if [[ "${ea_beta_build}" == "true" ]]; then
        if [[ -n "${configure_args}" ]]; then
            configure_args="${configure_args} --with-version-opt=ea"
        else
            configure_args="--with-version-opt=ea"
        fi
        log_info "EA/Beta build enabled - added --with-version-opt=ea to configure args"
    fi
    
    log_info "Build Configuration:"
    log_info "  Java Version: ${java_to_build}"
    log_info "  Target OS: ${target_os}"
    log_info "  Architecture: ${architecture}"
    log_info "  Variant: ${variant}"
    log_info "  SCM Ref: ${scm_ref}"
    log_info "  Build Repo URL: ${build_repo_url}"
    log_info "  Build Ref: ${build_ref}"
    log_info "  Build Args: ${build_args}"
    log_info "  Configure Args: ${configure_args}"
    log_info "  Clean Workspace: ${clean_workspace}"
    log_info "  EA/Beta Build: ${ea_beta_build}"
    
    # Setup build environment
    setup_build_environment
    
    # Clone temurin-build repository
    setup_temurin_build "${build_repo_url}" "${build_ref}"
    
    # Prepare workspace
    prepare_workspace "${clean_workspace}"
    
    # Prepare target directory
    prepare_output_dir "${TARGET_DIR}"
    
    # Execute build using build-farm/make-adopt-build-farm.sh
    execute_build "${java_to_build}" "${target_os}" "${architecture}" "${variant}" "${build_args}" "${scm_ref}" "${configure_args}"
    
    # Extract and save metadata
    extract_build_metadata
    
    # Copy outputs to standard location
    organize_build_outputs
    
    # Create checksums
    create_checksums "${TARGET_DIR}"
    
    # Create stage metadata
    create_stage_metadata "${STAGE_NAME}" "success"
    
    # List final artifacts
    list_artifacts "${TARGET_DIR}"
    
    log_section "Build Stage - Complete"
}

# Setup build environment
setup_build_environment() {
    log_info "Setting up build environment"
    
    # Detect OS and architecture
    local detected_os=$(uname -s | tr '[:upper:]' '[:lower:]')
    local detected_arch=$(uname -m)
    
    log_info "Detected OS: ${detected_os}"
    log_info "Detected Architecture: ${detected_arch}"
    
    # Check for required tools
    local required_tools=("git" "make" "bash")
    for tool in "${required_tools[@]}"; do
        if ! command -v "${tool}" &> /dev/null; then
            log_error "Required tool not found: ${tool}"
            exit 1
        fi
        log_debug "Found tool: ${tool}"
    done
    
    # Check for Java (boot JDK)
    if ! command -v java &> /dev/null; then
        log_error "Boot JDK not found in PATH - required for building OpenJDK"
        log_error "Install a boot JDK (N-1 version of what you're building)"
        exit 1
    else
        local java_version=$(java -version 2>&1 | head -n 1)
        log_info "Boot JDK: ${java_version}"
        
        # Set JAVA_HOME if not set
        if [[ -z "${JAVA_HOME:-}" ]]; then
            if [[ "$(uname -s)" == "Darwin" ]]; then
                export JAVA_HOME=$(/usr/libexec/java_home)
                log_info "Set JAVA_HOME to: ${JAVA_HOME}"
            else
                log_warn "JAVA_HOME not set - build may fail"
            fi
        fi
    fi
    
    log_info "Build environment ready"
}

# Clone temurin-build repository (always fresh clone)
setup_temurin_build() {
    local build_repo_url=$1
    local build_ref=$2
    local build_repo_dir="${WORKSPACE}/temurin-build"
    
    log_section "Setting up temurin-build repository"
    log_info "Repository URL: ${build_repo_url}"
    log_info "Branch/Ref: ${build_ref}"
    
    # Remove existing directory if present (ensures clean state)
    if [[ -d "${build_repo_dir}" ]]; then
        log_info "Removing existing repository directory..."
        rm -rf "${build_repo_dir}"
    fi
    
    # Clone repository
    log_info "Cloning repository..."
    if git clone --branch "${build_ref}" "${build_repo_url}" "${build_repo_dir}"; then
        log_info "Repository cloned successfully"
    else
        log_error "Failed to clone repository from ${build_repo_url}"
        log_error "Branch/ref: ${build_ref}"
        exit 1
    fi
    
    # Verify build script exists
    local build_script="${build_repo_dir}/build-farm/make-adopt-build-farm.sh"
    if [[ ! -f "${build_script}" ]]; then
        log_error "Build script not found: ${build_script}"
        log_error "Expected: build-farm/make-adopt-build-farm.sh"
        exit 1
    fi
    
    log_info "temurin-build repository ready at: ${build_repo_dir}"
    log_info "Build script: ${build_script}"
}

# Prepare workspace for build
prepare_workspace() {
    local clean=$1
    
    if [[ "${clean}" == "true" ]]; then
        log_info "Cleaning workspace build directories"
        
        # Clean build output directories but preserve temurin-build repo
        rm -rf "${WORKSPACE}/workspace/build" || true
        rm -rf "${WORKSPACE}/workspace/target" || true
        
        log_info "Workspace cleaned"
    else
        log_info "Skipping workspace clean"
    fi
    
    # Always clean any previous openjdk build directory
    local openjdk_build_dir="${WORKSPACE}/workspace/build/src/build"
    if [[ -d "${openjdk_build_dir}" ]]; then
        log_info "Removing previous openjdk build directory: ${openjdk_build_dir}"
        rm -rf "${openjdk_build_dir}"
    fi
}

# Execute the actual JDK build using build-farm/make-adopt-build-farm.sh
execute_build() {
    local java_version=$1
    local os=$2
    local arch=$3
    local variant=$4
    local build_args=$5
    local scm_ref=$6
    local configure_args=$7
    
    log_section "Executing JDK Build"
    
    local build_repo_dir="${WORKSPACE}/temurin-build"
    local build_script="build-farm/make-adopt-build-farm.sh"
    
    log_info "Build parameters:"
    log_info "  Script: ${build_script}"
    log_info "  Java Version: ${java_version}"
    log_info "  OS: ${os}"
    log_info "  Architecture: ${arch}"
    log_info "  Variant: ${variant}"
    log_info "  Build Args: ${build_args}"
    log_info "  Configure Args: ${configure_args}"
    log_info "  SCM Ref: ${scm_ref}"
    
    # Set build environment variables (matching Jenkins pipeline)
    export JAVA_TO_BUILD="${java_version}"
    export TARGET_OS="${os}"
    export ARCHITECTURE="${arch}"
    export VARIANT="${variant}"
    export BUILD_ARGS="${build_args}"
    export WORKSPACE="${WORKSPACE}"
    export BUILD_NUMBER="${BUILD_NUMBER}"
    export SCM_REF="${scm_ref}"
    export CONFIGURE_ARGS="${configure_args}"
    
    # Additional environment variables
    export FILENAME="${FILENAME:-}"
    export OVERRIDE_FILE_NAME_VERSION="${OVERRIDE_FILE_NAME_VERSION:-}"
    
    log_info "Environment variables set:"
    log_debug "  JAVA_TO_BUILD=${JAVA_TO_BUILD}"
    log_debug "  TARGET_OS=${TARGET_OS}"
    log_debug "  ARCHITECTURE=${ARCHITECTURE}"
    log_debug "  VARIANT=${VARIANT}"
    log_debug "  BUILD_ARGS=${BUILD_ARGS}"
    log_debug "  CONFIGURE_ARGS=${CONFIGURE_ARGS}"
    log_debug "  WORKSPACE=${WORKSPACE}"
    log_debug "  BUILD_NUMBER=${BUILD_NUMBER}"
    log_debug "  SCM_REF=${SCM_REF}"
    log_debug "  JAVA_HOME=${JAVA_HOME:-not set}"
    
    log_info "Starting build at $(date)"
    log_info "Build command: bash ${build_script}"
    
    # Change to temurin-build directory
    cd "${build_repo_dir}"
    
    # Execute build script directly (timeout removed to allow stdin)
    # Note: Jenkins uses a 12-hour timeout, but we run without timeout to allow interactive input
    log_info "Starting build (no timeout - will run until completion)"
    
    if bash "${build_script}"; then
        log_info "Build completed successfully at $(date)"
    else
        local exit_code=$?
        log_error "Build failed with exit code: ${exit_code}"
        cd "${WORKSPACE}"
        exit 1
    fi
    
    cd "${WORKSPACE}"
    
    log_info "Build execution complete"
}

# Extract build metadata
extract_build_metadata() {
    log_info "Extracting build metadata"
    
    local build_repo_dir="${WORKSPACE}/temurin-build"
    local target_dir="${build_repo_dir}/workspace/target"
    
    # Look for version information
    local version="unknown"
    local version_file=""
    
    # Try different possible locations for version/release file
    for possible_file in \
        "${target_dir}/metadata/version.txt" \
        "${target_dir}/version.txt" \
        "${WORKSPACE}/workspace/build/src/build/"*/images/jdk/release; do
        
        if [[ -f "${possible_file}" ]]; then
            version_file="${possible_file}"
            break
        fi
    done
    
    if [[ -n "${version_file}" ]]; then
        if [[ "${version_file}" == */release ]]; then
            # Extract version from release file
            version=$(grep "JAVA_VERSION=" "${version_file}" | cut -d'"' -f2 || echo "unknown")
        else
            version=$(cat "${version_file}" || echo "unknown")
        fi
        log_info "Build version: ${version}"
    else
        log_warn "Version file not found"
    fi
    
    # Create build metadata JSON
    cat > "${WORKSPACE}/build-metadata.json" <<EOF
{
  "version": "${version}",
  "buildNumber": "${BUILD_NUMBER}",
  "timestamp": $(date +%s),
  "timestampISO": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "stage": "${STAGE_NAME}",
  "workspace": "${WORKSPACE}",
  "javaVersion": "$(get_config_value "${CONFIG_FILE}" ".buildConfig.JAVA_TO_BUILD")",
  "targetOS": "$(get_config_value "${CONFIG_FILE}" ".buildConfig.TARGET_OS")",
  "architecture": "$(get_config_value "${CONFIG_FILE}" ".buildConfig.ARCHITECTURE")",
  "variant": "$(get_config_value "${CONFIG_FILE}" ".buildConfig.VARIANT")"
}
EOF
    
    log_info "Build metadata saved to build-metadata.json"
    
    # Display metadata
    if command -v jq &> /dev/null; then
        log_debug "Build metadata:"
        cat "${WORKSPACE}/build-metadata.json" | jq . || true
    fi
}

# Organize build outputs into standard structure
organize_build_outputs() {
    log_info "Organizing build outputs"
    
    local build_repo_dir="${WORKSPACE}/temurin-build"
    local target_dir="${build_repo_dir}/workspace/target"
    
    if [[ ! -d "${target_dir}" ]]; then
        log_error "Target directory not found: ${target_dir}"
        exit 1
    fi
    
    log_info "Found build outputs in: ${target_dir}"
    
    # Find and copy JDK artifacts
    log_info "Searching for JDK artifacts..."
    local artifacts_found=0
    
    # Look for tar.gz and zip files
    while IFS= read -r -d '' artifact; do
        log_info "Found artifact: $(basename ${artifact})"
        cp "${artifact}" "${TARGET_DIR}/"
        artifacts_found=$((artifacts_found + 1))
    done < <(find "${target_dir}" -type f \( -name "*.tar.gz" -o -name "*.zip" \) -print0)
    
    if [[ ${artifacts_found} -eq 0 ]]; then
        log_error "No tar.gz or zip artifacts found in ${target_dir}"
        log_info "Directory contents:"
        ls -la "${target_dir}" || true
        exit 1
    else
        log_info "Copied ${artifacts_found} artifact(s)"
    fi
    
    # Copy metadata files
    if [[ -f "${WORKSPACE}/build-metadata.json" ]]; then
        cp "${WORKSPACE}/build-metadata.json" "${TARGET_DIR}/"
    fi
    
    # Copy any buildinfo or release files
    find "${target_dir}" -type f \( -name "buildinfo.json" -o -name "release" \) -exec cp {} "${TARGET_DIR}/" \; 2>/dev/null || true
    
    log_info "Build outputs organized in: ${TARGET_DIR}"
}

# Error handler
error_handler() {
    local line_number=$1
    log_error "Build stage failed at line ${line_number}"
    
    # Try to capture build logs
    local build_log="${WORKSPACE}/temurin-build/workspace/logs/build.log"
    if [[ -f "${build_log}" ]]; then
        log_error "Last 50 lines of build log:"
        tail -n 50 "${build_log}" || true
    fi
    
    create_stage_metadata "${STAGE_NAME}" "failed"
    exit 1
}

# Set error trap
trap 'error_handler ${LINENO}' ERR

# Execute main function
main "$@"

# Made with Bob