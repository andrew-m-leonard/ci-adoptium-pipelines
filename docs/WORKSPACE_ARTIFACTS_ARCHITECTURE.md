# Workspace and Artifacts Architecture

## Overview

The pipeline uses a clear separation between workspace (ephemeral, stage-specific) and artifacts (persistent, cross-stage) to support restartable pipelines that may execute stages on different nodes.

## Directory Structure

### Local Execution (run-pipeline.py)

```
${WORKSPACE}/                    # Base workspace directory
├── stage_workspace/             # Ephemeral workspace (cleaned before/after each stage)
│   └── [stage-specific files]  # Temporary files, cloned repos, build outputs
│
├── artifacts/                   # Persistent artifacts (never auto-cleaned)
│   ├── jdk/                    # Built JDK tarballs
│   ├── signed/                 # Signed artifacts
│   ├── installers/             # Built installers
│   ├── metadata/               # Build metadata, SBOMs
│   ├── test-results/           # Test results and reports
│   └── build-uid.txt           # Workspace validation marker
│
├── config-repo/                # Cloned configuration repository
└── pipeline-config.json        # Generated pipeline configuration
```

**Key Points**:
- `stage_workspace/` is cleaned before and optionally after each stage
- `artifacts/` persists across all stages and is never auto-cleaned
- Each stage uses `stage_workspace/` for WORKSPACE and `artifacts/` for TARGET_DIR
- `build-uid.txt` in artifacts/ validates workspace integrity

**Note**: The `artifacts/` directory is specific to local execution. Jenkins uses `archiveArtifacts` and `copyArtifacts` for artifact management instead of a local directory.

### Jenkins Execution

```
${WORKSPACE}/                    # Jenkins workspace directory
├── stage_workspace/             # Ephemeral workspace (cleaned via cleanWs())
│   └── [stage-specific files]  # Temporary files, cloned repos, build outputs
│
├── config-repo/                # Cloned configuration repository
└── pipeline-config.json        # Generated pipeline configuration

# Artifacts managed by Jenkins archiveArtifacts/copyArtifacts
# (not stored in workspace directory)
```

**Key Points**:
- Jenkins uses native `cleanWs()` utility for workspace cleanup
- Artifacts are managed through Jenkins' artifact system, not local directories
- Each stage cleans workspace before execution using `cleanWs()`
- Optional post-stage cleanup controlled by `CLEAN_WORKSPACE_AFTER_STAGE` parameter

## Key Principles

### 1. Workspace is Ephemeral

**Purpose**: Provide clean working directory for each stage

**Lifecycle**:
- **Before Stage**: Always cleaned (ensures fresh start, critical for restartability)
- **During Stage**: Stage writes temporary files, intermediate artifacts
- **After Stage**: Optionally cleaned (controlled by `cleanWorkspaceAfterStage`)

**Why Clean Before Every Stage?**
- Restartable pipelines may execute on different nodes
- Previous stage artifacts must come from artifact storage, not local workspace
- Prevents pollution from failed/aborted previous runs
- Ensures consistent behavior on first run vs restart

### 2. Artifacts are Persistent

**Purpose**: Store outputs that must persist across stages and restarts

**Lifecycle**:
- **Created**: By stages during execution
- **Persisted**: Copied to artifact storage (Jenkins) or local directory (run-pipeline.py)
- **Retrieved**: By subsequent stages from artifact storage
- **Never Auto-Cleaned**: Manual cleanup only

**What Goes in Artifacts?**
- JDK tarballs (`.tar.gz`, `.zip`)
- Signed binaries
- Installers (`.pkg`, `.msi`, `.deb`, `.rpm`)
- Metadata files (SBOMs, checksums, build info)
- Test results and reports

### 3. Stage Workflow

Every stage follows this pattern:

**Local Execution (run-pipeline.py)**:
```
1. Validate workspace (check build-uid.txt)
2. Clean stage_workspace/ (always)
3. Retrieve artifacts from artifacts/ directory (if needed)
4. Execute stage logic in stage_workspace/
5. Copy outputs to artifacts/ directory
6. Optionally clean stage_workspace/ (if cleanWorkspaceAfterStage=true)
```

**Jenkins Execution**:
```
1. Clean workspace with cleanWs() (always, before stage)
2. Retrieve artifacts using copyArtifacts (if needed)
3. Execute stage logic in workspace
4. Archive outputs using archiveArtifacts
5. Optionally clean workspace with cleanWs() (if CLEAN_WORKSPACE_AFTER_STAGE=true)
```

## Configuration

### Single Parameter

