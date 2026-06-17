#!/usr/bin/env python3
"""
Load JSON Configuration and Generate Pipeline Config

This script loads the jdkNN_pipeline_config.json file and generates
the pipeline-config.json needed by the build stages.

This is CI-agnostic and can be used by Jenkins, GitLab CI, GitHub Actions, etc.

Usage:
    python3 load-json-config.py \
        --jdk-version jdk21u \
        --variant temurin \
        --target-os mac \
        --architecture aarch64 \
        --config-dir ./configurations \
        --output-dir .
"""

import json
import sys
import argparse
from pathlib import Path


def get_platform_key(architecture, target_os):
    """Construct platform key from architecture and OS"""
    # Capitalize first letter of OS
    os_capitalized = target_os[0].upper() + target_os[1:]
    return f"{architecture}{os_capitalized}"


def extract_variant_value(value, variant):
    """
    Extract variant-specific value from config.
    Handles both simple strings and variant-specific objects.

    Args:
        value: Either a string or a dict with variant keys
        variant: The variant to extract (temurin, openj9, hotspot)

    Returns:
        The value for the specified variant, or None
    """
    if value is None:
        return None

    # If it's a string, return it directly
    if isinstance(value, str):
        return value

    # If it's a dict, extract variant-specific value
    if isinstance(value, dict):
        variant_value = value.get(variant)
        if variant_value is not None:
            return variant_value
        # Fall back to 'default' key if present
        return value.get('default')

    return None


def get_test_list(test_config, is_release, is_weekly):
    """Get test list based on build type"""
    if test_config is None or test_config == 'default':
        return []

    if isinstance(test_config, dict):
        if is_release and 'release' in test_config:
            return test_config['release']
        elif is_weekly and 'weekly' in test_config:
            return test_config['weekly']
        elif 'nightly' in test_config:
            return test_config['nightly']

    return []


def build_node_label(target_os, architecture, additional_labels):
    """Build node label from components"""
    labels = ['build', target_os, architecture]

    if additional_labels:
        labels.insert(0, additional_labels)

    return '&&'.join(labels)


def load_configuration(args):
    """Load JSON configuration and generate pipeline configs"""

    jdk_version = args.jdk_version
    variant = args.variant
    target_os = args.target_os
    architecture = args.architecture
    config_dir = args.config_dir
    output_dir = args.output_dir

    # Optional parameters
    is_release = args.release
    is_weekly = args.weekly
    scm_ref = args.scm_ref or 'master'
    build_ref = args.build_ref or 'master'
    build_repo_url = args.build_repo_url or 'https://github.com/adoptium/temurin-build.git'
    enable_tests = args.enable_tests
    enable_installers = args.enable_installers
    enable_signer = args.enable_signer
    ea_beta_build = args.ea_beta_build
    compare_build = args.compare_build

    print(f"Loading configuration for: {jdk_version} {variant} {target_os} {architecture}")

    # Load JSON configuration file
    config_file = Path(config_dir) / f"{jdk_version}_pipeline_config.json"
    if not config_file.exists():
        print(f"ERROR: Configuration file not found: {config_file}", file=sys.stderr)
        sys.exit(1)

    with open(config_file, 'r') as f:
        json_config = json.load(f)

    build_configurations = json_config.get('buildConfigurations', {})

    # Construct platform key
    platform_key = get_platform_key(architecture, target_os)
    print(f"Platform key: {platform_key}")

    # Get platform configuration
    if platform_key not in build_configurations:
        available = ', '.join(build_configurations.keys())
        print(f"ERROR: Platform '{platform_key}' not found in configuration.", file=sys.stderr)
        print(f"Available platforms: {available}", file=sys.stderr)
        sys.exit(1)

    platform_config = build_configurations[platform_key]

    # Extract variant-specific values
    build_args = extract_variant_value(platform_config.get('buildArgs'), variant)
    configure_args = extract_variant_value(platform_config.get('configureArgs'), variant)
    docker_image = extract_variant_value(platform_config.get('dockerImage'), variant)
    docker_file = extract_variant_value(platform_config.get('dockerFile'), variant)
    additional_node_labels = extract_variant_value(platform_config.get('additionalNodeLabels'), variant)
    additional_test_labels = extract_variant_value(platform_config.get('additionalTestLabels'), variant)

    # Determine test list based on build type
    test_list = get_test_list(platform_config.get('test'), is_release, is_weekly)

    # Build node label
    node_label = build_node_label(target_os, architecture, additional_node_labels)

    # Create pipeline-config.json (new format only)
    pipeline_config = {
        'buildConfig': {
            'JAVA_TO_BUILD': jdk_version,
            'TARGET_OS': target_os,
            'ARCHITECTURE': architecture,
            'VARIANT': variant,
            'BUILD_ARGS': build_args or '',
            'CONFIGURE_ARGS': configure_args or '',
            'NODE_LABEL': node_label,
            'DOCKER_IMAGE': docker_image or '',
            'DOCKER_FILE': docker_file or '',
            'DOCKER_REGISTRY': platform_config.get('dockerRegistry', ''),
            'DOCKER_CREDENTIAL': platform_config.get('dockerCredential', ''),
            'DOCKER_ARGS': platform_config.get('dockerArgs', ''),
            'TEST_LIST': test_list,
            'ADDITIONAL_TEST_LABEL': additional_test_labels or ''
        },
        'parameters': {
            'enableTests': enable_tests,
            'enableInstallers': enable_installers,
            'enableSigner': enable_signer,
            'cleanWorkspaceAfterStage': platform_config.get('cleanWorkspaceAfterBuild', True),
            'eaBetaBuild': ea_beta_build,
            'compareBuild': compare_build,
            'release': is_release
        },
        'refs': {
            'scmRef': scm_ref,
            'buildRef': build_ref,
            'buildRepoUrl': build_repo_url
        }
    }

    # Add additional test params if present
    if 'additionalTestParams' in platform_config:
        variant_test_params = platform_config['additionalTestParams'].get(variant)
        if variant_test_params:
            pipeline_config['buildConfig']['ADDITIONAL_TEST_PARAMS'] = variant_test_params

    # Save configuration
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    pipeline_config_file = output_path / 'pipeline-config.json'
    with open(pipeline_config_file, 'w') as f:
        json.dump(pipeline_config, f, indent=2)
    print(f"Created: {pipeline_config_file}")

    print("\nConfiguration Summary:")
    print(f"  Platform: {platform_key}")
    print(f"  Build Args: {build_args}")
    print(f"  Configure Args: {configure_args}")
    print(f"  Node Label: {node_label}")
    print(f"  Docker Image: {docker_image}")
    print(f"  Tests: {len(test_list)} test suites")

    return pipeline_config


