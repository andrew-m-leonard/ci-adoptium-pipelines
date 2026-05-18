#!/usr/bin/env python3
"""
Local Pipeline Runner

Run the complete OpenJDK build pipeline locally from the command line.
This script orchestrates all stages: initialize, build, sign, installer, and tests.

Usage:
    python3 run-pipeline.py \
        --jdk-version jdk21u \
        --variant temurin \
        --target-os mac \
        --architecture aarch64 \
        --workspace ~/openjdk-build

Example:
    # Full build with all stages
    python3 run-pipeline.py --jdk-version jdk21u --variant temurin --target-os mac --architecture aarch64

    # Build only (skip tests and installers)
    python3 run-pipeline.py --jdk-version jdk21u --variant temurin --target-os mac --architecture aarch64 --no-tests --no-installers

    # Build with custom branch
    python3 run-pipeline.py --jdk-version jdk21u --variant temurin --target-os mac --architecture aarch64 --build-ref develop
"""

import argparse
import os
import sys
import subprocess
import json
from pathlib import Path
from datetime import datetime
from workspace_manager import WorkspaceManager


class PipelineRunner:
    # Define stage order
    STAGES = ['initialize', 'build', 'sign', 'installer', 'smoke-tests', 'reproducible-compare']
    
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
            
            # Stage 3: Sign (if enabled and applicable)
            if 'sign' in self.stages_to_run and self.args.enable_signer and not self.args.skip_build:
                self.stage_sign()
            
            # Stage 4: Build Installers (if enabled)
            if 'installer' in self.stages_to_run and self.args.enable_installers and not self.args.skip_build:
                self.stage_installer()
            
            # Stage 5: Smoke Tests (if enabled)
            if 'smoke-tests' in self.stages_to_run and self.args.enable_tests and not self.args.skip_build:
                self.stage_smoke_tests()
            
            # Stage 6: Reproducible Compare (if enabled)
            if 'reproducible-compare' in self.stages_to_run and self.args.compare_build and not self.args.skip_build:
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
            config_dir = self.workspace / 'config-repo'
            if config_dir.exists():
                print(f"ℹ️  Configuration repository already exists: {config_dir}")
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
                    str(config_dir)
                ]
                subprocess.run(clone_cmd, check=True)
                print("✅ Configuration repository cloned")
            
            # Use configurations subdirectory
            config_dir = config_dir / 'configurations'
            if not config_dir.exists():
                raise FileNotFoundError(
                    f"Configuration directory not found: {config_dir}\n"
                    f"Expected 'configurations/' subdirectory in repository"
                )
        else:
            # Use local configurations directory
            config_dir = self.script_dir / 'configurations'
            if not config_dir.exists():
                raise FileNotFoundError(
                    f"Configuration directory not found: {config_dir}\n"
                    f"Please provide --config-repo-url or ensure local configurations exist"
                )
        
        print(f"📁 Using configuration directory: {config_dir}")
        
        # Build command for load-json-config.py
        cmd = [
            'python3',
            str(self.script_dir / 'scripts' / 'lib' / 'load-json-config.py'),
            '--jdk-version', self.args.jdk_version,
            '--variant', self.args.variant,
            '--target-os', self.args.target_os,
            '--architecture', self.args.architecture,
            '--config-dir', str(config_dir),
            '--output-dir', str(self.workspace)
        ]
        
        # Add optional parameters
        if self.args.release:
            cmd.append('--release')
        if self.args.weekly:
            cmd.append('--weekly')
        if self.args.scm_ref:
            cmd.extend(['--scm-ref', self.args.scm_ref])
        if self.args.build_ref:
            cmd.extend(['--build-ref', self.args.build_ref])
        if self.args.build_repo_url:
            cmd.extend(['--build-repo-url', self.args.build_repo_url])
        if not self.args.enable_tests:
            cmd.append('--no-tests')
        if not self.args.enable_installers:
            cmd.append('--no-installers')
        if not self.args.enable_signer:
            cmd.append('--no-signer')
        if self.args.ea_beta_build:
            cmd.append('--ea-beta-build')
        
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
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['TARGET_DIR'] = str(self.artifacts_dir)
        
        cmd = [str(self.script_dir / 'scripts' / 'stages' / '02-build.sh')]
        
        print(f"Running: {' '.join(cmd)}")
        print(f"Environment:")
        print(f"  WORKSPACE={env['WORKSPACE']} (stage_workspace)")
        print(f"  CONFIG_FILE={env['CONFIG_FILE']}")
        print(f"  BUILD_NUMBER={env['BUILD_NUMBER']}")
        print(f"  TARGET_DIR={env['TARGET_DIR']} (artifacts_dir)")
        
        subprocess.run(cmd, env=env, check=True)
        print("\n✅ Build stage complete")
        print(f"   Artifacts in: {self.artifacts_dir}")
        
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
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['TARGET_DIR'] = str(self.artifacts_dir)
        
        cmd = [str(self.script_dir / 'scripts' / 'stages' / '06-sign.sh')]
        
        print(f"Running: {' '.join(cmd)}")
        print(f"Environment:")
        print(f"  WORKSPACE={env['WORKSPACE']} (stage_workspace)")
        print(f"  TARGET_DIR={env['TARGET_DIR']} (artifacts_dir)")
        print(f"  Note: Reads artifacts, signs them, writes back to artifacts_dir")
        
        subprocess.run(cmd, env=env, check=True)
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
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['TARGET_DIR'] = str(self.artifacts_dir)
        
        cmd = [str(self.script_dir / 'scripts' / 'stages' / '07-installer.sh')]
        
        print(f"Running: {' '.join(cmd)}")
        print(f"Environment:")
        print(f"  WORKSPACE={env['WORKSPACE']} (stage_workspace)")
        print(f"  TARGET_DIR={env['TARGET_DIR']} (artifacts_dir)")
        print(f"  Note: Reads signed artifacts, creates installers in artifacts_dir")
        
        subprocess.run(cmd, env=env, check=True)
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
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['TARGET_DIR'] = str(self.artifacts_dir)
        
        cmd = [str(self.script_dir / 'scripts' / 'stages' / '13-smoke-tests.sh')]
        
        print(f"Running: {' '.join(cmd)}")
        print(f"Environment:")
        print(f"  WORKSPACE={env['WORKSPACE']} (stage_workspace)")
        print(f"  TARGET_DIR={env['TARGET_DIR']} (artifacts_dir)")
        print(f"  Note: Reads JDK artifacts, writes test results to artifacts_dir")
        
        subprocess.run(cmd, env=env, check=True)
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
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['TARGET_DIR'] = str(self.artifacts_dir)
        env['SCM_REF'] = self.args.scm_ref
        env['RELEASE'] = 'true' if self.args.release else 'false'
        
        # Optional: BUILD_REPO_URL and BUILD_REF
        if self.args.build_repo_url:
            env['BUILD_REPO_URL'] = self.args.build_repo_url
        if self.args.build_ref:
            env['BUILD_REF'] = self.args.build_ref
        
        cmd = [str(self.script_dir / 'scripts' / 'stages' / '20-reproducible-compare.sh')]
        
        print(f"Running: {' '.join(cmd)}")
        print(f"Environment:")
        print(f"  WORKSPACE={env['WORKSPACE']} (stage_workspace)")
        print(f"  TARGET_DIR={env['TARGET_DIR']} (artifacts_dir)")
        print(f"  SCM_REF={env['SCM_REF']}")
        print(f"  RELEASE={env['RELEASE']}")
        print(f"  Note: Compares locally built JDK against production Adoptium binary")
        
        subprocess.run(cmd, env=env, check=True)
        print("\n✅ Reproducible build comparison complete")
        
        # Post-stage cleanup
        self.workspace_mgr.cleanup_stage_workspace('post')


