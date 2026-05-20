# CI Adoptium Pipelines Documentation

Welcome to the CI Adoptium Pipelines documentation. This directory contains comprehensive guides for understanding, using, and contributing to the pipeline infrastructure.

## 📚 Table of Contents

### Getting Started

- **[LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md](./LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md)** - ⭐ **NEW** Local runner workspace and artifacts architecture

### Architecture & Design

- **[CI_AGNOSTIC_ARCHITECTURE.md](./CI_AGNOSTIC_ARCHITECTURE.md)** - Overview of the CI-agnostic architecture design with before/after comparison ⭐
- **[CODE_CONFIG_SEPARATION.md](./CODE_CONFIG_SEPARATION.md)** - Separation of pipeline code and configuration
- **[STAGE_IO_SPECIFICATION.md](./STAGE_IO_SPECIFICATION.md)** - Input/output specifications for pipeline stages
- **[UNIVERSAL_STAGE_PATTERN.md](./UNIVERSAL_STAGE_PATTERN.md)** - Universal pattern for stage implementation

### Configuration

- **[CONFIGURATION_GUIDE.md](./CONFIGURATION_GUIDE.md)** - Complete guide to pipeline configuration
- **[JENKINS_ENVIRONMENT_VARIABLES.md](./JENKINS_ENVIRONMENT_VARIABLES.md)** - Jenkins environment variables reference

### Pipeline Features

- **[RESTARTABILITY_GUIDE.md](./RESTARTABILITY_GUIDE.md)** - Guide to restartable pipelines
- **[JENKINS_RESTART_BEHAVIOR.md](./JENKINS_RESTART_BEHAVIOR.md)** - Jenkins-specific restart behavior
- **[REPRO_COMPARE_INTEGRATION.md](./REPRO_COMPARE_INTEGRATION.md)** - Reproducible build comparison integration
- **[REPRODUCIBLE_COMPARE.md](./REPRODUCIBLE_COMPARE.md)** - Reproducible build comparison details

### Workspace Management

- **[WORKSPACE_ARTIFACTS_ARCHITECTURE.md](./WORKSPACE_ARTIFACTS_ARCHITECTURE.md)** - Workspace and artifacts separation architecture
- **[WORKSPACE_CLEANUP.md](./WORKSPACE_CLEANUP.md)** - Workspace cleanup strategies
- **[WORKSPACE_VALIDATION_PATTERN.md](./WORKSPACE_VALIDATION_PATTERN.md)** - Workspace validation patterns
- **[JENKINS_CLEANUP_REFACTORING.md](./JENKINS_CLEANUP_REFACTORING.md)** - ⭐ **NEW** Jenkins cleanup refactoring using native cleanWs()

### Implementation Details

- **[SHELL_SCRIPTS_SUMMARY.md](./SHELL_SCRIPTS_SUMMARY.md)** - Summary of shell scripts used in the pipeline
- **[STAGE_INPUT_STRATEGY.md](./STAGE_INPUT_STRATEGY.md)** - Strategy for stage input handling
- **[TARGET_DIR_REFACTORING.md](./TARGET_DIR_REFACTORING.md)** - Target directory refactoring details
- **[PIPELINE_RUNNER_GUIDE.md](./PIPELINE_RUNNER_GUIDE.md)** - Guide to the pipeline runner

### Migration & Planning

- **[MIGRATION_PLAN.md](./MIGRATION_PLAN.md)** - Migration plan for adopting the new pipeline
- **[MIGRATION_VISUAL_GUIDE.md](./MIGRATION_VISUAL_GUIDE.md)** - Visual guide for migration
- **[GITHUB_EPICS_AND_ISSUES.md](./GITHUB_EPICS_AND_ISSUES.md)** - GitHub epics and issues tracking

### Tools & Utilities

- **[Tools Documentation](../tools/README.md)** - ⭐ **NEW** Configuration conversion and workspace management tools
  - Groovy-to-JSON conversion tools
  - Batch conversion utilities
  - Workspace cleanup utilities

### Testing

