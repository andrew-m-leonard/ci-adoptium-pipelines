#!/bin/bash
# DEFAULT STUB: 04-assemble-images
#
# Runs the final OpenJDK make image processing to assemble signed JMODs into
# a complete JDK image. Must run after 03-internal-code-sign so that the
# resulting image contains only signed internal binaries.
# Windows & Mac only. Not applicable to jdk8.
#
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/04-assemble-images.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing signed jmods
#   TARGET_DIR            - Directory for assembled JDK image output
echo "ℹ️  Assemble Images: no vendor implementation configured — skipping"
exit 0
