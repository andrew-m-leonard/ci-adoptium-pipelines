#!/bin/bash
# DEFAULT STUB: 07-installer
#
# Building platform-specific installers is vendor-specific.
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/07-installer.{sh,groovy,py}
#
# Required Environment Variables (set by initializeStage):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing signed artifacts
#   BUILD_NUMBER          - Build number
#
# Stage-specific Environment Variables (set by Build Installers stage):
#   TARGET_DIR            - Directory for installer output
echo "ℹ️  Build Installers: no vendor implementation configured — skipping"
exit 0
