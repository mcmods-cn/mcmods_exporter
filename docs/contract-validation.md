# Export contract validation

The current build targets Minecraft 1.20.1, Java 17, Fabric 0.16.14 or Forge
47.4.21, Architectury 9.2.14 and JEI 15.20.0.133. `gradle.properties` is the
source for build versions. No Minecraft 1.21/NeoForge runtime is implied by the
format's future porting notes.

| Document | Current producer | Current schema validation | Prior format |
| --- | --- | --- | --- |
| Manifest | `mcmods-export/v1` | v1 | Unknown majors rejected |
| JEI category index | `mcmods-jei-categories/v7` | v7, including explicit template omission | v6 has a separate legacy schema; select by declared version |
| JEI templates / bindings | collection and record v2 | v2 | Unknown majors rejected |
| Advancement index | `mcmods-advancements/v2` | v2 displayed non-recipe filtering and counts | v1 is not relabeled as v2 |
| Natural generation | `mcmods-natural-generation/v2` | v2 normalized entries, partial data, and string category identifiers | v1 categories are not interpreted as v2 entries |
| Loot tables | `mcmods-loot-tables/v2` | v2 definitions, missing counters and recursive pools | v1 is not interpreted as complete v2 pools |

Install the validator dependency and run the complete synthetic positive and
negative contract examples:

```sh
python -m pip install -r tools/requirements-contracts.txt
python tools/check_export_contracts.py
```

For a real game `all` export, preserve the ZIP unchanged, record its SHA256 and
unpack it into a separate directory. Validate the directory without renaming
schema versions or filling in missing documents:

```sh
python tools/check_export_contracts.py --export-root /path/to/unpacked-export
```

This command validates the actual manifest, capabilities, four affected indexes
and every referenced JEI template/binding collection. It also joins each v7
category to its collections, checks counts and the omission policy, and resolves
each binding to the declared slot of the template in that category. The capability
loader, Minecraft version, profile and format must agree with the manifest. It also requires the supported
Minecraft/loader, `profile=all`, `status=complete` and `error_count=0` for that
complete-export assertion. Recorded stages must have finished and the error list must be empty. Missing files fail explicitly. Validation diagnostics
report schema paths/rules and do not echo document contents.

The bundled examples are synthetic wire-contract samples. JUnit logic tests,
synthetic schema validation, real game serialization, API/database import,
queue/Worker execution, independent moderation and public browser rendering are
separate evidence layers. A schema PASS is not an end-to-end import or release
approval. Preserve all prior failures and environment errors when rerunning.


## Reusing a recorded game export

The backend repository keeps small, unmodified Minecraft 1.20.1/Fabric 0.16.14/
JEI 15.20.0.133 exports in `internal/httpapi/testdata/audit-20261024/`.
`auditfixture-v7-game-provenance.json` identifies the full sample by producer
commit and SHA256. The producer commit for this sample is
`806214802c1e3447a9895fa5311e73f3be6edfcc`.
Keep the archive unchanged and use a fresh directory to check its contents.
To validate the same bytes with the website importer, run this from the
backend repository:

```sh
go test ./internal/httpapi -run '^TestExporterSamplePackageContracts$' -count=1
```

The backend check covers the full and template-only archives and exercises its
decoder, recipe ownership scope, geometry and binding rules in memory. Its
fixture and [provenance](https://github.com/mcmods-cn/mcmods-cn-backend/blob/main/docs/audit-jei-recipe-ownership.md)
describe a historical game run. For changes to game output or thread ownership,
record an export from the new producer commit and its actual loader/JEI matrix.
