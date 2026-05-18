# Code/Config Separation Pattern

## Overview

The Adoptium CI Pipelines architecture implements a strict separation between **pipeline code** and **pipeline configuration**. This separation enables:

- ✅ **Vendor Independence**: Configuration can be maintained separately by different vendors
- ✅ **Code Reusability**: Same pipeline code works for multiple vendors/organizations
- ✅ **Security**: Sensitive configuration can be kept in private repositories
- ✅ **Flexibility**: Easy to test with different configurations without modifying code
- ✅ **Governance**: Different teams can own code vs configuration

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    CODE REPOSITORY                               │
│         github.com/adoptium/ci-adoptium-pipelines                │
│                                                                  │
│  ├── scripts/                    # Pipeline implementation       │
│  │   ├── lib/                    # Shared utilities             │
│  │   └── stages/                 # Stage scripts                │
│  ├── Jenkinsfile.declarative     # Pipeline orchestration       │
│  ├── run-pipeline.py             # Local testing tool           │
│  └── docs/                       # Documentation                │
│                                                                  │
│  NO CONFIGURATION DATA HERE                                      │
└─────────────────────────────────────────────────────────────────┘
                              ↓ loads from
┌─────────────────────────────────────────────────────────────────┐
│                  CONFIG REPOSITORY                               │
│         github.com/adoptium/ci-jenkins-pipelines                 │
│         (or vendor-specific private repository)                  │
│                                                                  │
│  └── configurations/             # JSON configuration files      │
│      ├── jdk21u_pipeline_config.json                            │
│      ├── jdk17u_pipeline_config.json                            │
│      ├── jdk11u_pipeline_config.json                            │
│      └── ...                                                     │
│                                                                  │
│  ONLY CONFIGURATION DATA HERE                                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## How It Works

### 1. Jenkins Parameters

The `Jenkinsfile.declarative` accepts two parameters to specify the configuration source:

```groovy
parameters {
    string(
        name: 'CONFIG_REPO_URL',
        defaultValue: 'https://github.com/adoptium/ci-jenkins-pipelines.git',
        description: 'Git repository URL containing pipeline configurations (JSON files)'
    )
    string(
        name: 'CONFIG_REPO_BRANCH',
        defaultValue: 'master',
        description: 'Branch/tag to checkout from configuration repository'
    )
}
```

### 2. Configuration Checkout

During the Initialize stage, the pipeline:

1. **Checks out configuration** from the specified repository:
```groovy
dir('configurations') {
    checkout([
        $class: 'GitSCM',
        branches: [[name: "*/${params.CONFIG_REPO_BRANCH}"]],
        userRemoteConfigs: [[
            url: params.CONFIG_REPO_URL
        ]],
        extensions: [
            [$class: 'SparseCheckoutPaths',
             sparseCheckoutPaths: [[path: 'configurations/*']]]
        ]
    ])
}
```

2. **Checks out pipeline code** from ci-adoptium-pipelines:
```groovy
dir('scripts') {
    checkout([
        $class: 'GitSCM',
        branches: [[name: '*/main']],
        userRemoteConfigs: [[
            url: 'https://github.com/adoptium/ci-adoptium-pipelines.git'
        ]],
        extensions: [
            [$class: 'SparseCheckoutPaths',
             sparseCheckoutPaths: [[path: 'scripts/*']]]
        ]
    ])
}
```

### 3. Configuration Loading

Stage scripts load configuration using the JSON loader:

```bash
#!/bin/bash
source "$(dirname "$0")/../lib/config-utils.sh"

# Load configuration from JSON
CONFIG=$(load_json_config)

# Extract values
JDK_VERSION=$(echo "$CONFIG" | jq -r '.version')
BUILD_ARGS=$(echo "$CONFIG" | jq -r '.buildConfigurations.x64Linux.configureArgs')
```

---

## Use Cases

### Use Case 1: Adoptium Official Builds

**Configuration Repository**: `github.com/adoptium/ci-jenkins-pipelines`
- Public repository
- Contains official Adoptium build configurations
- Maintained by Adoptium team

**Pipeline Code**: `github.com/adoptium/ci-adoptium-pipelines`
- Public repository
- Contains reusable pipeline implementation
- Maintained by Adoptium infrastructure team

**Jenkins Parameters**:
```
CONFIG_REPO_URL: https://github.com/adoptium/ci-jenkins-pipelines.git
CONFIG_REPO_BRANCH: master
```

### Use Case 2: Vendor-Specific Builds

**Configuration Repository**: `github.com/acme-corp/openjdk-configs` (private)
- Private repository
- Contains ACME Corp's custom build configurations
- May include proprietary settings
- Maintained by ACME Corp

