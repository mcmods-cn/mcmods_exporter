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
    "worldgen-data-files-v1": ("worldgen.schema.json", "WorldgenDataStage.java", "worldgen/data_files.json", "files"),
    "natural-generation-v2": ("worldgen.schema.json", "WorldgenDataStage.java", "worldgen/natural_generation.json", "naturalGeneration"),
    "loot-tables-v2": ("worldgen.schema.json", "WorldgenDataStage.java", "worldgen/loot_tables.json", "lootTables"),
}


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


class ContractError(ValueError):
    """A contract mismatch with a diagnostic safe to include in the report."""


def require(condition, message):
    if not condition:
        raise ContractError(message)


def export_document(root, relative):
    relative = Path(relative)
    path = (root / relative).resolve()
    require(not relative.is_absolute() and path.is_relative_to(root), "Document escapes export root")
    return load(path)


def validate_jei_collections(category, templates, recipes):
    """Join the category's two v2 documents using producer IDs, never array positions."""
    for collection in (templates, recipes):
        require(collection["recipe_type_id"] == category["recipe_type_id"], "Collection recipe type disagrees with category")
        require(collection.get("export_mode") == category["export_mode"] and
                collection.get("recipes_omitted") == category["recipes_omitted"], "Collection omission policy disagrees with v7 index")
    require(recipes["template_collection"] == category["template_collection"], "Bindings reference a different template collection")
    require(category["template_count"] == templates["template_count"] == len(templates["templates"]), "Template count mismatch")
    require(category["exported_recipe_count"] == recipes["count"] == len(recipes["recipes"]), "Exported recipe count mismatch")
    require(not category["recipes_omitted"] or not recipes["recipes"], "Template-only category contains recipe bindings")

    slots_by_template = {}
    for template in templates["templates"]:
        template_id = template["template_id"]
        slots = {slot["slot_id"] for slot in template["slots"]}
        require(template_id not in slots_by_template, "Duplicate template ID within category")
        require(template["slot_count"] == len(template["slots"]) == len(slots), "Template slots have inconsistent count or duplicate IDs")
        slots_by_template[template_id] = slots
    for recipe in recipes["recipes"]:
        require(recipe["recipe_type_id"] == category["recipe_type_id"], "Recipe type disagrees with collection")
        slots = slots_by_template.get(recipe["template_id"])
        require(slots is not None, "Recipe references a missing template in its category")
        bindings = {binding["slot_id"] for binding in recipe["bindings"]}
        require(recipe["binding_count"] == len(recipe["bindings"]) == len(bindings), "Bindings have inconsistent count or duplicate IDs")
        require(bindings <= slots, "Recipe refers to an undeclared template slot")


