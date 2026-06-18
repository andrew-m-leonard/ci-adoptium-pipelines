# Pipeline Tools

This directory contains utility tools for working with the CI Adoptium Pipelines.

## Configuration Conversion Tools

### convert-legacy-configs-to-new-architecture.py

**Purpose**: Standalone tool to convert legacy Groovy pipeline configurations to the new launch job architecture. No external dependencies required.

**Usage**:
```bash
python3 tools/convert-legacy-configs-to-new-architecture.py \
    --source ~/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations \
    --output ~/workspace/ci-temurin-config
```

**What It Does**:
- Scans input directory for `*_pipeline_config.groovy` files
- Converts each file to JSON format
- **Removes "u" suffix** from output filenames (e.g., `jdk21u_pipeline_config.groovy` → `jdk21_pipeline_config.json`)
- Generates `jenkins_job_config.json` with active JDK versions
- Outputs individual version configs to `configurations/` directory
- Structures output for the new launch job + platform job architecture

**Output Structure**:

1. **jenkins_job_config.json** (top-level config):
```json
{
  "activeJdkVersions": [
    {
      "version": "jdk8",
      "enabled": true
    },
    {
      "version": "jdk11",
      "enabled": true
    },
    {
      "version": "jdk17",
      "enabled": true
    }
  ],
  "defaultBuildArgs": "--create-jre-image --create-sbom",
  "defaultConfigureArgs": "",
  "defaultVariant": "temurin",
  "defaultScmReference": "",
  "configFilePrefix": "configurations/",
  "configFileSuffix": "_pipeline_config.json"
}
```

**Note**: Each version in `activeJdkVersions` is an object with:
- `version`: The JDK version (e.g., "jdk21")
- `enabled`: Boolean flag to enable/disable job creation for this version

2. **configurations/jdk21_pipeline_config.json** (version-specific config):
```json
{
  "version": "jdk21",
  "openjdkVersion": "jdk21u",
  "enabled": true,
  "buildConfigurations": {
    "x64Mac": {
      "os": "mac",
      "arch": "x64",
      "buildArgs": "--create-jre-image --create-sbom",
      "configureArgs": "--enable-dtrace"
    }
  },
  "targetConfigurations": ["x64Mac", "x64Linux", ...]
}
```

**Field Descriptions**:
- `version`: Job/folder name (e.g., "jdk21" - without 'u' suffix)
- `openjdkVersion`: OpenJDK version/stream (e.g., "jdk21u" - with 'u' suffix)
- `enabled`: Whether jobs should be created for this version
- `buildConfigurations`: Platform-specific build settings
- `targetConfigurations`: List of platforms to build

**Command Options**:
```bash
# Dry run to preview what would be converted
python3 tools/convert-legacy-configs-to-new-architecture.py \
    --source ./configs --output ./output --dry-run

# Verbose output with detailed conversion info
python3 tools/convert-legacy-configs-to-new-architecture.py \
    --source ./configs --output ./output --verbose

# Force overwrite existing files
python3 tools/convert-legacy-configs-to-new-architecture.py \
    --source ./configs --output ./output --force
```

**Example Output**:
```
======================================================================
Legacy Groovy to New Architecture Converter
======================================================================

Scanning: /Users/user/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations
Pattern:  *_pipeline_config.groovy
Found:    7 configuration file(s)

Converting to: /Users/user/workspace/ci-temurin-config

[1/7] Converting jdk8u_pipeline_config.groovy... ✅
[2/7] Converting jdk11u_pipeline_config.groovy... ✅
[3/7] Converting jdk17u_pipeline_config.groovy... ✅
[4/7] Converting jdk21u_pipeline_config.groovy... ✅
[5/7] Converting jdk23u_pipeline_config.groovy... ✅
[6/7] Converting jdk24u_pipeline_config.groovy... ✅
[7/7] Converting jdk25u_pipeline_config.groovy... ✅

Generating jenkins_job_config.json... ✅

======================================================================
Conversion Summary
======================================================================
Total:   7
Success: 7
Failed:  0

✅ All configurations converted successfully!

Generated files:
  - jenkins_job_config.json
  - configurations/jdk8_pipeline_config.json
  - configurations/jdk11_pipeline_config.json
  - configurations/jdk17_pipeline_config.json
  - configurations/jdk21_pipeline_config.json
  - configurations/jdk23_pipeline_config.json
  - configurations/jdk24_pipeline_config.json
  - configurations/jdk25_pipeline_config.json

Next steps:
1. Review the generated JSON files
2. Verify platform configurations are correct
3. Test with the seed job
4. Commit to your configuration repository
```

**Important Notes**:
- ⚠️ This is a **semi-automated** conversion tool
- Manual review of the output is **strongly recommended**
- Pay special attention to:
  - Nested maps and lists
  - Variant-specific configurations (temurin vs hotspot)
  - Test configurations
  - Special characters in strings
- The tool automatically removes the "u" suffix from version names in filenames
- SCM references retain the "u" suffix (e.g., `jdk21u`) for compatibility

---

### convert-groovy-to-json.py

