#!/usr/bin/env python3
"""
collect-stage-params.py — CI-agnostic stage parameter collation helper.

Walks all *.params.json sidecar files from the default stage scripts directory
and optionally merges vendor overrides from a config-repo vendor-scripts
directory (fetched via raw GitHub URL or read from a local path).

Merge strategy per stage stem:
  1. Load scripts/stages/<stem>.params.json  → default parameter groups
  2. If a vendor file exists for the same stem:
       a. Remove any param named in ignoreDefaultParams from the defaults
       b. For each vendor param: replace default with same name, or add if new
       c. Merge vendor parameterGroups: groups with the same name are merged;
          new vendor groups are appended
  3. Also load optional vendor_stage_params.json (cross-stage extras,
     for params not tied to a specific script override)
  4. Deduplicate by param name across all stages — last definition wins (warned)

Output JSON (written to --output):
  {
    "groups": [
      {
        "name":        "Source Control",
        "description": "...",
        "stageId":     "02-build",
        "parameters": [
          { "name": "SCM_REF", "type": "string", "default": "", "description": "..." },
          ...
        ]
      },
      ...
    ],
    "paramNames": ["SCM_REF", "VARIANT", ...]
  }

The output is consumed by CI-specific tooling (Jenkins Job DSL, local runner,
etc.) to construct job/pipeline parameters appropriate for that CI system.

Usage:
    # Local paths — used by the local CI runner and tests:
    python3 scripts/lib/collect-stage-params.py \\
        --default-stages-dir  scripts/stages \\
        --vendor-scripts-dir  config-repo/vendor-scripts \\
        --output              /tmp/collated-stage-params.json

    # Remote vendor files — used by Jenkins Job DSL at job-generation time:
    python3 scripts/lib/collect-stage-params.py \\
        --default-stages-dir  scripts/stages \\
        --vendor-raw-base-url https://raw.githubusercontent.com/myorg/myrepo/main \\
        --output              /tmp/collated-stage-params.json
"""

import argparse
import json
import sys
import urllib.request
import urllib.error
from pathlib import Path


# ---------------------------------------------------------------------------
# Validation helpers
# ---------------------------------------------------------------------------

VALID_TYPES = {'string', 'boolean'}


def _validate_param(param: dict, source: str) -> None:
    """Raise ValueError if a parameter entry is malformed."""
    name = param.get('name', '')
    if not name:
        raise ValueError(f"[{source}] Parameter entry missing 'name' field: {param}")
    if not name.isupper() or not all(c.isalnum() or c == '_' for c in name):
        raise ValueError(
            f"[{source}] Parameter name '{name}' must be UPPER_SNAKE_CASE "
            f"(uppercase letters, digits, and underscores only)"
        )
    ptype = param.get('type')
    if ptype not in VALID_TYPES:
        raise ValueError(
            f"[{source}] Parameter '{name}' has invalid type '{ptype}'. "
            f"Must be one of: {sorted(VALID_TYPES)}"
        )
    default = param.get('default')
    if ptype == 'boolean' and not isinstance(default, bool):
        raise ValueError(
            f"[{source}] Parameter '{name}' is type 'boolean' but default "
            f"value {default!r} is not a JSON boolean (true/false)"
        )
    if ptype == 'string' and not isinstance(default, str):
        raise ValueError(
            f"[{source}] Parameter '{name}' is type 'string' but default "
            f"value {default!r} is not a JSON string"
        )


def _validate_params_file(data: dict, source: str) -> None:
    """Validate a full .params.json document."""
    if 'parameterGroups' not in data:
        raise ValueError(f"[{source}] Missing required 'parameterGroups' key")
    for group in data['parameterGroups']:
        if 'name' not in group:
            raise ValueError(f"[{source}] A parameterGroup entry is missing 'name'")
        for param in group.get('parameters', []):
            _validate_param(param, source)


# ---------------------------------------------------------------------------
# Loading helpers
# ---------------------------------------------------------------------------

def _load_json_local(path: Path) -> dict | None:
    """Load a JSON file from a local path. Returns None if the file does not exist."""
    if not path.exists():
        return None
    with open(path, 'r') as f:
        return json.load(f)


