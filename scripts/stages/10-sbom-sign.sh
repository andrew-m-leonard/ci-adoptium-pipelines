#!/bin/bash
# DEFAULT STUB: 10-sbom-sign
#
# SBOM JSF signing is vendor-specific.
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/10-sbom-sign.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing SBOM files
#   TARGET_DIR            - Directory for signed SBOM output
echo "ℹ️  SBOM Sign: no vendor implementation configured — skipping"
exit 0
