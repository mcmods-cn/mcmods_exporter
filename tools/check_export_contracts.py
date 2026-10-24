#!/usr/bin/env python3
"""Validate complete synthetic contract samples, or an unmodified export directory.

The bundled samples exercise the declared wire contract; they are NOT evidence of
Minecraft/JEI execution. --export-root validates actual unpacked game output.
"""
import argparse
import copy
import json
from pathlib import Path
import re
import sys

from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = {
    "jei-categories-v7": ("jei-categories.schema.json", "JeiRecipeGuiStage.java", "recipes/jei/categories.json", None),
    "jei-categories-v7-templates-only": ("jei-categories.schema.json", "JeiRecipeGuiStage.java", None, None),
    "advancements-v2": ("advancements.schema.json", "AdvancementStage.java", "advancements/advancements.json", None),
    "natural-generation-v2": ("worldgen.schema.json", "WorldgenDataStage.java", "worldgen/natural_generation.json", "naturalGeneration"),
    "loot-tables-v2": ("worldgen.schema.json", "WorldgenDataStage.java", "worldgen/loot_tables.json", "lootTables"),
}


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export-root", type=Path, help="Unmodified, unpacked all-profile game export (no synthetic fallback)")
    args = parser.parse_args()
    observations = []

    def record(name, operation):
        try:
            operation()
            observations.append({"test": name, "status": "PASS"})
        except ValidationError as error:
            # ValidationError.__str__ includes the entire failed instance. Never
            # print imported documents: diagnostics need only path and rule.
            observations.append({"test": name, "status": "FAIL", "reason": "schema violation", "instance_path": list(error.absolute_path), "schema_path": list(error.absolute_schema_path), "validator": error.validator})
        except Exception as error:
            observations.append({"test": name, "status": "FAIL", "reason": str(error)})

    for name, (schema_file, source_file, export_file, definition) in CONTRACTS.items():
        schema = load(ROOT / "schema" / schema_file)
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema)
        sample = load(ROOT / "schema" / "fixtures" / (name + ".json"))
        expected = (schema["$defs"][definition] if definition else schema)["properties"]["schema_version"]["const"]

        def version_matches_source():
            source = (ROOT / "common/src/main/java/cn/mcmod/wiki/exporter/stage" / source_file).read_text()
            versions = re.findall(r'addProperty\("schema_version",\s*"([^"]+)"\)', source)
            assert expected in versions, f"Schema {expected} has no matching runtime writer; found {versions}"

        record(name + ":runtime-version", version_matches_source)
        record(name + ":complete-synthetic-document", lambda: validator.validate(sample))

        def rejects_old_version():
            invalid = copy.deepcopy(sample)
            invalid["schema_version"] = re.sub(r"/v\d+$", "/v0", invalid["schema_version"])
            assert not validator.is_valid(invalid), "Unrecognized version was accepted"

        record(name + ":reject-unknown-version", rejects_old_version)

        def rejects_previous_version():
            invalid = copy.deepcopy(sample)
            match = re.search(r"/v(\d+)$", invalid["schema_version"])
            invalid["schema_version"] = invalid["schema_version"][:match.start()] + "/v" + str(int(match.group(1)) - 1)
            assert not validator.is_valid(invalid), "Prior major version was silently accepted by current schema"

        record(name + ":reject-prior-major-version", rejects_previous_version)

        def rejects_missing_required():
            invalid = copy.deepcopy(sample)
            del invalid["schema_version"]
            assert not validator.is_valid(invalid), "Missing required version was accepted"

        record(name + ":reject-missing-required", rejects_missing_required)
        if args.export_root and export_file:
            record(name + ":actual-game-document", lambda: validator.validate(load(args.export_root / export_file)))

    # v7 introduces omission semantics. A templates-only category must never
    # pretend its samples are exported recipes; full mode retains recipes.
    schema = load(ROOT / "schema/jei-categories.schema.json")
    validator = Draft202012Validator(schema)
    sample = load(ROOT / "schema/fixtures/jei-categories-v7-templates-only.json")

    def rejects_inconsistent_omission():
        invalid = copy.deepcopy(sample)
        invalid["categories"][0]["recipes_omitted"] = False
        assert not validator.is_valid(invalid), "templates_only without recipes_omitted was accepted"

    record("jei-v7:reject-inconsistent-omission", rejects_inconsistent_omission)

    def rejects_wrong_nested_type():
        invalid = copy.deepcopy(load(ROOT / "schema/fixtures/natural-generation-v2.json"))
        invalid["entries"][0]["outputs"] = "not-an-array"
        assert not Draft202012Validator(load(ROOT / "schema/worldgen.schema.json")).is_valid(invalid), "Invalid nested outputs accepted"

    record("natural-generation-v2:reject-wrong-nested-type", rejects_wrong_nested_type)

    record("jei-v6:explicit-legacy-contract", lambda: Draft202012Validator(
        load(ROOT / "schema/legacy/jei-categories-v6.schema.json")
    ).validate(load(ROOT / "schema/fixtures/jei-categories-v6.json")))
    template_schema = Draft202012Validator(load(ROOT / "schema/jei-templates.schema.json"))
    recipe_schema = Draft202012Validator(load(ROOT / "schema/jei-recipes.schema.json"))
    template = load(ROOT / "schema/fixtures/jei-templates-v2.json")
    recipes = load(ROOT / "schema/fixtures/jei-recipes-v2.json")
    record("jei-v2:complete-template-distinct-hit-and-visual-rect", lambda: template_schema.validate(template))
    record("jei-v2:complete-bindings-empty-slot-and-unknown-chance", lambda: recipe_schema.validate(recipes))

    def rejects_bad_rect():
        invalid = copy.deepcopy(template)
        invalid["templates"][0]["slots"][0]["rect"]["width"] = -1
        assert not template_schema.is_valid(invalid), "Negative hit rectangle width was accepted"

    record("jei-v2:reject-bad-template-rect", rejects_bad_rect)

    def rejects_bad_probability():
        invalid = copy.deepcopy(recipes)
        invalid["recipes"][0]["bindings"][1].update(chance_available=True, chance=1.5)
        assert not recipe_schema.is_valid(invalid), "Out-of-range probability was accepted"

    record("jei-v2:reject-bad-probability", rejects_bad_probability)

    def rejects_fractional_count():
        invalid = copy.deepcopy(recipes)
        invalid["recipes"][0]["bindings"][1]["alternatives"][0]["count"] = 0.5
        assert not recipe_schema.is_valid(invalid), "Fractional item count was accepted"

    record("jei-v2:reject-fractional-item-count", rejects_fractional_count)

    if args.export_root:
        export_root = args.export_root.resolve()

        def validate_actual_collections():
            categories = load(export_root / "recipes/jei/categories.json")
            for category in categories["categories"]:
                for key, validator in [("template_collection", template_schema), ("recipe_collection", recipe_schema)]:
                    relative = Path(category[key])
                    document = (export_root / relative).resolve()
                    assert not relative.is_absolute() and document.is_relative_to(export_root), "Collection escapes export root"
                    validator.validate(load(document))

        record("jei:actual-game-referenced-template-and-binding-collections", validate_actual_collections)
        record("manifest:actual-game-schema", lambda: Draft202012Validator(
            load(ROOT / "schema/manifest.schema.json")
        ).validate(load(export_root / "manifest.json")))

        def actual_complete_supported_export():
            manifest = load(export_root / "manifest.json")
            assert manifest["minecraft_version"] == "1.20.1", "Sample requires this repository's supported Minecraft version"
            assert manifest["loader"] in ["fabric", "forge"], "Sample requires a currently supported loader"
            assert manifest["status"] == "complete" and manifest["error_count"] == 0, "Game export is incomplete or contains exporter errors; inspect original game evidence"
            assert manifest["configuration"]["profile"] == "all", "Full contract validation requires the all profile"

        record("manifest:actual-game-complete-supported-all-profile", actual_complete_supported_export)
    print(json.dumps({"sample_kind": "actual-game-and-synthetic" if args.export_root else "synthetic-contract-only", "results": observations}, ensure_ascii=False, indent=2))
    return int(any(row["status"] != "PASS" for row in observations))


if __name__ == "__main__":
    sys.exit(main())