- **[TEST_BUILD_UID_GUIDE.md](./TEST_BUILD_UID_GUIDE.md)** - Guide for testing with build UIDs

## 🎯 Quick Navigation by Use Case

### I want to...

#### Run a Local Build
1. Understand the workspace architecture in [LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md](./LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md) ⭐
2. Configure your build using [CONFIGURATION_GUIDE.md](./CONFIGURATION_GUIDE.md)
3. Use [PIPELINE_RUNNER_GUIDE.md](./PIPELINE_RUNNER_GUIDE.md) for detailed runner usage

#### Understand the Architecture
1. Read [CI_AGNOSTIC_ARCHITECTURE.md](./CI_AGNOSTIC_ARCHITECTURE.md) for the overall design and before/after comparison
2. Review [CODE_CONFIG_SEPARATION.md](./CODE_CONFIG_SEPARATION.md) for configuration approach

#### Work with Restartable Pipelines
1. Start with [RESTARTABILITY_GUIDE.md](./RESTARTABILITY_GUIDE.md)
2. Understand [JENKINS_RESTART_BEHAVIOR.md](./JENKINS_RESTART_BEHAVIOR.md)
3. Learn about workspace management in [WORKSPACE_ARTIFACTS_ARCHITECTURE.md](./WORKSPACE_ARTIFACTS_ARCHITECTURE.md)
4. See the latest cleanup approach in [JENKINS_CLEANUP_REFACTORING.md](./JENKINS_CLEANUP_REFACTORING.md) ⭐

#### Implement a New Stage
1. Follow the [UNIVERSAL_STAGE_PATTERN.md](./UNIVERSAL_STAGE_PATTERN.md)
2. Review [STAGE_IO_SPECIFICATION.md](./STAGE_IO_SPECIFICATION.md)
3. Check [STAGE_INPUT_STRATEGY.md](./STAGE_INPUT_STRATEGY.md)

#### Convert Legacy Configurations
1. Read [Tools Documentation](../tools/README.md) for conversion tools
2. Use `convert-groovy-config-to-json.sh` for single file conversion
3. Use `convert-all-legacy-groovy-configs.py` for batch conversion
4. Validate converted JSON with pipeline runner

#### Migrate from Old Pipeline
1. Start with [MIGRATION_PLAN.md](./MIGRATION_PLAN.md)
2. Use [MIGRATION_VISUAL_GUIDE.md](./MIGRATION_VISUAL_GUIDE.md) for visual reference
3. Track progress with [GITHUB_EPICS_AND_ISSUES.md](./GITHUB_EPICS_AND_ISSUES.md)

## 📝 Recent Updates

### 2026-05-19
- ⭐ **NEW**: [Tools Documentation](../tools/README.md) - Comprehensive documentation for pipeline tools
  - Configuration conversion tools (Groovy to JSON)
  - Batch conversion utilities
  - Workspace management tools
  - Migration workflow guide

### 2026-05-18
- ⭐ **NEW**: [LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md](./LOCAL_RUNNER_WORKSPACE_ARCHITECTURE.md) - Local runner workspace/artifacts separation
  - Two-directory architecture: `stage_workspace/` (ephemeral) + `artifacts/` (persistent)
  - Strict workspace validation rules
  - Automatic pre/post cleanup
  - Restartability support
- ⭐ **NEW**: [JENKINS_CLEANUP_REFACTORING.md](./JENKINS_CLEANUP_REFACTORING.md) - Simplified workspace cleanup using Jenkins native `cleanWs()` utility
  - Removed custom cleanup helper function
  - Updated all 12 pipeline stages
  - Improved restartability and reliability
- **UPDATED**: Multiple documentation files to reflect current architecture
  - Repository separation (ci-adoptium-pipelines vs ci-temurin-config)
  - Current directory structure
  - Two-directory workspace architecture

## 🤝 Contributing

See [../CONTRIBUTING.md](../CONTRIBUTING.md) for contribution guidelines.

## 📖 Main README

For project overview and quick links, see [../README.md](../README.md).

---

**Note**: All documentation is organized by topic. If you can't find what you're looking for, try using your editor's search functionality across all markdown files in this directory.