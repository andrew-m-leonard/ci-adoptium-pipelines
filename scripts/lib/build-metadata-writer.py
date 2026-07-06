#!/usr/bin/env python
"""
Build Metadata Writer

Writes a build-metadata.json file from values supplied as CLI arguments and
environment variables.  All dynamic data is passed in explicitly - nothing is
read from the shell environment via string interpolation - so the script is
safe to call from any shell context.

Usage:
    python3 build-metadata-writer.py \\
        --output /path/to/build-metadata.json \\
        --version  jdk-21.0.12+7 \\
        --build-number 42 \\
        --stage build \\
        --workspace /workspace/build

Optional arguments (fall back to empty string when absent):
    --build-uid   <uid>
    --group-uid   <uid>

The following fields are read from environment variables (set by the pipeline):
    CONFIG_JAVA_TO_BUILD
    CONFIG_TARGET_OS
    CONFIG_ARCHITECTURE
    CONFIG_VARIANT
"""

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone


class BuildMetadataWriter:
    """Collects build metadata and serialises it to a JSON file."""

    def __init__(self, args: argparse.Namespace) -> None:
        self._output = args.output
        self._version = args.version
        self._build_number = args.build_number
        self._build_uid = args.build_uid or ""
        self._group_uid = args.group_uid or ""
        self._stage = args.stage
        self._workspace = args.workspace

    def _collect(self) -> dict:
        now = time.time()
        iso = datetime.fromtimestamp(now, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        return {
            "version":      self._version,
            "buildNumber":  self._build_number,
            "buildUid":     self._build_uid,
            "groupUid":     self._group_uid,
            "timestamp":    int(now),
            "timestampISO": iso,
            "stage":        self._stage,
            "workspace":    self._workspace,
            "javaVersion":  os.environ.get("CONFIG_JAVA_TO_BUILD", ""),
            "targetOS":     os.environ.get("CONFIG_TARGET_OS", ""),
            "architecture": os.environ.get("CONFIG_ARCHITECTURE", ""),
            "variant":      os.environ.get("CONFIG_VARIANT", ""),
        }

    def write(self) -> None:
        metadata = self._collect()
        try:
            with open(self._output, "w") as fh:
                json.dump(metadata, fh, indent=2)
                fh.write("\n")
        except OSError as exc:
            print(f"ERROR: could not write {self._output}: {exc}", file=sys.stderr)
            sys.exit(1)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Write build-metadata.json for an Adoptium build stage",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--output",       required=True,  help="Destination path for build-metadata.json")
    parser.add_argument("--version",      required=True,  help="JDK version string (e.g. jdk-21.0.12+7)")
    parser.add_argument("--build-number", required=True,  help="Build number")
    parser.add_argument("--stage",        required=True,  help="Stage name (e.g. build)")
    parser.add_argument("--workspace",    required=True,  help="Absolute path to the build workspace")
    parser.add_argument("--build-uid",    default="",     help="Build UID (optional)")
    parser.add_argument("--group-uid",    default="",     help="Group UID (optional)")

    args = parser.parse_args()
    BuildMetadataWriter(args).write()
    return 0


if __name__ == "__main__":
    sys.exit(main())
