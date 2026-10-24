# Synthetic contract fixtures

These complete documents are synthetic wire-contract examples assembled from the
current production writers' field semantics. They are **not** captured Minecraft
exports. They preserve templates-only omission semantics, displayed advancement
filters, partial natural-generation records, provider-valued counts, missing loot
definitions, and raw unknown loot extensions.

Run `python -m pip install -r tools/requirements-contracts.txt`, then
`python tools/check_export_contracts.py`. For an unmodified actual game export,
unpack its ZIP and run `python tools/check_export_contracts.py --export-root PATH`.
The latter fails on missing files; it never substitutes synthetic examples.
Keep the original ZIP, SHA256, loader/JEI matrix, game logs and invocation beside
the validation report. Schema success alone is not a game or website end-to-end
acceptance result.

`jei-categories-v7.json` exercises full export; the templates-only fixture has
four layout samples and zero actual recipes. The legacy v6 schema remains
separately versioned. Unknown versions and v7 omission inconsistencies are
negative assertions, not accepted by changing the document version string.