def _load_json_url(url: str) -> dict | None:
    """Fetch and parse a JSON file from a URL. Returns None on 404, raises on other errors."""
    try:
        with urllib.request.urlopen(url, timeout=15) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise RuntimeError(f"HTTP {e.code} fetching {url}: {e.reason}") from e
    except Exception as e:
        raise RuntimeError(f"Failed to fetch {url}: {e}") from e


# ---------------------------------------------------------------------------
# Per-stage merge logic
# ---------------------------------------------------------------------------

def _params_list_to_map(params: list) -> dict:
    """Convert a list of param dicts to a dict keyed by name, preserving order."""
    return {p['name']: p for p in params}


def _merge_stage(default_data: dict | None, vendor_data: dict | None,
                 stage_stem: str) -> list:
    """
    Merge default and vendor parameterGroups for one stage stem.

    Returns a list of group dicts, each containing:
      name, description, stageId, parameters
    """
    source_default = f"{stage_stem}.params.json (default)"
    source_vendor  = f"{stage_stem}.params.json (vendor)"

    if default_data:
        _validate_params_file(default_data, source_default)
    if vendor_data:
        _validate_params_file(vendor_data, source_vendor)

    # Build the default group map: group_name → group dict
    # and a reverse index: param_name → group_name
    default_groups: dict[str, dict] = {}
    default_param_to_group: dict[str, str] = {}

    if default_data:
        for grp in default_data.get('parameterGroups', []):
            gname = grp['name']
            default_groups[gname] = {
                'name':        gname,
                'description': grp.get('description', ''),
                'stageId':     stage_stem,
                'parameters':  list(grp.get('parameters', [])),
            }
            for p in grp.get('parameters', []):
                default_param_to_group[p['name']] = gname

    if not vendor_data:
        return list(default_groups.values())

    # --- Apply ignoreDefaultParams ---
    ignore_list = vendor_data.get('ignoreDefaultParams', [])

    # A name in both ignoreDefaultParams and vendor parameters is contradictory — hard error
    vendor_param_names = {
        p['name']
        for grp in vendor_data.get('parameterGroups', [])
        for p in grp.get('parameters', [])
    }
    contradictions = [n for n in ignore_list if n in vendor_param_names]
    if contradictions:
        raise ValueError(
            f"[{source_vendor}] These names appear in both 'ignoreDefaultParams' "
            f"and 'parameters' — contradictory intent: {contradictions}"
        )

    # A name in ignoreDefaultParams that doesn't exist in defaults is a warning, not an error
    # (the default may have been removed since the vendor file was authored)
    for name in ignore_list:
        if name not in default_param_to_group:
            print(
                f"WARNING [{source_vendor}] 'ignoreDefaultParams' entry '{name}' "
                f"does not exist in the default params file — ignoring.",
                file=sys.stderr
            )

    # Remove ignored params from their default groups; drop the group if now empty
    for name in ignore_list:
        gname = default_param_to_group.get(name)
        if gname and gname in default_groups:
            default_groups[gname]['parameters'] = [
                p for p in default_groups[gname]['parameters'] if p['name'] != name
            ]
            if not default_groups[gname]['parameters']:
                del default_groups[gname]

    # --- Merge vendor parameterGroups ---
    for vgrp in vendor_data.get('parameterGroups', []):
        vgname  = vgrp['name']
        vparams = vgrp.get('parameters', [])

        if vgname in default_groups:
            # Vendor params replace defaults with the same name; new names are appended
            existing_map   = _params_list_to_map(default_groups[vgname]['parameters'])
            existing_names = set(existing_map)
            for vp in vparams:
                existing_map[vp['name']] = vp
            new_additions = [vp for vp in vparams if vp['name'] not in existing_names]
            default_groups[vgname]['parameters'] = (
                [existing_map[p['name']] for p in default_groups[vgname]['parameters']]
                + new_additions
            )
            # Vendor may also update the group description
            if vgrp.get('description'):
                default_groups[vgname]['description'] = vgrp['description']
        else:
            # Brand-new vendor group — append it
            default_groups[vgname] = {
                'name':        vgname,
                'description': vgrp.get('description', ''),
                'stageId':     stage_stem,
                'parameters':  list(vparams),
            }

    return list(default_groups.values())


# ---------------------------------------------------------------------------
# Main collation
# ---------------------------------------------------------------------------