**Pipeline Code**: `github.com/adoptium/ci-adoptium-pipelines`
- Public repository (reused from Adoptium)
- No modifications needed
- Benefits from Adoptium updates

**Jenkins Parameters**:
```
CONFIG_REPO_URL: https://github.com/acme-corp/openjdk-configs.git
CONFIG_REPO_BRANCH: production
```

### Use Case 3: Testing New Configurations

**Configuration Repository**: `github.com/adoptium/ci-jenkins-pipelines`
- Feature branch with experimental configs
- Test without affecting production

**Pipeline Code**: `github.com/adoptium/ci-adoptium-pipelines`
- Stable main branch

**Jenkins Parameters**:
```
CONFIG_REPO_URL: https://github.com/adoptium/ci-jenkins-pipelines.git
CONFIG_REPO_BRANCH: feature/new-jdk-version
```

### Use Case 4: Local Development

**Configuration Repository**: Local filesystem or fork
- Developer's local modifications
- Quick iteration

**Pipeline Code**: Local checkout

**Command**:
```bash
python3 run-pipeline.py \
  --config /path/to/local/configs/jdk21u_pipeline_config.json \
  --platform x64Mac \
  --variant temurin
```

---

## Configuration Repository Structure

### Required Structure

```
config-repository/
└── configurations/
    ├── jdk21u_pipeline_config.json
    ├── jdk17u_pipeline_config.json
    ├── jdk11u_pipeline_config.json
    └── jdk8u_pipeline_config.json
```

### Configuration File Format

Each JSON file must follow this schema:

```json
{
  "version": "jdk21u",
  "scmReference": "jdk21u",
  "buildConfigurations": {
    "x64Linux": {
      "os": "linux",
      "arch": "x64",
      "additionalNodeLabels": "centos6&&build",
      "test": "default",
      "dockerImage": "adoptopenjdk/centos6_build_image",
      "configureArgs": "--enable-unlimited-crypto"
    },
    "x64Mac": {
      "os": "mac",
      "arch": "x64",
      "additionalNodeLabels": "macos",
      "test": "default",
      "configureArgs": "--enable-unlimited-crypto"
    }
  },
  "targetConfigurations": [
    "x64Linux",
    "x64Mac",
    "x64Windows"
  ]
}
```

See [CONFIGURATION_GUIDE.md](CONFIGURATION_GUIDE.md) for complete schema.

---

## Benefits

### For Adoptium

1. **Open Source Pipeline Code**: Community can contribute improvements
2. **Vendor Adoption**: Other organizations can reuse the pipeline
3. **Configuration Control**: Adoptium maintains official configurations
4. **Security**: No secrets in public pipeline code

### For Vendors

1. **Code Reuse**: Use Adoptium's battle-tested pipeline
2. **Configuration Privacy**: Keep proprietary settings private
3. **Easy Updates**: Pull pipeline improvements without config conflicts
4. **Customization**: Maintain vendor-specific configurations

### For Developers

1. **Clear Boundaries**: Know where to make changes (code vs config)
2. **Easy Testing**: Test with different configs without code changes
3. **Local Development**: Use local configs for rapid iteration
4. **Version Control**: Config and code versioned independently

---

## Migration from Monolithic Pipeline

### Before (Monolithic)

```groovy
// openjdk_build_pipeline.groovy (2000+ lines)

def buildConfigurations = [
    x64Linux: [
        os: 'linux',
        arch: 'x64',
        configureArgs: '--enable-unlimited-crypto'
    ]
]

// ... 2000 more lines of mixed code and config
```

**Problems**:
- ❌ Configuration embedded in code
- ❌ Can't reuse code without config
- ❌ Hard to maintain separate vendor configs
- ❌ Changes to config require code review

### After (Separated)

**Code Repository** (`ci-adoptium-pipelines`):
```groovy
// Jenkinsfile.declarative (clean orchestration)
stage('Build') {
    steps {
        sh './scripts/stages/02-build.sh'
    }
}
```

**Config Repository** (`ci-jenkins-pipelines` or vendor repo):
```json
{
  "buildConfigurations": {
    "x64Linux": {
      "os": "linux",
      "arch": "x64",
      "configureArgs": "--enable-unlimited-crypto"
    }
  }
}
```

**Benefits**:
- ✅ Code and config separated
- ✅ Code reusable across vendors
- ✅ Easy to maintain multiple configs
- ✅ Config changes don't need code review

---

## Best Practices

### 1. Never Hardcode Configuration

