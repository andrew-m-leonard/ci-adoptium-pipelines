#!/usr/bin/env python3
"""
Local Pipeline Runner — orchestrates OpenJDK build pipeline stages locally.

Usage:
    python3 run-pipeline.py --jdk-version jdk21 --target-os mac --architecture aarch64
    python3 run-pipeline.py --help
"""

import argparse
import os
import sys
import subprocess
import json
import re
from pathlib import Path
from datetime import datetime
from workspace_manager import WorkspaceManager
from stage_resolver import StageResolver


class PipelineRunner:
    # Define stage order
    STAGES = ['initialize', 'build', 'validate-sbom', 'sign', 'installer', 'smoke-tests', 'reproducible-compare']

    def __init__(self, args):
        self.args = args
        self.script_dir = Path(__file__).parent.parent.parent.resolve()  # ci-adoptium-pipelines root

        # Initialize workspace manager
        pipeline_workspace = Path(args.workspace).expanduser().resolve()
        config_file = pipeline_workspace / 'pipeline-config.json'
        self.workspace_mgr = WorkspaceManager(pipeline_workspace, config_file)

        # Convenience properties
        self.pipeline_workspace = self.workspace_mgr.pipeline_workspace
        self.stage_workspace = self.workspace_mgr.stage_workspace
        self.build_artifacts_dir = self.workspace_mgr.build_artifacts_dir
        self.workspace = self.pipeline_workspace
        self.config_file = self.workspace_mgr.config_file
        self.build_number = args.build_number or f"local-{datetime.now().strftime('%Y%m%d-%H%M%S')}"

        # StageResolver is initialised lazily after stage_initialize() has
        # cloned the config repo.  _make_resolver() is called at the start
        # of each _run_stage() call to ensure it is always up-to-date.
        self._resolver: StageResolver | None = None

        # Validate reproducible compare parameters
        if args.compare_build and not args.scm_ref:
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
            self._resolver = StageResolver(self.script_dir, config_repo_root, self.config_file)
            src = str(config_repo_root) if config_repo_root else 'defaults only'
            print(f"ℹ️  StageResolver initialised (config repo: {src})")

        return self._resolver

    def _stage_env(self, extra: dict | None = None) -> dict:
        """
        Build the standard environment dict passed to every stage script.
        Mirrors PipelineHelper.initializeStage() in Jenkins.

        All five standard variables are set:
          WORKSPACE, CONFIG_FILE, INPUT_ARTIFACTS_DIR, TARGET_DIR, BUILD_NUMBER
        plus PIPELINE_ROOT for vendor scripts that source shared lib utilities.
        """
        env = os.environ.copy()
        env['WORKSPACE']            = str(self.stage_workspace)
        env['PIPELINE_ROOT']        = str(self.script_dir)
        env['CONFIG_FILE']          = str(self.stage_workspace / 'pipeline-config.json')
        env['INPUT_ARTIFACTS_DIR']  = str(self.stage_workspace)
        env['TARGET_DIR']           = str(self.stage_workspace / 'target')
        env['BUILD_NUMBER']         = self.build_number
        if extra:
            env.update(extra)
        return env

    def _run_stage(self, stage_label: str, stem: str, artifact_filter: str,
                   extra_env: dict | None = None, unstable_ok: bool = False) -> int:
        """
        Execute one pipeline stage — the local equivalent of a Jenkins stage block.

        Mirrors the Jenkins pattern exactly:
          1. Pre-cleanup  (≈ cleanWs)
          2. Restore inputs from build_artifacts/ (≈ copyArtifacts)
          3. Build standard environment
          4. Run stage script via StageResolver
          5. Archive outputs from stage_workspace/target/ (≈ archiveArtifacts)
          6. Post-cleanup (≈ finalizeStage cleanWs)

        Args:
            stage_label:   Human-readable name for log output.
            stem:          Stage script stem (e.g. '02-build').
            artifact_filter: Comma-separated glob patterns for restore_stage_inputs.
            extra_env:     Additional env vars beyond the standard set.
            unstable_ok:   If True, non-zero exit code is printed as a warning
                           rather than raising (UNSTABLE-equivalent, like Jenkins).

        Returns:
            exit code of the stage script (0 on success or disabled/no-op).
        """
        print(f"\n{'=' * 80}")
        print(f"STAGE: {stage_label}")
        print('=' * 80)

        self.workspace_mgr.cleanup_stage_workspace('pre')
        self.workspace_mgr.restore_stage_inputs(stage_label, artifact_filter)

        exit_code = self._make_resolver().run(stem, self._stage_env(extra_env))

        self.workspace_mgr.archive_stage_outputs(stage_label)
        self.workspace_mgr.cleanup_stage_workspace('post')

        if exit_code != 0:
            if unstable_ok:
                print(f"\n⚠️  {stage_label} completed with warnings (exit code: {exit_code})")
            else:
                raise subprocess.CalledProcessError(exit_code, stem)

        return exit_code

    def _load_adoptium_pipeline_config(self, config_repo_dir: Path) -> dict:
        """Load adoptium_pipeline_config.json from the config repo directory."""
        cfg_path = config_repo_dir / 'adoptium_pipeline_config.json'
        if not cfg_path.exists():
            print("ℹ️  adoptium_pipeline_config.json not found in config repo — using defaults")
            return {}

        with open(cfg_path, 'r') as f:
            cfg = json.load(f)

        print("✅ Loaded adoptium_pipeline_config.json")
        print(f"   Default variant: {cfg.get('defaultVariant', 'temurin')}")
        active = [v['version'] for v in cfg.get('activeJdkVersions', []) if v.get('enabled')]
        if active:
            print(f"   Active JDK versions: {', '.join(active)}")
        return cfg

    # ------------------------------------------------------------------
    # Pipeline entry point
    # ------------------------------------------------------------------

    def run(self):
        """Run the complete pipeline."""
        print("=" * 80)
        print("OpenJDK Build Pipeline - Local Runner")
        print("=" * 80)
        print(f"Workspace: {self.workspace}")
        print(f"Build Number: {self.build_number}")
        print()

        self.workspace_mgr.validate_and_setup(
            is_restarting=self.args.start_from_stage is not None,
            clean_requested=self.args.clean_workspace,
            start_from_stage=self.args.start_from_stage
        )

        try:
            if 'initialize' in self.stages_to_run:
                self.stage_initialize()

            if 'build' in self.stages_to_run:
                self._run_stage('Build', '02-build', 'pipeline-config.json')

            if 'validate-sbom' in self.stages_to_run:
                self._run_stage('Validate SBOM', '12-validate-sbom', 'pipeline-config.json,*sbom*.json')

            if 'sign' in self.stages_to_run:
                self._run_stage('Post-Build Code Sign', '06-post-build-code-sign',
                                'pipeline-config.json,*.tar.gz,*.zip,metadata/**/*')

            if 'installer' in self.stages_to_run:
                self._run_stage('Build Installer', '07-installer',
                                'pipeline-config.json,*.tar.gz,*.zip,metadata/**/*')

            if 'smoke-tests' in self.stages_to_run:
                self._run_stage('Smoke Tests', '13-smoke-tests',
                                'pipeline-config.json,*.tar.gz,*.zip')

            if 'reproducible-compare' in self.stages_to_run:
                release_type = (self.args.release_type or 'NIGHTLY').upper()
                self._run_stage('Reproducible Compare', '20-reproducible-compare',
                                'pipeline-config.json,*.tar.gz,*.zip',
                                extra_env={
                                    'SCM_REF':        self.args.scm_ref,
                                    'RELEASE':        'true' if release_type == 'RELEASE' else 'false',
                                    **({'BUILD_REPO_URL': self.args.build_repo_url} if self.args.build_repo_url else {}),
                                    **({'BUILD_REF':      self.args.build_ref}       if self.args.build_ref      else {}),
                                },
                                unstable_ok=True)

            print()
            print("=" * 80)
            print("✅ Pipeline completed successfully!")
            print("=" * 80)
            print(f"\n📦 All artifacts in: {self.build_artifacts_dir}")
            return 0

        except subprocess.CalledProcessError as e:
            print()
            print("=" * 80)
            print(f"❌ Pipeline failed: {e.cmd} (exit code {e.returncode})")
            print("=" * 80)
            return e.returncode
        except Exception as e:
            print()
            print("=" * 80)
            print(f"❌ Pipeline failed: {e}")
            print("=" * 80)
            return 1

    # ------------------------------------------------------------------
    # Initialize stage (unique logic — not reducible to _run_stage)
    # ------------------------------------------------------------------

    def stage_initialize(self):
        """Stage 1: Generate pipeline configuration."""
        print("\n" + "=" * 80)
        print("STAGE: Initialize - Generate Configuration")
        print("=" * 80)

        self.workspace_mgr.cleanup_stage_workspace('pre')

        if self.args.config_repo_url:
            config_repo_dir = self.workspace / 'config-repo'
            if config_repo_dir.exists():
                print(f"ℹ️  Configuration repository already exists: {config_repo_dir}")
                print("   (Use --clean-workspace to re-clone)")
            else:
                print(f"📥 Cloning configuration repository...")
                print(f"   URL: {self.args.config_repo_url}")
                print(f"   Branch: {self.args.config_repo_branch}")
                subprocess.run([
                    'git', 'clone',
                    '--branch', self.args.config_repo_branch,
                    '--depth', '1',
                    self.args.config_repo_url,
                    str(config_repo_dir)
                ], check=True)
                print("✅ Configuration repository cloned")

            adoptium_cfg = self._load_adoptium_pipeline_config(config_repo_dir)
            config_prefix = adoptium_cfg.get('configFilePrefix', 'configurations/')
            config_dir = config_repo_dir / config_prefix.rstrip('/')
            if not config_dir.exists():
                raise FileNotFoundError(
                    f"Configuration directory not found: {config_dir}\n"
                    f"Expected '{config_prefix}' subdirectory in repository"
                )
        else:
            adoptium_cfg = {}
            config_dir = self.script_dir / 'configurations'
            if not config_dir.exists():
                raise FileNotFoundError(
                    f"Configuration directory not found: {config_dir}\n"
                    f"Please provide --config-repo-url or ensure local configurations exist"
                )

        print(f"📁 Using configuration directory: {config_dir}")

        variant = adoptium_cfg.get('defaultVariant', 'temurin')
        print(f"   Variant: {variant}")

        # Resolve refs from CLI or adoptium_pipeline_config.json
        repo_cfg = adoptium_cfg.get('repository', {})
        build_ref      = self.args.build_ref      or repo_cfg.get('buildBranch')
        aqa_ref        = self.args.aqa_ref        or repo_cfg.get('aqaBranch')
        build_repo_url = self.args.build_repo_url or repo_cfg.get('buildRepoUrl')
        aqa_repo_url   = self.args.aqa_repo_url   or repo_cfg.get('aqaRepoUrl')

        missing = [k for k, v in {
            'repository.buildBranch':  build_ref,
            'repository.aqaBranch':    aqa_ref,
            'repository.buildRepoUrl': build_repo_url,
            'repository.aqaRepoUrl':   aqa_repo_url,
        }.items() if not v]
        if missing:
            raise ValueError(
                f"Required fields missing from adoptium_pipeline_config.json: {', '.join(missing)}\n"
                f"These must be defined in the config repo or overridden via CLI args."
            )

        cmd = [
            'python3', str(self.script_dir / 'scripts' / 'lib' / 'load-json-config.py'),
            '--jdk-version',   self.args.jdk_version,
            '--variant',       variant,
            '--target-os',     self.args.target_os,
            '--architecture',  self.args.architecture,
            '--config-dir',    str(config_dir),
            '--output-dir',    str(self.workspace),
            '--build-ref',     build_ref,
            '--aqa-ref',       aqa_ref,
            '--build-repo-url', build_repo_url,
            '--aqa-repo-url',  aqa_repo_url,
        ]

        if self.args.release_type:
            release_type = self.args.release_type.upper()
            valid = ['NIGHTLY', 'WEEKLY', 'RELEASE']
            if release_type not in valid:
                raise ValueError(
                    f"Invalid release type '{self.args.release_type}'. Must be one of: {', '.join(valid)}"
                )
            cmd.extend(['--release-type', release_type])
        if self.args.scm_ref:
            cmd.extend(['--scm-ref', self.args.scm_ref])
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

        if not self.config_file.exists():
            raise FileNotFoundError(f"Configuration file not created: {self.config_file}")

        with open(self.config_file, 'r') as f:
            config = json.load(f)
        print("\nGenerated Configuration:")
        print(json.dumps(config, indent=2))

        # Archive pipeline-config.json → build_artifacts/ (≈ Jenkins archiveArtifacts)
        self.workspace_mgr.archive_file(self.config_file, 'Initialize')

        print("\n✅ Initialize stage complete")
        self.workspace_mgr.cleanup_stage_workspace('post')


