#!/bin/bash
# Tool to convert Groovy pipeline config files to JSON
#
# Usage: ./convert-groovy-config-to-json.sh <groovy_config_file> [output_json_file]
#
# This tool parses a jdkNN_pipeline_config.groovy file and converts it to JSON format
# suitable for use with the initialize stage.

set -euo pipefail

# Check arguments
if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <groovy_config_file> [output_json_file]"
    echo ""
    echo "Example:"
    echo "  $0 ~/workspace/ci-jenkins-pipelines/pipelines/jobs/configurations/jdk21u_pipeline_config.groovy"
    echo "  $0 jdk21u_pipeline_config.groovy jdk21u_pipeline_config.json"
    exit 1
fi

GROOVY_FILE="$1"
OUTPUT_FILE="${2:-$(basename "${GROOVY_FILE}" .groovy).json}"

if [[ ! -f "${GROOVY_FILE}" ]]; then
    echo "Error: File not found: ${GROOVY_FILE}"
    exit 1
fi

echo "Converting ${GROOVY_FILE} to ${OUTPUT_FILE}..."
echo ""
echo "NOTE: This is a semi-automated conversion tool."
echo "      Manual review and adjustment of the output JSON is recommended."
echo ""

# Create a Python script to do the conversion
python3 - <<'PYTHON_SCRIPT' "${GROOVY_FILE}" "${OUTPUT_FILE}"
import sys
import re
import json

def parse_groovy_config(groovy_file):
    """Parse Groovy config file and extract build configurations."""
    
    with open(groovy_file, 'r') as f:
        content = f.read()
    
    # Extract the buildConfigurations map
    config_match = re.search(r'buildConfigurations\s*=\s*\[(.*?)\n\s*\]', content, re.DOTALL)
    if not config_match:
        print("Error: Could not find buildConfigurations in file", file=sys.stderr)
        sys.exit(1)
    
    config_content = config_match.group(1)
    
    # Parse each platform configuration
    platforms = {}
    
    # Find all platform entries (e.g., "x64Mac : [")
    platform_pattern = r'(\w+)\s*:\s*\[(.*?)(?=\n\s*\w+\s*:|$)'
    
    for match in re.finditer(platform_pattern, config_content, re.DOTALL):
        platform_name = match.group(1)
        platform_content = match.group(2)
        
        platform_config = {}
        
        # Parse simple key-value pairs
        for key_match in re.finditer(r'(\w+)\s*:\s*[\'"]([^\'"]+)[\'"]', platform_content):
            key = key_match.group(1)
            value = key_match.group(2)
            platform_config[key] = value
        
        # Parse boolean values
        for key_match in re.finditer(r'(\w+)\s*:\s*(true|false)', platform_content):
            key = key_match.group(1)
            value = key_match.group(2) == 'true'
            platform_config[key] = value
        
        # Parse maps (like buildArgs, configureArgs)
        for map_match in re.finditer(r'(\w+)\s*:\s*\[(.*?)\]', platform_content, re.DOTALL):
            key = map_match.group(1)
            map_content = map_match.group(2)
            
            # Check if it's a simple list or a map
            if ':' in map_content:
                # It's a map
                map_dict = {}
                for item_match in re.finditer(r'[\'"]?(\w+)[\'"]?\s*:\s*[\'"]([^\'"]+)[\'"]', map_content):
                    map_key = item_match.group(1)
                    map_value = item_match.group(2)
                    map_dict[map_key] = map_value
                platform_config[key] = map_dict
            else:
                # It's a list
                items = re.findall(r'[\'"]([^\'"]+)[\'"]', map_content)
                platform_config[key] = items
        
        platforms[platform_name] = platform_config
    
    return {"buildConfigurations": platforms}

def main():
    if len(sys.argv) < 3:
        print("Error: Missing arguments", file=sys.stderr)
        sys.exit(1)
    
    groovy_file = sys.argv[1]
    output_file = sys.argv[2]
    
    try:
        config = parse_groovy_config(groovy_file)
        
        with open(output_file, 'w') as f:
            json.dump(config, f, indent=2)
        
        print(f"✅ Successfully converted to {output_file}")
        print(f"   Found {len(config['buildConfigurations'])} platform configurations")
        print("")
        print("Platforms:")
        for platform in config['buildConfigurations'].keys():
            print(f"  - {platform}")
        print("")
        print("⚠️  Please review the output file and adjust as needed:")
        print("   - Nested maps may need manual adjustment")
        print("   - Test configurations may need restructuring")
        print("   - Docker configurations should be verified")
        
    except Exception as e:
        print(f"Error during conversion: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
PYTHON_SCRIPT

echo ""
echo "Conversion complete!"
echo ""
echo "Next steps:"
echo "1. Review ${OUTPUT_FILE}"
echo "2. Adjust any nested configurations manually"
echo "3. Test with: ./scripts/stages/01-initialize.sh"

# Made with Bob
