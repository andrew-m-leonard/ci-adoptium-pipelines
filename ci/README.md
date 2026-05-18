# CI Integration Directory

This directory contains CI-specific integration files for different continuous integration platforms.

## Directory Structure

```
ci/
├── jenkins/          # Jenkins-specific files
│   └── Jenkinsfile.declarative
├── local/            # Local execution tools
│   └── run-pipeline.py
└── README.md         # This file
```

## Purpose

The `ci/` directory separates CI-specific orchestration code from the CI-agnostic pipeline logic in `scripts/` and `tools/`. This separation provides:

- **Clear Boundaries**: CI-specific code is isolated from portable shell scripts
- **Multiple CI Support**: Easy to add support for other CI platforms (GitLab, GitHub Actions, etc.)
- **Local Testing**: Local execution tools for development and testing
- **Maintainability**: Changes to CI integration don't affect core pipeline logic

## Subdirectories

### jenkins/
Contains Jenkins-specific pipeline definitions and configuration.

**Files:**
- `Jenkinsfile.declarative` - Declarative pipeline for Jenkins with stage-level restart capability

**Usage:**
Configure Jenkins job to use this Jenkinsfile via SCM:
- Repository: `https://github.com/adoptium/ci-adoptium-pipelines.git`
- Script Path: `ci/jenkins/Jenkinsfile.declarative`

### local/
Contains tools for running the pipeline locally without a CI server.

**Files:**
- `run-pipeline.py` - Python script for local pipeline execution

**Usage:**
```bash
python3 ci/local/run-pipeline.py \
  --jdk-version jdk21u \
  --variant temurin \
  --target-os mac \
  --architecture aarch64
```

## Design Philosophy

**90/10 Rule:**
- 90% of pipeline logic is in CI-agnostic shell scripts (`scripts/` and `tools/`)
- 10% is CI-specific orchestration code (this directory)

This ensures:
- Maximum code reuse across CI platforms
- Easy local testing and debugging
- Reduced vendor lock-in
- Simpler maintenance

## Adding New CI Platforms

To add support for a new CI platform:

1. Create a new subdirectory: `ci/[platform-name]/`
2. Add platform-specific pipeline definition
3. Ensure it calls the same CI-agnostic scripts in `scripts/`
4. Update this README with usage instructions

Example for GitLab CI:
```
ci/
├── gitlab/
│   └── .gitlab-ci.yml
```

## Related Documentation

- [CI_AGNOSTIC_ARCHITECTURE.md](../CI_AGNOSTIC_ARCHITECTURE.md) - Architecture overview
- [CODE_CONFIG_SEPARATION.md](../CODE_CONFIG_SEPARATION.md) - Code/config separation pattern
- [LOCAL_TESTING_GUIDE.md](../LOCAL_TESTING_GUIDE.md) - Local testing instructions
- [PIPELINE_RUNNER_GUIDE.md](../PIPELINE_RUNNER_GUIDE.md) - Pipeline runner documentation