def main():
    parser = argparse.ArgumentParser(
        description='Load JSON configuration and generate pipeline configs',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Basic usage
  python3 load-json-config.py \\
      --jdk-version jdk21u \\
      --variant temurin \\
      --target-os mac \\
      --architecture aarch64

  # Release build
  python3 load-json-config.py \\
      --jdk-version jdk17u \\
      --variant temurin \\
      --target-os linux \\
      --architecture x64 \\
      --release \\
      --scm-ref jdk-17.0.10+7

  # Disable tests and installers
  python3 load-json-config.py \\
      --jdk-version jdk21u \\
      --variant temurin \\
      --target-os mac \\
      --architecture aarch64 \\
      --no-tests \\
      --no-installers
        """
    )

    # Required arguments
    parser.add_argument('--jdk-version', required=True, help='JDK version (e.g., jdk21u, jdk17u)')
    parser.add_argument('--variant', required=True, help='Build variant (temurin, openj9, hotspot)')
    parser.add_argument('--target-os', required=True, help='Target OS (mac, linux, windows, aix)')
    parser.add_argument('--architecture', required=True, help='Target architecture (aarch64, x64, x32, ppc64, s390x)')

    # Optional arguments
    parser.add_argument('--config-dir', default='./configurations', help='Configuration directory (default: ./configurations)')
    parser.add_argument('--output-dir', default='.', help='Output directory for generated configs (default: .)')

    parser.add_argument('--release', action='store_true', help='Release build')
    parser.add_argument('--weekly', action='store_true', help='Weekly build')
    parser.add_argument('--scm-ref', help='OpenJDK source branch/tag (default: master)')
    parser.add_argument('--build-ref', help='temurin-build branch/tag (default: master)')
    parser.add_argument('--build-repo-url', help='temurin-build repository URL (default: https://github.com/adoptium/temurin-build.git)')

    parser.add_argument('--no-tests', dest='enable_tests', action='store_false', help='Disable tests')
    parser.add_argument('--no-installers', dest='enable_installers', action='store_false', help='Disable installers')
    parser.add_argument('--no-signer', dest='enable_signer', action='store_false', help='Disable signing')
    parser.add_argument('--ea-beta-build', dest='ea_beta_build', action='store_true', help='Enable EA/Beta build (adds --with-version-opt=ea to configure args)')
    parser.add_argument('--compare-build', dest='compare_build', action='store_true', help='Enable reproducible build comparison with path padding (requires --scm-ref)')
    parser.set_defaults(enable_tests=True, enable_installers=True, enable_signer=True, ea_beta_build=False, compare_build=False)

    args = parser.parse_args()

    try:
        load_configuration(args)
        print("\n✅ Configuration loaded successfully")
        return 0
    except Exception as e:
        print(f"\n❌ Error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1


if __name__ == '__main__':
    sys.exit(main())

# Made with Bob
