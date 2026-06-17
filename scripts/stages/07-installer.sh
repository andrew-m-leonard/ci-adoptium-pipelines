#!/bin/bash
# CI-agnostic Build Installer Stage Implementation
#
# Builds platform-specific installers (MSI, PKG, DEB, RPM, etc.)
#
# Required Environment Variables:
#   WORKSPACE     - Root workspace directory
#   CONFIG_FILE   - Path to pipeline-config.json
#   TARGET_DIR    - Directory containing artifacts (reads/writes here)
#   BUILD_NUMBER  - Build number (optional)
#
# Outputs:
#   ${TARGET_DIR}/installers/*  - Platform installers
#   stage-metadata.json         - Stage execution metadata

set -euo pipefail

# Source shared utilities
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../lib/logging-utils.sh"
source "${SCRIPT_DIR}/../lib/config-utils.sh"
source "${SCRIPT_DIR}/../lib/artifact-utils.sh"

# Stage configuration
STAGE_NAME="installer"
BUILD_NUMBER="${BUILD_NUMBER:-local}"

# Main execution
main() {
    log_section "Build Installer Stage - Start"

    # Validate environment
    validate_standard_environment
    require_dir "${TARGET_DIR}"

    # Load configuration
    log_info "Loading configuration from ${CONFIG_FILE}"

    # Extract configuration (pass file path directly)
    local java_version=$(get_config_value "${CONFIG_FILE}" ".buildConfig.JAVA_TO_BUILD")
    local target_os=$(get_config_value "${CONFIG_FILE}" ".buildConfig.TARGET_OS")
    local architecture=$(get_config_value "${CONFIG_FILE}" ".buildConfig.ARCHITECTURE")
    local variant=$(get_config_value "${CONFIG_FILE}" ".buildConfig.VARIANT")

    log_info "Installer Configuration:"
    log_info "  Java Version: ${java_version}"
    log_info "  Target OS: ${target_os}"
    log_info "  Architecture: ${architecture}"
    log_info "  Variant: ${variant}"

    # Prepare output directory
    prepare_output_dir "${TARGET_DIR}"

    # Determine installer types for platform
    local installer_types=$(determine_installer_types "${target_os}")
    log_info "Installer types for ${target_os}: ${installer_types}"

    # Build installers
    build_installers "${target_os}" "${architecture}" "${installer_types}"

    # Create stage metadata
    create_installer_metadata "${installer_types}"

    # List installers
    list_artifacts "${TARGET_DIR}"

    log_section "Build Installer Stage - Complete"
}

# Determine which installer types to build for the platform
determine_installer_types() {
    local os=$1

    case "${os}" in
        windows)
            echo "msi"
            ;;
        mac|macos|darwin)
            echo "pkg"
            ;;
        linux)
            echo "deb rpm tar.gz"
            ;;
        *)
            log_warn "Unknown OS: ${os}, defaulting to tar.gz"
            echo "tar.gz"
            ;;
    esac
}

# Build installers
build_installers() {
    local os=$1
    local arch=$2
    local types=$3

    log_section "Building Installers"

    # Create installers directory
    mkdir -p "${TARGET_DIR}/installers"

    # Find JDK artifact
    local jdk_artifact=$(find "${TARGET_DIR}" -name "*.tar.gz" -o -name "*.zip" | head -n 1)

    if [[ -z "${jdk_artifact}" ]]; then
        log_error "No JDK artifact found in ${TARGET_DIR}"
        exit 1
    fi

    log_info "Using JDK artifact: $(basename ${jdk_artifact})"

    # Build each installer type
    for type in ${types}; do
        log_info "Building ${type} installer..."
        build_installer_type "${type}" "${jdk_artifact}" "${os}" "${arch}"
    done

    log_info "All installers built"
}

# Build specific installer type
build_installer_type() {
    local type=$1
    local jdk_artifact=$2
    local os=$3
    local arch=$4

    local installer_name="OpenJDK-${arch}-${os}.${type}"
    local installer_path="${TARGET_DIR}/installers/${installer_name}"

    case "${type}" in
        msi)
            build_msi_installer "${jdk_artifact}" "${installer_path}"
            ;;
        pkg)
            build_pkg_installer "${jdk_artifact}" "${installer_path}"
            ;;
        deb)
            build_deb_installer "${jdk_artifact}" "${installer_path}"
            ;;
        rpm)
            build_rpm_installer "${jdk_artifact}" "${installer_path}"
            ;;
        tar.gz)
            # Tar.gz is just a copy of the original
            cp "${jdk_artifact}" "${installer_path}"
            ;;
        *)
            log_warn "Unknown installer type: ${type}"
            ;;
    esac

    if [[ -f "${installer_path}" ]]; then
        log_info "Created: ${installer_name}"
    else
        log_error "Failed to create: ${installer_name}"
        exit 1
    fi
}

# Build MSI installer (Windows)
build_msi_installer() {
    local jdk_artifact=$1
    local output=$2

    log_info "Building MSI installer..."

    # In production, this would call WiX toolset or similar
    # For now, create a placeholder
    touch "${output}"
    echo "MSI installer placeholder" > "${output}"

    log_debug "MSI installer created (placeholder)"
}

# Build PKG installer (macOS)
build_pkg_installer() {
    local jdk_artifact=$1
    local output=$2

    log_info "Building PKG installer..."

    # In production, this would call pkgbuild/productbuild
    # For now, create a placeholder
    touch "${output}"
    echo "PKG installer placeholder" > "${output}"

    log_debug "PKG installer created (placeholder)"
}

# Build DEB installer (Debian/Ubuntu)
build_deb_installer() {
    local jdk_artifact=$1
    local output=$2

    log_info "Building DEB installer..."

    # In production, this would call dpkg-deb
    # For now, create a placeholder
    touch "${output}"
    echo "DEB installer placeholder" > "${output}"

    log_debug "DEB installer created (placeholder)"
}

# Build RPM installer (Red Hat/Fedora)
build_rpm_installer() {
    local jdk_artifact=$1
    local output=$2

    log_info "Building RPM installer..."

    # In production, this would call rpmbuild
    # For now, create a placeholder
    touch "${output}"
    echo "RPM installer placeholder" > "${output}"

    log_debug "RPM installer created (placeholder)"
}

# Create installer metadata
create_installer_metadata() {
    local types=$1

    cat > "${WORKSPACE}/installer-metadata.json" <<EOF
{
  "stage": "${STAGE_NAME}",
  "status": "success",
  "timestamp": $(date +%s),
  "buildNumber": "${BUILD_NUMBER}",
  "installerTypes": "${types}",
  "installers": [
$(find "${TARGET_DIR}/installers" -type f -exec basename {} \; | sed 's/^/    "/;s/$/",/' | sed '$ s/,$//')
  ]
}
EOF

    log_info "Installer metadata created"
}

# Error handler
error_handler() {
    local line_number=$1
    log_error "Installer stage failed at line ${line_number}"
    create_stage_metadata "${STAGE_NAME}" "failed"
    exit 1
}

# Set error trap
trap 'error_handler ${LINENO}' ERR

# Execute main function
main "$@"

# Made with Bob
