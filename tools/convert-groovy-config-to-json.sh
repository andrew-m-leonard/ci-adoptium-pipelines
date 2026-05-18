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

# Create an improved Python script to do the conversion
python3 - <<'PYTHON_SCRIPT' "${GROOVY_FILE}" "${OUTPUT_FILE}"
import sys
import re
import json
from typing import Any, Dict, List, Union

def parse_groovy_value(value_str: str) -> Any:
    """Parse a Groovy value and convert to Python type."""
    value_str = value_str.strip()
    
    # Boolean
    if value_str == 'true':
        return True
    if value_str == 'false':
        return False
    
    # Null
    if value_str == 'null':
        return None
    
    # String (remove quotes)
    if (value_str.startswith("'") and value_str.endswith("'")) or \
       (value_str.startswith('"') and value_str.endswith('"')):
        return value_str[1:-1]
    
    # Number
    try:
        if '.' in value_str:
            return float(value_str)
        return int(value_str)
    except ValueError:
        pass
    
    # Return as string if can't parse
    return value_str

def parse_groovy_list(content: str) -> List[Any]:
    """Parse a Groovy list."""
    items = []
    # Split by comma, but respect nested structures
    depth = 0
    current = []
    
    for char in content:
        if char in '[{':
            depth += 1
        elif char in ']}':
            depth -= 1
        elif char == ',' and depth == 0:
            item = ''.join(current).strip()
            if item:
                items.append(parse_groovy_value(item))
            current = []
            continue
        current.append(char)
    
    # Don't forget the last item
    item = ''.join(current).strip()
    if item:
        items.append(parse_groovy_value(item))
    
    return items

def parse_groovy_map(content: str) -> Dict[str, Any]:
    """Parse a Groovy map recursively."""
    result = {}
    
    # Remove outer brackets if present
    content = content.strip()
    if content.startswith('[') and content.endswith(']'):
        content = content[1:-1]
    
    # Track depth for nested structures
    depth = 0
    current_key = None
    current_value = []
    in_key = True
    
    i = 0
    while i < len(content):
        char = content[i]
        
        # Track depth
        if char in '[{':
            depth += 1
            if not in_key:
                current_value.append(char)
        elif char in ']}':
            depth -= 1
            if not in_key:
                current_value.append(char)
        elif char == ':' and depth == 0 and in_key:
            # Found key-value separator
            current_key = ''.join(current_value).strip()
            # Remove quotes from key
            if (current_key.startswith("'") and current_key.endswith("'")) or \
               (current_key.startswith('"') and current_key.endswith('"')):
                current_key = current_key[1:-1]
            current_value = []
            in_key = False
        elif char == ',' and depth == 0 and not in_key:
            # End of key-value pair
            value_str = ''.join(current_value).strip()
            
            # Parse the value
            if value_str.startswith('[') and value_str.endswith(']'):
                # It's a list or map
                inner = value_str[1:-1].strip()
                if ':' in inner:
                    # It's a map
                    result[current_key] = parse_groovy_map(value_str)
                else:
                    # It's a list
                    result[current_key] = parse_groovy_list(inner)
            else:
                result[current_key] = parse_groovy_value(value_str)
            
            current_key = None
            current_value = []
            in_key = True
        else:
            current_value.append(char)
        
        i += 1
    
    # Don't forget the last pair
    if current_key is not None and current_value:
        value_str = ''.join(current_value).strip()
        
        if value_str.startswith('[') and value_str.endswith(']'):
            inner = value_str[1:-1].strip()
            if ':' in inner:
                result[current_key] = parse_groovy_map(value_str)
            else:
                result[current_key] = parse_groovy_list(inner)
        else:
            result[current_key] = parse_groovy_value(value_str)
    
    return result

def extract_build_configurations(content: str) -> Dict[str, Any]:
    """Extract buildConfigurations from Groovy file."""
    
    # Find the buildConfigurations map
    # Pattern: buildConfigurations = [...]
    pattern = r'buildConfigurations\s*=\s*\[(.*?)\n\s*\]'
    match = re.search(pattern, content, re.DOTALL)
    
    if not match:
        print("Error: Could not find buildConfigurations", file=sys.stderr)
        return {}
    
    config_content = match.group(1)
    
    # Parse each platform configuration
    platforms = {}
    
    # Find platform entries: platformName : [...]
    # We need to be careful with nested brackets
    platform_pattern = r'(\w+)\s*:\s*\['
    
    matches = list(re.finditer(platform_pattern, config_content))
    
    for idx, match in enumerate(matches):
        platform_name = match.group(1)
        start_pos = match.end() - 1  # Include the opening bracket
        
        # Find the matching closing bracket
        depth = 0
        end_pos = start_pos
        for i in range(start_pos, len(config_content)):
            if config_content[i] == '[':
                depth += 1
            elif config_content[i] == ']':
                depth -= 1
                if depth == 0:
                    end_pos = i + 1
                    break
        
        platform_content = config_content[start_pos:end_pos]
        
        # Parse the platform configuration
        try:
            platform_config = parse_groovy_map(platform_content)
            platforms[platform_name] = platform_config
        except Exception as e:
            print(f"Warning: Error parsing {platform_name}: {e}", file=sys.stderr)
            platforms[platform_name] = {}
    
    return platforms

def main():
    if len(sys.argv) < 3:
        print("Error: Missing arguments", file=sys.stderr)
        sys.exit(1)
    
    groovy_file = sys.argv[1]
    output_file = sys.argv[2]
    
    try:
        with open(groovy_file, 'r') as f:
            content = f.read()
        
        # Extract version from filename
        version_match = re.search(r'(jdk\d+u?)_pipeline_config', groovy_file)
        version = version_match.group(1) if version_match else 'unknown'
        
        # Extract build configurations
        build_configs = extract_build_configurations(content)
        
        if not build_configs:
            print("Error: No build configurations found", file=sys.stderr)
            sys.exit(1)
        
        # Create final JSON structure
        config = {
            "version": version,
            "scmReference": version,
            "buildConfigurations": build_configs,
            "targetConfigurations": list(build_configs.keys())
        }
        
        # Write to file
        with open(output_file, 'w') as f:
            json.dump(config, f, indent=2)
        
        print(f"✅ Successfully converted to {output_file}")
        print(f"   Version: {version}")
        print(f"   Found {len(build_configs)} platform configurations")
        print("")
        print("Platforms:")
        for platform in build_configs.keys():
            print(f"  - {platform}")
        print("")
        print("⚠️  Please review the output file and adjust as needed:")
        print("   - Verify nested maps and lists")
        print("   - Check variant-specific configurations")
        print("   - Validate test configurations")
        
    except Exception as e:
        print(f"Error during conversion: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
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
echo "3. Test with: python3 ../ci-adoptium-pipelines/scripts/lib/load-json-config.py --jdk-version <version> --variant temurin --target-os linux --architecture x64 --config-dir ."

# Made with Bob
