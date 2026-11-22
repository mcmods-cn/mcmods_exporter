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

Each exported advancement carries a `rewards` field. Minecraft 1.20.1 writes
`null` for `AdvancementRewards.EMPTY` and an object for a reward definition.

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
loader, Minecraft version, profile and format must agree with the manifest.
The complete-export check requires a supported Minecraft version and loader,
`profile=all`, `status=complete`, finished stages and an empty error list with
`error_count=0`. Diagnostics identify the missing file or the failing schema
path and rule.

The bundled examples are synthetic wire-contract samples. JUnit logic tests,
synthetic schema validation, real game serialization, API/database import,
queue/Worker execution, independent moderation and public browser rendering are
separate evidence layers. A schema PASS is not an end-to-end import or release
approval. Preserve all prior failures and environment errors when rerunning.

## Producing a package from a checkout

Use JDK 17 and a disposable singleplayer world with the mod whose namespace
you intend to export. Both loaders require the JEI runtime. Common sources
compile against its API. To start a development client, save the following as
an external Gradle init script named `client-jei.gradle`. Set `loader` to the
selected Gradle subproject.

```groovy
def loader = 'fabric'
gradle.beforeProject { project ->
    if (project.path == ":${loader}") {
        project.pluginManager.withPlugin('dev.architectury.loom') {
            def versions = project.rootProject
            project.dependencies.add('modLocalRuntime',
                "mezz.jei:jei-${versions.minecraft_version}-${loader}:${versions.jei_version}")
        }
    }
}
```

Run `./gradlew -I /path/to/client-jei.gradle :fabric:runClient`, or use
`forge` in the script and `:forge:runClient` for Forge. Place the target mod
in the selected subproject's `run/mods/`. Enter the world, wait for JEI, then
run `/mcmods_export <modid> all`. Record the source commit, loader, JEI version,
input mod hash, command, ZIP hash and manifest outcome. For an installed-JAR
smoke test, load the remapped JAR, Architectury, JEI and the applicable Fabric
API in a separate launcher profile.

Inspect `status`, `error_count`, capabilities, and each stage before sending
the ZIP to a test project. A cancelled job keeps its staging directory. A ZIP
write failure records `status=failed` in the staging manifest when the
directory remains writable. Retain this evidence, resolve the output path or
disk issue, and start a fresh job. For regression checks keep the previous JAR
and ZIP hash, so the previous loader-matched JAR can be restored while existing
import revisions continue to refer to their original package.

On the first sampled error, the game log identifies a private file under the
game instance's `logs/mcmods-export-*.log`. Its header records the export
archive name, stage and object. Stacks include causes and suppressed exceptions.
Treat these files as private when sharing a ZIP or reporting an issue. Each
job samples one stack per stage, object and exception type, up to 15 ordinary
errors plus a reserved archive failure. Individual stacks have a marked
32,768-character limit. If the file system cannot create an owner-only file,
the game reports that diagnostic logging is unavailable and keeps the manifest
error counts. A successful job with no errors creates no diagnostic file.

To investigate a slow export, reuse the same world, installed mods, resources,
configuration and rendering environment. Inspect the stage `work_steps`,
`max_step_us` and elapsed time in the manifest. In the world-generation stage
each registry step handles at most one entry. Resource scans, individual codecs,
JSON writes, renders and ZIP finalization still use the client thread. Measure
finalization separately when attributing wall time to compression.

The language stage indexes the selected resource stacks once and processes
one locale per step. Dependency translations remain available when resolving a
component, and selected namespaces take precedence over dependencies. A single
large locale still needs its JSON decoded and written on the client thread.

### Checking JEI ingredient placement in game output

With Minecraft 1.20.1 and JEI 15.20.0.133, export the `minecraft` namespace
and look up `minecraft:torch` in the crafting recipe collection. Follow that
recipe's `template_id`. The result slot starts at (99, 23) in the 124×62
logical canvas and has size 16×16. The separate background PNG uses 4× scale.

For a custom renderer, use a separate game instance and enable `DebugMode`
in `config/jei/jei-debug.ini` before starting JEI. Export the `jei` namespace.
For a focused run, set `mcmods.export.modules=compatibility,jei` and
`mcmods.export.recipeTypes.exclude=jei:obnoxiously_large_recipe`.
In the upstream `jei:debug` template the fluid input uses 12×47 logical pixels
at (28, 4). Its two fluid outputs use 16×58 at (94, 4) and (114, 4). Each
18×60 tank frame starts one pixel above and to the left of its ingredient.
The frame background and overlay come from JEI's
`assets/jei/textures/jei/gui/debug.png` at texture coordinates (220, 196)
and (238, 196). This exercises offset decorations with the custom sizes and
keeps the upstream zero-amount fluid distinguishable from an empty slot.

## Reusing a recorded game export

When checking a previous producer, pass `--expected-exporter-version` from its
recorded provenance alongside `--export-root`. The default checks the current
build version. Keep the input ZIP and its digest with the validation record.

The backend keeps both loader-specific test mods, their unmodified exports and
the producer commit in `internal/httpapi/testdata/game-20261121/`. Each mod
declares `governancefixture:two_sticks` with one oak plank and two sticks.
Use the matching loader's input JAR to repeat the game run. Keep the original
ZIP for byte-level comparisons. The directory's `provenance.json` pins both
ZIPs and input JARs by SHA256.

The backend repository keeps small, unmodified Minecraft 1.20.1/Fabric 0.16.14/
JEI 15.20.0.133 exports in `internal/httpapi/testdata/audit-20261024/`.
`auditfixture-v7-game-provenance.json` identifies the full sample by producer
commit and SHA256. The producer commit for this sample is
`806214802c1e3447a9895fa5311e73f3be6edfcc`.
Keep the archive unchanged and use a fresh directory to check its contents.
To validate the same bytes with the website importer, run this from the
backend repository:

```sh
go test ./internal/httpapi -run '^(TestExporterSamplePackageContracts|TestExportersForSupportedLoadersMatchRecordedPackages)$' -count=1
```

Set `MCMODS_EXPORT_TEST_DIR` to an isolated directory containing additional
unchanged ZIPs to exercise the same decoder and ownership checks on a new run.

The backend check covers the full and template-only archives and exercises its
decoder, recipe ownership scope, geometry and binding rules in memory. Its
fixture and [provenance](https://github.com/mcmods-cn/mcmods-cn-backend/blob/main/docs/audit-jei-recipe-ownership.md)
describe a historical game run. For changes to game output or thread ownership,
record an export from the new producer commit and its actual loader/JEI matrix.
