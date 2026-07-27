# 附魔与绑定热键导出

本说明适用于 `mcmods_exporter` 1.21.1 版 0.9.3 及以后版本。

## 文件

- `registries/enchantments.json`
- `registries/key_mappings.json`

两个文件均使用 `mcmods-registry/v1` 容器：

```json
{
  "schema_version": "mcmods-registry/v1",
  "registry": "enchantments",
  "count": 1,
  "entries": []
}
```

`count` 必须等于 `entries` 的长度。没有匹配内容时仍应生成文件，并输出空数组。

## 附魔

附魔来自当前客户端世界的最终 `minecraft:enchantment` 动态注册表，而不是只扫描模组 JAR 中的原始 JSON。因此数据包替换、标签合并和模组运行时注册结果已经生效。

每个条目包含：

- `id`、`namespace`、`path`
- `translation_key` 与全部已发现语言的 `names`
- `rarity`、`rarity_weight`
- `min_level`、`max_level`、`anvil_cost`
- `treasure_only`、`curse`、`tradeable`、`discoverable`
- `slots`
- `supported_items_tag`、`supported_items`
- `primary_items_tag`、`primary_items`
- `exclusive_with`
- 各等级的 `costs`
- `effect_component_count`

单个第三方附魔读取失败时只记录该附魔错误，其余附魔仍会导出。

## 绑定热键

每个条目包含：

- `id` 与 `translation_key`
- 全部已发现语言的 `names`
- `category_translation_key` 与 `category_names`
- `default_key`
- `bound_key`
- `is_default`
- `is_unbound`
- `source_mod_id`
- `source_detection`

原版键位通过 Minecraft `Options` 的原版键位字段按对象身份识别，`source_mod_id` 为 `minecraft`，`source_detection` 为 `vanilla_options_field`。

模组键位根据名称或分类翻译键中的完整 Mod ID 段识别。例如：

- `key.mekanism.mode` 属于 `mekanism`
- `key.categories.create` 属于 `create`
- `key.some_create_addon.mode` 不会因为包含 `create` 子字符串而误判为 `create`

模组键位的 `source_detection` 为 `translation_key_namespace`。无法从翻译键可靠判断归属的非原版键位在全量导出中仍会保留，并使用 `source_mod_id=unknown`；指定 Mod ID 导出时不会猜测归属。

## 阶段容错

`registry_data` 内部各注册表分节独立执行。某个分节失败时：

1. 错误写入 `manifest.json.errors`；
2. `registry_sections_failed` 计数增加；
3. 后续分节继续导出。

因此生物、流体或第三方注册对象的异常不会再导致附魔和键位文件一起缺失。
