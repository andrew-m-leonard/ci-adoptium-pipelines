#!/usr/bin/env python3
"""
Convert legacy Groovy pipeline configurations to new JSON architecture.

This tool converts legacy Groovy configs to the new launch job architecture:
- Generates jenkins_job_config.json (top-level config with active versions)
- Generates individual jdk${version}_pipeline_config.json files (without "u" suffix)
- Structures output for launch job + platform job architecture

Usage:
    # Convert all configs and generate jenkins_job_config.json
    python3 convert-legacy-configs-to-new-architecture.py \
        --source ~/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations \
        --output ~/workspace/ci-temurin-config

    # Dry run to preview
    python3 convert-legacy-configs-to-new-architecture.py \
        --source ./configs --output ./output --dry-run

    # Verbose output
    python3 convert-legacy-configs-to-new-architecture.py \
        --source ./configs --output ./output --verbose
"""

import argparse
import sys
import json
import re
from pathlib import Path
from typing import Any, Dict, List, Union

# Import the Groovy parser from convert-groovy-to-json.py
try:
    from convert_groovy_to_json import GroovyParser
except ImportError:
    print("Error: Could not import GroovyParser from convert-groovy-to-json.py", file=sys.stderr)
    print("Please ensure convert-groovy-to-json.py is in the same directory.", file=sys.stderr)
    sys.exit(1)


def extract_version_from_filename(filename: str) -> Union[str, None]:
    """Extract JDK version from filename, removing 'u' suffix."""
    # Match jdk8u, jdk11u, jdk17u, etc.
    match = re.search(r'(jdk\d+)u?_pipeline_config', filename)
    if match:
        return match.group(1)  # Returns jdk8, jdk11, jdk17, etc. (without 'u')
    return None


def parse_groovy_config(groovy_file: Path) -> Dict[str, Any]:
    """Parse a Groovy configuration file and return structured data."""
    try:
        with open(groovy_file, 'r') as f:
            content = f.read()

        # Extract version
        version = extract_version_from_filename(groovy_file.name)
        if not version:
            raise ValueError(f"Could not extract version from filename: {groovy_file.name}")

        # Find buildConfigurations map
        pattern = r'buildConfigurations\s*=\s*\[(.*?)\n\s*\]'
        match = re.search(pattern, content, re.DOTALL)

        if not match:
            raise ValueError("Could not find buildConfigurations in file")

        config_content = match.group(1)

        # Parse platform configurations
        platforms = {}
        platform_pattern = r'(\w+)\s*:\s*\['

        matches = list(re.finditer(platform_pattern, config_content))

        for match in matches:
            platform_name = match.group(1)
            start_pos = match.end() - 1

            # Find matching closing bracket
            depth = 0
            end_pos = start_pos
            for i in range(start_pos, len(config_content)):
                if config_content[i] == '[':
                    depth += 1
                elif config_content[i] == ']':
                    depth -= 1
                    if depth == 0:
                        end_pos = i + 1
                        break

            platform_content = config_content[start_pos:end_pos]

            # Parse using GroovyParser
            parser = GroovyParser(platform_content)
            try:
                platform_config = parser.parse_collection()
                if isinstance(platform_config, dict):
                    platforms[platform_name] = platform_config
                else:
                    print(f"Warning: {platform_name} parsed as list, expected dict", file=sys.stderr)
                    platforms[platform_name] = {}
            except Exception as e:
                print(f"Warning: Error parsing {platform_name}: {e}", file=sys.stderr)
                platforms[platform_name] = {}

        # Create config structure
        config = {
            "version": version,
            "scmReference": version + "u",  # Keep 'u' for SCM reference
            "buildConfigurations": platforms,
            "targetConfigurations": list(platforms.keys())
        }

        return config

    except Exception as e:
        raise ValueError(f"Error parsing {groovy_file}: {e}")


