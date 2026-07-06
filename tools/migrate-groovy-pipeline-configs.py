#!/usr/bin/env python3
"""
Migrate legacy Groovy pipeline configurations to new JSON architecture.

This tool converts legacy Groovy configs to the new launch job architecture:
- Uses batch-convert-groovy-configs.py for conversion
- Removes "u" suffix from output filenames
- Generates adoptium_pipeline_config.json  (CI-agnostic config)
- Generates jenkins_job_config.json        (Jenkins-specific config)
- Structures output for launch job + platform job architecture

Usage:
    # Convert all configs and generate config files
    python3 migrate-groovy-pipeline-configs.py \
        --source ~/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations \
        --output ~/workspace/ci-temurin-config

    # Dry run to preview
    python3 migrate-groovy-pipeline-configs.py \
        --source ./configs --output ./output --dry-run

    # Verbose output
    python3 migrate-groovy-pipeline-configs.py \
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
    """Find the batch-convert-groovy-configs.py script."""
    script_dir = Path(__file__).parent
    converter = script_dir / "batch-convert-groovy-configs.py"

    if converter.exists():
        return converter

    raise FileNotFoundError(
        "Could not find batch-convert-groovy-configs.py script. "
        "Please ensure it's in the same directory as this script."
    )


def extract_version_from_filename(filename: str) -> str | None:
    """Extract JDK version from filename, removing 'u' suffix."""
    match = re.search(r'(jdk\d+)u?_pipeline_config', filename)
    if match:
        return match.group(1)  # Returns jdk8, jdk11, jdk17, etc. (without 'u')
    return None


def check_if_job_disabled(source_dir: Path, version: str) -> bool:
    """
    Check if the job is disabled by looking for 'disableJob = true' in jdkNN(u).groovy file.
    
    Args:
        source_dir: Directory containing the Groovy files
        version: JDK version (e.g., 'jdk21')
    
    Returns:
        True if job is disabled, False otherwise
    """
    # Try both with and without 'u' suffix
    for suffix in ['u', '']:
        groovy_file = source_dir / f"{version}{suffix}.groovy"
        if groovy_file.exists():
            try:
                with open(groovy_file, 'r') as f:
                    content = f.read()
                    # Look for disableJob = true (with optional whitespace)
                    if re.search(r'disableJob\s*=\s*true', content):
                        return True
            except Exception as e:
                print(f"  Warning: Could not read {groovy_file.name}: {e}")
    
    # If we can't find the file or disableJob, assume it's enabled
    return False


def generate_adoptium_pipeline_config(version_configs: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Generate adoptium_pipeline_config.json — CI-agnostic configuration."""
    version_configs_sorted = sorted(version_configs, key=lambda x: x["version"])

    active_versions = [
        {
            "version": config["version"],
            "enabled": config.get("enabled", True)
        }
        for config in version_configs_sorted
    ]

    return {
        "activeJdkVersions": active_versions,
        "defaultBuildArgs": "--create-jre-image --create-sbom",
        "defaultConfigureArgs": "",
        "defaultVariant": "temurin",
        "configFilePrefix": "configurations/",
        "configFileSuffix": "_pipeline_config.json",
        "repository": {
            "url": "https://github.com/adoptium/ci-adoptium-pipelines.git",
            "branch": "main",
            "credentialsId": "",
            "buildRepoUrl": "https://github.com/adoptium/temurin-build.git",
            "buildBranch": "master",
            "aqaRepoUrl": "https://github.com/adoptium/aqa-tests.git",
            "aqaBranch": "master"
        }
    }


