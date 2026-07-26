# 战利品表规范化导出 v2

`worldgen/loot_tables.json` 使用 `mcmods-loot-tables/v2`。网站不需要再从 ZIP 内的数据包路径重新寻找并解析战利品表文件。

## 完整性字段

根节点包含：

- `count`：导出的战利品表 ID 数量；
- `definition_count`：实际取得生效 JSON 定义的数量；
- `definition_missing_count`：只有运行时 ID、没有定义的数量；
- `normalized_pool_count`：所有战利品表规范化后的池总数；
- `predicate_count`、`item_modifier_count`：取得的谓词和物品修饰器数量；
- `pools_format=mcmods-normalized-loot-pools/v1`。

网站应在自动入库前检查 `definition_missing_count`。非零表示当前运行环境没有提供部分服务端数据，不能把缺失条目解释为空战利品表。

## 单表字段

每个 `loot_tables[]` 条目包含：

- `id`、`category`、`selected_namespace`、`runtime_loaded`；
- `definition_available` 和可取得时的 `data_path`；
- 生效的原始 `definition`，用于审计和兼容未知 Mod 扩展；
- 规范化 `pools[]`；
- `pool_count`、`top_level_entry_count`；
- `referenced_loot_tables`；
- `possible_item_ids`、`possible_item_tags`、`possible_items_complete=false`。

## 规范化池

每个 `pools[]` 元素包含：

- `pool_index`；
- 原样保留数值提供器的 `rolls`、`bonus_rolls`；
- `conditions`、`condition_types`；
- `functions`、`function_types`；
- `entry_count` 和递归 `entries[]`；
- 当前池递归解析嵌套战利品表后的 `possible_item_ids`、`possible_item_tags`。

每个规范化条目包含 `entry_index`、`entry_type`、`entry_kind`、权重、品质、条件、函数及递归子条目。常用条目还会提前映射：

- 物品：`item_id`；
- Tag：`tag_id`；
- 嵌套战利品表：`loot_table_id`；
- 动态掉落：`dynamic_id`。

未知条目不会丢弃所在的原始 `definition`，规范化条目会标记 `normalization_status=partial`。

## 版本目录适配

Minecraft 1.20.1 使用复数目录：

```text
loot_tables/
predicates/
item_modifiers/
```

Minecraft 1.21+ 使用单数目录：

```text
loot_table/
predicate/
item_modifier/
```

版本分支必须在导出器端完成目录映射，并统一输出相同的 `mcmods-loot-tables/v2`，网站不得按 Minecraft 版本猜测数据包目录。