**Purpose**: Python module for Groovy-to-JSON conversion. This is a legacy library module kept for reference.

**Note**: The new [`convert-legacy-configs-to-new-architecture.py`](../ci-adoptium-pipelines/tools/convert-legacy-configs-to-new-architecture.py:1) is standalone and does not require this module.

---

## Workspace Management Tools

### workspace-cleanup.sh

**Purpose**: Clean workspace directories for local pipeline execution.

**Usage**:
```bash
./tools/workspace-cleanup.sh <workspace_dir> [stage_name]
```

**Examples**:

1. Clean entire workspace:
```bash
./tools/workspace-cleanup.sh ~/openjdk-build
```

2. Clean specific stage workspace:
```bash
./tools/workspace-cleanup.sh ~/openjdk-build build
```

**What It Does**:
- Removes temporary files and directories
- Preserves artifacts directory (never auto-cleaned)
- Cleans stage-specific workspace directories
- Safe to run between pipeline stages

**Important Notes**:
- Used by `ci/local/run-pipeline.py` for workspace management
- Jenkins uses native `cleanWs()` utility instead
- Artifacts directory is **never** automatically cleaned

---

## Migration Workflow

### Converting Legacy Configurations to New Architecture

**Step 1**: Clone the legacy configuration repository
```bash
git clone https://github.com/adoptium/ci-jenkins-pipelines.git ~/workspace/ci-jenkins-pipelines
```

**Step 2**: Convert all configurations to new architecture
```bash
cd ~/workspace/ci-adoptium-pipelines

python3 tools/convert-legacy-configs-to-new-architecture.py \
    --source ~/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations \
    --output ~/workspace/ci-temurin-config
```

This will generate:
- `jenkins_job_config.json` (top-level config with active versions)
- `configurations/jdk8_pipeline_config.json`
- `configurations/jdk11_pipeline_config.json`
- `configurations/jdk17_pipeline_config.json`
- `configurations/jdk21_pipeline_config.json`
- `configurations/jdk23_pipeline_config.json`
- `configurations/jdk24_pipeline_config.json`
- `configurations/jdk25_pipeline_config.json`

**Step 3**: Review and validate the generated files
```bash
cd ~/workspace/ci-temurin-config

# Check the top-level config
cat jenkins_job_config.json | jq .

# Check a version-specific config
cat configurations/jdk21_pipeline_config.json | jq .

# Verify all active versions are present
jq '.activeJdkVersions' jenkins_job_config.json
```

**Step 4**: Test with the seed job
```bash
# The seed job will use these configs to create:
# - Launch jobs: jdk${version}-launch-build-pipelines
# - Platform jobs: jdk${version}-${platform}-build-pipeline (created dynamically)
```

**Step 5**: Commit to configuration repository
```bash
cd ~/workspace/ci-temurin-config
git add jenkins_job_config.json configurations/*.json
git commit -m "Convert legacy Groovy configs to new launch job architecture"
git push
```

---

## Related Documentation

- [Configuration Guide](../docs/CONFIGURATION_GUIDE.md) - Complete configuration system documentation
- [Code/Config Separation](../docs/CODE_CONFIG_SEPARATION.md) - Architecture for separating code and config
- [Migration Plan](../docs/MIGRATION_PLAN.md) - Overall migration strategy

---

## Troubleshooting

### Conversion Issues

**Problem**: Nested maps not converting correctly
```
Error parsing x64Linux: unexpected token
```

**Solution**: Manually review the Groovy file for complex nested structures and adjust the JSON output.

---

**Problem**: Variant-specific values not preserved
```groovy
buildArgs: [
    temurin: '--create-jre-image --create-sbom',
    hotspot: '--create-jre-image'
]
```

**Solution**: The tool should handle this, but verify the JSON output:
```json
"buildArgs": {
  "temurin": "--create-jre-image --create-sbom",
  "hotspot": "--create-jre-image"
}
```

---

**Problem**: Test configurations missing
```
Warning: No test configuration found for platform
```

**Solution**: Add test configuration manually to the JSON:
```json
"test": {
  "nightly": ["sanity.openjdk", "sanity.system"],
  "weekly": ["sanity.openjdk", "sanity.system", "extended.system"],
  "release": ["sanity.openjdk", "sanity.system", "extended.system", "extended.openjdk"]
}
```

---

**Problem**: Version suffix mismatch
```
Error: Config file not found: configurations/jdk21u_pipeline_config.json
```

**Solution**: The new architecture removes the "u" suffix from config filenames. Ensure your [`jenkins_job_config.json`](../ci-temurin-config/jenkins_job_config.json:1) uses the correct naming:
- ✅ Correct: `configurations/jdk21_pipeline_config.json`
- ❌ Incorrect: `configurations/jdk21u_pipeline_config.json`

---

## Contributing

When adding new tools to this directory:

1. Add comprehensive inline documentation (usage, examples)
2. Update this README with tool description
3. Add examples to relevant documentation in `docs/`
4. Test thoroughly with real-world configurations
5. Consider edge cases and error handling

---

**Last Updated**: 2026-06-18