def generate_jenkins_job_config() -> Dict[str, Any]:
    """Generate jenkins_job_config.json — Jenkins-specific configuration."""
    return {
        "jenkinsfilePath": "ci/jenkins/Jenkinsfile.declarative",
        "pipelineTimeoutHours": 8,
        "jobConfiguration": {
            "defaultParameters": {
                "VARIANT": "temurin",
                "CLEAN_WORKSPACE_AFTER_STAGE": True,
                "RUN_TESTS": True,
                "ENABLE_INSTALLERS": True,
                "SIGN_ARTIFACTS": True,
                "PUBLISH_ARTIFACTS": False,
                "RUN_REPRODUCIBLE_COMPARE": False
            },
            "logRotation": {
                "daysToKeep": 30,
                "numToKeep": 50,
                "artifactDaysToKeep": 7,
                "artifactNumToKeep": 10
            }
        },
        # Per-stage agent label templates. Placeholders {os} and {arch} are
        # substituted at runtime with the build job's TARGET_OS / ARCHITECTURE
        # values. Override any entry to route a stage to vendor-specific nodes
        # (e.g. a dedicated signing cluster or an OS-matched test worker).
        "stageAgentLabels": {
            "Initialize":                 "worker",
            "Build":                      "build&&{os}&&{arch}",
            "Internal Code Sign":         "eclipse-codesign",
            "Assemble Images":            "build&&{os}&&{arch}",
            "Post-Build Code Sign":       "worker",
            "Build Installers":           "build&&{os}&&{arch}",
            "Code Sign Installer":        "worker",
            "SBOM Sign":                  "worker",
            "Digital Artifact Sign":      "worker",
            "Verify Signing":             "worker",
            "Validate SBOM":              "build&&{os}&&{arch}",
            "Smoke Tests":                "build&&{os}&&{arch}",
            "Reproducible Compare Build": "build&&{os}&&{arch}",
            "AQA Tests":                  "build&&{arch}",
            "TCK Tests":                  "build&&{arch}",
            "Publish Artifacts":          "worker"
        }
    }


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
                print("4. Generate adoptium_pipeline_config.json")
                print("5. Generate jenkins_job_config.json")
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
            failed_files = []
            
            for i, temp_file in enumerate(sorted(converted_files), 1):
                # Extract version (remove 'u' suffix)
                version = extract_version_from_filename(temp_file.name)
                if not version:
                    print(f"[{i}/{len(converted_files)}] Skipping {temp_file.name} (could not extract version)")
                    continue
                
                try:
                    # Read the converted file
                    with open(temp_file, 'r') as f:
                        config = json.load(f)
                    
                    # Update version field (remove 'u' suffix)
                    config["version"] = version
                    
                    # Set openjdkVersion with 'u' suffix (e.g., jdk21u)
                    # This represents the OpenJDK version/stream, not an SCM reference
                    if not config.get("openjdkVersion"):
                        config["openjdkVersion"] = version + "u"
                    
                    # Remove obsolete fields from each platform entry
                    for platform_config in config.get("buildConfigurations", {}).values():
                        platform_config.pop("test", None)
                        platform_config.pop("additionalTestParams", None)
                        platform_config.pop("additionalTestLabels", None)

                    # Check if the job is disabled in the jdkNN(u).groovy file
                    is_disabled = check_if_job_disabled(args.source, version)
                    
                    # Add enabled flag (inverted from disabled)
                    config["enabled"] = not is_disabled
                    
                    version_configs.append(config)
                    
                    # Write to new location with corrected filename
                    output_file = config_dir / f"{version}_pipeline_config.json"
                    
                    status = "✅" if config["enabled"] else "⚠️  (disabled)"
                    print(f"[{i}/{len(converted_files)}] {temp_file.name} -> {output_file.name} {status}")
                    
                    with open(output_file, 'w') as f:
                        json.dump(config, f, indent=2)
                    
                    if args.verbose:
                        print(f"  Version: {config['version']}")
                        print(f"  OpenJDK Version: {config['openjdkVersion']}")
                        print(f"  Enabled: {config['enabled']}")
                        print(f"  Platforms: {len(config.get('buildConfigurations', {}))}")
                        print(f"  Removed obsolete fields: test, additionalTestParams, additionalTestLabels")
                
                except json.JSONDecodeError as e:
                    print(f"[{i}/{len(converted_files)}] {temp_file.name} ❌ (Invalid JSON: {e})")
                    failed_files.append((temp_file.name, str(e)))
                    if args.verbose:
                        print(f"  Error at line {e.lineno}, column {e.colno}")
                        print(f"  You may need to manually review: {temp_file}")
                except Exception as e:
                    print(f"[{i}/{len(converted_files)}] {temp_file.name} ❌ ({e})")
                    failed_files.append((temp_file.name, str(e)))
            
            # Step 3: Generate adoptium_pipeline_config.json and jenkins_job_config.json
            if version_configs:
                print()
                print("Step 3: Generating adoptium_pipeline_config.json...")

                adoptium_config = generate_adoptium_pipeline_config(version_configs)
                adoptium_config_file = args.output / "adoptium_pipeline_config.json"

                with open(adoptium_config_file, 'w') as f:
                    json.dump(adoptium_config, f, indent=2)

                print(f"  Created: {adoptium_config_file.name}")

                if args.verbose:
                    print(f"  Active versions: {adoptium_config['activeJdkVersions']}")

                print()
                print("Step 4: Generating jenkins_job_config.json...")

                jenkins_config = generate_jenkins_job_config()
                jenkins_config_file = args.output / "jenkins_job_config.json"

                with open(jenkins_config_file, 'w') as f:
                    json.dump(jenkins_config, f, indent=2)

                print(f"  Created: {jenkins_config_file.name}")
            
            # Print summary
            print()
            print("=" * 70)
            print("Conversion Summary")
            print("=" * 70)
            print(f"Total files: {len(converted_files)}")
            print(f"Successful: {len(version_configs)}")
            print(f"Failed: {len(failed_files)}")
            print()
            
            if failed_files:
                print("⚠️  Some files failed to convert:")
                for filename, error in failed_files:
                    print(f"  ❌ {filename}: {error}")
                print()
                print("Note: Failed files were skipped. You may need to convert them manually.")
                print()
            
            if version_configs:
                print("✅ Successfully converted configurations!")
            else:
                print("❌ No configurations were successfully converted")
                return 1
            print()
            print("Generated files:")
            print(f"  - adoptium_pipeline_config.json  (CI-agnostic)")
            print(f"  - jenkins_job_config.json         (Jenkins-specific)")
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
