# SYS48 T01/T03: new non-graphical contract at exporter8062148

Test-only proof derived directly from `806214802c1e3447a9895fa5311e73f3be6edfcc`.
No production files, existing tests, schema labels, support matrix or root ledger
are changed. New tests are NOT_RUN at source freeze. Prior49 JUnit, old059b and
SYS48-T02 do not establish complete T01/T03.

| Original scene / unchanged level | Original action | Original assertions |
| --- | --- | --- |
| SYS48-T01 GAME_CLIENT_OR_CONTRACT | 导出木棍/木门及动态容量配方，同几何改变材料/NBT/概率 | 固定3x3共模板；材料概率不改template_id；不同type作用域分离；背景不烘焙材料 |
| SYS48-T03 GAME_CLIENT_OR_CONTRACT | anvil设templateOnly、另一类exclude；保留有证据副产物和未知概率 | 前者有模板但零bindings并标omitted；后者无类别文件；byproduct不改slot身份；unknown/ordered=null保留 |

Original canonical status SHA256: T01
`6ed4ee48bbe74f47047ca0891ec9e7461885d1f55fb3cb482c895d92cbc954d7`,
T03 `62f6659db3f097fdb64868c49dd791fd054100cb02472c4831988ba6b3014fa6`.
Exact JSONL lines including newline: line142
`39cec99501d3f3ab9164cb8e73f5cf313d3b732193cffc07e3b4a3415f726b10`,
line144 `f3afe4f8794751747b1395a0797ff39134f89afcbb485d2e157b1a9ed13cfb0c`.

`Sys48WireMatrixContractTest` calls actual production implementations:

1. `materialNbtChanceAndDynamicCapacityHaveIndependentExpectedRelations`:
   nine declared serialized-slot vectors -> actual `JeiSlotSemantics.enrich` ->
   `JeiLayoutTemplateJson.split` -> `RecipeLayoutNormalizer.write` -> actual
   `JsonFiles.write` and read. Independently expected relations cover fixed3x3
   stick/door, 2/5 dynamic capacity, material/count/SNBT/25%/75% changes, empty
   slots, byproduct/unknown chance and stable slot/template identity.
2. `unknownOrderingRemainsExplicitNullAfterActualDiskSerializer`:
   real unknown normalizer -> assertion of explicit null in memory -> real
   `JsonFiles.write` -> assertion that the exact disk field still exists and
   is JSON null, with unknown/unavailable metadata. Missing, boolean or other
   values fail. No arbitrary exception can satisfy this positive test. It is
   separate so a null serialization failure cannot hide geometry test results.
3. `realCollectionWriterKeepsTypeScopesAndDeclaredTemplateOnlyBoundary`:
   explicit `ExportContext(null,...)`, actual config and actual private writer
   via reflection; real split-derived maps -> actual type-scoped v7/v2
   collection output and read. Full/omitted envelopes and separate type paths
   are checked. It also checks explicit unknown ordered:null on actual recipe
   collection bytes. This invokes the real private `writeOutputs`, not a mock.

The third test supplies valid selected/rendered writer-boundary maps. Its
empty anvil bindings and absent excluded type are declared PRECONDITIONS, not
proof of actual stage.start/policy exclusion/renderEntry. No game instance or
Architectury Platform is fabricated. The explicit null Minecraft is never
used by this writer. No fake Platform class, proxy runtime, replacement output
serializer or replacement renderer is supplied. The background path says
`PENDING_REAL_GL_NOT_A_PNG`: these boundary files are not a complete package.
The vectors are declared serialized inputs, not actual game ItemStack output.

Future one targeted offline command after root source review/resource release,
from `/workspace/audit-fixes-20261024` (the existing wrapper is read and keeps
normal Java17/CA/proxy configuration):

```text
python runtime/run-exporter-gradle.py -p verification/exporter-sys48-t01-t03-contract-20261115 -I verification/exporter-sys48-t01-t03-contract-20261115/tools/audit/sys48-contract/runtime.init.gradle :common:test --tests cn.mcmod.wiki.exporter.stage.Sys48WireMatrixContractTest --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m --console=plain
```

The init adds actual compile-only JEI API to this test's runtime and constrains
one fork; it does not patch production build files. Compile dependencies may
need work, but no accepted whole build, old49 JUnit or old schema/game gate is
requested. Preserve all first native outcomes, stdout/stderr, exact pin/SHA,
command, Java version, source before/after and test XML. Do not automatically
retry, extend budget or alter expected null to make a test pass.

The two independent analytic PNG goldens are retained test data for the
separate pending real-client pixel oracle. They are not exercised by these
JUnit methods and cannot establish any background/GL or complete-scene PASS.
`pixel-spec.json` fully declares the known panel content. For each pixel
(logical coordinate = integer pixel/4), start transparent; within logical
[4,120)x[4,58) use the selected opaque panel, then vertical x8..10 and horizontal
y46..48 relative to the content (horizontal last). PNG is496x248 RGBA. Actual
JEI's default4px border will be checked by an independent ordinary empty-input
real JEI control, not assumed equal to the transparent analytic margin.

Remaining original coverage still needs a real supported development client:
actual MC1.20.1/Fabric0.16.14/JEI15.20.0.133 slot/NBT/Platform path, fixed and
dynamic categories, actual anvil templateOnly and a populated excluded type,
known/unknown byproduct, real material-free background pixel/control checks,
unmodified new product ZIP and normal owned client shutdown. A separate
ordinary JEI input plugin/normal Loom init/read-only pixel oracle is statically
prepared outside this clean headless tree and requires independent review and
its own game resource window. No Java/Gradle/game/display/NSS/download was
started during source preparation. The old supported T02 actual82.827s dev
run supports feasibility, not these new assertions. Normal release-JAR install
remains separately unexecuted; no normal launcher/session is fabricated and
its missing prerequisites do not block this honestly scoped contract layer.
