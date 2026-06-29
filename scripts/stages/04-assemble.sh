#!/bin/bash
# DEFAULT STUB: 04-assemble
#
# Assembly of signed JMODs into a final JDK image is vendor-specific.
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/04-assemble.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing signed jmods
#   TARGET_DIR            - Directory for assembled JDK output
echo "ℹ️  Assemble: no vendor implementation configured — skipping"
exit 0
