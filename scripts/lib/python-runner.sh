#!/usr/bin/env sh
# python-runner.sh — resolve the Python interpreter and exec the given script.
#
# Usage:  python-runner.sh <script.py> [args...]
#
# Tries python3 first, then falls back to python.  Exits with code 127 if
# neither is found.  All arguments are forwarded verbatim to the interpreter.
#
# This is the single canonical entry point for all shell-context Python
# invocations in the pipeline (ConfigHelper, StageScriptRunner, etc.).

PYTHON=""
if command -v python3 >/dev/null 2>&1; then
    PYTHON="python3"
elif command -v python >/dev/null 2>&1; then
    PYTHON="python"
else
    echo "ERROR: No Python interpreter found on PATH (tried python3, python)." >&2
    exit 127
fi

exec "$PYTHON" "$@"
