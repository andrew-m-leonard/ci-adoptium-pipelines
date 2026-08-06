#!/bin/bash
# DEFAULT STUB: 14-aqa-tests
#
# AQA test execution is vendor-specific.
# Override this stub by placing a script at:
#   config-repo/vendor-scripts/14-aqa-tests.{sh,groovy,py}
#
# Required Environment Variables (for vendor implementations):
#   WORKSPACE             - Stage workspace directory
#   CONFIG_FILE           - Path to pipeline-config.json
#   INPUT_ARTIFACTS_DIR   - Directory containing JDK artifacts to test
#   TARGET_DIR            - Directory for test results output

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../lib/logging-utils.sh"

# AQA_REF stage param takes precedence; fall back to CONFIG_AQA_REF then 'master'.
aqa_ref="${AQA_REF:-${CONFIG_AQA_REF:-master}}"
aqa_ref_source="default"; [[ -n "${AQA_REF:-}" ]] && aqa_ref_source="param"
aqa_repo_url="${CONFIG_AQA_REPO_URL:-https://github.com/adoptium/aqa-tests.git}"

log_info "Test Configuration:"
log_info "  AQA Repo URL: ${aqa_repo_url} (${aqa_ref_source})"
log_info "  AQA Ref: ${aqa_ref} (${aqa_ref_source})"

echo "ℹ️  AQA Tests: no vendor implementation configured — skipping"
exit 0
