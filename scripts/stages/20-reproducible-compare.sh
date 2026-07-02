#!/bin/bash
# DEFAULT STUB: 20-reproducible-compare
#
# Compares a locally built JDK against the vendor's published production binary
# to verify bit-for-bit reproducibility. The comparison tooling, the binary
# source (API endpoint, artifact store, etc.), and acceptance criteria are all
# vendor-specific (e.g., Temurin downloads from api.adoptium.net and uses
# temurin-build/tooling/reproducible/repro_compare.sh).
#
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/20-reproducible-compare.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE            - Stage workspace directory
#   CONFIG_FILE          - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR  - Directory containing locally built JDK tarballs/zips
#   TARGET_DIR           - Directory for comparison report output
#   SCM_REF              - Git tag/ref for the build (e.g., jdk-21.0.2+13)
#   RELEASE              - Boolean: true for release builds, false for EA
echo "ℹ️  Reproducible Compare: no vendor implementation configured — skipping"
exit 0
