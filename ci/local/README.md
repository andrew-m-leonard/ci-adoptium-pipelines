# Local Pipeline Execution

This directory contains tools for running the Adoptium build pipeline locally without a CI server.

## Files

### run-pipeline.py

Python script for local pipeline execution with full stage orchestration.

## Quick Start

```bash
# Basic build
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os mac \
  --architecture aarch64

# Build without tests or installers
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os linux \
  --architecture x64 \
  --no-tests \
  --no-installers

# Resume from specific stage
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os mac \
  --architecture aarch64 \
  --start-from-stage smoke-tests
```

## Features

### Stage Orchestration

The runner executes all pipeline stages in order:
1. **Initialize**: Generate configuration from JSON
2. **Build**: Compile OpenJDK
3. **Sign**: Sign artifacts (if enabled)
4. **Installer**: Create installers (if enabled)
5. **Smoke Tests**: Run validation tests (if enabled)

### Configuration Repository Support

Automatically clones external configuration repository:

```bash
# Use default ci-temurin-config repository
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os mac \
  --architecture aarch64

# Use custom configuration repository
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os linux \
  --architecture x64 \
  --config-repo-url https://github.com/myorg/my-jdk-configs.git \
  --config-repo-branch develop
```

### Stage Resume

Resume from any stage after a failure:

```bash
# Build fails at installer stage
python3 ci/local/run-pipeline.py ... 
# (fails)

# Fix the issue, then resume from installer
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os mac \
  --architecture aarch64 \
  --start-from-stage installer
```

### Workspace Management

Control workspace behavior:

```bash
# Clean workspace before build (fresh start)
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os mac \
  --architecture aarch64 \
  --clean-workspace

# Use existing workspace (faster for iterative testing)
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os mac \
  --architecture aarch64
```

## Command-Line Options

### Required Arguments

- `--jdk-version`: JDK version (jdk8u, jdk11u, jdk17u, jdk21u, jdk22u, jdk23u, jdk)
- `--variant`: Build variant (temurin, openj9, hotspot)
- `--target-os`: Target OS (mac, linux, windows, aix)
- `--architecture`: Target architecture (aarch64, x64, x32, ppc64, s390x)

### Optional Arguments

**Workspace:**
- `--workspace`: Workspace directory (default: ~/openjdk-build)
- `--build-number`: Build number (default: local-YYYYMMDD-HHMMSS)
- `--clean-workspace`: Remove existing workspace before starting

**Build Type:**
- `--release`: Release build
- `--weekly`: Weekly build

**Git References:**
- `--scm-ref`: OpenJDK source branch/tag
- `--build-ref`: temurin-build branch/tag
- `--build-repo-url`: temurin-build repository URL

**Configuration Repository:**
- `--config-repo-url`: Configuration repository URL (default: ci-temurin-config)
- `--config-repo-branch`: Configuration branch (default: main)

**Stage Control:**
- `--start-from-stage`: Start from specific stage (initialize, build, sign, installer, smoke-tests)
- `--skip-build`: Skip build stage (only generate configuration)
- `--no-tests`: Disable tests
- `--no-installers`: Disable installer building
- `--no-signer`: Disable artifact signing

## Examples

### Development Workflow

```bash
# 1. Initial build
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os mac \
  --architecture aarch64 \
  --workspace ~/my-jdk-build

# 2. Make code changes to stage scripts

# 3. Re-run from specific stage
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os mac \
  --architecture aarch64 \
  --workspace ~/my-jdk-build \
  --start-from-stage build
```

### Testing Configuration Changes

```bash
# Test with custom configuration
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os linux \
  --architecture x64 \
  --config-repo-url file:///path/to/local/config/repo \
  --config-repo-branch my-test-branch
```

### Quick Validation

```bash
# Fast validation without tests/installers
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os mac \
  --architecture aarch64 \
  --no-tests \
  --no-installers \
  --no-signer
```

## Output

All artifacts are stored in the workspace using a two-directory structure:

```
~/openjdk-build/                    # pipeline_workspace (root)
├── pipeline-config.json            # Generated configuration
├── stage_workspace/                # Ephemeral - cleaned before/after each stage
│   └── (temporary stage files)
├── artifacts/                      # Persistent - all build outputs
│   ├── *.tar.gz                   # JDK tarballs
│   ├── *.sig                      # Signatures
│   ├── *.pkg / *.msi              # Installers
│   └── test-results/              # Test outputs
└── config-repo/                    # Cloned configuration repository
```

**Directory Purpose:**
- `stage_workspace/` - Ephemeral workspace cleaned before each stage (ensures clean state)
- `artifacts/` - Persistent directory where all build outputs are stored

## Troubleshooting

### Configuration Repository Clone Fails

**Problem**: "Failed to clone configuration repository"

**Solution**: 
- Verify URL is accessible
- Check branch name is correct
- Ensure git is installed and configured

### Stage Script Not Found

**Problem**: "scripts/stages/XX-stage.sh: not found"

**Solution**: Run from ci-adoptium-pipelines repository root

### Permission Denied

**Problem**: "Permission denied" when running scripts

**Solution**: Ensure scripts are executable:
```bash
chmod +x scripts/stages/*.sh
```

