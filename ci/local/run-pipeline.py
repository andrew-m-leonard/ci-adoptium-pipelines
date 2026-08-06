#!/usr/bin/env python3
"""
Local Pipeline Runner — orchestrates OpenJDK build pipeline stages locally.

Stage-specific parameters are loaded dynamically from scripts/stages/*.params.json
(and any vendor-scripts/*.params.json overrides in a checked-out config repo) via
scripts/lib/collect-stage-params.py. This ensures the local runner always presents
the same parameter surface as the Jenkins jobs without any hardcoding.

Stage IDs and display labels are loaded from scripts/stages/pipeline-stages.json —
the same canonical registry used by Jenkinsfile.declarative and the migration tools.

Usage:
    python3 run-pipeline.py --jdk-version jdk21 --target-os mac --architecture aarch64
    python3 run-pipeline.py --help
"""

import argparse
import enum
import os
import sys
import subprocess
import json
import re
from pathlib import Path
from datetime import datetime
from workspace_manager import WorkspaceManager
from stage_resolver import StageResolver


class StageResult(enum.Enum):
    """Mirrors Jenkins build result states: SUCCESS → UNSTABLE → FAILURE."""
    SUCCESS  = 'SUCCESS'
    UNSTABLE = 'UNSTABLE'
    FAILURE  = 'FAILURE'

    def is_worse_than(self, other: 'StageResult') -> bool:
        _rank = {StageResult.SUCCESS: 0, StageResult.UNSTABLE: 1, StageResult.FAILURE: 2}
        return _rank[self] > _rank[other]

    @staticmethod
    def from_exit_code(exit_code: int) -> 'StageResult':
        if exit_code == 0:
            return StageResult.SUCCESS
        if exit_code == 1:
            return StageResult.UNSTABLE
        return StageResult.FAILURE


def _load_stage_registry(script_dir: Path) -> dict:
    """Load pipeline-stages.json and return an id → label mapping.

    Args:
        script_dir: Root of the ci-adoptium-pipelines checkout.

    Returns:
        Dict mapping stageId strings to their display labels,
        e.g. {'02-build': 'Build', '13-smoke-tests': 'Smoke Tests', ...}
    """
    registry_path = script_dir / 'scripts' / 'stages' / 'pipeline-stages.json'
    with open(registry_path, 'r') as f:
        stages = json.load(f)['pipelineStages']
    return {s['id']: s['label'] for s in stages}


# ---------------------------------------------------------------------------
# Stage parameter collation (CI-agnostic helper)
# ---------------------------------------------------------------------------

def _collect_stage_params(script_dir: Path, vendor_scripts_dir: Path | None,
                           silent: bool = False,
                           orchestrated_stages: list[str] | None = None) -> dict:
    """
    Run scripts/lib/collect-stage-params.py and return the parsed output.

    Args:
        script_dir:           Root of the ci-adoptium-pipelines checkout.
        vendor_scripts_dir:   Path to config-repo/vendor-scripts, or None.
        silent:               Suppress stdout (used when building --help text).
        orchestrated_stages:  List of stage IDs to include; stems not in this
                              list are skipped by the collator.  Pass
                              _LOCAL_STAGES to restrict output to only the
                              stages this runner actually executes.

    Returns:
        Dict with keys 'groups' and 'paramNames', or empty structure on failure.
    """
    import tempfile

    collector = script_dir / 'scripts' / 'lib' / 'collect-stage-params.py'
    if not collector.exists():
        return {'groups': [], 'paramNames': []}

    stages_dir = script_dir / 'scripts' / 'stages'
    with tempfile.NamedTemporaryFile(suffix='.json', delete=False) as tmp:
        tmp_path = tmp.name

    cmd = [
        sys.executable, str(collector),
        '--default-stages-dir', str(stages_dir),
        '--output', tmp_path,
    ]
    if vendor_scripts_dir and vendor_scripts_dir.exists():
        cmd += ['--vendor-scripts-dir', str(vendor_scripts_dir)]
    if orchestrated_stages:
        cmd += ['--orchestrated-stages', ','.join(orchestrated_stages)]

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"WARNING: collect-stage-params.py failed — stage params not loaded.\n"
              f"{result.stderr.strip()}", file=sys.stderr)
        return {'groups': [], 'paramNames': []}

    if not silent and result.stdout.strip():
        print(f"  {result.stdout.strip()}")

    with open(tmp_path, 'r') as f:
        return json.load(f)


