#!/bin/bash
# Logging utilities for CI-agnostic pipeline stages

log_info() {
    echo "[INFO] $(date '+%Y-%m-%d %H:%M:%S') - $*" >&2
}

log_error() {
    echo "[ERROR] $(date '+%Y-%m-%d %H:%M:%S') - $*" >&2
}

log_warn() {
    echo "[WARN] $(date '+%Y-%m-%d %H:%M:%S') - $*" >&2
}

log_debug() {
    if [[ "${DEBUG:-false}" == "true" ]]; then
        echo "[DEBUG] $(date '+%Y-%m-%d %H:%M:%S') - $*" >&2
    fi
}

log_section() {
    echo "" >&2
    echo "==========================================" >&2
    echo "$*" >&2
    echo "==========================================" >&2
    echo "" >&2
}

# Made with Bob
