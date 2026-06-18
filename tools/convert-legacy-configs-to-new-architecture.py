#!/usr/bin/env python3
"""
Convert legacy Groovy pipeline configurations to new JSON architecture.

This tool converts legacy Groovy configs to the new launch job architecture:
- Uses the existing convert-all-legacy-groovy-configs.py for conversion
- Removes "u" suffix from output filenames
- Generates jenkins_job_config.json (top-level config with active versions)
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
import subprocess
import shutil
import tempfile
from pathlib import Path
from typing import List, Dict, Any


def find_converter_script() -> Path:
    """Find the convert-all-legacy-groovy-configs.py script."""
    script_dir = Path(__file__).parent
    converter = script_dir / "convert-all-legacy-groovy-configs.py"
    
    if converter.exists():
        return converter
    
    raise FileNotFoundError(
        "Could not find convert-all-legacy-groovy-configs.py script. "
        "Please ensure it's in the same directory as this script."
    )


def extract_version_from_filename(filename: str) -> str | None:
    """Extract JDK version from filename, removing 'u' suffix."""
    match = re.search(r'(jdk\d+)u?_pipeline_config', filename)
    if match:
        return match.group(1)  # Returns jdk8, jdk11, jdk17, etc. (without 'u')
    return None


def generate_jenkins_job_config(version_configs: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Generate the top-level jenkins_job_config.json."""
    versions = sorted([config["version"] for config in version_configs])
    
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
        # Find the converter script
        converter = find_converter_script()
        if args.verbose:
            print(f"Using converter: {converter}")
            print()

        # Create temporary directory for initial conversion
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            
            if args.dry_run:
                print("DRY RUN - No files will be converted")
                print()
                print(f"Would use converter: {converter}")
                print(f"Source: {args.source}")
                print(f"Output: {args.output}")
                print()
                print("Process:")
                print("1. Convert Groovy files to JSON (temp directory)")
                print("2. Remove 'u' suffix from filenames")
                print("3. Move to configurations/ subdirectory")
                print("4. Generate jenkins_job_config.json")
                return 0

            # Step 1: Run the existing converter to temp directory
            print("Step 1: Converting Groovy files to JSON...")
            print()
            
            cmd = [
                "python3",
                str(converter),
                "--source", str(args.source),
                "--output", str(temp_path)
            ]
            
            if args.force:
                cmd.append("--force")
            
            if args.verbose:
                cmd.append("--verbose")
            
            result = subprocess.run(cmd, capture_output=not args.verbose, text=True)
            
            if result.returncode != 0:
                print(f"Error: Conversion failed", file=sys.stderr)
                if result.stderr:
                    print(result.stderr, file=sys.stderr)
                return 1
            
            # Step 2: Process converted files
            print()
            print("Step 2: Processing converted files...")
            print()
            
            # Create output directories
            args.output.mkdir(parents=True, exist_ok=True)
            config_dir = args.output / "configurations"
            config_dir.mkdir(parents=True, exist_ok=True)
            
            # Process each converted file
            version_configs = []
            converted_files = list(temp_path.glob("*_pipeline_config.json"))
            
            for i, temp_file in enumerate(sorted(converted_files), 1):
                # Extract version (remove 'u' suffix)
                version = extract_version_from_filename(temp_file.name)
                if not version:
                    print(f"[{i}/{len(converted_files)}] Skipping {temp_file.name} (could not extract version)")
                    continue
                
                # Read the converted file
                with open(temp_file, 'r') as f:
                    config = json.load(f)
                
                # Update version field (remove 'u' suffix)
                config["version"] = version
                
                # Keep scmReference with 'u' suffix for compatibility
                if not config.get("scmReference"):
                    config["scmReference"] = version + "u"
                
                version_configs.append(config)
                
                # Write to new location with corrected filename
                output_file = config_dir / f"{version}_pipeline_config.json"
                
                print(f"[{i}/{len(converted_files)}] {temp_file.name} -> {output_file.name}")
                
                with open(output_file, 'w') as f:
                    json.dump(config, f, indent=2)
                
                if args.verbose:
                    print(f"  Version: {config['version']}")
                    print(f"  SCM Reference: {config['scmReference']}")
                    print(f"  Platforms: {len(config.get('buildConfigurations', {}))}")
            
            # Step 3: Generate jenkins_job_config.json
            if version_configs:
                print()
                print("Step 3: Generating jenkins_job_config.json...")
                
                jenkins_config = generate_jenkins_job_config(version_configs)
                jenkins_config_file = args.output / "jenkins_job_config.json"
                
                with open(jenkins_config_file, 'w') as f:
                    json.dump(jenkins_config, f, indent=2)
                
                print(f"  Created: {jenkins_config_file.name}")
                
                if args.verbose:
                    print(f"  Active versions: {jenkins_config['activeJdkVersions']}")
            
            # Print summary
            print()
            print("=" * 70)
            print("Conversion Summary")
            print("=" * 70)
            print(f"Converted: {len(version_configs)} configurations")
            print()
            
            print("✅ All configurations converted successfully!")
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
