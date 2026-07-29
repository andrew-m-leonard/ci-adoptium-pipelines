#!/usr/bin/env python3
"""
Tests for collect-stage-params.py — cross-stage duplicate parameter handling.

Covers:
  - Same param name, same group → emitted once with description merged
  - Same param name, same group, identical descriptions → single description kept
  - Same param name, same group, different descriptions → descriptions concatenated
  - Same param name, different groups → ValueError raised
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

# Allow importing the module from scripts/lib/
sys.path.insert(0, str(Path(__file__).parent.parent / 'scripts' / 'lib'))
import importlib
collect_mod = importlib.import_module('collect-stage-params')
collect = collect_mod.collect


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _write_params_json(directory: Path, stem: str, groups: list) -> None:
    """Write a minimal *.params.json file for a given stage stem."""
    data = {'parameterGroups': groups}
    (directory / f'{stem}.params.json').write_text(json.dumps(data))


def _make_param(name: str, description: str = '') -> dict:
    return {'name': name, 'type': 'string', 'default': '', 'description': description}


def _make_group(name: str, params: list) -> dict:
    return {'name': name, 'description': '', 'parameters': params}


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestCrossStageDuplicateParams(unittest.TestCase):

    def _collect(self, stage_dir: Path) -> dict:
        return collect(
            default_stages_dir=stage_dir,
            vendor_scripts_dir=None,
            vendor_raw_base_url=None,
        )

    # ------------------------------------------------------------------
    # Identical descriptions → keep single description
    # ------------------------------------------------------------------
    def test_duplicate_same_group_identical_description(self):
        """
        TEMURIN_BUILD_REPO appears in two stages under the same group name
        with the same description text. The collated output must contain it
        exactly once with the original description unchanged.
        """
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            shared_desc = 'Git repo for the Temurin build scripts.'
            _write_params_json(d, '01-init', [
                _make_group('Source Control', [_make_param('TEMURIN_BUILD_REPO', shared_desc)])
            ])
            _write_params_json(d, '02-build', [
                _make_group('Source Control', [_make_param('TEMURIN_BUILD_REPO', shared_desc)])
            ])

            result = self._collect(d)

        # Exactly one parameter named TEMURIN_BUILD_REPO in the output
        all_params = [p for g in result['groups'] for p in g['parameters']]
        matching = [p for p in all_params if p['name'] == 'TEMURIN_BUILD_REPO']
        self.assertEqual(len(matching), 1, 'Expected exactly one TEMURIN_BUILD_REPO param')

        # Description must be unchanged (not doubled)
        self.assertEqual(matching[0]['description'], shared_desc)

        # paramNames must list it once
        self.assertEqual(result['paramNames'].count('TEMURIN_BUILD_REPO'), 1)

    # ------------------------------------------------------------------
    # Different descriptions → concatenate with " / "
    # ------------------------------------------------------------------
    def test_duplicate_same_group_different_descriptions(self):
        """
        TEMURIN_BUILD_REPO appears in two stages under the same group name
        but with different description texts. The collated output must contain
        it once and the description must be 'desc_a / desc_b'.
        """
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            _write_params_json(d, '01-init', [
                _make_group('Source Control', [_make_param('TEMURIN_BUILD_REPO', 'Used during init.')])
            ])
            _write_params_json(d, '02-build', [
                _make_group('Source Control', [_make_param('TEMURIN_BUILD_REPO', 'Used during build.')])
            ])

            result = self._collect(d)

        all_params = [p for g in result['groups'] for p in g['parameters']]
        matching = [p for p in all_params if p['name'] == 'TEMURIN_BUILD_REPO']
        self.assertEqual(len(matching), 1, 'Expected exactly one TEMURIN_BUILD_REPO param')
        self.assertEqual(matching[0]['description'], 'Used during init. / Used during build.')

    # ------------------------------------------------------------------
    # Different groups → hard error
    # ------------------------------------------------------------------
    def test_duplicate_different_groups_raises(self):
        """
        TEMURIN_BUILD_REPO appears in two stages under different group names.
        collect() must raise a ValueError explaining the conflict.
        """
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            _write_params_json(d, '01-init', [
                _make_group('Source Control', [_make_param('TEMURIN_BUILD_REPO', 'desc')])
            ])
            _write_params_json(d, '02-build', [
                _make_group('Build Options', [_make_param('TEMURIN_BUILD_REPO', 'desc')])
            ])

            with self.assertRaises(ValueError) as ctx:
                self._collect(d)

        self.assertIn('TEMURIN_BUILD_REPO', str(ctx.exception))
        self.assertIn('Source Control', str(ctx.exception))
        self.assertIn('Build Options', str(ctx.exception))

    # ------------------------------------------------------------------
    # Duplicate with one empty description → use the non-empty one
    # ------------------------------------------------------------------
    def test_duplicate_one_empty_description(self):
        """
        When one definition has an empty description and the other has text,
        the merged description should be the non-empty text (no stray ' / ').
        """
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            _write_params_json(d, '01-init', [
                _make_group('Source Control', [_make_param('TEMURIN_BUILD_REPO', '')])
            ])
            _write_params_json(d, '02-build', [
                _make_group('Source Control', [_make_param('TEMURIN_BUILD_REPO', 'Non-empty description.')])
            ])

            result = self._collect(d)

        all_params = [p for g in result['groups'] for p in g['parameters']]
        matching = [p for p in all_params if p['name'] == 'TEMURIN_BUILD_REPO']
        self.assertEqual(len(matching), 1)
        self.assertEqual(matching[0]['description'], 'Non-empty description.')

    # ------------------------------------------------------------------
    # No duplicate → normal path unaffected
    # ------------------------------------------------------------------
    def test_no_duplicate_unaffected(self):
        """
        Two stages with entirely distinct parameter names are not affected
        by the duplicate-handling logic.
        """
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            _write_params_json(d, '01-init', [
                _make_group('Source Control', [_make_param('SCM_REF', 'Source ref.')])
            ])
            _write_params_json(d, '02-build', [
                _make_group('Build Options', [_make_param('EXTRA_MAKE_OPTIONS', 'Extra make flags.')])
            ])

            result = self._collect(d)

        names = result['paramNames']
        self.assertIn('SCM_REF', names)
        self.assertIn('EXTRA_MAKE_OPTIONS', names)
        self.assertEqual(len(names), 2)

    # ------------------------------------------------------------------
    # Duplicate across more than two stages → all descriptions merged
    # ------------------------------------------------------------------
    def test_duplicate_three_stages_different_descriptions(self):
        """
        Same param in three stages under the same group; descriptions
        should accumulate: 'A / B / C'.
        """
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            _write_params_json(d, '01-init', [
                _make_group('Source Control', [_make_param('TEMURIN_BUILD_REPO', 'A')])
            ])
            _write_params_json(d, '02-build', [
                _make_group('Source Control', [_make_param('TEMURIN_BUILD_REPO', 'B')])
            ])
            _write_params_json(d, '03-test', [
                _make_group('Source Control', [_make_param('TEMURIN_BUILD_REPO', 'C')])
            ])

            result = self._collect(d)

        all_params = [p for g in result['groups'] for p in g['parameters']]
        matching = [p for p in all_params if p['name'] == 'TEMURIN_BUILD_REPO']
        self.assertEqual(len(matching), 1)
        self.assertEqual(matching[0]['description'], 'A / B / C')


if __name__ == '__main__':
    unittest.main()
