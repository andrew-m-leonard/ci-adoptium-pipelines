#!/bin/bash
set -euo pipefail

################################################################################
# Stage 20: Reproducible Build Comparison
#
# Compares locally built JDK against production Adoptium binaries to verify
# reproducibility. Uses temurin-build/tooling/reproducible/repro_compare.sh
#
# Required Environment Variables:
#   WORKSPACE         - Jenkins/local workspace directory
#   CONFIG_FILE       - Path to pipeline-config.json
#   TARGET_DIR        - Directory containing build artifacts
#   SCM_REF           - Git tag/ref for the build (e.g., jdk-21.0.2+13)
#   RELEASE           - Boolean: true for release builds, false for EA
#
# Optional Environment Variables:
#   BUILD_REPO_URL    - temurin-build repository URL
#   BUILD_REF         - temurin-build branch/tag
################################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../lib/logging-utils.sh"
source "${SCRIPT_DIR}/../lib/config-utils.sh"

log_section "Stage 20: Reproducible Build Comparison"

# Validate standard environment (WORKSPACE, CONFIG_FILE, TARGET_DIR)
validate_standard_environment

# Validate additional required environment variables for this stage
require_env "SCM_REF"
require_env "RELEASE"

# Extract configuration values
VARIANT=$(get_config_value "${CONFIG_FILE}" ".buildConfig.VARIANT")
TARGET_OS=$(get_config_value "${CONFIG_FILE}" ".buildConfig.TARGET_OS")
ARCHITECTURE=$(get_config_value "${CONFIG_FILE}" ".buildConfig.ARCHITECTURE")
JAVA_TO_BUILD=$(get_config_value "${CONFIG_FILE}" ".buildConfig.JAVA_TO_BUILD")

log_info "Configuration:"
log_info "  Variant: ${VARIANT}"
log_info "  Target OS: ${TARGET_OS}"
log_info "  Architecture: ${ARCHITECTURE}"
log_info "  Java Version: ${JAVA_TO_BUILD}"
log_info "  SCM Ref: ${SCM_REF}"
log_info "  Release: ${RELEASE}"

# Set defaults
BUILD_REPO_URL="${BUILD_REPO_URL:-https://github.com/adoptium/temurin-build.git}"
BUILD_REF="${BUILD_REF:-master}"

# Create temporary workspace directories
COMPARE_WORKSPACE="${WORKSPACE}/reproducible-compare"
JDK_UPSTREAM="${COMPARE_WORKSPACE}/jdk-upstream"
JDK_BUILT="${COMPARE_WORKSPACE}/jdk-built"
TEMURIN_BUILD_DIR="${COMPARE_WORKSPACE}/temurin-build"

log_info "Creating comparison workspace: ${COMPARE_WORKSPACE}"
mkdir -p "${JDK_UPSTREAM}" "${JDK_BUILT}"

################################################################################
# Step 1: Clone temurin-build repository
################################################################################

log_section "Cloning temurin-build repository"

log_info "Cloning temurin-build from ${BUILD_REPO_URL} (branch: ${BUILD_REF})"
git clone --branch "${BUILD_REF}" --depth 1 "${BUILD_REPO_URL}" "${TEMURIN_BUILD_DIR}"

REPRO_COMPARE_SCRIPT="${TEMURIN_BUILD_DIR}/tooling/reproducible/repro_compare.sh"

if [ ! -f "${REPRO_COMPARE_SCRIPT}" ]; then
    log_error "repro_compare.sh not found at: ${REPRO_COMPARE_SCRIPT}"
    exit 1
fi

log_info "temurin-build cloned successfully"

################################################################################
# Step 2: Prepare API parameters
################################################################################

log_section "Preparing Adoptium API parameters"

# Remove "_adopt" suffix from SCM_REF if present
SCM_REF_FOR_API="${SCM_REF/_adopt/}"
log_info "SCM_REF_FOR_API: ${SCM_REF_FOR_API}"

# Add "-ea-beta" suffix for EA builds
if [ "${RELEASE}" = "false" ]; then
    SCM_REF_FOR_API="${SCM_REF_FOR_API}-ea-beta"
    log_info "EA build detected, using: ${SCM_REF_FOR_API}"
