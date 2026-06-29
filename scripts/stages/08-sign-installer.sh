#!/bin/bash
# DEFAULT STUB: 08-sign-installer
#
# Installer signing is vendor-specific.
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/08-sign-installer.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing installers from Build Installers stage
#   TARGET_DIR            - Directory for signed installer output
echo "ℹ️  Sign Installers: no vendor implementation configured — skipping"
exit 0
