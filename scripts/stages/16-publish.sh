#!/bin/bash
# DEFAULT STUB: 16-publish
#
# Publishing artifacts to a release repository is vendor-specific.
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/16-publish.{sh,groovy,py}
#
# Required Environment Variables (set by initializeStage):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing artifacts to publish
#   BUILD_NUMBER          - Build number
#
# Stage-specific Environment Variables (set by Publish Artifacts stage):
#   TARGET_DIR            - Directory for publish output/receipts
echo "ℹ️  Publish Artifacts: no vendor implementation configured — skipping"
exit 0
