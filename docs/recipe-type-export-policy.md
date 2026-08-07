# 合成类型导出策略

导出器按 JEI 的 `recipe_type_id` 支持三种导出模式：

| 模式 | 类别与 Catalyst | 背景 GUI | 模板与槽位 | 具体配方绑定 | `recipes/recipes.json` |
| --- | --- | --- | --- | --- | --- |
| `full` | 导出 | 导出 | 导出 | 导出 | 导出 |
| `templates_only` | 导出 | 导出 | 导出 | 不导出 | 同类型配方不导出 |
| `exclude` | 不导出 | 不导出 | 不导出 | 不导出 | 同类型配方不导出 |

这里配置的是 `recipe_type_id`，例如 `minecraft:anvil`，不是某一条具体配方的 `recipe_id`。

## 配置文件

首次建立导出配置时会生成：

```text
<游戏目录>/config/mcmods_exporter.properties
```

配置示例：

```properties
# 完全排除这些合成类型
mcmods.export.recipeTypes.exclude=create:automatic_shaped

# 仅保留类别、背景和模板
mcmods.export.recipeTypes.templateOnly=minecraft:anvil
```

多个 ID 使用英文逗号分隔。ID 必须是完整的 `namespace:path`。如果同一个 ID 同时出现在两个列表中，`exclude` 优先。

可以用 `mcmods.export.config` 指定其他配置文件：

```text
-Dmcmods.export.config=config/my-export.properties
```

相对路径以游戏目录为基准。

## JVM 启动参数

配置文件中的键可以直接作为 JVM `-D` 参数。启动参数优先于配置文件：

```text
-Dmcmods.export.recipeTypes.templateOnly=minecraft:anvil
-Dmcmods.export.recipeTypes.exclude=create:automatic_shaped,minecraft:smithing
```

自动导出和 `/mcmods_export` 手动导出都会读取这些设置；每次开始新的导出任务时会重新读取配置文件。

## `templates_only` 输出

以 `minecraft:anvil` 为例，仍会生成：

```text
recipes/jei/categories.json
recipes/jei/templates/minecraft/anvil.json
recipes/jei/backgrounds/minecraft/anvil/<template_id>.png
recipes/jei/recipes/minecraft/anvil.json
```

最后一个配方集合文件为了保持导入器兼容仍会存在，但包含：

```json
{
  "recipe_type_id": "minecraft:anvil",
  "export_mode": "templates_only",
  "recipes_omitted": true,
  "count": 0,
  "recipes": []
}
```

类别和模板集合也会输出 `export_mode=templates_only` 与 `recipes_omitted=true`。`categories.json` 的顶层 `recipes[]` 不会出现该类别的具体配方，普通 `recipes/recipes.json` 也会省略底层类型相同的配方。

导出器仍需用 JEI 中的配方作为布局样本，以发现同一 `recipe_type_id` 下可能存在的多个模板；样本仅参与计算 `template_id` 和背景，不会写入配方绑定。固定槽位的铁砧通常最终只产生一个模板。

## 清单记录

`manifest.json.configuration` 会记录本次真正生效的：

- `excluded_recipe_type_ids`
- `template_only_recipe_type_ids`

网站应以导出包中的这些字段和 `categories.json` 的 `export_mode` 为准，不要根据服务器本地配置进行二次猜测。
