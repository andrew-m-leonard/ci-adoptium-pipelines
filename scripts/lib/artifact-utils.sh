#!/bin/bash
# Artifact management utilities for CI-agnostic pipeline stages

# Source logging utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/logging-utils.sh"

# Create stage metadata file
create_stage_metadata() {
    local stage_name=$1
    local status=$2
    local metadata_file="${WORKSPACE}/stage-metadata.json"

    log_info "Creating stage metadata for ${stage_name}"

    cat > "${metadata_file}" <<EOF
{
  "stage": "${stage_name}",
  "status": "${status}",
  "timestamp": $(date +%s),
  "build_number": "${BUILD_NUMBER:-unknown}",
  "workspace": "${WORKSPACE}",
  "hostname": "$(hostname)",
  "user": "$(whoami)"
}
EOF

    log_info "Stage metadata created: ${metadata_file}"
}

# Copy artifacts from source to destination
copy_artifacts() {
    local source=$1
    local dest=$2

    if [[ ! -d "${source}" ]]; then
        log_error "Source directory does not exist: ${source}"
        return 1
    fi

    log_info "Copying artifacts from ${source} to ${dest}"
    mkdir -p "${dest}"

    if cp -r "${source}"/* "${dest}/" 2>/dev/null; then
        log_info "Artifacts copied successfully"
        return 0
    else
        log_warn "No artifacts found in ${source} or copy failed"
        return 1
    fi
}

# Verify artifact exists
verify_artifact() {
    local artifact_path=$1

    if [[ -f "${artifact_path}" ]]; then
        log_info "Verified artifact exists: ${artifact_path}"
        local size=$(du -h "${artifact_path}" | cut -f1)
        log_info "Artifact size: ${size}"
        return 0
    else
        log_error "Artifact not found: ${artifact_path}"
        return 1
    fi
}

# List artifacts in directory
list_artifacts() {
    local dir=$1

    if [[ ! -d "${dir}" ]]; then
        log_warn "Directory does not exist: ${dir}"
        return 1
    fi

    log_info "Artifacts in ${dir}:"
    find "${dir}" -type f -exec ls -lh {} \; | while read -r line; do
        log_info "  ${line}"
    done
}

# Create checksum file for artifacts
create_checksums() {
    local artifact_dir=$1
    local checksum_file="${artifact_dir}/checksums.txt"

    log_info "Creating checksums for artifacts in ${artifact_dir}"

    pushd "${artifact_dir}" > /dev/null || return 1

    find . -type f ! -name "checksums.txt" -exec sha256sum {} \; > "${checksum_file}"

    popd > /dev/null

    log_info "Checksums created: ${checksum_file}"
    cat "${checksum_file}"
}

# Verify checksums
verify_checksums() {
    local artifact_dir=$1
    local checksum_file="${artifact_dir}/checksums.txt"

    if [[ ! -f "${checksum_file}" ]]; then
        log_error "Checksum file not found: ${checksum_file}"
        return 1
    fi

    log_info "Verifying checksums in ${artifact_dir}"

    pushd "${artifact_dir}" > /dev/null || return 1

    if sha256sum -c "${checksum_file}"; then
        log_info "All checksums verified successfully"
        popd > /dev/null
        return 0
    else
        log_error "Checksum verification failed"
        popd > /dev/null
        return 1
    fi
}

# Prepare output directory
prepare_output_dir() {
    local target_dir=${1:-${TARGET_DIR}}

    log_info "Preparing target directory: ${target_dir}"

    # Create directory if it doesn't exist
    mkdir -p "${target_dir}"

    # Clean if requested
    if [[ "${CLEAN_OUTPUT:-false}" == "true" ]]; then
        log_info "Cleaning target directory"
        rm -rf "${target_dir:?}"/*
    fi

    log_info "Target directory ready: ${target_dir}"
}

# Made with Bob