def generate_jenkins_job_config(version_configs: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Generate the top-level jenkins_job_config.json."""
    # Extract versions and sort them
    versions = sorted([config["version"] for config in version_configs])

    # Create jenkins_job_config structure
    jenkins_config = {
        "activeJdkVersions": versions,
        "defaultBuildArgs": "--create-jre-image --create-sbom",
        "defaultConfigureArgs": "",
        "defaultVariant": "temurin",
        "defaultScmReference": "",
        "configFilePrefix": "configurations/",
        "configFileSuffix": "_pipeline_config.json"
    }

    return jenkins_config


def find_groovy_configs(source_dir: Path, pattern: str = "*_pipeline_config.groovy") -> List[Path]:
    """Find all Groovy configuration files matching the pattern."""
    if not source_dir.exists():
        raise FileNotFoundError(f"Source directory not found: {source_dir}")

    if not source_dir.is_dir():
        raise NotADirectoryError(f"Source path is not a directory: {source_dir}")

    configs = sorted(source_dir.glob(pattern))

    if not configs:
        raise FileNotFoundError(
            f"No Groovy configuration files found in {source_dir} "
            f"matching pattern '{pattern}'"
        )

    return configs


def main():
    parser = argparse.ArgumentParser(
        description="Convert legacy Groovy configs to new launch job architecture",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Convert all configs and generate jenkins_job_config.json
  %(prog)s --source ../ci-jenkins-pipelines/pipelines/jobs/configurations \\
           --output ../ci-temurin-config

  # Dry run to preview
  %(prog)s --source ./configs --output ./output --dry-run

  # Verbose output
  %(prog)s --source ./configs --output ./output --verbose
        """
    )

    parser.add_argument(
        "--source", "-s",
        type=Path,
        required=True,
        help="Source directory containing Groovy configuration files"
    )

    parser.add_argument(
        "--output", "-o",
        type=Path,
        required=True,
        help="Output directory for JSON configuration files"
    )

    parser.add_argument(
        "--pattern", "-p",
        type=str,
        default="*_pipeline_config.groovy",
        help="Glob pattern for Groovy config files (default: *_pipeline_config.groovy)"
    )

    parser.add_argument(
        "--dry-run", "-n",
        action="store_true",
        help="Show what would be converted without actually converting"
    )

    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Show detailed conversion output"
    )

    parser.add_argument(
        "--force", "-f",
        action="store_true",
        help="Overwrite existing JSON files without prompting"
    )

    args = parser.parse_args()

    # Print header
    print("=" * 70)
    print("Legacy Groovy to New Architecture Converter")
    print("=" * 70)
    print()

    try:
        # Find all Groovy configs
        print(f"Scanning: {args.source}")
        print(f"Pattern:  {args.pattern}")
        configs = find_groovy_configs(args.source, args.pattern)
        print(f"Found:    {len(configs)} configuration file(s)")
        print()

        # Create output directories
        if not args.dry_run:
            args.output.mkdir(parents=True, exist_ok=True)
            (args.output / "configurations").mkdir(parents=True, exist_ok=True)

        # List files to be converted
        if args.dry_run:
            print("DRY RUN - No files will be converted")
            print()
            print("Files that would be converted:")
            for config in configs:
                version = extract_version_from_filename(config.name)
                if version:
                    output_file = f"{version}_pipeline_config.json"
                    print(f"  {config.name} -> configurations/{output_file}")
            print()
            print(f"  jenkins_job_config.json (generated)")
            print()
            print(f"Output directory: {args.output}")
            return 0

        # Convert each file
        print(f"Converting to: {args.output}")
        print()

        success_count = 0
        failed_count = 0
        failed_files = []
        version_configs = []

        for i, config in enumerate(configs, 1):
            version = extract_version_from_filename(config.name)
            if not version:
                print(f"[{i}/{len(configs)}] Skipping {config.name} (could not extract version)")
                continue

            output_file = args.output / "configurations" / f"{version}_pipeline_config.json"

            # Check if output file exists
            if output_file.exists() and not args.force:
                print(f"[{i}/{len(configs)}] Skipping {config.name} (output exists, use --force to overwrite)")
                continue

            print(f"[{i}/{len(configs)}] Converting {config.name}...", end=" ", flush=True)

            try:
                # Parse the Groovy config
                parsed_config = parse_groovy_config(config)
                version_configs.append(parsed_config)

                # Write to file
                with open(output_file, 'w') as f:
                    json.dump(parsed_config, f, indent=2)

                print("✅")
                success_count += 1

                if args.verbose:
                    print(f"  Output: {output_file}")
                    print(f"  Version: {parsed_config['version']}")
                    print(f"  Platforms: {len(parsed_config['buildConfigurations'])}")

            except Exception as e:
                print("❌")
                failed_count += 1
                failed_files.append((config.name, str(e)))
                print(f"  Error: {e}")

        # Generate jenkins_job_config.json
        if version_configs:
            print()
            print("Generating jenkins_job_config.json...", end=" ", flush=True)
            try:
                jenkins_config = generate_jenkins_job_config(version_configs)
                jenkins_config_file = args.output / "jenkins_job_config.json"

                with open(jenkins_config_file, 'w') as f:
                    json.dump(jenkins_config, f, indent=2)

                print("✅")
                if args.verbose:
                    print(f"  Output: {jenkins_config_file}")
                    print(f"  Active versions: {jenkins_config['activeJdkVersions']}")

            except Exception as e:
                print("❌")
                print(f"  Error: {e}")
                failed_count += 1

        # Print summary
        print()
        print("=" * 70)
        print("Conversion Summary")
        print("=" * 70)
        print(f"Total:   {len(configs)}")
        print(f"Success: {success_count}")
        print(f"Failed:  {failed_count}")
        print()

        if failed_files:
            print("Failed conversions:")
            for filename, error in failed_files:
                print(f"  ❌ {filename}")
                print(f"     {error}")
            print()
            return 1

        print(f"✅ All configurations converted successfully!")
        print()
        print("Generated files:")
        print(f"  - jenkins_job_config.json")
        for config in version_configs:
            print(f"  - configurations/{config['version']}_pipeline_config.json")
        print()
        print("Next steps:")
        print("1. Review the generated JSON files")
        print("2. Verify platform configurations are correct")
        print("3. Test with the seed job")
        print("4. Commit to your configuration repository")
        print()

        return 0

    except FileNotFoundError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())

# Made with Bob