def collect(default_stages_dir: Path,
            vendor_scripts_dir: Path | None,
            vendor_raw_base_url: str | None) -> dict:
    """
    Collate all stage *.params.json files into a single structured output dict.

    Returns:
        {
          "groups":     [ { name, description, stageId, parameters: [...] }, ... ],
          "paramNames": [ "PARAM_A", "PARAM_B", ... ]
        }
    """

    def load_vendor_stem(stem: str) -> dict | None:
        filename = f"{stem}.params.json"
        if vendor_raw_base_url:
            url = f"{vendor_raw_base_url.rstrip('/')}/vendor-scripts/{filename}"
            return _load_json_url(url)
        if vendor_scripts_dir:
            return _load_json_local(vendor_scripts_dir / filename)
        return None

    def load_vendor_cross_stage() -> dict | None:
        """Load optional vendor_stage_params.json from the config repo root."""
        filename = "vendor_stage_params.json"
        if vendor_raw_base_url:
            # vendor_stage_params.json lives at the config repo root.
            # Strip trailing /vendor-scripts path component if present.
            base = vendor_raw_base_url.rstrip('/')
            if base.endswith('/vendor-scripts'):
                base = base[: -len('/vendor-scripts')]
            return _load_json_url(f"{base}/{filename}")
        if vendor_scripts_dir:
            return _load_json_local(vendor_scripts_dir.parent / filename)
        return None

    # Collect stage stems from default params files, preserving sort order
    stems_seen: list[str] = []
    stems_set:  set[str]  = set()

    for path in sorted(default_stages_dir.glob('*.params.json')):
        stem = path.name.replace('.params.json', '')
        if stem not in stems_set:
            stems_seen.append(stem)
            stems_set.add(stem)

    # Also pick up vendor-only stems (vendor script stages with no default params file)
    # Only possible when using a local directory; remote enumeration is not supported
    # — vendor-only remote stems must use vendor_stage_params.json instead.
    if vendor_scripts_dir:
        for path in sorted(vendor_scripts_dir.glob('*.params.json')):
            stem = path.name.replace('.params.json', '')
            if stem not in stems_set:
                stems_seen.append(stem)
                stems_set.add(stem)

    # Track all param names for cross-stage deduplication warnings
    all_param_names: dict[str, str] = {}   # name → "stageId/groupName/paramName" source label
    output_groups:   list[dict]     = []

    for stem in stems_seen:
        default_data = _load_json_local(default_stages_dir / f"{stem}.params.json")
        vendor_data  = load_vendor_stem(stem)

        if default_data is None and vendor_data is None:
            continue

        merged_groups = _merge_stage(default_data, vendor_data, stem)

        for grp in merged_groups:
            clean_params = []
            for p in grp['parameters']:
                source_label = f"{stem}/{grp['name']}/{p['name']}"
                if p['name'] in all_param_names:
                    print(
                        f"WARNING: Parameter '{p['name']}' defined in both "
                        f"'{all_param_names[p['name']]}' and '{source_label}' — "
                        f"latter definition wins.",
                        file=sys.stderr
                    )
                all_param_names[p['name']] = source_label
                clean_params.append(p)
            if clean_params:
                output_groups.append({
                    'name':        grp['name'],
                    'description': grp['description'],
                    'stageId':     grp['stageId'],
                    'parameters':  clean_params,
                })

    # --- Merge vendor_stage_params.json (cross-stage extras) ---
    cross_stage = load_vendor_cross_stage()
    if cross_stage:
        for stage_id, stage_entry in cross_stage.get('vendorStageParams', {}).items():
            ignore      = stage_entry.get('ignoreDefaultParams', [])
            extra_params = stage_entry.get('parameters', [])
            source_label = f"vendor_stage_params.json/{stage_id}"

            # Remove ignored params from already-collated groups for this stage
            for name in ignore:
                found = False
                for grp in output_groups:
                    if grp['stageId'] == stage_id:
                        before = len(grp['parameters'])
                        grp['parameters'] = [
                            p for p in grp['parameters'] if p['name'] != name
                        ]
                        if len(grp['parameters']) < before:
                            found = True
                if not found:
                    print(
                        f"WARNING [{source_label}] 'ignoreDefaultParams' entry '{name}' "
                        f"not found in collated params for stage '{stage_id}' — ignoring.",
                        file=sys.stderr
                    )

            if not extra_params:
                continue

            # Guard: name in both ignore and parameters is contradictory
            contradictions = [p['name'] for p in extra_params if p['name'] in ignore]
            if contradictions:
                raise ValueError(
                    f"[{source_label}] Names in both 'ignoreDefaultParams' and "
                    f"'parameters': {contradictions}"
                )

            for p in extra_params:
                _validate_param(p, source_label)

            # Fold into an existing 'Vendor Options' group for this stage, or create one
            target_group = next(
                (g for g in output_groups
                 if g['stageId'] == stage_id and g['name'] == 'Vendor Options'),
                None
            )
            if target_group is None:
                target_group = {
                    'name':        'Vendor Options',
                    'description': (
                        f"Additional parameters supplied via vendor_stage_params.json "
                        f"for stage {stage_id}."
                    ),
                    'stageId':     stage_id,
                    'parameters':  [],
                }
                output_groups.append(target_group)

            existing_map = _params_list_to_map(target_group['parameters'])
            for p in extra_params:
                if p['name'] in all_param_names:
                    print(
                        f"WARNING: Parameter '{p['name']}' from vendor_stage_params.json "
                        f"already defined at '{all_param_names[p['name']]}' — "
                        f"vendor_stage_params.json definition wins.",
                        file=sys.stderr
                    )
                all_param_names[p['name']] = f"{source_label}/{p['name']}"
                existing_map[p['name']] = p
            target_group['parameters'] = list(existing_map.values())

    # Build flat ordered param name list for STAGE_PARAM_NAMES
    param_names_ordered: list[str] = []
    seen_names:          set[str]  = set()
    for grp in output_groups:
        for p in grp['parameters']:
            if p['name'] not in seen_names:
                param_names_ordered.append(p['name'])
                seen_names.add(p['name'])

    return {
        'groups':     output_groups,
        'paramNames': param_names_ordered,
    }


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(
        description='CI-agnostic collation of stage *.params.json files.',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Local paths (local CI runner, tests):
  python3 scripts/lib/collect-stage-params.py \\
      --default-stages-dir scripts/stages \\
      --vendor-scripts-dir config-repo/vendor-scripts \\
      --output /tmp/collated-stage-params.json

  # Remote vendor files (Jenkins Job DSL at job-generation time):
  python3 scripts/lib/collect-stage-params.py \\
      --default-stages-dir scripts/stages \\
      --vendor-raw-base-url https://raw.githubusercontent.com/myorg/myrepo/main \\
      --output /tmp/collated-stage-params.json
        """
    )
    parser.add_argument(
        '--default-stages-dir', required=True,
        help='Path to the directory containing default *.params.json files (scripts/stages)'
    )
    parser.add_argument(
        '--vendor-scripts-dir', default=None,
        help='Local path to vendor-scripts directory inside a checked-out config repo'
    )
    parser.add_argument(
        '--vendor-raw-base-url', default=None,
        help=(
            'Base raw URL of the config repo '
            '(e.g. https://raw.githubusercontent.com/org/repo/branch). '
            'Used to fetch vendor-scripts/*.params.json and vendor_stage_params.json remotely.'
        )
    )
    parser.add_argument(
        '--output', required=True,
        help='Path to write the collated output JSON'
    )
    args = parser.parse_args()

    if args.vendor_scripts_dir and args.vendor_raw_base_url:
        print(
            "ERROR: --vendor-scripts-dir and --vendor-raw-base-url are mutually exclusive.",
            file=sys.stderr
        )
        return 1

    default_dir = Path(args.default_stages_dir)
    if not default_dir.is_dir():
        print(
            f"ERROR: --default-stages-dir '{default_dir}' is not a directory.",
            file=sys.stderr
        )
        return 1

    vendor_dir = Path(args.vendor_scripts_dir) if args.vendor_scripts_dir else None

    try:
        result = collect(
            default_stages_dir=default_dir,
            vendor_scripts_dir=vendor_dir,
            vendor_raw_base_url=args.vendor_raw_base_url,
        )
    except (ValueError, RuntimeError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, 'w') as f:
        json.dump(result, f, indent=2)

    total_params = len(result['paramNames'])
    total_groups = len(result['groups'])
    print(
        f"✓ Collated {total_params} parameter(s) across "
        f"{total_groups} group(s) → {output_path}"
    )
    return 0


if __name__ == '__main__':
    sys.exit(main())
