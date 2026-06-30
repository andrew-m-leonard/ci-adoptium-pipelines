#!/usr/bin/env python3
"""
Local Pipeline Runner

Run the complete OpenJDK build pipeline locally from the command line.
This script orchestrates all stages: initialize, build, sign, installer, and tests.

Usage:
    python3 run-pipeline.py \
        --jdk-version jdk21u \
        --target-os mac \
        --architecture aarch64 \
        --workspace ~/openjdk-build

Example:
    # Full build with all stages
    python3 run-pipeline.py --jdk-version jdk21u --target-os mac --architecture aarch64

    # Build only (skip tests and installers)
    python3 run-pipeline.py --jdk-version jdk21u --target-os mac --architecture aarch64 --no-tests --no-installers

    # Build with custom branch
    python3 run-pipeline.py --jdk-version jdk21u --target-os mac --architecture aarch64 --build-ref develop
"""

import argparse
import os
import sys
import subprocess
import json
from pathlib import Path
from datetime import datetime
from workspace_manager import WorkspaceManager
from stage_resolver import StageResolver


class PipelineRunner:
    # Define stage order
    STAGES = ['initialize', 'build', 'validate-sbom', 'sign', 'installer', 'smoke-tests', 'reproducible-compare']

    def __init__(self, args):
        self.args = args
        self.script_dir = Path(__file__).parent.parent.parent.resolve()  # Go up to ci-adoptium-pipelines root

        # Initialize workspace manager
        pipeline_workspace = Path(args.workspace).expanduser().resolve()
        config_file = pipeline_workspace / 'pipeline-config.json'
        self.workspace_mgr = WorkspaceManager(pipeline_workspace, config_file)

        # Convenience properties for backward compatibility
        self.pipeline_workspace = self.workspace_mgr.pipeline_workspace
        self.stage_workspace = self.workspace_mgr.stage_workspace
        self.artifacts_dir = self.workspace_mgr.artifacts_dir
        self.workspace = self.pipeline_workspace
        self.config_file = self.workspace_mgr.config_file
        self.build_number = args.build_number or f"local-{datetime.now().strftime('%Y%m%d-%H%M%S')}"

        # StageResolver is initialised lazily after stage_initialize() has
        # cloned the config repo.  _make_resolver() is called at the start
        # of each stage_*() method to ensure it is always up-to-date.
        self._resolver: StageResolver | None = None

        # Validate reproducible compare parameters
        if args.compare_build:
            if not args.scm_ref:
                raise ValueError(
                    "ERROR: --compare-build requires --scm-ref to be specified.\n"
                    "The SCM reference is needed to download the production binary from Adoptium API.\n"
                    "Example: --scm-ref jdk-21.0.2+13 --compare-build"
                )

        # Determine which stages to run
        if args.start_from_stage:
            if args.start_from_stage not in self.STAGES:
                raise ValueError(f"Invalid stage: {args.start_from_stage}. Must be one of: {', '.join(self.STAGES)}")
            start_index = self.STAGES.index(args.start_from_stage)
            self.stages_to_run = self.STAGES[start_index:]
            print(f"ℹ️  Starting from stage: {args.start_from_stage}")
            print(f"   Will run: {', '.join(self.stages_to_run)}")
        else:
            self.stages_to_run = self.STAGES.copy()

    def _make_resolver(self) -> StageResolver:
        """
        Return a StageResolver, (re-)creating it if the config repo has
        been cloned since the last call (i.e. after stage_initialize()).

        The resolver reads parameters from pipeline-config.json (CONFIG_FILE)
        to gate stages (enableTests, enableSigner, etc.).
        """
        config_repo_root = None
        if self.args.config_repo_url:
            candidate = self.workspace / 'config-repo'
            if candidate.exists():
                config_repo_root = candidate

        if self._resolver is None or (
            config_repo_root is not None
            and self._resolver.config_repo_root != config_repo_root
        ):
            self._resolver = StageResolver(
                self.script_dir, config_repo_root, self.config_file
            )
            src = str(config_repo_root) if config_repo_root else 'defaults only'
            print(f"ℹ️  StageResolver initialised (config repo: {src})")

        return self._resolver

    def _load_adoptium_pipeline_config(self, config_repo_dir: Path) -> dict:
        """
        Load adoptium_pipeline_config.json from the config repo directory.

        Returns the parsed dict, or {} if the file does not exist (graceful
        fallback for repos that haven't adopted the split yet).
        """
        cfg_path = config_repo_dir / 'adoptium_pipeline_config.json'
        if not cfg_path.exists():
            print(f"ℹ️  adoptium_pipeline_config.json not found in config repo — using defaults")
            return {}

        with open(cfg_path, 'r') as f:
            cfg = json.load(f)

        print(f"✅ Loaded adoptium_pipeline_config.json")
        print(f"   Default variant: {cfg.get('defaultVariant', 'temurin')}")
        active = [v['version'] for v in cfg.get('activeJdkVersions', []) if v.get('enabled')]
        if active:
            print(f"   Active JDK versions: {', '.join(active)}")
        return cfg

    def run(self):
        """Run the complete pipeline"""
        print("=" * 80)
        print("OpenJDK Build Pipeline - Local Runner")
        print("=" * 80)
        print(f"Workspace: {self.workspace}")
        print(f"Build Number: {self.build_number}")
        print()

        # Validate and setup workspace using WorkspaceManager
        self.workspace_mgr.validate_and_setup(
            is_restarting=self.args.start_from_stage is not None,
            clean_requested=self.args.clean_workspace,
            start_from_stage=self.args.start_from_stage
        )

        try:
            # Stage 1: Initialize (generate configuration)
            if 'initialize' in self.stages_to_run:
                self.stage_initialize()

            # Stage 2: Build
            if 'build' in self.stages_to_run and not self.args.skip_build:
                self.stage_build()

            # Stage 3: Validate SBOM (if SBOM generation is enabled)
            if 'validate-sbom' in self.stages_to_run and not self.args.skip_build:
                self.stage_validate_sbom()

            # Stage 4: Sign (enablement gated by pipeline-config.json parameters.enableSigner)
            if 'sign' in self.stages_to_run and not self.args.skip_build:
                self.stage_sign()

            # Stage 5: Build Installers (enablement gated by pipeline-config.json parameters.enableInstallers)
            if 'installer' in self.stages_to_run and not self.args.skip_build:
                self.stage_installer()

            # Stage 6: Smoke Tests (enablement gated by pipeline-config.json parameters.enableTests)
            if 'smoke-tests' in self.stages_to_run and not self.args.skip_build:
                self.stage_smoke_tests()

            # Stage 7: Reproducible Compare (enablement gated by pipeline-config.json parameters.compareBuild)
            if 'reproducible-compare' in self.stages_to_run and not self.args.skip_build:
                self.stage_reproducible_compare()

            print()
            print("=" * 80)
            print("✅ Pipeline completed successfully!")
            print("=" * 80)
            print(f"\n📦 All artifacts in: {self.artifacts_dir}")
            print(f"   - JDK tarballs")
            print(f"   - Signed artifacts")
            print(f"   - Installers")
            print(f"   - Test results")
            if self.args.compare_build:
                print(f"   - Reproducible build comparison results")

            return 0

        except subprocess.CalledProcessError as e:
            print()
            print("=" * 80)
            print(f"❌ Pipeline failed at stage: {e.cmd[0] if e.cmd else 'unknown'}")
            print(f"Exit code: {e.returncode}")
            print("=" * 80)
            return e.returncode
        except Exception as e:
            print()
            print("=" * 80)
            print(f"❌ Pipeline failed with error: {e}")
            print("=" * 80)
            return 1

    def stage_initialize(self):
        """Stage 1: Generate pipeline configuration"""
        print("\n" + "=" * 80)
        print("STAGE 1: Initialize - Generate Configuration")
        print("=" * 80)

        # Pre-cleanup: Always clean stage_workspace before stage
        self.workspace_mgr.cleanup_stage_workspace('pre')

        # Determine configuration directory
        if self.args.config_repo_url:
            # Clone external configuration repository
            config_repo_dir = self.workspace / 'config-repo'
            if config_repo_dir.exists():
                print(f"ℹ️  Configuration repository already exists: {config_repo_dir}")
                print("   (Use --clean-workspace to re-clone)")
            else:
                print(f"📥 Cloning configuration repository...")
                print(f"   URL: {self.args.config_repo_url}")
                print(f"   Branch: {self.args.config_repo_branch}")

                clone_cmd = [
                    'git', 'clone',
                    '--branch', self.args.config_repo_branch,
                    '--depth', '1',
                    self.args.config_repo_url,
                    str(config_repo_dir)
                ]
                subprocess.run(clone_cmd, check=True)
                print("✅ Configuration repository cloned")

            # Load adoptium_pipeline_config.json (CI-agnostic defaults)
            adoptium_cfg = self._load_adoptium_pipeline_config(config_repo_dir)

            # Use configFilePrefix from adoptium_pipeline_config.json to locate configs
            config_prefix = adoptium_cfg.get('configFilePrefix', 'configurations/')
            # Strip trailing slash for path joining
            config_dir = config_repo_dir / config_prefix.rstrip('/')
            if not config_dir.exists():
                raise FileNotFoundError(
                    f"Configuration directory not found: {config_dir}\n"
                    f"Expected '{config_prefix}' subdirectory in repository"
                )
        else:
            adoptium_cfg = {}
            # Use local configurations directory
            config_dir = self.script_dir / 'configurations'
            if not config_dir.exists():
                raise FileNotFoundError(
                    f"Configuration directory not found: {config_dir}\n"
                    f"Please provide --config-repo-url or ensure local configurations exist"
                )

        print(f"📁 Using configuration directory: {config_dir}")

        # Resolve variant from adoptium_pipeline_config.json (CLI override removed)
        variant = adoptium_cfg.get('defaultVariant', 'temurin')
        print(f"   Variant: {variant}")

        # Build command for load-json-config.py
        cmd = [
            'python3',
            str(self.script_dir / 'scripts' / 'lib' / 'load-json-config.py'),
            '--jdk-version', self.args.jdk_version,
            '--variant', variant,
            '--target-os', self.args.target_os,
            '--architecture', self.args.architecture,
            '--config-dir', str(config_dir),
            '--output-dir', str(self.workspace)
        ]

        # Add optional parameters
        if self.args.release_type:
            # Convert to uppercase for case-insensitive handling
            release_type = self.args.release_type.upper()
            
            # Validate release type
            valid_release_types = ['NIGHTLY', 'WEEKLY', 'RELEASE']
            if release_type not in valid_release_types:
                raise ValueError(f"Invalid release type '{self.args.release_type}'. Must be one of: {', '.join(valid_release_types)} (case-insensitive)")
            
            cmd.extend(['--release-type', release_type])
        if self.args.scm_ref:
            cmd.extend(['--scm-ref', self.args.scm_ref])

        # Resolve build/aqa refs and repo URLs from CLI override or adoptium_pipeline_config.json
        repo_cfg = adoptium_cfg.get('repository', {})
        build_ref = self.args.build_ref or repo_cfg.get('buildBranch')
        aqa_ref = self.args.aqa_ref or repo_cfg.get('aqaBranch')
        build_repo_url = self.args.build_repo_url or repo_cfg.get('buildRepoUrl')
        aqa_repo_url = self.args.aqa_repo_url or repo_cfg.get('aqaRepoUrl')

        # These must be resolved — error if missing from both CLI and config
        missing = []
        if not build_ref:
            missing.append('repository.buildBranch')
        if not aqa_ref:
            missing.append('repository.aqaBranch')
        if not build_repo_url:
            missing.append('repository.buildRepoUrl')
        if not aqa_repo_url:
            missing.append('repository.aqaRepoUrl')
        if missing:
            raise ValueError(
                f"Required fields missing from adoptium_pipeline_config.json: {', '.join(missing)}\n"
                f"These must be defined in the config repo or overridden via CLI args."
            )

        cmd.extend(['--build-ref', build_ref])
        cmd.extend(['--aqa-ref', aqa_ref])
        cmd.extend(['--build-repo-url', build_repo_url])
        cmd.extend(['--aqa-repo-url', aqa_repo_url])
        if not self.args.enable_tests:
            cmd.append('--no-tests')
        if not self.args.enable_installers:
            cmd.append('--no-installers')
        if not self.args.enable_signer:
            cmd.append('--no-signer')
        if (self.args.release_type or '').upper() == 'WEEKLY':
            cmd.append('--ea-beta-build')
        if self.args.compare_build:
            cmd.append('--compare-build')

        print(f"Running: {' '.join(cmd)}")
        subprocess.run(cmd, check=True)

        # Verify configuration was created
        if not self.config_file.exists():
            raise FileNotFoundError(f"Configuration file not created: {self.config_file}")

        # Display configuration
        with open(self.config_file, 'r') as f:
            config = json.load(f)

        print("\nGenerated Configuration:")
        print(json.dumps(config, indent=2))
        print("\n✅ Initialize stage complete")

        # Post-stage cleanup (config now exists, so cleanup can read it)
        self.workspace_mgr.cleanup_stage_workspace('post')

    def stage_build(self):
        """Stage 2: Build OpenJDK"""
        print("\n" + "=" * 80)
        print("STAGE 2: Build OpenJDK")
        print("=" * 80)

        # Pre-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('pre')

        # Ensure artifacts directory exists
        self.artifacts_dir.mkdir(parents=True, exist_ok=True)

        env = os.environ.copy()
        env['WORKSPACE'] = str(self.stage_workspace)
        env['PIPELINE_ROOT'] = str(self.script_dir)
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['TARGET_DIR'] = str(self.artifacts_dir)
        # Build stage doesn't need INPUT_ARTIFACTS_DIR (first stage)

        exit_code = self._make_resolver().run('02-build', env)
        if exit_code != 0:
            raise subprocess.CalledProcessError(exit_code, '02-build')
        print("\n✅ Build stage complete")
        print(f"   Artifacts in: {self.artifacts_dir}")

        # Post-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('post')
    
    def stage_validate_sbom(self):
        """Stage 3: Validate SBOM files (if SBOM generation is enabled)"""
        # Check if SBOM generation is enabled by reading the config
        try:
            with open(self.config_file, 'r') as f:
                config = json.load(f)
                build_args = config.get('buildConfig', {}).get('BUILD_ARGS', '')
                
                if '--create-sbom' not in build_args:
                    print("\nℹ️  Skipping SBOM validation (--create-sbom not in BUILD_ARGS)")
                    return
        except Exception as e:
            print(f"\n⚠️  Warning: Could not read config file to check SBOM status: {e}")
            print("   Skipping SBOM validation")
            return

        print("\n" + "=" * 80)
        print("STAGE 3: Validate SBOM")
        print("=" * 80)

        # Pre-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('pre')

        env = os.environ.copy()
        env['WORKSPACE'] = str(self.stage_workspace)
        env['PIPELINE_ROOT'] = str(self.script_dir)
        env['CONFIG_FILE'] = str(self.config_file)
        env['INPUT_ARTIFACTS_DIR'] = str(self.artifacts_dir)
        env['TARGET_DIR'] = str(self.artifacts_dir)

        exit_code = self._make_resolver().run('12-validate-sbom', env)
        if exit_code != 0:
            print(f"\n❌ SBOM validation failed with exit code {exit_code}")
            raise subprocess.CalledProcessError(exit_code, '12-validate-sbom')
        print("\n✅ SBOM validation stage complete")

        # Post-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('post')

    def stage_sign(self):
        """Stage 3: Sign artifacts"""
        print("\n" + "=" * 80)
        print("STAGE 3: Sign Artifacts")
        print("=" * 80)

        # Pre-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('pre')

        env = os.environ.copy()
        env['WORKSPACE'] = str(self.stage_workspace)
        env['PIPELINE_ROOT'] = str(self.script_dir)
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['INPUT_ARTIFACTS_DIR'] = str(self.artifacts_dir)
        env['TARGET_DIR'] = str(self.artifacts_dir)

        exit_code = self._make_resolver().run('06-sign', env)
        if exit_code != 0:
            raise subprocess.CalledProcessError(exit_code, '06-sign')
        print("\n✅ Sign stage complete")

        # Post-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('post')

    def stage_installer(self):
        """Stage 4: Build installers"""
        print("\n" + "=" * 80)
        print("STAGE 4: Build Installers")
        print("=" * 80)

        # Pre-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('pre')

        env = os.environ.copy()
        env['WORKSPACE'] = str(self.stage_workspace)
        env['PIPELINE_ROOT'] = str(self.script_dir)
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['INPUT_ARTIFACTS_DIR'] = str(self.artifacts_dir)
        env['TARGET_DIR'] = str(self.artifacts_dir)

        exit_code = self._make_resolver().run('07-installer', env)
        if exit_code != 0:
            raise subprocess.CalledProcessError(exit_code, '07-installer')
        print("\n✅ Installer stage complete")

        # Post-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('post')

    def stage_smoke_tests(self):
        """Stage 5: Run smoke tests"""
        print("\n" + "=" * 80)
        print("STAGE 5: Smoke Tests")
        print("=" * 80)

        # Pre-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('pre')

        env = os.environ.copy()
        env['WORKSPACE'] = str(self.stage_workspace)
        env['PIPELINE_ROOT'] = str(self.script_dir)
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['INPUT_ARTIFACTS_DIR'] = str(self.artifacts_dir)
        env['TARGET_DIR'] = str(self.artifacts_dir)

        exit_code = self._make_resolver().run('13-smoke-tests', env)
        if exit_code != 0:
            raise subprocess.CalledProcessError(exit_code, '13-smoke-tests')
        print("\n✅ Smoke tests complete")

        # Post-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('post')

    def stage_reproducible_compare(self):
        """Stage 6: Reproducible build comparison"""
        print("\n" + "=" * 80)
        print("STAGE 6: Reproducible Build Comparison")
        print("=" * 80)

        # Pre-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('pre')

        env = os.environ.copy()
        env['WORKSPACE'] = str(self.stage_workspace)
        env['PIPELINE_ROOT'] = str(self.script_dir)
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['INPUT_ARTIFACTS_DIR'] = str(self.artifacts_dir)
        env['TARGET_DIR'] = str(self.artifacts_dir)
        env['SCM_REF'] = self.args.scm_ref
        # Set RELEASE based on release_type (true if RELEASE, false otherwise)
        release_type = (self.args.release_type or 'NIGHTLY').upper()
        env['RELEASE'] = 'true' if release_type == 'RELEASE' else 'false'

        # Optional: BUILD_REPO_URL and BUILD_REF
        if self.args.build_repo_url:
            env['BUILD_REPO_URL'] = self.args.build_repo_url
        if self.args.build_ref:
            env['BUILD_REF'] = self.args.build_ref

        print(f"  Note: Compares locally built JDK against production Adoptium binary")

        # Capture exit code without raising — result drives UNSTABLE-equivalent behaviour
        comparison_exit_code = self._make_resolver().run('20-reproducible-compare', env)

        # Check for comparison results in stage_workspace
        comparison_report = self.stage_workspace / 'reproducible-compare' / 'comparison-report.txt'
        reprotest_diff = self.stage_workspace / 'reproducible-compare' / 'reprotest.diff'
        reproducible_percent = self.stage_workspace / 'reproducible-compare' / 'ReproduciblePercent'
        reproducible_log = self.stage_workspace / 'reproducible-compare' / 'reproducible_evidence.log'

        print(f"\nComparison exit code: {comparison_exit_code}")

        # Display results
        if comparison_exit_code == 0:
            print("✅ SUCCESS: Build is 100% reproducible")

            # Show reproducibility percentage if available
            if reproducible_percent.exists():
                percent = reproducible_percent.read_text().strip()
                print(f"   Reproducibility: {percent}%")
        else:
            print(f"❌ FAILED: Reproducible build comparison failed (exit code: {comparison_exit_code})")

            # Show comparison report if available
            if comparison_report.exists():
                print("\n📄 Comparison Report:")
                print(comparison_report.read_text())

            # Show reprotest.diff if available
            if reprotest_diff.exists():
                print("\n📄 Differences (reprotest.diff):")
                diff_content = reprotest_diff.read_text()
                # Show first 50 lines to avoid overwhelming output
                diff_lines = diff_content.split('\n')
                if len(diff_lines) > 50:
                    print('\n'.join(diff_lines[:50]))
                    print(f"\n... ({len(diff_lines) - 50} more lines, see {reprotest_diff})")
                else:
                    print(diff_content)

            # Show reproducibility percentage if available
            if reproducible_percent.exists():
                percent = reproducible_percent.read_text().strip()
                print(f"\n   Reproducibility: {percent}%")

        # List all comparison artifacts
        print(f"\n📁 Comparison artifacts saved to: {self.stage_workspace / 'reproducible-compare'}")
        if comparison_report.exists():
            print(f"   - comparison-report.txt")
        if reprotest_diff.exists():
            print(f"   - reprotest.diff")
        if reproducible_percent.exists():
            print(f"   - ReproduciblePercent")
        if reproducible_log.exists():
            print(f"   - reproducible_evidence.log")

        # Fail the stage if comparison failed
        if comparison_exit_code != 0:
            print("\n⚠️  Stage failed due to reproducibility issues")
            raise subprocess.CalledProcessError(comparison_exit_code, cmd)

        print("\n✅ Reproducible build comparison complete")

        # Post-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('post')


