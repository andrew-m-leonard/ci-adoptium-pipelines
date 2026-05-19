# Pipeline Tools

This directory contains utility tools for working with the CI Adoptium Pipelines.

## Configuration Conversion Tools

### convert-groovy-config-to-json.sh

**Purpose**: Convert legacy Groovy pipeline configuration files to the new JSON format.

**Usage**:
```bash
./tools/convert-groovy-config-to-json.sh <groovy_config_file> [output_json_file]
```

**Examples**:

1. Convert with automatic output filename:
```bash
./tools/convert-groovy-config-to-json.sh \
    ~/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations/jdk21u_pipeline_config.groovy
# Creates: jdk21u_pipeline_config.json
```

2. Convert with custom output filename:
```bash
./tools/convert-groovy-config-to-json.sh \
    ~/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations/jdk21u_pipeline_config.groovy \
    configurations/jdk21u_pipeline_config.json
```

**What It Does**:
- Parses Groovy configuration syntax
- Extracts `buildConfigurations` map
- Converts platform-specific settings to JSON
- Handles nested maps and lists
- Preserves variant-specific configurations

**Output Structure**:
```json
{
  "version": "jdk21u",
  "scmReference": "jdk21u",
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

**Important Notes**:
- ⚠️ This is a **semi-automated** conversion tool
- Manual review of the output is **strongly recommended**
- Pay special attention to:
  - Nested maps and lists
  - Variant-specific configurations (temurin vs hotspot)
  - Test configurations
  - Special characters in strings

**Testing the Output**:
```bash
# Validate the generated JSON
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture x64 \
    --skip-build
```

---

### convert-groovy-to-json.py

**Purpose**: Python module for Groovy-to-JSON conversion (used by convert-groovy-config-to-json.sh).

**Usage**: This is a library module, not meant to be run directly. Use `convert-groovy-config-to-json.sh` instead.

---

### convert-all-legacy-groovy-configs.py

**Purpose**: Batch convert all Groovy configuration files in a directory to JSON format.

**Usage**:
```bash
python3 tools/convert-all-legacy-groovy-configs.py \
    --input-dir ~/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations \
    --output-dir ./configurations
```

**What It Does**:
- Scans input directory for `*_pipeline_config.groovy` files
- Converts each file to JSON using the conversion logic
- Outputs all JSON files to the specified directory
- Provides summary of successful and failed conversions

**Example Output**:
```
Converting Groovy configurations to JSON...

Processing: jdk8u_pipeline_config.groovy
  ✅ Successfully converted to jdk8u_pipeline_config.json

Processing: jdk11u_pipeline_config.groovy
  ✅ Successfully converted to jdk11u_pipeline_config.json

Processing: jdk17u_pipeline_config.groovy
  ✅ Successfully converted to jdk17u_pipeline_config.json

Summary:
  Total files: 10
  Successful: 10
  Failed: 0
```

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

### Converting Legacy Configurations

**Step 1**: Clone the legacy configuration repository
```bash
git clone https://github.com/adoptium/ci-jenkins-pipelines.git ~/workspace/ci-jenkins-pipelines
```

**Step 2**: Convert all configurations
```bash
python3 tools/convert-all-legacy-groovy-configs.py \
    --input-dir ~/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations \
    --output-dir ./configurations
```

**Step 3**: Review and validate each JSON file
```bash
# Check the structure
cat configurations/jdk21u_pipeline_config.json | jq .

# Test with pipeline runner
python3 ci/local/run-pipeline.py \
    --jdk-version jdk21u \
    --variant temurin \
    --target-os mac \
    --architecture x64 \
    --skip-build
```

**Step 4**: Commit to configuration repository
```bash
cd ~/workspace/ci-temurin-config
git add configurations/*.json
git commit -m "Convert legacy Groovy configs to JSON"
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

## Contributing

When adding new tools to this directory:

1. Add comprehensive inline documentation (usage, examples)
2. Update this README with tool description
3. Add examples to relevant documentation in `docs/`
4. Test thoroughly with real-world configurations
5. Consider edge cases and error handling

---

**Last Updated**: 2026-05-19