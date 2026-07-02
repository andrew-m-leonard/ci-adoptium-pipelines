#!/bin/bash
# DEFAULT STUB: 09-sbom-sign
#
# JSF-signs the SBOM by embedding a JSON signature directly inside the SBOM
# document. Must run before 10-digital-artifact-sign so that the signed SBOM
# is included in the set of artifacts that receive a detached GPG signature.
# Only applicable when SBOMs are generated (--create-sbom in BUILD_ARGS).
#
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/09-sbom-sign.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing SBOM files
#   TARGET_DIR            - Directory for JSF-signed SBOM output
echo "ℹ️  SBOM Sign: no vendor implementation configured — skipping"
exit 0
