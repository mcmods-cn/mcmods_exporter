# Synthetic contract fixtures

These complete documents are synthetic wire-contract examples assembled from the
current production writers' field semantics. They are **not** captured Minecraft
exports. They preserve templates-only omission semantics, displayed advancement
filters, partial natural-generation records, provider-valued counts, missing loot
definitions, and raw unknown loot extensions.

From the repository root, use Python 3.12 to run
`python -m pip install -r tools/requirements-contracts.txt`, then
`python tools/check_export_contracts.py`. To validate an unmodified game export
with profile `all`, unpack its ZIP and run
`python tools/check_export_contracts.py --export-root PATH`. Missing required
files fail the real-package check. Keep the original ZIP, SHA-256, loader and
JEI versions, game logs and command beside the validation report.
[Current source, scope and artifact digests](../../docs/contract-validation.md)

`jei-categories-v7.json` exercises full export; the templates-only fixture has
four layout samples and zero actual recipes. The legacy v6 schema remains
separately versioned. Unknown versions and v7 omission inconsistencies are
negative assertions, not accepted by changing the document version string.
