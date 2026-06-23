#!/bin/bash
# CI-agnostic Initialize Stage Implementation
#
# This script initializes the build by:
# 1. Loading the JDK version-specific configuration
# 2. Selecting the platform configuration based on OS and architecture
# 3. Generating the BUILD_CONFIGURATION JSON for the build
#
# Required Environment Variables:
#   WORKSPACE           - Root workspace directory
#   JAVA_VERSION        - JDK version to build (e.g., jdk21u, jdk17u)
#   TARGET_OS           - Target operating system (e.g., mac, linux, windows)
#   ARCHITECTURE        - Target architecture (e.g., aarch64, x64)
#   VARIANT             - Build variant (e.g., temurin, openj9, hotspot)
#   CONFIG_DIR          - Directory containing jdkNN_pipeline_config.json files
#
# Optional Environment Variables:
#   SCM_REF             - Git reference for OpenJDK source (default: from config)
#   BUILD_REF           - Git reference for temurin-build (default: master)
#   CI_REF              - Git reference for ci-jenkins-pipelines (default: master)
#   HELPER_REF          - Git reference for helper scripts (default: master)
#   RELEASE             - Whether this is a release build (default: false)
#   WEEKLY              - Whether this is a weekly build (default: false)
#
# Outputs:
#   ${WORKSPACE}/BUILD_CONFIGURATION.json - Generated build configuration
#   ${WORKSPACE}/stage-metadata.json      - Stage execution metadata

set -euo pipefail

# Source shared utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../lib/logging-utils.sh"
source "${SCRIPT_DIR}/../lib/config-utils.sh"
source "${SCRIPT_DIR}/../lib/artifact-utils.sh"

# Stage configuration
STAGE_NAME="initialize"
BUILD_NUMBER="${BUILD_NUMBER:-local}"

# Main execution
main() {
    log_section "Initialize Stage - Start"

    # Validate required environment variables
    require_env "WORKSPACE"
    require_env "JAVA_VERSION"
    require_env "TARGET_OS"
    require_env "ARCHITECTURE"
    require_env "VARIANT"

    # Set defaults for optional variables
    export CONFIG_DIR="${CONFIG_DIR:-${WORKSPACE}/configurations}"
    export SCM_REF="${SCM_REF:-}"
    export BUILD_REF="${BUILD_REF:-master}"
    export CI_REF="${CI_REF:-master}"
    export HELPER_REF="${HELPER_REF:-master}"
    export AQA_REF="${AQA_REF:-}"
    export RELEASE="${RELEASE:-false}"
    export WEEKLY="${WEEKLY:-false}"

    log_info "Initialize Configuration:"
    log_info "  Java Version: ${JAVA_VERSION}"
    log_info "  Target OS: ${TARGET_OS}"
    log_info "  Architecture: ${ARCHITECTURE}"
    log_info "  Variant: ${VARIANT}"
    log_info "  Config Directory: ${CONFIG_DIR}"
    log_info "  Release: ${RELEASE}"
    log_info "  Weekly: ${WEEKLY}"

    # Load version-specific configuration
    local config_file=$(find_config_file "${JAVA_VERSION}")
    log_info "Using configuration file: ${config_file}"

    # Find platform configuration key
    local platform_key=$(get_platform_key "${TARGET_OS}" "${ARCHITECTURE}")
    log_info "Platform key: ${platform_key}"

    # Generate BUILD_CONFIGURATION
    generate_build_configuration "${config_file}" "${platform_key}"

    # Create stage metadata
    create_stage_metadata "${STAGE_NAME}" "success"

    log_section "Initialize Stage - Complete"
}

