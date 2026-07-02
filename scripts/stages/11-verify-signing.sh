#!/bin/bash
# DEFAULT STUB: 11-verify-signing
#
# Verifies that all necessary signing has been completed successfully.
# Checks that Windows and macOS executables are code-signed, installer packages
# are code-signed (and notarized on macOS), and that detached GPG signatures
# (.sig / .asc) are present for every distribution artifact.
#
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/11-verify-signing.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing signed artifacts and signatures
#   TARGET_DIR            - Directory for verification report output
echo "ℹ️  Verify Signing: no vendor implementation configured — skipping"
exit 0
