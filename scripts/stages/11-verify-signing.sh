#!/bin/bash
# DEFAULT STUB: 11-verify-signing
#
# Signing verification is vendor-specific.
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/11-verify-signing.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing signed artifacts and signatures
#   TARGET_DIR            - Directory for verification output
echo "ℹ️  Verify Signing: no vendor implementation configured — skipping"
exit 0