❌ **Bad**:
```bash
# In stage script
CONFIGURE_ARGS="--enable-unlimited-crypto --with-zlib=system"
```

✅ **Good**:
```bash
# In stage script
CONFIGURE_ARGS=$(echo "$CONFIG" | jq -r '.buildConfigurations.x64Linux.configureArgs')
```

### 2. Use Default Configuration Repository

Set sensible defaults in Jenkinsfile:
```groovy
string(
    name: 'CONFIG_REPO_URL',
    defaultValue: 'https://github.com/adoptium/ci-jenkins-pipelines.git',
    description: 'Git repository URL containing pipeline configurations'
)
```

### 3. Document Configuration Schema

Maintain schema documentation in config repository:
- JSON Schema file
- Example configurations
- Validation scripts

### 4. Version Configuration Separately

- Use semantic versioning for config repo
- Tag stable configurations
- Use branches for experimental configs

### 5. Validate Configuration

Add validation in Initialize stage:
```bash
# Validate JSON syntax
jq empty configurations/jdk21u_pipeline_config.json

# Validate against schema
jsonschema -i configurations/jdk21u_pipeline_config.json schema.json
```

---

## Security Considerations

### Public Code Repository

The `ci-adoptium-pipelines` repository is public and should **never** contain:
- ❌ Credentials or secrets
- ❌ API keys or tokens
- ❌ Private URLs or endpoints
- ❌ Vendor-specific information
- ❌ Proprietary configuration

### Private Config Repository

Vendors can use private repositories for configurations containing:
- ✅ Internal build server URLs
- ✅ Custom signing configurations
- ✅ Proprietary build flags
- ✅ Internal artifact repositories
- ✅ Vendor-specific settings

**Note**: Even in private repos, use Jenkins credentials for secrets, not JSON files.

---

## Testing

### Test with Different Configurations

```bash
# Test with Adoptium config
python3 run-pipeline.py \
  --config https://github.com/adoptium/ci-jenkins-pipelines.git/configurations/jdk21u_pipeline_config.json

# Test with vendor config
python3 run-pipeline.py \
  --config https://github.com/acme-corp/configs.git/jdk21u_custom.json

# Test with local config
python3 run-pipeline.py \
  --config /path/to/local/test-config.json
```

### Validate Configuration Changes

Before committing config changes:

1. **Syntax validation**:
```bash
jq empty configurations/jdk21u_pipeline_config.json
```

2. **Schema validation**:
```bash
jsonschema -i configurations/jdk21u_pipeline_config.json schema.json
```

3. **Test build**:
```bash
python3 run-pipeline.py --config configurations/jdk21u_pipeline_config.json
```

---

## FAQ

### Q: Can I use a private repository for configurations?

**A**: Yes! That's the whole point. Set `CONFIG_REPO_URL` to your private repository URL. Jenkins will need credentials to access it.

### Q: Do I need to fork ci-adoptium-pipelines?

**A**: No. You can use the public ci-adoptium-pipelines code as-is and only maintain your own configuration repository.

### Q: Can I have multiple configuration repositories?

**A**: Yes. Create different Jenkins jobs with different `CONFIG_REPO_URL` parameters.

### Q: How do I update to the latest pipeline code?

**A**: The pipeline automatically checks out the latest code from ci-adoptium-pipelines. No action needed unless you've forked it.

### Q: Can I override the pipeline code repository?

**A**: Currently, the pipeline code repository is hardcoded to `github.com/adoptium/ci-adoptium-pipelines`. If you need to use a fork, modify the `Jenkinsfile.declarative` checkout section.

### Q: What if my config repository has a different structure?

**A**: Adjust the `sparseCheckoutPaths` in the Jenkinsfile to match your repository structure. The pipeline expects configs in a `configurations/` directory.

---

## Related Documentation

- [CONFIGURATION_GUIDE.md](CONFIGURATION_GUIDE.md) - Configuration file schema
- [ARCHITECTURE_COMPARISON.md](ARCHITECTURE_COMPARISON.md) - Architecture overview
- [MIGRATION_PLAN.md](MIGRATION_PLAN.md) - Migration strategy
- [CONTRIBUTING.md](CONTRIBUTING.md) - Contribution guidelines

---

## Summary

The code/config separation pattern provides:

1. **Flexibility**: Use same code with different configurations
2. **Security**: Keep sensitive configs private
3. **Reusability**: Vendors can adopt Adoptium's pipeline
4. **Maintainability**: Clear boundaries between code and data
5. **Governance**: Different teams can own different aspects

This pattern is essential for a successful open-source CI/CD pipeline that serves multiple organizations with different requirements.