fi

# Map OS and ARCH to Adoptium API format
case "${TARGET_OS}" in
    mac) API_OS="mac" ;;
    linux) API_OS="linux" ;;
    windows) API_OS="windows" ;;
    aix) API_OS="aix" ;;
    *) log_error "Unsupported OS: ${TARGET_OS}"; exit 1 ;;
esac

# Map TARGET_OS to uname-style OS name for repro_compare.sh
case "${TARGET_OS}" in
    mac) REPRO_OS="Darwin" ;;
    linux) REPRO_OS="Linux" ;;
    windows) REPRO_OS="CYGWIN" ;;
    aix) REPRO_OS="AIX" ;;
    *) log_error "Unsupported OS for repro_compare: ${TARGET_OS}"; exit 1 ;;
esac

case "${ARCHITECTURE}" in
    aarch64) API_ARCH="aarch64" ;;
    x64) API_ARCH="x64" ;;
    x32) API_ARCH="x32" ;;
    ppc64) API_ARCH="ppc64" ;;
    ppc64le) API_ARCH="ppc64le" ;;
    s390x) API_ARCH="s390x" ;;
    *) log_error "Unsupported architecture: ${ARCHITECTURE}"; exit 1 ;;
esac

# Construct API URLs
API_JDK_URL="https://api.adoptium.net/v3/binary/version/${SCM_REF_FOR_API}/${API_OS}/${API_ARCH}/jdk/hotspot/normal/eclipse?project=jdk"
API_SBOM_URL="https://api.adoptium.net/v3/binary/version/${SCM_REF_FOR_API}/${API_OS}/${API_ARCH}/sbom/hotspot/normal/eclipse?project=jdk"

log_info "API URLs:"
log_info "  JDK:  ${API_JDK_URL}"
log_info "  SBOM: ${API_SBOM_URL}"

################################################################################
# Step 3: Download production binaries
################################################################################

log_section "Downloading production binaries from Adoptium API"

# Determine file extension based on OS
case "${TARGET_OS}" in
    windows) JDK_EXT="zip" ;;
    *) JDK_EXT="tar.gz" ;;
esac

UPSTREAM_JDK_FILE="${COMPARE_WORKSPACE}/upstream-jdk.${JDK_EXT}"
UPSTREAM_SBOM_FILE="${COMPARE_WORKSPACE}/upstream-sbom.json"

log_info "Downloading JDK binary..."
if ! curl -L -f -o "${UPSTREAM_JDK_FILE}" "${API_JDK_URL}"; then
    log_error "Failed to download JDK from Adoptium API"
    log_error "URL: ${API_JDK_URL}"
    log_error "This may indicate the build is not yet published or the version string is incorrect"
    exit 1
fi
log_info "JDK binary downloaded: ${UPSTREAM_JDK_FILE}"

log_info "Downloading SBOM..."
if ! curl -L -f -o "${UPSTREAM_SBOM_FILE}" "${API_SBOM_URL}"; then
    log_warn "Failed to download SBOM (may not be available for this version)"
else
    log_info "SBOM downloaded: ${UPSTREAM_SBOM_FILE}"
fi

################################################################################
# Step 4: Unpack binaries
################################################################################

log_section "Unpacking binaries for comparison"

# Unpack upstream JDK
log_info "Unpacking upstream JDK to: ${JDK_UPSTREAM}"
cd "${JDK_UPSTREAM}"
case "${JDK_EXT}" in
    tar.gz)
        tar -xzf "${UPSTREAM_JDK_FILE}"
        ;;
    zip)
        unzip -q "${UPSTREAM_JDK_FILE}"
        ;;
esac
log_info "Upstream JDK unpacked"

# Find the locally built JDK in TARGET_DIR
log_info "Finding locally built JDK in: ${TARGET_DIR}"
# Try multiple patterns: OpenJDK* (Adoptium naming) or jdk* (temurin-build naming)
BUILT_JDK_FILE=$(find "${TARGET_DIR}" -name "OpenJDK*-jdk_*.${JDK_EXT}" -o -name "OpenJDK*jdk-*.${JDK_EXT}" -o -name "jdk*-hotspot.${JDK_EXT}" -o -name "jdk*.${JDK_EXT}" | grep -v "debugimage\|testimage\|static-libs\|jre" | head -n 1)

