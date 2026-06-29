#!/bin/bash
# DEFAULT STUB: 03-internal-sign
#
# Internal signing of JMOD files is vendor-specific.
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/03-internal-sign.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing jmods from Build stage
#   TARGET_DIR            - Directory for signed jmod output
echo "ℹ️  Internal Sign: no vendor implementation configured — skipping"
exit 0
