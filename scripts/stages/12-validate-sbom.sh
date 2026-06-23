#!/bin/bash
set -euo pipefail

################################################################################
# SBOM Validation Stage Script
#
# This script validates SBOM (Software Bill of Materials) files generated 
# during the build. It clones the temurin-build repository and uses the 
# validateSBOM.sh script.
#
# Required Environment Variables:
#   WORKSPACE     - Jenkins workspace directory
#   CONFIG_FILE   - Path to pipeline-config.json
#   TARGET_DIR    - Directory containing build artifacts
#
# Optional Environment Variables:
#   JAVA_VERSION  - Java version being built (extracted from config if not set)
#   SCM_REF       - SCM reference for the build (extracted from config if not set)
#   TEMURIN_BUILD_REPO - URL of temurin-build repo (default: https://github.com/adoptium/temurin-build.git)
#   TEMURIN_BUILD_BRANCH - Branch to clone (default: master)
#
# Exit Codes:
#   0 - Success (all SBOM files validated)
#   1 - Failure (validation failed or no SBOM files found)
################################################################################

echo "=== SBOM Validation Stage ==="

# Validate required environment variables
if [ -z "${WORKSPACE:-}" ]; then
    echo "ERROR: WORKSPACE environment variable is not set"
    exit 1
fi

if [ -z "${CONFIG_FILE:-}" ]; then
    echo "ERROR: CONFIG_FILE environment variable is not set"
    exit 1
fi

if [ -z "${TARGET_DIR:-}" ]; then
    echo "ERROR: TARGET_DIR environment variable is not set"
    exit 1
fi

# Set defaults for temurin-build repository
TEMURIN_BUILD_REPO="${TEMURIN_BUILD_REPO:-https://github.com/adoptium/temurin-build.git}"
TEMURIN_BUILD_BRANCH="${TEMURIN_BUILD_BRANCH:-master}"
TEMURIN_BUILD_DIR="${WORKSPACE}/temurin-build-sbom-validation"

# Clone temurin-build repository if not already present
if [ ! -d "${TEMURIN_BUILD_DIR}" ]; then
    echo "Cloning temurin-build repository..."
    git clone --depth 1 --branch "${TEMURIN_BUILD_BRANCH}" "${TEMURIN_BUILD_REPO}" "${TEMURIN_BUILD_DIR}"
else
    echo "Using existing temurin-build repository at ${TEMURIN_BUILD_DIR}"
fi

# Verify validateSBOM.sh exists
VALIDATE_SBOM_SCRIPT="${TEMURIN_BUILD_DIR}/tooling/validateSBOM.sh"
if [ ! -f "${VALIDATE_SBOM_SCRIPT}" ]; then
    echo "ERROR: validateSBOM.sh not found at ${VALIDATE_SBOM_SCRIPT}"
    exit 1
fi

# Extract configuration if not provided via environment
if [ -z "${JAVA_VERSION:-}" ]; then
    JAVA_VERSION=$(python3 -c "import json; print(json.load(open('${CONFIG_FILE}'))['buildConfig']['JAVA_TO_BUILD'])")
    echo "Extracted JAVA_VERSION from config: ${JAVA_VERSION}"
fi

# Extract numeric version for validateSBOM.sh (e.g., "jdk21u" -> "21")
# validateSBOM.sh expects just the numeric version as first argument
JDK_NUMERIC_VERSION=$(echo "${JAVA_VERSION}" | sed 's/[^0-9]//g')
echo "Extracted numeric JDK version: ${JDK_NUMERIC_VERSION}"

if [ -z "${SCM_REF:-}" ]; then
    SCM_REF=$(python3 -c "import json; config=json.load(open('${CONFIG_FILE}')); print(config.get('refs', {}).get('scmRef', 'HEAD'))")
    echo "Extracted SCM_REF from config: ${SCM_REF}"
fi

# Find all SBOM JSON files (excluding metadata files)
echo "Searching for SBOM files in ${TARGET_DIR}..."
SBOM_FILES=$(find "${TARGET_DIR}" -name '*sbom*.json' -type f | grep -v metadata || true)

if [ -z "${SBOM_FILES}" ]; then
    echo "WARNING: No SBOM files found in ${TARGET_DIR}"
    echo "This may indicate that SBOM generation was not successful"
    exit 1
fi

echo "Found SBOM files:"
echo "${SBOM_FILES}"

# Validate each SBOM file
VALIDATION_FAILED=0
while IFS= read -r sbom_file; do
    if [ -n "${sbom_file}" ]; then
        echo ""
        echo "Validating SBOM: ${sbom_file}"
        
        if bash "${VALIDATE_SBOM_SCRIPT}" \
            "${JDK_NUMERIC_VERSION}" \
            "${SCM_REF}" \
            "${sbom_file}"; then
            echo "SUCCESS: SBOM validation passed for ${sbom_file}"
        else
            echo "ERROR: SBOM validation failed for ${sbom_file}"
            VALIDATION_FAILED=1
        fi
    fi
done <<< "${SBOM_FILES}"

if [ ${VALIDATION_FAILED} -eq 1 ]; then
    echo ""
    echo "=== SBOM Validation Failed ==="
    exit 1
fi

echo ""
echo "=== SBOM Validation Complete ==="
exit 0

# Made with Bob