## Architecture

### Code Organization

The local pipeline runner is organized into two main modules:

#### run-pipeline.py (556 lines)
Main pipeline orchestrator that:
- Manages stage execution flow
- Handles command-line arguments
- Coordinates between stages
- Reports pipeline status

#### workspace_manager.py (137 lines)
Dedicated workspace management module that:
- Validates workspace state
- Manages directory structure (pipeline_workspace, stage_workspace, artifacts_dir)
- Handles cleanup operations (pre/post stage)
- Provides user-friendly error messages

### Workspace Manager

The `WorkspaceManager` class encapsulates all workspace-related logic:

**Constructor:**
```python
WorkspaceManager(pipeline_workspace, config_file)
```

**Methods:**
- `validate_and_setup(is_restarting, clean_requested, start_from_stage)` - Validates workspace state and creates directory structure
- `cleanup_stage_workspace(cleanup_type)` - Cleans ephemeral stage workspace ('pre' or 'post')

**Properties:**
- `pipeline_workspace` - Root workspace directory
- `stage_workspace` - Ephemeral workspace for stage execution
- `artifacts_dir` - Persistent artifacts directory
- `config_file` - Path to pipeline configuration JSON

### Two-Directory Architecture

The workspace uses a two-directory structure for optimal restartability:

```
~/openjdk-build/                    # pipeline_workspace (root)
├── stage_workspace/                # Ephemeral - cleaned before/after each stage
│   └── (temporary stage files)
├── artifacts/                      # Persistent - survives between stages
│   └── (build outputs, configs)
├── pipeline-config.json            # Generated configuration
└── workspace/
    └── target/                     # Shared artifact directory
        ├── *.tar.gz               # JDK tarballs
        ├── *.sig                  # Signatures
        ├── *.pkg / *.msi          # Installers
        └── test-results/          # Test outputs
```

**Benefits:**
- **Restartability**: Clean workspace before each stage ensures consistent state
- **Artifact Persistence**: Important outputs survive stage cleanup
- **Isolation**: Each stage starts with a clean ephemeral workspace
- **Debugging**: Artifacts directory preserves outputs for inspection

### Workspace Validation

The workspace manager enforces strict validation rules:

| Operation Mode | Workspace Exists | --clean-workspace | Result |
|---------------|------------------|-------------------|---------|
| Fresh build | No | N/A | ✅ Create workspace |
| Fresh build | Yes | No | ❌ Error: Use --clean-workspace or --start-from-stage |
| Fresh build | Yes | Yes | ✅ Clean and recreate workspace |
| Restart | No | N/A | ❌ Error: Workspace must exist |
| Restart | Yes | No | ✅ Use existing workspace |
| Restart | Yes | Yes | ❌ Error: Cannot clean when restarting |

### Cleanup Operations

Each stage performs cleanup operations:

**Pre-cleanup (Always):**
- Cleans `stage_workspace/` before stage execution
- Ensures clean state for restartability
- Critical for consistent builds

**Post-cleanup (Optional):**
- Cleans `stage_workspace/` after stage completion
- Controlled by `cleanWorkspaceAfterStage` parameter in config
- Saves disk space but preserves artifacts

### Integration Example

```python
# Initialize workspace manager
self.workspace_mgr = WorkspaceManager(pipeline_workspace, config_file)

# Validate and setup workspace
self.workspace_mgr.validate_and_setup(
    is_restarting=self.args.start_from_stage is not None,
    clean_requested=self.args.clean_workspace,
    start_from_stage=self.args.start_from_stage
)

# In each stage:
def stage_build(self):
    # Pre-cleanup: Always clean stage_workspace
    self.workspace_mgr.cleanup_stage_workspace('pre')
    
    # Execute stage logic...
    
    # Post-cleanup: Clean if cleanWorkspaceAfterStage=true
    self.workspace_mgr.cleanup_stage_workspace('post')
```

## Design Benefits

### 1. Improved Readability
- Main pipeline logic is focused on orchestration
- Workspace concerns separated into dedicated module
- Reduced cognitive load when reading code

### 2. Better Maintainability
- Workspace logic centralized in one place
- Changes to workspace behavior only affect `workspace_manager.py`
- Easier to test workspace management independently

### 3. Cleaner Code Organization
- Single Responsibility Principle applied
- `PipelineRunner`: Orchestrates pipeline stages
- `WorkspaceManager`: Manages workspace directories and cleanup

### 4. Preserved Functionality
- All existing functionality intact
- Backward compatibility maintained
- No changes to external API or CLI

## Related Documentation

- [LOCAL_TESTING_GUIDE.md](../../docs/LOCAL_TESTING_GUIDE.md) - Comprehensive local testing guide
- [LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md](../../docs/LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md) - Detailed workspace architecture
- [JENKINS_CLEANUP_REFACTORING.md](../../docs/JENKINS_CLEANUP_REFACTORING.md) - Jenkins cleanup patterns
- [RESTARTABILITY_GUIDE.md](../../docs/RESTARTABILITY_GUIDE.md) - Pipeline restart guide
- [CI_AGNOSTIC_ARCHITECTURE.md](../../docs/CI_AGNOSTIC_ARCHITECTURE.md) - Architecture overview