if [ -z "${BUILT_JDK_FILE}" ]; then
    log_error "No locally built JDK found in ${TARGET_DIR}"
    log_error "Expected patterns: OpenJDK*-jdk_*.${JDK_EXT}, OpenJDK*jdk-*.${JDK_EXT}, jdk*-hotspot.${JDK_EXT}, or jdk*.${JDK_EXT}"
    log_error "Files found in ${TARGET_DIR}:"
    ls -la "${TARGET_DIR}"
    exit 1
fi

log_info "Found locally built JDK: ${BUILT_JDK_FILE}"

# Unpack locally built JDK
log_info "Unpacking locally built JDK to: ${JDK_BUILT}"
cd "${JDK_BUILT}"
case "${JDK_EXT}" in
    tar.gz)
        tar -xzf "${BUILT_JDK_FILE}"
        ;;
    zip)
        unzip -q "${BUILT_JDK_FILE}"
        ;;
esac
log_info "Locally built JDK unpacked"

################################################################################
# Step 5: Run reproducible comparison
################################################################################

log_section "Running reproducible build comparison"

# Find the actual JDK directories (they may be nested)
log_info "Searching for JDK directories..."
UPSTREAM_JDK_DIR=$(find "${JDK_UPSTREAM}" -mindepth 1 -maxdepth 2 -type d \( -name "jdk-*" -o -name "jdk*" \) | head -n 1)
BUILT_JDK_DIR=$(find "${JDK_BUILT}" -mindepth 1 -maxdepth 2 -type d \( -name "jdk-*" -o -name "jdk*" \) | head -n 1)

log_debug "Found upstream JDK dir: ${UPSTREAM_JDK_DIR}"
log_debug "Found built JDK dir: ${BUILT_JDK_DIR}"

if [ -z "${UPSTREAM_JDK_DIR}" ]; then
    log_error "Could not find unpacked upstream JDK directory in ${JDK_UPSTREAM}"
    log_error "Contents of ${JDK_UPSTREAM}:"
    ls -la "${JDK_UPSTREAM}"
    exit 1
fi

if [ -z "${BUILT_JDK_DIR}" ]; then
    log_error "Could not find unpacked built JDK directory in ${JDK_BUILT}"
    log_error "Contents of ${JDK_BUILT}:"
    ls -la "${JDK_BUILT}"
    exit 1
fi

log_info "Comparison directories:"
log_info "  Upstream: ${UPSTREAM_JDK_DIR}"
log_info "  Built:    ${BUILT_JDK_DIR}"

# Run repro_compare.sh
log_info "Running repro_compare.sh..."
cd "${COMPARE_WORKSPACE}"

COMPARE_OUTPUT="${COMPARE_WORKSPACE}/comparison-report.txt"

if bash "${REPRO_COMPARE_SCRIPT}" "${VARIANT}" "${UPSTREAM_JDK_DIR}" "${VARIANT}" "${BUILT_JDK_DIR}" "${REPRO_OS}" | tee "${COMPARE_OUTPUT}"; then
    log_info "Reproducible build comparison PASSED"
    log_info "The locally built JDK matches the production binary"
else
    COMPARE_EXIT_CODE=$?
    log_error "Reproducible build comparison FAILED (exit code: ${COMPARE_EXIT_CODE})"
    log_error "Differences detected between locally built and production binaries"

    # Show comparison report
    if [ -f "${COMPARE_OUTPUT}" ]; then
        log_info "Comparison report:"
        cat "${COMPARE_OUTPUT}"
    fi

    exit ${COMPARE_EXIT_CODE}
fi

################################################################################
# Cleanup and Summary
################################################################################

log_section "Comparison Summary"

log_info "Comparison workspace: ${COMPARE_WORKSPACE}"
log_info "Comparison report: ${COMPARE_OUTPUT}"
log_info "Upstream JDK: ${UPSTREAM_JDK_DIR}"
log_info "Built JDK: ${BUILT_JDK_DIR}"

log_info "Stage 20: Reproducible Build Comparison - Complete"

# Made with Bob
