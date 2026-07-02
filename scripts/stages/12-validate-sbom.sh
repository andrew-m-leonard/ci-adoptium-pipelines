#!/bin/bash
# DEFAULT STUB: 12-validate-sbom
#
# Validates SBOM (Software Bill of Materials) files produced during the Build
# stage. Only applicable when SBOMs are generated (--create-sbom in BUILD_ARGS).
#
# The validation tooling and acceptance criteria are vendor-specific (e.g.,
# Temurin uses temurin-build/tooling/validateSBOM.sh).
#
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/12-validate-sbom.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE            - Stage workspace directory
#   CONFIG_FILE          - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR  - Directory containing *sbom*.json files from Build
#   TARGET_DIR           - Directory for validation report output
echo "ℹ️  Validate SBOM: no vendor implementation configured — skipping"
exit 0
