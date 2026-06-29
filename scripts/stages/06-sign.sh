#!/bin/bash
# DEFAULT STUB: 06-sign
#
# Artifact signing is vendor-specific.
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/06-sign.{sh,groovy,py}
#
# Required Environment Variables (set by initializeStage):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing artifacts to sign
#   BUILD_NUMBER          - Build number
#
# Stage-specific Environment Variables (set by Sign Artifacts stage):
#   TARGET_DIR            - Directory for signed artifact output
echo "ℹ️  Sign Artifacts: no vendor implementation configured — skipping"
exit 0