```json
{
  "parameters": {
    "cleanWorkspaceAfterStage": true
  }
}
```

**cleanWorkspaceAfterStage**:
- **Type**: Boolean
- **Default**: `true`
- **Description**: Clean workspace after stage completes
- **Use Cases**:
  - `true`: Production builds, save disk space
  - `false`: Debugging, inspect workspace after failure

**Removed Parameters**:
- `cleanWorkspace`: Removed (always true for restartability)

## Implementation Details

### Local Execution (run-pipeline.py)

**Directory Structure**:
```
~/openjdk-build/              # Workspace root
├── workspace/                # Ephemeral (cleaned before each stage)
├── artifacts/                # Persistent (never auto-cleaned, local only)
├── config-repo/             # Configuration repository
└── pipeline-config.json     # Generated configuration
```

**Workspace Validation**:
- When starting a new build (not using `--start-from-stage`), the workspace directory must NOT exist
- This prevents pollution from previous builds and ensures clean state
- Use `--clean-workspace` flag to remove existing workspace, or manually delete it
- When restarting from a stage (`--start-from-stage`), existing workspace is expected and required

**Stage Pattern**:
```python
def stage_build(self):
    # 1. Clean workspace
    self.clean_workspace()

    # 2. No artifacts to retrieve (first stage)

    # 3. Execute build
    artifacts_dir = self.workspace / 'artifacts'
    workspace_dir = self.workspace / 'workspace' / 'build'

    env['WORKSPACE_DIR'] = str(workspace_dir)
    env['ARTIFACTS_DIR'] = str(artifacts_dir)

    subprocess.run(['bash', 'scripts/stages/02-build.sh'], env=env)

    # 4. Artifacts already in artifacts/ (script writes there)

    # 5. Optional cleanup
    if self.should_clean_after_stage():
        self.clean_workspace()
```

### Jenkins Execution

**Directory Structure**:
```
${WORKSPACE}/                 # Jenkins workspace (cleaned by cleanWs() at start of each stage)
├── config-repo/             # Config repo (re-cloned each stage by initializeStage)
├── stage_input_artifacts/   # Copied in by copyArtifacts
└── pipeline-config.json     # Copied in by copyArtifacts

# Artifacts managed by Jenkins artifact store:
# - archiveArtifacts: stores outputs from TARGET_DIR
# - copyArtifacts: retrieves previous stage outputs into stage_input_artifacts/
```

**Stage Pattern** (via `PipelineHelper.initializeStage` + `stageRunner.run`):
```groovy
pipelineHelper.executeStageWithTracking('Build') {
    def config = pipelineHelper.initializeStage('Build', ['Initialize'])
    // ^ cleanWs, checkout scm, clone config-repo, copyArtifacts(pipeline-config.json)

    env.TARGET_DIR = "${WORKSPACE}/build_output"
    def exitCode = stageRunner.run('02-build', config)
    if (exitCode != 0) { error("Build failed") }

    dir(env.TARGET_DIR) {
        archiveArtifacts artifacts: '**/*', fingerprint: true
    }
    pipelineHelper.finalizeStage('Build')
}
```

## Benefits

1. **Restartability**: Clean workspace ensures consistent behavior
2. **Clarity**: Clear separation between ephemeral and persistent
3. **Debugging**: Can disable post-stage cleanup to inspect workspace
4. **Disk Management**: Can clean workspace to save space
5. **Node Migration**: Stages can run on different nodes (artifacts via Jenkins)

## Examples

### Example 1: Production Build

```json
{
  "parameters": {
    "cleanWorkspaceAfterStage": true
  }
}
```

**Behavior**:
- Workspace cleaned before each stage (always)
- Workspace cleaned after each stage (save disk space)
- Artifacts persist in `artifacts/` directory

### Example 2: Debug Build

```json
{
  "parameters": {
    "cleanWorkspaceAfterStage": false
  }
}
```

**Behavior**:
- Workspace cleaned before each stage (always)
- Workspace kept after each stage (for inspection)
- Artifacts persist in `artifacts/` directory

## Troubleshooting

### Issue: Stage Can't Find Previous Artifacts

**Cause**: Artifacts not copied to `artifacts/` directory

**Solution**: Check stage script writes to `${ARTIFACTS_DIR}`

### Issue: Workspace Not Clean

**Cause**: Cleanup script not running

**Solution**: Check workspace cleanup is called before stage

### Issue: Disk Space Full

**Cause**: `cleanWorkspaceAfterStage: false` and large workspaces

**Solution**: Set `cleanWorkspaceAfterStage: true` or manually clean