def _param_name_to_cli_flag(name: str) -> str:
    """Convert UPPER_SNAKE_CASE param name to --lower-kebab-case CLI flag."""
    return '--' + name.lower().replace('_', '-')


def _parse_extra_args(extra: list[str], collated: dict) -> tuple[dict, list[str], list[str]]:
    """
    Parse a list of raw unknown CLI tokens against the collated stage parameter
    definitions.

    Both boolean and string parameters now require an explicit value token:
      --create-sbom true          boolean
      --create-sbom false         boolean
      --scm-ref jdk-21.0.7+6     string
      --extra-build-args "..."    string

    This mirrors the syntax used by Jenkins build parameters and avoids
    ambiguity between a flag and the next positional token.

    Returns:
        (stage_params, unrecognised, errors)
          stage_params   — dict of PARAM_NAME → value for every valid token
          unrecognised   — list of flag names that matched no collated param
          errors         — list of human-readable error strings for malformed
                           tokens (missing value, wrong boolean value)
    """
    # Build a lookup: --lower-kebab-case flag → param def dict
    flag_to_param: dict[str, dict] = {}
    for group in collated.get('groups', []):
        for p in group.get('parameters', []):
            flag_to_param[_param_name_to_cli_flag(p['name'])] = p

    stage_params: dict[str, str] = {}
    unrecognised: list[str] = []
    errors:       list[str] = []

    i = 0
    while i < len(extra):
        token = extra[i]
        if not token.startswith('--'):
            i += 1
            continue

        # Handle --flag=value and --flag value forms
        if '=' in token:
            flag, value = token.split('=', 1)
        else:
            flag = token
            value = None

        p = flag_to_param.get(flag)
        if p is None:
            unrecognised.append(flag)
            i += 1
            continue

        # All parameter types (boolean and string) require an explicit value token
        if value is None:
            if i + 1 < len(extra) and not extra[i + 1].startswith('--'):
                value = extra[i + 1]
                i += 2
            else:
                errors.append(
                    f"  {flag}: missing value (expected {p['type']})"
                )
                i += 1
                continue

        if p['type'] == 'boolean':
            if value.lower() not in ('true', 'false'):
                errors.append(
                    f"  {flag}: invalid value {value!r} — boolean must be 'true' or 'false'"
                )
                i += 1
                continue
            stage_params[p['name']] = value.lower()
        else:
            stage_params[p['name']] = value

    return stage_params, unrecognised, errors


# ---------------------------------------------------------------------------
# Stage ID constants — match the "id" fields in pipeline-stages.json exactly.
# Used throughout PipelineRunner to refer to stages without raw string literals.
# ---------------------------------------------------------------------------
INITIALIZE           = '01-initialize'
BUILD                = '02-build'
INTERNAL_CODE_SIGN   = '03-internal-code-sign'
ASSEMBLE_IMAGES      = '04-assemble-images'
POST_BUILD_CODE_SIGN = '06-post-build-code-sign'
BUILD_INSTALLERS     = '07-installer'
CODE_SIGN_INSTALLER  = '08-code-sign-installer'
SBOM_SIGN            = '09-sbom-sign'
DIGITAL_ARTIFACT_SIGN= '10-digital-artifact-sign'
VERIFY_SIGNING       = '11-verify-signing'
VALIDATE_SBOM        = '12-validate-sbom'
SMOKE_TESTS          = '13-smoke-tests'
AQA_TESTS            = '14-aqa-tests'
TCK_TESTS            = '15-tck-tests'
PUBLISH_ARTIFACTS    = '16-publish'
REPRODUCIBLE_COMPARE = '20-reproducible-compare'

# Ordered list of stageIds that the local runner executes (subset of all pipeline
# stages — CI-only stages such as code-signing and publishing are excluded).
_LOCAL_STAGES = [
    INITIALIZE,
    BUILD,
    VALIDATE_SBOM,
    SMOKE_TESTS,
    AQA_TESTS,
    REPRODUCIBLE_COMPARE,
]


