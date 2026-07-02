#!/bin/bash
# DEFAULT STUB: 06-post-build-code-sign
#
# Code signs EXEs/DLLs and dylibs that were not signed during the internal
# signing stage (03-internal-code-sign). This covers all binaries for jdk8
# (which has no internal signing stage), and the limited set of jdk11+
# binaries that exist outside of JMODs.
# Windows & Mac only.
#
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/06-post-build-code-sign.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing the assembled JDK image
#   TARGET_DIR            - Directory for code-signed output
echo "ℹ️  Post-Build Code Sign: no vendor implementation configured — skipping"
exit 0
