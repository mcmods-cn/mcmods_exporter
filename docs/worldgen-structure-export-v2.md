# 世界生成结构大类导出 v2

`worldgen/structures.json` 使用 `mcmods-structures/v2`，面向百科网站只导出 Minecraft `worldgen/structure` 注册表中的顶层结构。村庄房屋、道路、远古城市房间等 `data/<namespace>/structures/*.nbt` 是拼图结构的内部实现零件，不再作为结构条目，也不再复制进 ZIP。

## 目录规则

- 一个 `structures[]` 条目对应一个稳定的 `structure_id`，例如 `minecraft:ancient_city`、`minecraft:village_desert`。
- `catalog_kind` 固定为 `top_level_structure`。
- `export_mode` 固定为 `catalog_only`，`internal_templates_exported` 固定为 `false`。
- `omitted_internal_template_count` 是导出时识别并跳过的有效 NBT 模板数量，只用于审计，不代表某个模板一定能唯一归属到某个顶层结构。
- `worldgen/template_pool` 和 `worldgen/processor_list` 不进入自然生成公开目录，也不复制原始 JSON。
- `worldgen/structure_set` 不创建独立百科条目；它会被转换成对应结构的 `structure_set_ids` 和 `structure_set_references`。

## 已规范化字段

导出器保留生效的 `definition`，并提前提取网站常用字段：

- `structure_type`
- `biomes`
- `generation_step`
- `terrain_adaptation`
- `spawn_overrides`
- `start_pool`
- `start_jigsaw_name`
- `jigsaw_size`
- `start_height`
- `max_distance_from_center`
- `use_expansion_hack`
- `project_start_to_heightmap`
- `liquid_settings`

结构集引用包含 `structure_set_id`、权重和完整的放置参数。网站可以直接保存这些字段，不需要重新解析结构集 JSON。

## 导入要求

网站应以 `structure_id` 作为结构大类的唯一键。`mcmods-structures/v2` 中不会出现只有 `template_path` 的内部零件记录。兼容旧 `mcmods-structures/v1` 时，只应导入 `runtime_registered=true` 或拥有 `worldgen/structure` 定义的条目，忽略只有 NBT 模板的条目。

拼图结构没有唯一、固定的完整 NBT。若未来需要三维预览，应单独导出一次实际组装后的示例结构或渲染结果，不应重新把每个内部模板变成百科条目。
