#!/usr/bin/env python3
"""
Workspace Manager for Local Pipeline Runner

Handles workspace validation, cleanup, and directory structure management.
Extracted from run-pipeline.py to improve code organization and readability.
"""

import json
import shutil
from pathlib import Path


class WorkspaceManager:
    """
    Manages workspace directories and cleanup operations for the local pipeline runner.

    Implements a two-directory architecture:
    - stage_workspace/: Ephemeral workspace cleaned before/after each stage
    - artifacts/: Persistent directory for artifacts that survive between stages
    """

    def __init__(self, pipeline_workspace, config_file):
        """
        Initialize workspace manager.

        Args:
            pipeline_workspace: Path to the root pipeline workspace directory
            config_file: Path to the pipeline configuration JSON file
        """
        self.pipeline_workspace = Path(pipeline_workspace).expanduser().resolve()
        self.stage_workspace = self.pipeline_workspace / 'stage_workspace'
        self.artifacts_dir = self.pipeline_workspace / 'artifacts'
        self.config_file = Path(config_file)

    def validate_and_setup(self, is_restarting, clean_requested, start_from_stage=None):
        """
        Validate workspace state and set up directory structure.

        Args:
            is_restarting: True if restarting from a specific stage
            clean_requested: True if --clean-workspace flag was provided
            start_from_stage: Name of stage to restart from (if restarting)

        Raises:
            ValueError: If workspace state is invalid for the requested operation
        """
        workspace_exists = self.pipeline_workspace.exists()

        # Validate workspace state based on operation mode
        if clean_requested and is_restarting:
            # Option conflict: can't clean workspace when restarting
            raise ValueError(
                f"ERROR: Option conflict - cannot use --clean-workspace with --start-from-stage\n"
                f"\n"
                f"When restarting from a stage, the workspace must be preserved to access\n"
                f"artifacts from previous stages. Remove --clean-workspace to continue.\n"
            )

        if is_restarting:
            # Restarting: workspace MUST exist
            if not workspace_exists:
                raise ValueError(
                    f"ERROR: Cannot restart from stage '{start_from_stage}' - workspace does not exist: {self.pipeline_workspace}\n"
                    f"\n"
                    f"When restarting from a stage, the workspace must exist with artifacts from previous stages.\n"
                    f"Run a full build first (without --start-from-stage) to create the workspace.\n"
                )
            print(f"ℹ️  Restarting from stage '{start_from_stage}'")
            print(f"   Using existing workspace: {self.pipeline_workspace}")
        else:
            # Fresh build: workspace must NOT exist (unless cleaning)
            if workspace_exists:
                if not clean_requested:
                    raise ValueError(
                        f"ERROR: Workspace already exists: {self.pipeline_workspace}\n"
                        f"\n"
                        f"For a fresh build, you must either:\n"
                        f"  1. Use --clean-workspace to clean the existing workspace\n"
                        f"  2. Use --start-from-stage <stage> to restart from a specific stage\n"
                        f"  3. Manually remove the workspace directory\n"
                        f"\n"
                        f"This ensures workspace cleanliness and prevents pollution from previous runs.\n"
                    )
                # Clean the entire pipeline workspace
                print(f"⚠️  Cleaning existing workspace: {self.pipeline_workspace}")
                shutil.rmtree(self.pipeline_workspace)
                print("✅ Workspace cleaned")

            # Create workspace structure
            print(f"📁 Creating workspace structure:")
            self.pipeline_workspace.mkdir(parents=True, exist_ok=True)
            self.stage_workspace.mkdir(parents=True, exist_ok=True)
            self.artifacts_dir.mkdir(parents=True, exist_ok=True)
            print(f"   Pipeline workspace: {self.pipeline_workspace}")
            print(f"   Stage workspace:    {self.stage_workspace}")
            print(f"   Artifacts directory: {self.artifacts_dir}")

    def cleanup_stage_workspace(self, cleanup_type):
        """
        Clean the ephemeral stage_workspace directory.

        Args:
            cleanup_type: Either 'pre' or 'post'
                - 'pre': ALWAYS cleans stage_workspace (critical for restartability)
                - 'post': Cleans stage_workspace if cleanWorkspaceAfterStage=true
        """
        if cleanup_type == 'pre':
            # Pre-cleanup: ALWAYS clean stage_workspace
            if self.stage_workspace.exists():
                print(f"🧹 Pre-cleanup: Cleaning stage workspace...")
                shutil.rmtree(self.stage_workspace)
                self.stage_workspace.mkdir(parents=True, exist_ok=True)
                print(f"   ✅ Stage workspace cleaned: {self.stage_workspace}")

        elif cleanup_type == 'post':
            # Post-cleanup: Clean if cleanWorkspaceAfterStage=true
            try:
                # Read config to check cleanWorkspaceAfterStage setting
                if self.config_file.exists():
                    with open(self.config_file, 'r') as f:
                        config = json.load(f)

                    clean_after = config.get('parameters', {}).get('cleanWorkspaceAfterStage', True)

                    if clean_after:
                        if self.stage_workspace.exists():
                            print(f"🧹 Post-cleanup: Cleaning stage workspace...")
                            shutil.rmtree(self.stage_workspace)
                            self.stage_workspace.mkdir(parents=True, exist_ok=True)
                            print(f"   ✅ Stage workspace cleaned: {self.stage_workspace}")
                    else:
                        print(f"ℹ️  Post-cleanup: Skipped (cleanWorkspaceAfterStage=false)")
            except Exception as e:
                print(f"⚠️  Warning: Post-cleanup failed: {e}")

# Made with Bob
