#!/bin/bash
# DEFAULT STUB: 15-tck-tests
#
# TCK test execution is vendor-specific.
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/15-tck-tests.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing JDK artifacts to test
#   TARGET_DIR            - Directory for test results output
echo "ℹ️  TCK Tests: no vendor implementation configured — skipping"
exit 0
