#!/usr/bin/env python3
"""
SBOM Workspace Extractor

Reads an Adoptium SBOM JSON file and extracts the 'Build Workspace Directory'
property from the first component.  Prints the value to stdout so the calling
shell can capture it.  Exits with code 0 and prints an empty line when the
property is absent; exits with a non-zero code only on hard errors (unreadable
file, invalid JSON).

Usage:
    value=$(python3 sbom-workspace-extractor.py --sbom /path/to/sbom.json)
"""

import argparse
import json
import sys


# The SBOM property name defined by the Adoptium build pipeline
_PROPERTY_NAME = "Build Workspace Directory"


class SbomWorkspaceExtractor:
    """Parses an Adoptium SBOM and retrieves the build workspace directory."""

    def __init__(self, sbom_path: str) -> None:
        self._sbom_path = sbom_path

    def _load(self) -> dict:
        try:
            with open(self._sbom_path) as fh:
                return json.load(fh)
        except OSError as exc:
            print(f"ERROR: cannot open SBOM file '{self._sbom_path}': {exc}", file=sys.stderr)
            sys.exit(1)
        except json.JSONDecodeError as exc:
            print(f"ERROR: invalid JSON in '{self._sbom_path}': {exc}", file=sys.stderr)
            sys.exit(1)

    def extract(self) -> str:
        """Return the Build Workspace Directory value, or an empty string."""
        data = self._load()
        try:
            properties = data["components"][0]["properties"]
        except (KeyError, IndexError, TypeError):
            return ""
        for prop in properties:
            if prop.get("name") == _PROPERTY_NAME:
                return prop.get("value", "")
        return ""


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Extract Build Workspace Directory from an Adoptium SBOM",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--sbom", required=True, help="Path to the SBOM JSON file")

    args = parser.parse_args()
    print(SbomWorkspaceExtractor(args.sbom).extract())
    return 0


if __name__ == "__main__":
    sys.exit(main())