def main():
    parser = argparse.ArgumentParser(
        description='Run OpenJDK build pipeline locally',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Standard build
  python3 run-pipeline.py --jdk-version jdk21 --target-os mac --architecture aarch64

  # Release build, resume from sign stage, with reproducible comparison
  python3 run-pipeline.py \\
      --jdk-version jdk21 --target-os linux --architecture x64 \\
      --release-type RELEASE --scm-ref jdk-21.0.2+13 --compare-build \\
      --start-from-stage sign
        """
    )

    # Required arguments
    parser.add_argument('--jdk-version', required=True,
                        help='JDK version to build (e.g., jdk21, jdk8). Format: jdkNN.')
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
    parser.add_argument('--release-type', type=str,
                        help='Type of release build: NIGHTLY (default), WEEKLY, or RELEASE (case-insensitive)')

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
    parser.add_argument('--no-tests',      dest='enable_tests',      action='store_false',
                        help='Disable tests')
    parser.add_argument('--no-installers', dest='enable_installers',  action='store_false',
                        help='Disable installer building')
    parser.add_argument('--no-signer',     dest='enable_signer',      action='store_false',
                        help='Disable artifact signing')
    parser.add_argument('--compare-build', action='store_true',
                        help='Enable reproducible build comparison against production Adoptium binaries (requires --scm-ref)')

    parser.set_defaults(enable_tests=True, enable_installers=True, enable_signer=True)

    args = parser.parse_args()

    if not re.match(r'^jdk\d+$', args.jdk_version):
        parser.error(
            f"Invalid --jdk-version format: '{args.jdk_version}'. "
            f"Must be in format jdkNN where NN is the version number (e.g., jdk21, jdk8)."
        )

    runner = PipelineRunner(args)
    return runner.run()


if __name__ == '__main__':
    sys.exit(main())