def main():
    parser = argparse.ArgumentParser(
        description='Run OpenJDK build pipeline locally',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Full build with all stages
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --variant temurin \\
      --target-os mac \\
      --architecture aarch64

  # Build only (skip tests and installers)
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --variant temurin \\
      --target-os mac \\
      --architecture aarch64 \\
      --no-tests \\
      --no-installers

  # Release build with custom refs
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --variant temurin \\
      --target-os linux \\
      --architecture x64 \\
      --release \\
      --scm-ref jdk-21.0.2+13 \\
      --build-ref master

  # Build with custom workspace
  python3 run-pipeline.py \\
      --jdk-version jdk17u \\
      --variant temurin \\
      --target-os mac \\
      --architecture aarch64 \\
      --workspace ~/my-custom-workspace

  # Resume from a specific stage (e.g., after build failure)
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --variant temurin \\
      --target-os mac \\
      --architecture aarch64 \\
      --start-from-stage smoke-tests

  # Re-run just the installer stage
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --variant temurin \\
      --target-os mac \\
      --architecture aarch64 \\
      --start-from-stage installer \\
      --no-tests

  # Build with reproducible comparison
  python3 run-pipeline.py \\
      --jdk-version jdk21u \\
      --variant temurin \\
      --target-os mac \\
      --architecture aarch64 \\
      --scm-ref jdk-21.0.2+13 \\
      --release \\
      --compare-build
        """
    )
    
    # Required arguments
    parser.add_argument('--jdk-version', required=True,
                        choices=['jdk8u', 'jdk11u', 'jdk17u', 'jdk21u', 'jdk22u', 'jdk23u', 'jdk'],
                        help='JDK version to build')
    parser.add_argument('--variant', required=True,
                        choices=['temurin', 'openj9', 'hotspot'],
                        help='Build variant')
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
    
    # Build type
    parser.add_argument('--release', action='store_true',
                        help='Release build')
    parser.add_argument('--weekly', action='store_true',
                        help='Weekly build')
    
    # Git refs
    parser.add_argument('--scm-ref',
                        help='OpenJDK source branch/tag (default: master)')
    parser.add_argument('--build-ref',
                        help='temurin-build branch/tag (default: master)')
    parser.add_argument('--build-repo-url',
                        help='temurin-build repository URL (default: https://github.com/adoptium/temurin-build.git)')
    
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
    parser.add_argument('--ea-beta-build', action='store_true',
                        help='Enable EA/Beta build (adds --with-version-opt=ea to configure args)')
    
    parser.set_defaults(enable_tests=True, enable_installers=True, enable_signer=True)
    
    args = parser.parse_args()
    
    # Run pipeline
    runner = PipelineRunner(args)
    return runner.run()


if __name__ == '__main__':
    sys.exit(main())

# Made with Bob
