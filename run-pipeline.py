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


class PipelineRunner:
    # Define stage order
    STAGES = ['initialize', 'build', 'sign', 'installer', 'smoke-tests']
    
    def __init__(self, args):
        self.args = args
        self.workspace = Path(args.workspace).expanduser().resolve()
        self.script_dir = Path(__file__).parent.resolve()
        self.config_file = self.workspace / 'pipeline-config.json'
        self.build_number = args.build_number or f"local-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
        
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
        
        # Handle existing workspace
        if self.workspace.exists():
            if self.args.clean_workspace:
                print(f"⚠️  Cleaning existing workspace: {self.workspace}")
                import shutil
                shutil.rmtree(self.workspace)
                print("✅ Workspace cleaned")
            else:
                print(f"ℹ️  Using existing workspace: {self.workspace}")
                print("   (Use --clean-workspace to start fresh)")
        
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
            
            print()
            print("=" * 80)
            print("✅ Pipeline completed successfully!")
            print("=" * 80)
            print(f"\n📦 All artifacts in: {self.workspace / 'workspace' / 'target'}")
            print(f"   - JDK tarballs")
            print(f"   - Signed artifacts")
            print(f"   - Installers")
            print(f"   - Test results")
            
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
        
        # Create workspace directory
        self.workspace.mkdir(parents=True, exist_ok=True)
        
        # Build command for load-json-config.py
        cmd = [
            'python3',
            str(self.script_dir / 'scripts' / 'lib' / 'load-json-config.py'),
            '--jdk-version', self.args.jdk_version,
            '--variant', self.args.variant,
            '--target-os', self.args.target_os,
            '--architecture', self.args.architecture,
            '--config-dir', str(self.script_dir / 'configurations'),
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
    
    def stage_build(self):
        """Stage 2: Build OpenJDK"""
        print("\n" + "=" * 80)
        print("STAGE 2: Build OpenJDK")
        print("=" * 80)
        
        # Use workspace/target as the shared artifact directory
        target_dir = self.workspace / 'workspace' / 'target'
        target_dir.mkdir(parents=True, exist_ok=True)
        
        env = os.environ.copy()
        env['WORKSPACE'] = str(self.workspace)
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['TARGET_DIR'] = str(target_dir)  # Shared artifact directory
        
        cmd = [str(self.script_dir / 'scripts' / 'stages' / '02-build.sh')]
        
        print(f"Running: {' '.join(cmd)}")
        print(f"Environment:")
        print(f"  WORKSPACE={env['WORKSPACE']}")
        print(f"  CONFIG_FILE={env['CONFIG_FILE']}")
        print(f"  BUILD_NUMBER={env['BUILD_NUMBER']}")
        print(f"  TARGET_DIR={env['TARGET_DIR']} (shared artifact directory)")
        
        subprocess.run(cmd, env=env, check=True)
        print("\n✅ Build stage complete")
        print(f"   Artifacts in: {target_dir}")
    
    def stage_sign(self):
        """Stage 3: Sign artifacts"""
        print("\n" + "=" * 80)
        print("STAGE 3: Sign Artifacts")
        print("=" * 80)
        
        # Use shared artifact directory
        target_dir = self.workspace / 'workspace' / 'target'
        
        env = os.environ.copy()
        env['WORKSPACE'] = str(self.workspace)
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['TARGET_DIR'] = str(target_dir)  # Shared artifact directory
        
        cmd = [str(self.script_dir / 'scripts' / 'stages' / '06-sign.sh')]
        
        print(f"Running: {' '.join(cmd)}")
        print(f"Environment:")
        print(f"  TARGET_DIR={env['TARGET_DIR']} (shared artifact directory)")
        print(f"  Note: Reads artifacts, signs them, writes back to same directory")
        
        subprocess.run(cmd, env=env, check=True)
        print("\n✅ Sign stage complete")
    
    def stage_installer(self):
        """Stage 4: Build installers"""
        print("\n" + "=" * 80)
        print("STAGE 4: Build Installers")
        print("=" * 80)
        
        # Use shared artifact directory
        target_dir = self.workspace / 'workspace' / 'target'
        
        env = os.environ.copy()
        env['WORKSPACE'] = str(self.workspace)
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['TARGET_DIR'] = str(target_dir)  # Shared artifact directory
        
        cmd = [str(self.script_dir / 'scripts' / 'stages' / '07-installer.sh')]
        
        print(f"Running: {' '.join(cmd)}")
        print(f"Environment:")
        print(f"  TARGET_DIR={env['TARGET_DIR']} (shared artifact directory)")
        print(f"  Note: Reads signed artifacts, creates installers in same directory")
        
        subprocess.run(cmd, env=env, check=True)
        print("\n✅ Installer stage complete")
    
    def stage_smoke_tests(self):
        """Stage 5: Run smoke tests"""
        print("\n" + "=" * 80)
        print("STAGE 5: Smoke Tests")
        print("=" * 80)
        
        # Use shared artifact directory
        target_dir = self.workspace / 'workspace' / 'target'
        
        env = os.environ.copy()
        env['WORKSPACE'] = str(self.workspace)
        env['CONFIG_FILE'] = str(self.config_file)
        env['BUILD_NUMBER'] = self.build_number
        env['TARGET_DIR'] = str(target_dir)  # Shared artifact directory
        
        cmd = [str(self.script_dir / 'scripts' / 'stages' / '13-smoke-tests.sh')]
        
        print(f"Running: {' '.join(cmd)}")
        print(f"Environment:")
        print(f"  TARGET_DIR={env['TARGET_DIR']} (shared artifact directory)")
        print(f"  Note: Reads JDK artifacts, writes test results to same directory")
        
        subprocess.run(cmd, env=env, check=True)
        print("\n✅ Smoke tests complete")


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
    
    # Workspace control
    parser.add_argument('--clean-workspace', action='store_true',
                        help='Remove existing workspace before starting (ensures clean build)')
    
    # Stage control
    parser.add_argument('--start-from-stage',
                        choices=['initialize', 'build', 'sign', 'installer', 'smoke-tests'],
                        help='Start pipeline from a specific stage (skips earlier stages)')
    parser.add_argument('--skip-build', action='store_true',
                        help='Skip build stage (only generate configuration)')
    parser.add_argument('--no-tests', dest='enable_tests', action='store_false',
                        help='Disable tests')
    parser.add_argument('--no-installers', dest='enable_installers', action='store_false',
                        help='Disable installer building')
    parser.add_argument('--no-signer', dest='enable_signer', action='store_false',
                        help='Disable artifact signing')
    
    parser.set_defaults(enable_tests=True, enable_installers=True, enable_signer=True)
    
    args = parser.parse_args()
    
    # Run pipeline
    runner = PipelineRunner(args)
    return runner.run()


if __name__ == '__main__':
    sys.exit(main())

# Made with Bob
