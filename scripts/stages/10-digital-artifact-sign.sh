#!/bin/bash
# DEFAULT STUB: 10-digital-artifact-sign
#
# Applies detached GPG signatures (.sig / .asc) to all build artifacts for
# public distribution verification. Covers code-signed JDK archives, installer
# packages, and JSF-signed SBOMs. GPG armoring all artifacts including the
# signed SBOM is why this stage runs after 09-sbom-sign.
#
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/10-digital-artifact-sign.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing signed artifacts and signed SBOMs
#   TARGET_DIR            - Directory for GPG-signed output (.sig / .asc files)
echo "ℹ️  Digital Artifact Sign: no vendor implementation configured — skipping"
exit 0
