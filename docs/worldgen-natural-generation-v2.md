# 自然生成规范化导出 v2

`worldgen/natural_generation.json` 使用 `mcmods-natural-generation/v2`。该文件是面向百科网站的公开目录，不再把每个 `worldgen/*` 技术注册表作为并列类别转储。

## 唯一键与条目

- `entry_kind=placed_feature`：`generation_id` 等于 `placed_feature_id`。配置地物只作为它的依赖合并，不单独创建百科条目。
- `entry_kind=configured_carver`：洞穴、峡谷等雕刻器以配置雕刻器 ID 作为 `generation_id`。
- 相同配置地物可以被多个放置地物以不同高度、数量或群系使用，因此不能按输出方块或 `configured_feature_id` 合并。
- 未被任何公开放置地物引用的配置地物不创建公开条目，根节点通过 `unreferenced_configured_feature_count` 记录数量。

## 已规范化字段

每个条目至少包含：

- `generation_id`、`entry_kind`、`source_mod_id`、可取得时的 `source_mod_version`；
- `category`，当前稳定分类包括 `ore`、`tree`、`vegetation`、`lake`、`spring`、`geode`、`disk`、`underground`、`surface`、`carver`、`other`；
- `feature_type` 或 `carver_type`；
- `configured_feature_id/configured_feature_ids` 和 `referenced_feature_ids`；
- `outputs[].block_id`、`targets[]`、矿脉 `size` 和暴露丢弃概率；
- `placement.count`、`placement.rarity`、`placement.height`、可静态确定时的 `min_y/max_y`、`biome_filter` 和完整的放置修饰器；
- `generation_step/generation_steps`、`biome_selectors`、`resolved_biome_ids`、`biome_modifier_ids`；
- 只能从标准群系标签可靠推断时输出的 `dimension_ids`，并通过 `dimension_resolution` 标记推断方式；
- `normalization_status=complete|partial`。

配置地物中的嵌套地物引用会递归解析，生成结果方块和替换目标在导出器内合并。检测到输出方块翻译时，条目还会带上 `translation_key`、`name_source_block_id` 和覆盖导出语言的 `names`。

## 群系和加载器兼容

在单人世界中，导出器会编码全部运行时群系并反向建立 `placed_feature -> biome` 关系，因此可以反映 Fabric 代码注入以及 Forge/NeoForge Modifier 生效后的结果。数据文件形式的 `forge:add_features`、`neoforge:add_features` 等 Biome Modifier 也会被消费并合并到对应条目。

连接远程服务器时无法取得完整服务端动态注册表。此时只能使用已安装 Mod 的数据文件和 Modifier，字段可能不完整，条目会使用 `normalization_status=partial` 或空的 `resolved_biome_ids`，网站不得把空数组解释为“不会自然生成”。

## 技术数据

`noise`、`density_function`、`noise_settings`、世界预设等不会成为 `entries[]`。根节点的 `technical_registry_categories_omitted_from_catalog` 仅用于说明哪些技术类别被排除；原始数据索引仍可用于审计和未来兼容，不要求网站导入为百科内容。

`structures.json`、`biomes.json`、`dimensions.json` 和 `loot_tables.json` 继续使用各自独立结构。结构集、模板池、处理器列表和内部结构 NBT 不会混入自然生成条目。
