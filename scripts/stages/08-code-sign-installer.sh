#!/bin/bash
# DEFAULT STUB: 08-code-sign-installer
#
# Code signs installer packages (.msi on Windows, .pkg on macOS).
# On macOS, also submits the signed package to Apple for Notarization
# and staples the notarization ticket to the installer.
# Windows & Mac only.
#
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/08-code-sign-installer.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing installers from Build Installer stage
#   TARGET_DIR            - Directory for signed installer output
echo "ℹ️  Code Sign Installer: no vendor implementation configured — skipping"
exit 0
