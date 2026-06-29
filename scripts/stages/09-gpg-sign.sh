#!/bin/bash
# DEFAULT STUB: 09-gpg-sign
#
# GPG signing of artifacts is vendor-specific.
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/09-gpg-sign.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing signed artifacts
#   TARGET_DIR            - Directory for GPG-signed output
echo "ℹ️  GPG Sign: no vendor implementation configured — skipping"
exit 0
