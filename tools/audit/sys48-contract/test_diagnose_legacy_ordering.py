"""Synthetic CLI regressions; these archives are not production export samples."""
from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile


TOOL = Path(__file__).with_name("diagnose_legacy_ordering.py")
PRIVATE_MARKER = "synthetic-private-value-must-not-appear"


class LegacyOrderingCliTest(unittest.TestCase):
    def run_archive(self, entries):
        with tempfile.TemporaryDirectory(prefix="mcmods-legacy-ordering-test-") as directory:
            archive = Path(directory) / "synthetic-ordering.zip"
            with zipfile.ZipFile(archive, "w") as output:
                for name, raw in entries:
                    output.writestr(name, raw)
            original = archive.read_bytes()
            result = subprocess.run(
                [sys.executable, "-B", str(TOOL), str(archive)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(original, archive.read_bytes())
            self.assertEqual("", result.stderr)
            self.assertNotIn(PRIVATE_MARKER, result.stdout)
            return result.returncode, json.loads(result.stdout)

    def test_field_presence_and_distinct_document_families(self):
        # In particular, absent, null, false, numeric zero and the literal
        # layout_kind "ABSENT" are independent inputs, never truthy defaults.
        rows = [
            {},
            {"layout_kind": "ABSENT"},
            {"layout_kind": "unknown"},
            {"layout_kind": "unknown", "ordered": None},
            {"layout_kind": "unknown", "ordered": False},
            {"layout_kind": "unknown", "ordered": True},
            {"layout_kind": "unknown", "ordered": 0},
        ]
        for row in rows:
            row["recipe_id"] = PRIVATE_MARKER
        code, report = self.run_archive([
            ("manifest.json", json.dumps({"private_note": PRIVATE_MARKER})),
            ("recipes/jei/categories.json", json.dumps({"recipes": rows})),
            ("recipes/jei/recipes/example/type.json", json.dumps({"recipes": [
                {"layout_kind": "not_applicable", "ordered": None},
                {"layout_kind": "shapeless", "ordered": False},
            ]})),
        ])
        self.assertEqual(0, code)
        self.assertEqual({
            "index_recipe_summaries": {
                "ABSENT:ABSENT": 1,
                "OTHER:ABSENT": 1,
                "unknown:ABSENT": 1,
                "unknown:FALSE": 1,
                "unknown:INVALID_TYPE": 1,
                "unknown:NULL": 1,
                "unknown:TRUE": 1,
            },
            "recipe_collection_bindings": {
                "not_applicable:NULL": 1,
                "shapeless:FALSE": 1,
            },
        }, report["countsByDistinctDocumentFamilyDoNotSumAsUniqueRecipes"])
        self.assertEqual({
            "index_recipe_summaries": 1,
            "recipe_collection_bindings": 1,
        }, report["documents"])
        self.assertIs(False, report["zipModified"])
        self.assertIs(False, report["extracted"])
        self.assertIs(False, report["productionInventoryOrExecutionClaim"])

    def test_unsafe_member_is_precisely_rejected_without_leaking_its_name(self):
        code, report = self.run_archive([
            ("../" + PRIVATE_MARKER + ".json", "{}"),
            ("recipes/jei/categories.json", '{"recipes":[]}'),
        ])
        self.assertEqual(1, code)
        self.assertEqual({
            "state": "HOLD_NO_DIAGNOSTIC_CLAIM",
            "errorType": "RuntimeError",
            "reasonCode": "UNSAFE_ZIP_PATH",
        }, report)


if __name__ == "__main__":
    unittest.main()