def main():
    parser = argparse.ArgumentParser(
        description='Run OpenJDK build pipeline locally',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Full build with all stages (variant from config repo)
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --target-os mac \\
      --architecture aarch64

  # Build only (skip tests and installers)
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --target-os mac \\
      --architecture aarch64 \\
      --no-tests \\
      --no-installers

  # Release build with custom refs
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --target-os linux \\
      --architecture x64 \\
      --release-type RELEASE \\
      --scm-ref jdk-21.0.2+13 \\
      --build-ref develop

  # Build with custom workspace
  python3 run-pipeline.py \\
      --jdk-version jdk17u \\
      --target-os mac \\
      --architecture aarch64 \\
      --workspace ~/my-custom-workspace

  # Resume from a specific stage (e.g., after build failure)
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --target-os mac \\
      --architecture aarch64 \\
      --start-from-stage smoke-tests

  # Re-run just the installer stage
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --target-os mac \\
      --architecture aarch64 \\
      --start-from-stage installer \\
      --no-tests

  # Build with reproducible comparison
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --target-os mac \\
      --architecture aarch64 \\
      --scm-ref jdk-21.0.2+13 \\
      --release-type RELEASE \\
      --compare-build
        """
    )

    # Required arguments
    parser.add_argument('--jdk-version', required=True,
                        help='JDK version to build (e.g., jdk21, jdk8). Must be in format jdkNN where NN is the version number.')
    parser.add_argument('--target-os', required=True,
                        choices=['mac', 'linux', 'windows', 'aix'],
                        help='Target operating system')
    parser.add_argument('--architecture', required=True,
                        choices=['aarch64', 'x64', 'x32', 'ppc64', 's390x'],
                        help='Target architecture')

    # Optional arguments
    parser.add_argument('--workspace', default='~/openjdk-build',
                        help='Workspace directory (default: ~/openjdk-build)')
    parser.add_argument('--build-number',
                        help='Build number (default: local-YYYYMMDD-HHMMSS)')

    # Build type - case-insensitive (will be converted to uppercase)
    parser.add_argument('--release-type', type=str,
                        help='Type of release build: NIGHTLY (default), WEEKLY (EA beta builds), or RELEASE (case-insensitive)')

    # Git refs
    parser.add_argument('--scm-ref',
                        help='OpenJDK source branch/tag (default: HEAD)')
    parser.add_argument('--build-ref',
                        help='temurin-build branch/tag (overrides adoptium_pipeline_config.json)')
    parser.add_argument('--aqa-ref',
                        help='aqa-tests branch/tag (overrides adoptium_pipeline_config.json)')
    parser.add_argument('--build-repo-url',
                        help='temurin-build repository URL (overrides adoptium_pipeline_config.json)')
    parser.add_argument('--aqa-repo-url',
                        help='aqa-tests repository URL (overrides adoptium_pipeline_config.json)')

    # Configuration repository
    parser.add_argument('--config-repo-url',
                        default='https://github.com/adoptium/ci-temurin-config.git',
                        help='Configuration repository URL (default: https://github.com/adoptium/ci-temurin-config.git)')
    parser.add_argument('--config-repo-branch',
                        default='main',
                        help='Configuration repository branch (default: main)')

    # Workspace control
    parser.add_argument('--clean-workspace', action='store_true',
                        help='Remove existing workspace before starting (ensures clean build)')

    # Stage control
    parser.add_argument('--start-from-stage',
                        choices=['initialize', 'build', 'sign', 'installer', 'smoke-tests', 'reproducible-compare'],
                        help='Start pipeline from a specific stage (skips earlier stages)')
    parser.add_argument('--skip-build', action='store_true',
                        help='Skip build stage (only generate configuration)')
    parser.add_argument('--no-tests', dest='enable_tests', action='store_false',
                        help='Disable tests')
    parser.add_argument('--no-installers', dest='enable_installers', action='store_false',
                        help='Disable installer building')
    parser.add_argument('--no-signer', dest='enable_signer', action='store_false',
                        help='Disable artifact signing')
    parser.add_argument('--compare-build', action='store_true',
                        help='Enable reproducible build comparison against production Adoptium binaries (requires --scm-ref)')

    parser.set_defaults(enable_tests=True, enable_installers=True, enable_signer=True)

    args = parser.parse_args()

    # Validate jdk-version format (must be jdkNN where NN is a number)
    import re
    if not re.match(r'^jdk\d+$', args.jdk_version):
        parser.error(f"Invalid --jdk-version format: '{args.jdk_version}'. Must be in format jdkNN where NN is the version number (e.g., jdk21, jdk8).")

    # Run pipeline
    runner = PipelineRunner(args)
    return runner.run()


if __name__ == '__main__':
    sys.exit(main())

# Made with Bob