# Find the configuration file for the given Java version
find_config_file() {
    local java_version=$1
    local config_file="${CONFIG_DIR}/${java_version}_pipeline_config.json"

    if [[ ! -f "${config_file}" ]]; then
        log_error "Configuration file not found: ${config_file}"
        log_error "Available configurations:"
        ls -1 "${CONFIG_DIR}"/*.json 2>/dev/null || log_error "No configurations found in ${CONFIG_DIR}"
        exit 1
    fi

    echo "${config_file}"
}

# Get the platform configuration key based on OS and architecture
get_platform_key() {
    local os=$1
    local arch=$2

    # Normalize architecture name
    local arch_normalized="${arch}"
    case "${arch}" in
        aarch64|arm64)
            arch_normalized="aarch64"
            ;;
        x64|x86_64|amd64)
            arch_normalized="x64"
            ;;
        x86|x32)
            arch_normalized="x32"
            ;;
    esac

    # Normalize OS name
    local os_normalized="${os}"
    case "${os}" in
        mac|macos|darwin)
            os_normalized="Mac"
            ;;
        linux)
            os_normalized="Linux"
            ;;
        alpine-linux)
            os_normalized="AlpineLinux"
            ;;
        windows|win)
            os_normalized="Windows"
            ;;
        aix)
            os_normalized="Aix"
            ;;
        solaris)
            os_normalized="Solaris"
            ;;
    esac

    # Construct platform key (e.g., "aarch64Mac", "x64Linux")
    echo "${arch_normalized}${os_normalized}"
}

# Generate BUILD_CONFIGURATION JSON
generate_build_configuration() {
    local config_file=$1
    local platform_key=$2

    log_section "Generating BUILD_CONFIGURATION"

    # Check if platform exists in config
    local platform_exists=$(jq -r ".buildConfigurations.${platform_key} != null" "${config_file}")
    if [[ "${platform_exists}" != "true" ]]; then
        log_error "Platform '${platform_key}' not found in configuration"
        log_error "Available platforms:"
        jq -r '.buildConfigurations | keys[]' "${config_file}"
        exit 1
    fi

    # Extract platform configuration
    local platform_config=$(jq ".buildConfigurations.${platform_key}" "${config_file}")

    # Get variant-specific values
    local build_args=$(echo "${platform_config}" | jq -r ".buildArgs.${VARIANT} // .buildArgs // \"\"")
    local configure_args=$(echo "${platform_config}" | jq -r ".configureArgs.${VARIANT} // .configureArgs // \"\"")
    local docker_file=$(echo "${platform_config}" | jq -r ".dockerFile.${VARIANT} // .dockerFile // \"\"")
    local additional_node_labels=$(echo "${platform_config}" | jq -r ".additionalNodeLabels.${VARIANT} // .additionalNodeLabels // \"\"")
    local additional_test_labels=$(echo "${platform_config}" | jq -r ".additionalTestLabels.${VARIANT} // \"\"")

    # Get test list based on build type
    local test_list
    if [[ "${WEEKLY}" == "true" ]]; then
        test_list=$(echo "${platform_config}" | jq -c '.test.weekly // []')
    elif [[ "${RELEASE}" == "true" ]]; then
        test_list=$(echo "${platform_config}" | jq -c '.test.release // .test.weekly // []')
    else
        test_list=$(echo "${platform_config}" | jq -c '.test.nightly // .test.weekly // []')
    fi

    # Get other platform values
    local os=$(echo "${platform_config}" | jq -r '.os')
    local arch=$(echo "${platform_config}" | jq -r '.arch')
    local docker_image=$(echo "${platform_config}" | jq -r '.dockerImage // ""')
    local docker_registry=$(echo "${platform_config}" | jq -r '.dockerRegistry // ""')
    local docker_credential=$(echo "${platform_config}" | jq -r '.dockerCredential // ""')
    local docker_args=$(echo "${platform_config}" | jq -r '.dockerArgs // ""')
    local clean_workspace_after=$(echo "${platform_config}" | jq -r '.cleanWorkspaceAfterBuild // false')

    # Construct node label
    local node_label="build&&${os}&&${arch}"
    if [[ -n "${additional_node_labels}" ]]; then
        node_label="${additional_node_labels}&&${node_label}"
    fi

    # Determine SCM_REF if not provided
    if [[ -z "${SCM_REF}" ]]; then
        if [[ "${RELEASE}" == "true" ]]; then
            # For releases, SCM_REF should be provided externally
            SCM_REF="master"
            log_warn "SCM_REF not provided for release build, using: ${SCM_REF}"
        else
            SCM_REF="master"
        fi
    fi

    # Generate BUILD_CONFIGURATION JSON
    cat > "${WORKSPACE}/BUILD_CONFIGURATION.json" <<EOF
{
  "ARCHITECTURE": "${arch}",
  "TARGET_OS": "${os}",
  "VARIANT": "${VARIANT}",
  "JAVA_TO_BUILD": "${JAVA_VERSION}",
  "TEST_LIST": ${test_list},
  "SCM_REF": "${SCM_REF}",
  "BUILD_REF": "${BUILD_REF}",
  "CI_REF": "${CI_REF}",
  "HELPER_REF": "${HELPER_REF}",
  "AQA_REF": "${AQA_REF}",
  "AQA_AUTO_GEN": true,
  "BUILD_ARGS": "${build_args}",
  "NODE_LABEL": "${node_label}",
  "ADDITIONAL_TEST_LABEL": "${additional_test_labels}",
  "KEEP_TEST_REPORTDIR": false,
  "ACTIVE_NODE_TIMEOUT": "5",
  "CODEBUILD": false,
  "DOCKER_IMAGE": "${docker_image}",
  "DOCKER_ARGS": "${docker_args}",
  "DOCKER_FILE": "${docker_file}",
  "DOCKER_NODE": "",
  "DOCKER_REGISTRY": "${docker_registry}",
  "DOCKER_CREDENTIAL": "${docker_credential}",
  "PLATFORM_CONFIG_LOCATION": "ci-jenkins-pipelines/build-farm/platform-specific-configurations",
  "CONFIGURE_ARGS": "${configure_args}",
  "OVERRIDE_FILE_NAME_VERSION": "",
  "USE_ADOPT_SHELL_SCRIPTS": true,
  "RELEASE": ${RELEASE},
  "WEEKLY": ${WEEKLY},
  "PUBLISH_NAME": "",
  "ADOPT_BUILD_NUMBER": "",
  "ENABLE_REPRODUCIBLE_COMPARE": false,
  "RUN_TESTS": true,
  "ENABLE_TESTDYNAMICPARALLEL": true,
  "ENABLE_INSTALLERS": true,
  "SIGN_ARTIFACTS": true,
  "CLEAN_WORKSPACE": false,
  "CLEAN_WORKSPACE_AFTER": ${clean_workspace_after},
  "CLEAN_WORKSPACE_BUILD_OUTPUT_ONLY_AFTER": true
}
EOF

    log_info "BUILD_CONFIGURATION generated successfully"

    # Display the configuration
    if command -v jq &> /dev/null; then
        log_info "Generated BUILD_CONFIGURATION:"
        cat "${WORKSPACE}/BUILD_CONFIGURATION.json" | jq .
    fi

    # Also create a simplified version for the build stage
    cat > "${WORKSPACE}/pipeline-config.json" <<EOF
{
  "buildConfig": {
    "JAVA_TO_BUILD": "${JAVA_VERSION}",
    "TARGET_OS": "${os}",
    "ARCHITECTURE": "${arch}",
    "VARIANT": "${VARIANT}",
    "BUILD_ARGS": "${build_args}",
    "NODE_LABEL": "${node_label}",
    "DOCKER_IMAGE": "${docker_image}",
    "DOCKER_ARGS": "${docker_args}",
    "USE_ADOPT_SHELL_SCRIPTS": true,
    "CLEAN_WORKSPACE": false,
    "RUN_TESTS": true,
    "ENABLE_INSTALLERS": true,
    "SIGN_ARTIFACTS": true,
    "SCM_REF": "${SCM_REF}",
    "CI_REF": "${CI_REF}",
    "BUILD_REF": "${BUILD_REF}",
    "HELPER_REF": "${HELPER_REF}"
  },
  "parameters": {
    "enableTests": true,
    "enableInstallers": true,
    "enableSigner": true,
    "enableTCK": false,
    "cleanWorkspace": false,
    "cleanWorkspaceAfter": ${clean_workspace_after}
  },
  "buildNumber": "${BUILD_NUMBER}",
  "buildTag": "${BUILD_NUMBER}",
  "jobName": "${JAVA_VERSION}-${os}-${arch}-${VARIANT}",
  "timestamp": $(date +%s)
}
EOF

    log_info "Simplified pipeline-config.json also created"
}

# Error handler
error_handler() {
    local line_number=$1
    log_error "Initialize stage failed at line ${line_number}"
    create_stage_metadata "${STAGE_NAME}" "failed"
    exit 1
}

# Set error trap
trap 'error_handler ${LINENO}' ERR

# Execute main function
main "$@"

# Made with Bob