#!/bin/bash
# Configuration utilities for CI-agnostic pipeline stages

# Source logging utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/logging-utils.sh"

# Require environment variable to be set
require_env() {
    local var_name=$1
    if [[ -z "${!var_name:-}" ]]; then
        log_error "Required environment variable not set: ${var_name}"
        exit 1
    fi
    log_debug "Environment variable ${var_name} = ${!var_name}"
}

# Require file to exist
require_file() {
    local file_path=$1
    if [[ ! -f "${file_path}" ]]; then
        log_error "Required file not found: ${file_path}"
        exit 1
    fi
    log_debug "Found required file: ${file_path}"
}

# Require directory to exist
require_dir() {
    local dir_path=$1
    if [[ ! -d "${dir_path}" ]]; then
        log_error "Required directory not found: ${dir_path}"
        exit 1
    fi
    log_debug "Found required directory: ${dir_path}"
}

# Load JSON configuration file
load_config() {
    local config_file=$1
    require_file "${config_file}"

    if ! command -v jq &> /dev/null; then
        log_error "jq is required but not installed"
        exit 1
    fi

    cat "${config_file}"
}

# Get value from JSON configuration
get_config_value() {
    local config=$1
    local json_path=$2
    local default_value=${3:-}

    # If config looks like a file path, read directly from file
    if [[ -f "${config}" ]]; then
        local value=$(jq -r "${json_path}" "${config}" 2>/dev/null)
    else
        # Otherwise treat as JSON string
        local value=$(echo "${config}" | jq -r "${json_path}" 2>/dev/null)
    fi

    if [[ "${value}" == "null" ]] || [[ -z "${value}" ]]; then
        if [[ -n "${default_value}" ]]; then
            echo "${default_value}"
        else
            log_error "Configuration value not found: ${json_path}"
            exit 1
        fi
    else
        echo "${value}"
    fi
}

# Get boolean value from JSON configuration
get_config_bool() {
    local config=$1
    local json_path=$2
    local default_value=${3:-false}

    # If config looks like a file path, read directly from file
    if [[ -f "${config}" ]]; then
        local value=$(jq -r "${json_path}" "${config}" 2>/dev/null)
    else
        # Otherwise treat as JSON string
        local value=$(echo "${config}" | jq -r "${json_path}" 2>/dev/null)
    fi

    if [[ "${value}" == "true" ]]; then
        echo "true"
    elif [[ "${value}" == "false" ]]; then
        echo "false"
    else
        echo "${default_value}"
    fi
}

# Validate standard environment
validate_standard_environment() {
    log_info "Validating environment..."

    require_env "WORKSPACE"
    require_env "CONFIG_FILE"
    require_file "${CONFIG_FILE}"

    # Set default directory if not set
    export TARGET_DIR="${TARGET_DIR:-${WORKSPACE}/workspace/target}"

    log_info "Environment validated successfully"
    log_debug "WORKSPACE=${WORKSPACE}"
    log_debug "CONFIG_FILE=${CONFIG_FILE}"
    log_debug "TARGET_DIR=${TARGET_DIR}"
}

# Made with Bob