class _PipelineAbort(Exception):
    """Raised internally to stop stage execution after a FAILURE result."""


class PipelineRunner:
    def __init__(self, args, stage_params: dict | None = None,
                 collated: dict | None = None):
        self.args = args
        self.script_dir = Path(__file__).parent.parent.parent.resolve()  # ci-adoptium-pipelines root
        self._stage_registry = _load_stage_registry(self.script_dir)

        # Collated stage params: { paramName → value } derived from
        # scripts/stages/*.params.json + vendor-scripts/*.params.json.
        # Injected into every stage environment via _stage_env().
        self._stage_param_values: dict[str, str] = stage_params or {}

        # Stage metadata maps derived from the collated output.
        # _stage_disabled:    stageId → bool   (stageDisabled field)
        # _stage_conditions:  stageId → list of { param, value } dicts
        self._stage_disabled:    dict[str, bool]        = {}
        self._stage_conditions:  dict[str, list[dict]]  = {}
        if collated:
            self._load_stage_metadata(collated)

        # StageResolver is initialised lazily after stage_initialize() has
        # cloned the config repo.  _make_resolver() is called at the start
        # of each _run_stage() call to ensure it is always up-to-date.
        self._resolver: StageResolver | None = None

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

        # Determine which stages to run
        if args.start_from_stage:
            if args.start_from_stage not in _LOCAL_STAGES:
                raise ValueError(f"Invalid stage: {args.start_from_stage}. Must be one of: {', '.join(_LOCAL_STAGES)}")
            start_index = _LOCAL_STAGES.index(args.start_from_stage)
            self.stages_to_run = _LOCAL_STAGES[start_index:]
            print(f"ℹ️  Starting from stage: {args.start_from_stage}")
            print(f"   Will run: {', '.join(self.stages_to_run)}")
        else:
            self.stages_to_run = _LOCAL_STAGES.copy()

    def _load_stage_metadata(self, collated: dict) -> None:
        """Extract stageDisabled and stageCondition maps from the collated output."""
        for grp in collated.get('groups', []):
            stage_id = grp.get('stageId', '')
            if not stage_id:
                continue
            # stageDisabled — first group seen per stageId wins
            if stage_id not in self._stage_disabled:
                self._stage_disabled[stage_id] = bool(grp.get('stageDisabled', False))
            # stageCondition — merge across groups sharing the same stageId
            conds = grp.get('stageCondition', [])
            if conds:
                existing = self._stage_conditions.get(stage_id, [])
                seen_params = {c['param'] for c in existing}
                merged = existing + [c for c in conds if c['param'] not in seen_params]
                self._stage_conditions[stage_id] = merged

    def _stage_condition_met(self, stage_id: str) -> bool:
        """
        Return True if all stageCondition entries for stage_id are satisfied,
        or if no conditions are defined (unconditional stage).

        Checks _stage_param_values (explicit CLI overrides) first, then the
        process environment.

        Value matching:
          - If the condition value begins with "regex:" the remainder is treated
            as a Python regex and matched with re.search() (substring match,
            mirroring Groovy's =~ find operator).
          - Otherwise a case-insensitive string equality check is performed to
            handle boolean coercion ('true'/'false' strings).
        """
        if self._stage_disabled.get(stage_id, False):
            print(f"  ↳ [{stage_id}] stageDisabled=true — skipping")
            return False
        conditions = self._stage_conditions.get(stage_id, [])
        for cond in conditions:
            param_name = cond['param']
            expected   = str(cond['value'])
            actual     = str(
                self._stage_param_values.get(param_name, os.environ.get(param_name, ''))
            )
            if expected.startswith('regex:'):
                pattern = expected[len('regex:'):]
                if not re.search(pattern, actual):
                    print(f"  ↳ [{stage_id}] skipped: {param_name}={actual!r} "
                          f"(regex {pattern!r} did not match)")
                    return False
            else:
                if actual.lower() != expected.lower():
                    print(f"  ↳ [{stage_id}] skipped: {param_name}={actual!r} (need {expected!r})")
                    return False
        return True

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
            self._resolver = StageResolver(self.script_dir, config_repo_root)
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

        CONFIG_* variables are populated from pipeline-config.json so that
        stage scripts (e.g. 02-build.sh) can read them without needing jq.
        """
        env = os.environ.copy()
        env['WORKSPACE']                  = str(self.stage_workspace)
        env['PIPELINE_ROOT']              = str(self.script_dir)
        env['CONFIG_FILE']                = str(self.stage_workspace / 'pipeline-config.json')
        env['INPUT_ARTIFACTS_DIR']        = str(self.stage_workspace)
        env['TARGET_DIR']                 = str(self.stage_workspace / 'target')
        env['BUILD_NUMBER']               = self.build_number
        # Fixed job-level params that stage scripts read directly (not in pipeline-config.json)
        env['RELEASE_TYPE']               = (self.args.release_type or 'NIGHTLY').upper()
        env['CLEAN_WORKSPACE_AFTER_STAGE'] = 'true' if self.args.clean_workspace else 'false'

        # Inject CONFIG_* variables from pipeline-config.json so that stage
        # shell scripts can consume them without a jq dependency.
        config_path = self.build_artifacts_dir / 'pipeline-config.json'
        if config_path.exists():
            with open(config_path, 'r') as f:
                cfg = json.load(f)

            build_cfg = cfg.get('buildConfig', {})
            # Map every buildConfig key → CONFIG_<KEY>
            for key, value in build_cfg.items():
                env[f'CONFIG_{key}'] = str(value) if value is not None else ''

            repo_defaults = cfg.get('repoDefaults', {})
            if repo_defaults.get('buildRef'):
                env['CONFIG_BUILD_REF'] = repo_defaults['buildRef']
            if repo_defaults.get('buildRepoUrl'):
                env['CONFIG_BUILD_REPO_URL'] = repo_defaults['buildRepoUrl']
            if repo_defaults.get('aqaRef'):
                env['CONFIG_AQA_REF'] = repo_defaults['aqaRef']
            if repo_defaults.get('aqaRepoUrl'):
                env['CONFIG_AQA_REPO_URL'] = repo_defaults['aqaRepoUrl']

        # Inject collated stage params so vendor stage scripts can read them
        # as environment variables without needing any other mechanism.
        # Stage params always override ambient env — the stage script itself
        # decides how to merge its param with the CONFIG_* repo defaults.
        for name, value in self._stage_param_values.items():
            env[name] = str(value)

        if extra:
            env.update(extra)
        return env

    def _run_stage(self, stage_id: str, artifact_filter: str,
                   extra_env: dict | None = None) -> StageResult:
        """
        Execute one pipeline stage — the local equivalent of a Jenkins stage block.

        Mirrors the Jenkins pattern exactly:
          1. Pre-cleanup  (≈ cleanWs)
          2. Restore inputs from build_artifacts/ (≈ copyArtifacts)
          3. Build standard environment
          4. Run stage script via StageResolver
          5. Archive outputs from stage_workspace/target/ (≈ archiveArtifacts)
          6. Post-cleanup (≈ finalizeStage cleanWs)

        Exit-code → StageResult mapping (mirrors Jenkins):
          0  → SUCCESS
          1  → UNSTABLE  (stage completed but reported differences / warnings)
          >1 → FAILURE   (hard failure — pipeline stops after this stage)

        Args:
            stage_id:        StageId from pipeline-stages.json (e.g. '02-build').
                             Used as both the script stem and the display label source.
            artifact_filter: Comma-separated glob patterns for restore_stage_inputs.
            extra_env:       Additional env vars beyond the standard set.

        Returns:
            StageResult reflecting the stage outcome.
        """
        stage_label = self._stage_registry.get(stage_id, stage_id)
        print(f"\n{'=' * 80}")
        print(f"STAGE: {stage_label}")
        print('=' * 80)

        self.workspace_mgr.cleanup_stage_workspace('pre')
        self.workspace_mgr.restore_stage_inputs(stage_label, artifact_filter)

        env = self._stage_env(extra_env)
        exit_code = self._make_resolver().run(stage_id, env)

        self.workspace_mgr.archive_stage_outputs(stage_label, target_dir=env.get('TARGET_DIR'))
        self.workspace_mgr.cleanup_stage_workspace('post')

        result = StageResult.from_exit_code(exit_code)
        if result == StageResult.UNSTABLE:
            print(f"\n⚠️  {stage_label} completed as UNSTABLE (exit code: {exit_code})")
        elif result == StageResult.FAILURE:
            print(f"\n❌ {stage_label} FAILED (exit code: {exit_code})")

        return result

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

    def run(self, skip_initialize: bool = False):
        """Run the complete pipeline.

        Args:
            skip_initialize: When True the Initialize stage is not run by this
                method — the caller (main) has already run it so that the
                config repo is available before stage params are validated.
                Also suppresses the workspace-exists guard in validate_and_setup.
        """
        print("=" * 80)
        print("OpenJDK Build Pipeline - Local Runner")
        print("=" * 80)
        print(f"Workspace: {self.workspace}")
        print(f"Build Number: {self.build_number}")
        print()

        self.workspace_mgr.validate_and_setup(
            is_restarting=self.args.start_from_stage is not None,
            clean_requested=self.args.clean_workspace,
            start_from_stage=self.args.start_from_stage,
            initialize_already_run=skip_initialize,
        )

        pipeline_result = StageResult.SUCCESS
        failure_exit_code = 0

        def _run(stage_id, artifact_filter, extra_env=None):
            """Run a stage, update pipeline_result, return False if pipeline should stop."""
            nonlocal pipeline_result, failure_exit_code
            result = self._run_stage(stage_id, artifact_filter, extra_env=extra_env)
            if result.is_worse_than(pipeline_result):
                pipeline_result = result
            if result == StageResult.FAILURE:
                failure_exit_code = 2  # sentinel — actual code already printed
                return False
            return True

        try:
            if not skip_initialize and INITIALIZE in self.stages_to_run:
                self.stage_initialize()

            if BUILD in self.stages_to_run:
                if not _run(BUILD, 'pipeline-config.json',
                            extra_env={'TARGET_DIR': str(self.stage_workspace / 'build_output')}):
                    raise _PipelineAbort()

            if VALIDATE_SBOM in self.stages_to_run:
                if not _run(VALIDATE_SBOM, 'pipeline-config.json,*sbom*.json',
                            extra_env={'TARGET_DIR': str(self.stage_workspace / 'sbom_validation_output')}):
                    raise _PipelineAbort()

            if SMOKE_TESTS in self.stages_to_run and self._stage_condition_met(SMOKE_TESTS):
                if not _run(SMOKE_TESTS,
                            'pipeline-config.json,*.tar.gz,*.zip',
                            extra_env={'TARGET_DIR': str(self.stage_workspace / 'smoke_test_output')}):
                    raise _PipelineAbort()

            if AQA_TESTS in self.stages_to_run and self._stage_condition_met(AQA_TESTS):
                if not _run(AQA_TESTS,
                            'pipeline-config.json,*.tar.gz,*.zip',
                            extra_env={'TARGET_DIR': str(self.stage_workspace / 'aqa_test_output')}):
                    raise _PipelineAbort()

            if REPRODUCIBLE_COMPARE in self.stages_to_run and self._stage_condition_met(REPRODUCIBLE_COMPARE):
                release_type = (self.args.release_type or 'NIGHTLY').upper()
                _run(REPRODUCIBLE_COMPARE,
                     'pipeline-config.json,*.tar.gz,*.zip',
                     extra_env={
                         'TARGET_DIR': str(self.stage_workspace / 'reproducible_compare_output'),
                         'RELEASE':    'true' if release_type == 'RELEASE' else 'false',
                     })

        except _PipelineAbort:
            pass
        except Exception as e:
            print()
            print("=" * 80)
            print(f"❌ Pipeline failed: {e}")
            print("=" * 80)
            return 1

        print()
        print("=" * 80)
        if pipeline_result == StageResult.SUCCESS:
            print("✅ Pipeline completed successfully!")
            rc = 0
        elif pipeline_result == StageResult.UNSTABLE:
            print("⚠️  Pipeline completed as UNSTABLE (one or more stages reported warnings)")
            rc = 1
        else:
            print("❌ Pipeline FAILED")
            rc = failure_exit_code
        print("=" * 80)
        print(f"\n📦 All artifacts in: {self.build_artifacts_dir}")
        return rc

    # ------------------------------------------------------------------
    # Initialize stage (unique logic — not reducible to _run_stage)
    # ------------------------------------------------------------------

    def stage_initialize(self):
        """Stage 1: Generate pipeline configuration."""
        print("\n" + "=" * 80)
        print("STAGE: Initialize - Generate Configuration")
        print("=" * 80)

        self.workspace_mgr.cleanup_stage_workspace('pre')

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

        # load-json-config.py reads adoptium_pipeline_config.json itself from
        # the config repo root — no ref args needed here.
        cmd = [
            sys.executable, str(self.script_dir / 'scripts' / 'lib' / 'load-json-config.py'),
            '--jdk-version',      self.args.jdk_version,
            '--variant',          self._load_adoptium_pipeline_config(config_repo_dir).get('defaultVariant', 'temurin'),
            '--target-os',        self.args.target_os,
            '--architecture',     self.args.architecture,
            '--config-repo-path', str(config_repo_dir),
            '--output-dir',       str(self.workspace),
        ]

        if self.args.release_type:
            release_type = self.args.release_type.upper()
            valid = ['NIGHTLY', 'WEEKLY', 'RELEASE']
            if release_type not in valid:
                raise ValueError(
                    f"Invalid release type '{self.args.release_type}'. "
                    f"Must be one of: {', '.join(valid)}"
                )
            cmd.extend(['--release-type', release_type])

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



def _build_stage_params_help(script_dir: Path, argv: list[str]) -> str:
    """
    Build the stage parameters section for --help output.

    If --config-repo-url is present in argv, clone/reuse that repo into a
    temporary directory and collate the full vendor param set (default +
    vendor-scripts overrides).  Otherwise collate from the default
    scripts/stages/*.params.json files only.

    Returns a formatted string ready to append to the argparse epilog.
    """
    import tempfile
    import shutil

    # Only do any work when --help or -h is actually requested
    if '--help' not in argv and '-h' not in argv:
        return ''

    # Extract --config-repo-url and --config-repo-branch from raw argv
    config_repo_url    = None
    config_repo_branch = 'main'
    for i, tok in enumerate(argv):
        if tok == '--config-repo-url' and i + 1 < len(argv):
            config_repo_url = argv[i + 1]
        elif tok.startswith('--config-repo-url='):
            config_repo_url = tok.split('=', 1)[1]
        elif tok == '--config-repo-branch' and i + 1 < len(argv):
            config_repo_branch = argv[i + 1]
        elif tok.startswith('--config-repo-branch='):
            config_repo_branch = tok.split('=', 1)[1]

    # Also check --workspace so we can reuse an already-cloned config repo
    workspace = Path('~/openjdk-build').expanduser()
    for i, tok in enumerate(argv):
        if tok == '--workspace' and i + 1 < len(argv):
            workspace = Path(argv[i + 1]).expanduser()
        elif tok.startswith('--workspace='):
            workspace = Path(tok.split('=', 1)[1]).expanduser()

    vendor_scripts_dir = None
    tmp_dir            = None

    if config_repo_url:
        # Prefer the already-cloned repo in the workspace if it exists
        existing = workspace / 'config-repo'
        if existing.exists():
            candidate = existing / 'vendor-scripts'
            if candidate.exists():
                vendor_scripts_dir = candidate
                source_note = f"(from existing clone: {existing})"
        if vendor_scripts_dir is None:
            # Clone into a temp dir — cleaned up after help is printed
            try:
                tmp_dir = Path(tempfile.mkdtemp(prefix='run-pipeline-help-'))
                subprocess.run(
                    ['git', 'clone', '--depth', '1',
                     '--branch', config_repo_branch,
                     config_repo_url, str(tmp_dir)],
                    check=True, capture_output=True
                )
                candidate = tmp_dir / 'vendor-scripts'
                if candidate.exists():
                    vendor_scripts_dir = candidate
                source_note = f"(cloned from {config_repo_url})"
            except Exception:
                source_note = f"(clone of {config_repo_url} failed — showing defaults only)"
    else:
        source_note = "(defaults only — add --config-repo-url for vendor params)"

    try:
        collated = _collect_stage_params(script_dir, vendor_scripts_dir, silent=True,
                                         orchestrated_stages=_LOCAL_STAGES)
    except Exception:
        collated = {'groups': [], 'paramNames': []}
    finally:
        if tmp_dir and tmp_dir.exists():
            shutil.rmtree(tmp_dir, ignore_errors=True)

    if not collated.get('paramNames'):
        return ''

    lines = [
        '',
        f"Stage parameters {source_note}:",
        "  Pass as --<lower-kebab-case-name> <value>",
        "  Boolean params accept: true | false",
        '',
    ]

    for group in collated.get('groups', []):
        params = group.get('parameters', [])
        if not params:
            continue
        # Prefer stageIds list; fall back to scalar stageId
        stage_ids = group.get('stageIds') or [group.get('stageId', '?')]
        stage_str = ', '.join(stage_ids)
        lines.append(f"  [{stage_str}]  {group['name']}")
        for p in params:
            flag    = _param_name_to_cli_flag(p['name'])
            default = str(p.get('default', '')).lower() if p['type'] == 'boolean' else repr(p.get('default', ''))
            desc = p.get('description', '').strip()
            lines.append(f"    {flag} <{p['type']}>  default: {default}")
            if desc:
                lines.append(f"      {desc}")
        lines.append('')

    return '\n'.join(lines)



def main():
    script_dir = Path(__file__).parent.parent.parent.resolve()

    # -----------------------------------------------------------------------
    # If --help/-h is in sys.argv, build a dynamic stage params epilog before
    # the parser is created.  The parser exits after printing help, so this
    # must happen first.
    #
    # If --config-repo-url is also present we clone/reuse that repo and collate
    # the full vendor param set.  Otherwise we fall back to the default
    # scripts/stages/*.params.json files (always available locally).
    # -----------------------------------------------------------------------
    stage_params_epilog = _build_stage_params_help(script_dir, sys.argv)

    # -----------------------------------------------------------------------
    # Parse known fixed args; capture everything else as raw tokens.
    # Unknown tokens are stage params validated after Initialize clones the
    # config repo and the full vendor param set is collated.
    # -----------------------------------------------------------------------
    parser = argparse.ArgumentParser(
        description='Run OpenJDK build pipeline locally',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Standard nightly build
  python3 run-pipeline.py --jdk-version jdk21 --target-os mac --architecture aarch64 \\
      --config-repo-url https://github.com/adoptium/ci-temurin-config.git

  # Build without tests, installers, or SBOM
  python3 run-pipeline.py \\
      --jdk-version jdk21 --target-os mac --architecture aarch64 \\
      --config-repo-url https://github.com/adoptium/ci-temurin-config.git \\
      --run-tests false --enable-installers false --create-sbom false

  # Release build with reproducible compare, pinned source tag
  python3 run-pipeline.py \\
      --jdk-version jdk21 --target-os linux --architecture x64 \\
      --config-repo-url https://github.com/adoptium/ci-temurin-config.git \\
      --release-type RELEASE \\
      --scm-ref jdk-21.0.7+6_adopt \\
      --run-reproducible-compare true

  # Vendor fork with vendor-specific stage params
  python3 run-pipeline.py \\
      --jdk-version jdk21 --target-os linux --architecture s390x \\
      --config-repo-url https://github.com/myorg/ci-openj9-config.git \\
      --openj9-repo git@github.ibm.com:myuser/openj9.git \\
      --openj9-branch my-feature-branch
""" + stage_params_epilog
    )

    # ── Required ─────────────────────────────────────────────────────────────
    parser.add_argument('--jdk-version', required=True,
                        help='JDK version to build (e.g., jdk21, jdk8). Format: jdkNN.')
    parser.add_argument('--target-os', required=True,
                        choices=['mac', 'linux', 'windows', 'aix'],
                        help='Target operating system')
    parser.add_argument('--architecture', required=True,
                        choices=['aarch64', 'x64', 'x32', 'ppc64', 's390x'],
                        help='Target architecture')

    # ── Pipeline / workspace control ─────────────────────────────────────────
    parser.add_argument('--workspace', default='~/openjdk-build',
                        help='Workspace directory (default: ~/openjdk-build)')
    parser.add_argument('--build-number',
                        help='Build number (default: local-YYYYMMDD-HHMMSS)')
    parser.add_argument('--clean-workspace', action='store_true',
                        help='Remove existing workspace before starting (ensures clean build)')
    parser.add_argument('--start-from-stage',
                        choices=_LOCAL_STAGES,
                        help='Start pipeline from a specific stage (skips earlier stages)')

    # ── Release / build type ──────────────────────────────────────────────────
    parser.add_argument('--release-type', type=str,
                        help='Type of release build: NIGHTLY (default), WEEKLY, or RELEASE')

    # ── Configuration repository ──────────────────────────────────────────────
    parser.add_argument('--config-repo-url',
                        required=True,
                        help='Configuration repository URL')
    parser.add_argument('--config-repo-branch', default='main',
                        help='Configuration repository branch (default: main)')

    # parse_known_args: fixed args parsed normally; anything else returned as
    # a flat list of raw tokens (stage params from *.params.json, e.g.
    # --scm-ref, --run-tests, --create-sbom) validated after Initialize has
    # cloned the config repo and stage params are collated.
    args, extra_tokens = parser.parse_known_args()

    if not re.match(r'^jdk\d+$', args.jdk_version):
        parser.error(
            f"Invalid --jdk-version format: '{args.jdk_version}'. "
            f"Must be in format jdkNN (e.g., jdk21, jdk8)."
        )

    # Syntax-only pre-check: reject any extra token that doesn't start with '--'.
    # Single-dash flags (e.g. -dsfsdfsdf) are never valid stage params and would
    # otherwise silently pass through until post-Initialize collation.
    bad_tokens = [t for t in extra_tokens if t.startswith('-') and not t.startswith('--')]
    if bad_tokens:
        parser.error(
            f"Unrecognised argument(s): {' '.join(bad_tokens)}\n"
            f"Stage parameters must use --<lower-kebab-case-name> <value> syntax.\n"
            f"Run with --help to see all available parameters."
        )

    # Create runner — stage params are empty until the config repo is cloned.
    runner = PipelineRunner(args, stage_params={}, collated=None)

    # Perform workspace validation and clean BEFORE Initialize runs, so that
    # --clean-workspace takes effect even though validate_and_setup() will be
    # called again (with initialize_already_run=True) inside run() and skip it.
    runner.workspace_mgr.validate_and_setup(
        is_restarting=args.start_from_stage is not None,
        clean_requested=args.clean_workspace,
        start_from_stage=args.start_from_stage,
        initialize_already_run=False,
    )

    # Run Initialize: clones the config repo and generates pipeline-config.json.
    if not args.start_from_stage or args.start_from_stage == INITIALIZE:
        try:
            runner.stage_initialize()
        except Exception as e:
            print(f"\n❌ Initialize stage failed: {e}")
            return 1

    # -----------------------------------------------------------------------
    # Collate stage params now that the config repo has been cloned.
    # Merges default scripts/stages/*.params.json with any vendor-scripts/
    # *.params.json overrides so vendor-specific params are also recognised.
    # -----------------------------------------------------------------------
    vendor_dir = runner.workspace / 'config-repo' / 'vendor-scripts'
    collated   = _collect_stage_params(script_dir, vendor_dir if vendor_dir.exists() else None,
                                       orchestrated_stages=_LOCAL_STAGES)

    # Load stage metadata (disabled flags, conditions) from the collated output.
    runner._load_stage_metadata(collated)

    stage_params, unrecognised, param_errors = _parse_extra_args(extra_tokens, collated)

    failed = False
    if unrecognised:
        print(f"\n❌ Unrecognised parameter(s) — not defined in any *.params.json for this config repo:")
        for flag in unrecognised:
            print(f"   {flag}")
        print(f"\n   Run with --help to see all available stage parameters.")
        failed = True

    if param_errors:
        print(f"\n❌ Invalid parameter value(s):")
        for msg in param_errors:
            print(msg)
        print(f"\n   Run with --help to see all available stage parameters.")
        failed = True

    if failed:
        return 1

    if stage_params:
        print("Stage parameters accepted:")
        for name, value in stage_params.items():
            print(f"  {name} = {value!r}")
        print()

    # Inject into the runner and continue from the next stage
    runner._stage_param_values = stage_params

    return runner.run(skip_initialize=True)


if __name__ == '__main__':
    sys.exit(main())