def validate_capabilities(manifest, capabilities):
    for field in ("minecraft_version", "loader"):
        require(manifest[field] == capabilities[field], "Capability environment disagrees with manifest")
    require(capabilities["format_contract"] == manifest["schema_version"], "Capability format disagrees with manifest")
    require(capabilities["profile"] == manifest["configuration"]["profile"], "Capability profile disagrees with manifest")
    ids = [row["id"] for row in capabilities["capabilities"]]
    require(len(ids) == len(set(ids)), "Duplicate capability ID")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export-root", type=Path, help="Unmodified, unpacked all-profile game export (no synthetic fallback)")
    parser.add_argument("--expected-exporter-version", help="Producer version from the archive's provenance (defaults to the current build)")
    args = parser.parse_args()
    export_root = args.export_root.resolve() if args.export_root else None
    observations = []

    def record(name, operation):
        try:
            operation()
            observations.append({"test": name, "status": "PASS"})
        except ValidationError as error:
            # ValidationError.__str__ includes the entire failed instance. Never
            # print imported documents: diagnostics need only path and rule.
            observations.append({"test": name, "status": "FAIL", "reason": "schema violation", "instance_path": list(error.absolute_path), "schema_path": list(error.absolute_schema_path), "validator": error.validator})
        except ContractError as error:
            observations.append({"test": name, "status": "FAIL", "reason": str(error)})
        except Exception as error:
            observations.append({"test": name, "status": "FAIL", "reason": type(error).__name__})

    def fabric_jei_entrypoint_registered():
        metadata = load(ROOT / "fabric/src/main/resources/fabric.mod.json")
        plugins = metadata["entrypoints"].get("jei_mod_plugin", [])
        require(plugins.count("cn.mcmod.wiki.exporter.compat.jei.McModsJeiPlugin") == 1, "Fabric must register exactly one exporter JEI runtime plugin")

    record("fabric:exporter-jei-runtime-entrypoint", fabric_jei_entrypoint_registered)

    for name, (schema_file, source_file, export_file, definition) in CONTRACTS.items():
        schema = load(ROOT / "schema" / schema_file)
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema)
        sample = load(ROOT / "schema" / "fixtures" / (name + ".json"))
        expected = (schema["$defs"][definition] if definition else schema)["properties"]["schema_version"]["const"]

        def version_matches_source():
            source = (ROOT / "common/src/main/java/cn/mcmod/wiki/exporter/stage" / source_file).read_text()
            versions = re.findall(r'addProperty\("schema_version",\s*"([^"]+)"\)', source)
            require(expected in versions, f"Schema {expected} has no matching runtime writer; found {versions}")

        record(name + ":runtime-version", version_matches_source)
        record(name + ":complete-synthetic-document", lambda: validator.validate(sample))

        def rejects_old_version():
            invalid = copy.deepcopy(sample)
            invalid["schema_version"] = re.sub(r"/v\d+$", "/v0", invalid["schema_version"])
            require(not validator.is_valid(invalid), "Unrecognized version was accepted")

        record(name + ":reject-unknown-version", rejects_old_version)

        def rejects_previous_version():
            invalid = copy.deepcopy(sample)
            match = re.search(r"/v(\d+)$", invalid["schema_version"])
            invalid["schema_version"] = invalid["schema_version"][:match.start()] + "/v" + str(int(match.group(1)) - 1)
            require(not validator.is_valid(invalid), "Prior major version was silently accepted by current schema")

        record(name + ":reject-prior-major-version", rejects_previous_version)

        def rejects_missing_required():
            invalid = copy.deepcopy(sample)
            del invalid["schema_version"]
            require(not validator.is_valid(invalid), "Missing required version was accepted")

        record(name + ":reject-missing-required", rejects_missing_required)
        if export_root and export_file:
            record(name + ":actual-game-document", lambda: validator.validate(export_document(export_root, export_file)))

    # v7 introduces omission semantics. A templates-only category must never
    # pretend its samples are exported recipes; full mode retains recipes.
    schema = load(ROOT / "schema/jei-categories.schema.json")
    validator = Draft202012Validator(schema)
    sample = load(ROOT / "schema/fixtures/jei-categories-v7-templates-only.json")

    def rejects_inconsistent_omission():
        invalid = copy.deepcopy(sample)
        invalid["categories"][0]["recipes_omitted"] = False
        require(not validator.is_valid(invalid), "templates_only without recipes_omitted was accepted")

    record("jei-v7:reject-inconsistent-omission", rejects_inconsistent_omission)

    def rejects_wrong_nested_type():
        invalid = copy.deepcopy(load(ROOT / "schema/fixtures/natural-generation-v2.json"))
        invalid["entries"][0]["outputs"] = "not-an-array"
        require(not Draft202012Validator(load(ROOT / "schema/worldgen.schema.json")).is_valid(invalid), "Invalid nested outputs accepted")

    record("natural-generation-v2:reject-wrong-nested-type", rejects_wrong_nested_type)

    def checks_worldgen_data_file_source():
        validator = Draft202012Validator(load(ROOT / "schema/worldgen.schema.json"))
        document = load(ROOT / "schema/fixtures/worldgen-data-files-v1.json")
        item = document["files"][0]
        require(item["source_mod_id"] != item["object_id"].split(":", 1)[0], "Fixture must cover a namespace override")
        validator.validate(document)
        item["source_mod_id"] = 1
        require(not validator.is_valid(document), "Non-string source mod ID was accepted")
        del item["source_mod_id"]
        validator.validate(document)

    record("worldgen-data-files-v1:source-mod-id-and-prior-v1-compatibility", checks_worldgen_data_file_source)

    def checks_advancement_rewards():
        validator = Draft202012Validator(load(ROOT / "schema/advancements.schema.json"))
        document = load(ROOT / "schema/fixtures/advancements-v2.json")
        entry = document["advancements"][0]
        entry["rewards"] = None
        validator.validate(document)
        entry["rewards"] = {"experience": 0}
        validator.validate(document)
        for invalid in (False, 0, "none", []):
            entry["rewards"] = invalid
            require(not validator.is_valid(document), "Invalid advancement reward type was accepted")
        del entry["rewards"]
        require(not validator.is_valid(document), "Missing advancement rewards field was accepted")

    record("advancements-v2:empty-rewards-and-required-type", checks_advancement_rewards)

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
        require(not template_schema.is_valid(invalid), "Negative hit rectangle width was accepted")

    record("jei-v2:reject-bad-template-rect", rejects_bad_rect)

    def rejects_bad_probability():
        invalid = copy.deepcopy(recipes)
        invalid["recipes"][0]["bindings"][1].update(chance_available=True, chance=1.5)
        require(not recipe_schema.is_valid(invalid), "Out-of-range probability was accepted")

    record("jei-v2:reject-bad-probability", rejects_bad_probability)

    def rejects_fractional_count():
        invalid = copy.deepcopy(recipes)
        invalid["recipes"][0]["bindings"][1]["alternatives"][0]["count"] = 0.5
        require(not recipe_schema.is_valid(invalid), "Fractional item count was accepted")

    record("jei-v2:reject-fractional-item-count", rejects_fractional_count)

    category = load(ROOT / "schema/fixtures/jei-categories-v7.json")["categories"][0]
    record("jei-v7:category-to-template-to-bindings", lambda: validate_jei_collections(category, template, recipes))

    def rejects_dangling_slot():
        invalid = copy.deepcopy(recipes)
        invalid["recipes"][0]["bindings"][0]["slot_id"] = "input_99"
        recipe_schema.validate(invalid)
        try:
            validate_jei_collections(category, template, invalid)
        except ContractError:
            return
        raise ContractError("A binding to an undeclared slot was accepted")

    record("jei-v7:reject-undeclared-slot", rejects_dangling_slot)

    manifest_schema = Draft202012Validator(load(ROOT / "schema/manifest.schema.json"))
    failed_manifest = load(ROOT / "schema/fixtures/manifest-package-failed-v1.json")
    record("manifest:failed-staging-directory", lambda: manifest_schema.validate(failed_manifest))
    record("manifest:reject-failed-zip", lambda: require(
        not manifest_schema.is_valid(dict(failed_manifest, package_format="zip")),
        "A failed staging manifest was accepted as a ZIP"
    ))

    if export_root:
        def validate_actual_collections():
            categories = export_document(export_root, "recipes/jei/categories.json")
            for category in categories["categories"]:
                templates = export_document(export_root, category["template_collection"])
                bindings = export_document(export_root, category["recipe_collection"])
                template_schema.validate(templates)
                recipe_schema.validate(bindings)
                validate_jei_collections(category, templates, bindings)

        record("jei:actual-game-referenced-template-and-binding-collections", validate_actual_collections)
        record("manifest:actual-game-schema", lambda: Draft202012Validator(
            load(ROOT / "schema/manifest.schema.json")
        ).validate(export_document(export_root, "manifest.json")))

        def actual_capabilities():
            manifest = export_document(export_root, "manifest.json")
            capabilities = export_document(export_root, "compatibility/capabilities.json")
            Draft202012Validator(load(ROOT / "schema/capabilities.schema.json")).validate(capabilities)
            validate_capabilities(manifest, capabilities)

        record("capabilities:actual-game-schema-and-environment", actual_capabilities)

        def actual_complete_supported_export():
            manifest = export_document(export_root, "manifest.json")
            require(manifest["minecraft_version"] == "1.20.1", "Sample requires this repository's supported Minecraft version")
            require(manifest["loader"] in ["fabric", "forge"], "Sample requires a currently supported loader")
            require(manifest["status"] == "complete" and manifest["error_count"] == 0, "Game export is incomplete or contains exporter errors; inspect original game evidence")
            require(manifest["configuration"]["profile"] == "all", "Full contract validation requires the all profile")
            require(not manifest["errors"] and manifest["stages"] and all(stage["status"] == "complete" for stage in manifest["stages"]),
                    "Manifest contains errors or unfinished stages")
            build_version = next(line.split("=", 1)[1].strip() for line in (ROOT / "gradle.properties").read_text().splitlines() if line.startswith("mod_version="))
            expected_version = args.expected_exporter_version or build_version
            require(manifest["exporter_version"] == expected_version, "Manifest exporter version differs from the expected producer version")

        record("manifest:actual-game-complete-supported-all-profile", actual_complete_supported_export)
    print(json.dumps({"sample_kind": "actual-game-and-synthetic" if args.export_root else "synthetic-contract-only", "results": observations}, ensure_ascii=False, indent=2))
    return int(any(row["status"] != "PASS" for row in observations))


if __name__ == "__main__":
    sys.exit(main())
