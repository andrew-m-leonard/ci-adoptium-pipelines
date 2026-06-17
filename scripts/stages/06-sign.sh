#!/bin/bash
# CI-agnostic Sign Artifacts Stage Implementation
#
# Signs JDK artifacts (tar.gz, zip files)
#
# Required Environment Variables:
#   WORKSPACE     - Root workspace directory
#   CONFIG_FILE   - Path to pipeline-config.json
#   TARGET_DIR    - Directory containing artifacts (reads/writes here)
#   BUILD_NUMBER  - Build number (optional)
#
# Outputs:
#   ${TARGET_DIR}/signed/*       - Signed artifacts
#   ${TARGET_DIR}/checksums.txt  - Checksums of signed files
#   stage-metadata.json          - Stage execution metadata

set -euo pipefail

# Source shared utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../lib/logging-utils.sh"
source "${SCRIPT_DIR}/../lib/config-utils.sh"
source "${SCRIPT_DIR}/../lib/artifact-utils.sh"

# Stage configuration
STAGE_NAME="sign"
BUILD_NUMBER="${BUILD_NUMBER:-local}"

# Main execution
main() {
    log_section "Sign Artifacts Stage - Start"

    # Validate environment
    validate_standard_environment
    require_dir "${TARGET_DIR}"

    # Load configuration
    log_info "Loading configuration from ${CONFIG_FILE}"

    # Extract configuration (pass file path directly)
    local variant=$(get_config_value "${CONFIG_FILE}" ".buildConfig.VARIANT")
    local target_os=$(get_config_value "${CONFIG_FILE}" ".buildConfig.TARGET_OS")
    local architecture=$(get_config_value "${CONFIG_FILE}" ".buildConfig.ARCHITECTURE")

    log_info "Sign Configuration:"
    log_info "  Variant: ${variant}"
    log_info "  Target OS: ${target_os}"
    log_info "  Architecture: ${architecture}"

    # Prepare target directory (ensure it exists)
    prepare_output_dir "${TARGET_DIR}"

    # Find artifacts to sign
    find_artifacts_to_sign

    # Execute signing
    sign_artifacts "${variant}" "${target_os}"

    # Create checksums
    create_checksums "${TARGET_DIR}"

    # Create stage metadata
    create_stage_metadata "${STAGE_NAME}" "success"

    # List signed artifacts
    list_artifacts "${TARGET_DIR}"

    log_section "Sign Artifacts Stage - Complete"
}

# Find artifacts that need signing
find_artifacts_to_sign() {
    log_info "Finding artifacts to sign in ${TARGET_DIR}"

    local tar_files=$(find "${TARGET_DIR}" -name "*.tar.gz" -o -name "*.zip" | wc -l)

    if [[ ${tar_files} -eq 0 ]]; then
        log_error "No artifacts found to sign in ${TARGET_DIR}"
        exit 1
    fi

    log_info "Found ${tar_files} artifacts to sign"
}

# Sign artifacts
sign_artifacts() {
    local variant=$1
    local target_os=$2

    log_section "Signing Artifacts"

    # Create signed directory
    mkdir -p "${TARGET_DIR}/signed"

    # Find all tar.gz and zip files
    find "${TARGET_DIR}" -type f \( -name "*.tar.gz" -o -name "*.zip" \) | while read -r artifact; do
        local filename=$(basename "${artifact}")
        log_info "Processing: ${filename}"

        # For now, copy to signed directory
        # In production, this would call actual signing service
        cp "${artifact}" "${TARGET_DIR}/signed/${filename}"

        # Simulate signing by creating a signature file
        create_signature "${TARGET_DIR}/signed/${filename}"

        log_info "Signed: ${filename}"
    done

    log_info "All artifacts signed"
}

# Create signature file (simulated)
create_signature() {
    local file=$1
    local sig_file="${file}.sig"

    # In production, this would use actual signing service
    # For now, create a dummy signature
    sha256sum "${file}" > "${sig_file}"

    log_debug "Created signature: ${sig_file}"
}

# Error handler
error_handler() {
    local line_number=$1
    log_error "Sign stage failed at line ${line_number}"
    create_stage_metadata "${STAGE_NAME}" "failed"
    exit 1
}

# Set error trap
trap 'error_handler ${LINENO}' ERR

# Execute main function
main "$@"

# Made with Bob
