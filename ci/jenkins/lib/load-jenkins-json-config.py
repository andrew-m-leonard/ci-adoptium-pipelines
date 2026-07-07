#!/usr/bin/env python3
"""
Jenkins-specific configuration loader — generateJenkinsConfig().

Reads jenkins_job_config.json from the config repository root and
pipeline-config.json (produced by scripts/lib/load-json-config.py) from the
working directory.  Resolves the stageAgentLabels {os}/{arch} placeholders to
their sw.os.* / hw.arch.* schema label tokens and writes a new, separate
jenkins-config.json containing:

  stageAgentLabels         — raw templates from jenkins_job_config.json
  resolvedStageAgentLabels — fully substituted labels ready for Jenkins node()
  buildNodeLabel           — resolved Build-stage label (convenience top-level key)

pipeline-config.json is read-only; it is never modified by this script.

This script is intentionally separate from scripts/lib/load-json-config.py,
which is CI-agnostic.  Only the Jenkins CI flow calls this script.

Usage:
    python3 ci/jenkins/lib/load-jenkins-json-config.py \\
        --config-repo-path ./config-repo \\
        --pipeline-config  ./pipeline-config.json \\
        --output           ./jenkins-config.json
"""

import json
import sys
import argparse
from pathlib import Path


# ---------------------------------------------------------------------------
# Label-schema mappings (self-contained — no dependency on load-json-config.py)
# ---------------------------------------------------------------------------

# Mapping from temurin-build arch values to aqa-tests hw.arch.* label tokens.
# Note: x64 and x86-32 both map to hw.arch.x86 — the aqa-tests schema uses
# hw.arch.x86 for the whole x86 family; there is no hw.arch.x86-64.
_ARCH_TO_LABEL = {
    'x64':     'hw.arch.x86',
    'x86-32':  'hw.arch.x86',
    'aarch64': 'hw.arch.aarch64',
    'arm':     'hw.arch.aarch32',
    'ppc64':   'hw.arch.ppc64',
    'ppc64le': 'hw.arch.ppc64le',
    's390x':   'hw.arch.s390x',
    'riscv64': 'hw.arch.riscv',
    'sparcv9': 'hw.arch.sparcv9',
}

# Mapping from temurin-build os values to aqa-tests sw.os.* label tokens.
_OS_TO_LABEL = {
    'linux':        'sw.os.linux',
    'alpine-linux': 'sw.os.alpine-linux',
    'mac':          'sw.os.mac',
    'windows':      'sw.os.windows',
    'aix':          'sw.os.aix',
    'solaris':      'sw.os.solaris',
    'zos':          'sw.os.zos',
}


def _resolve_label(template, target_os, architecture):
    """Resolve {os} and {arch} placeholders in a label template."""
    os_label   = _OS_TO_LABEL.get(target_os, f'sw.os.{target_os}')
    arch_label = _ARCH_TO_LABEL.get(architecture, f'hw.arch.{architecture}')
    return template.replace('{os}', os_label).replace('{arch}', arch_label)


def generateJenkinsConfig(config_repo_path, pipeline_config_path, output_path):
    """Generate jenkins-config.json from jenkins_job_config.json.

    Reads TARGET_OS and ARCHITECTURE from pipeline-config.json to resolve
    label placeholders, then writes a new jenkins-config.json with:
      stageAgentLabels         — raw templates
      resolvedStageAgentLabels — fully substituted sw.os.* / hw.arch.* labels
      buildNodeLabel           — resolved Build-stage label (convenience key)

    pipeline-config.json is never modified.

    Args:
        config_repo_path:    Path to the config repository root.
        pipeline_config_path: Path to the existing pipeline-config.json (read-only).
        output_path:          Path to write jenkins-config.json.
    """
    # Load jenkins_job_config.json
    jenkins_config_path = Path(config_repo_path) / 'jenkins_job_config.json'
    if not jenkins_config_path.exists():
        print(f"ERROR: jenkins_job_config.json not found: {jenkins_config_path}", file=sys.stderr)
        sys.exit(1)

    with open(jenkins_config_path, 'r') as f:
        jenkins_config = json.load(f)

    stage_agent_labels = jenkins_config.get('stageAgentLabels', {})
    if not stage_agent_labels:
        print("WARNING: stageAgentLabels is empty in jenkins_job_config.json", file=sys.stderr)

    # Read TARGET_OS and ARCHITECTURE from the CI-agnostic pipeline-config.json
    pipeline_config_path = Path(pipeline_config_path)
    if not pipeline_config_path.exists():
        print(f"ERROR: pipeline-config.json not found: {pipeline_config_path}", file=sys.stderr)
        sys.exit(1)

    with open(pipeline_config_path, 'r') as f:
        pipeline_config = json.load(f)

    target_os    = pipeline_config['buildConfig']['TARGET_OS']
    architecture = pipeline_config['buildConfig']['ARCHITECTURE']

    # Resolve {os}/{arch} placeholders to sw.os.* / hw.arch.* schema tokens
    resolved = {
        stage: _resolve_label(template, target_os, architecture)
        for stage, template in stage_agent_labels.items()
    }

    # Build the jenkins-config.json output — pipeline-config.json is not touched
    build_node_label = resolved.get('Build', '')
    additional_node_labels = pipeline_config['buildConfig'].get('ADDITIONAL_NODE_LABELS', '')
    if build_node_label and additional_node_labels:
        build_node_label = build_node_label + '&&' + additional_node_labels

    jenkins_out = {
        'stageAgentLabels':         stage_agent_labels,
        'resolvedStageAgentLabels': resolved,
        'buildNodeLabel':           build_node_label,
    }

    with open(Path(output_path), 'w') as f:
        json.dump(jenkins_out, f, indent=2)

    print(f"✓ Created {output_path}")
    print(f"  Build node label: {build_node_label}")


def main():
    parser = argparse.ArgumentParser(
        description='Generate jenkins-config.json from jenkins_job_config.json',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Example:
  python3 ci/jenkins/lib/load-jenkins-json-config.py \\
      --config-repo-path ./config-repo \\
      --pipeline-config  ./pipeline-config.json \\
      --output           ./jenkins-config.json
        """
    )
    parser.add_argument(
        '--config-repo-path', required=True,
        help='Path to the config repository root (contains jenkins_job_config.json)'
    )
    parser.add_argument(
        '--pipeline-config', default='./pipeline-config.json',
        help='Path to the CI-agnostic pipeline-config.json to read (default: ./pipeline-config.json)'
    )
    parser.add_argument(
        '--output', default='./jenkins-config.json',
        help='Path to write jenkins-config.json (default: ./jenkins-config.json)'
    )
    args = parser.parse_args()
    generateJenkinsConfig(args.config_repo_path, args.pipeline_config, args.output)
    return 0


if __name__ == '__main__':
    sys.exit(main())
