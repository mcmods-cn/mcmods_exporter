NEW-EXPORT-004 的生产者修复使 `JsonFiles` 保留主动写入的 JSON null。旧生产者可能省略 `ordered:null`，但仅凭字段缺失无法恢复原值，也不能推断为 `false`。本工具只统计已经获准读取的包，不扫描生产库存，不修改或解压输入，不连接数据库，也不生成修复包。

在仓库根目录运行：

```text
python3 tools/audit/sys48-contract/diagnose_legacy_ordering.py /path/to/export.zip
```

工具只使用 Python 标准库，需要运行环境支持 `os.O_NOFOLLOW`，例如 Linux 或 WSL。输出到标准输出；保存报告时使用新的证据文件，保留原包和原 SHA256。退出码 0 表示完成了这些计数，不代表完整 Schema、整包 CRC、图片、manifest 或导入业务已经验收。未找到选中的 JEI 文档时，空计数也不表示存量已排除影响。

两个文档族分别统计：`index_recipe_summaries` 对应 `recipes/jei/categories.json`，`recipe_collection_bindings` 对应 `recipes/jei/recipes/*.json`。同一配方可能同时出现在两族中，不能相加作为去重配方数量。计数键为 `<layout_kind 分类>:<ordered 分类>`。

| 输入 | 计数分类 |
| --- | --- |
| 实际缺少 `layout_kind` 键 | `ABSENT` |
| `unknown`、`not_applicable`、`shaped`、`shapeless` | 保留相应名称 |
| 其他已提供的 `layout_kind`，包括字面字符串 `"ABSENT"` | `OTHER` |
| 实际缺少 `ordered` 键 | `ABSENT` |
| 显式 JSON null | `NULL` |
| JSON true / false | `TRUE` / `FALSE` |
| 数字、字符串、数组等其他 `ordered` 值，包括数字 0 | `INVALID_TYPE` |

`unknown:NULL` 保留“顺序未知”，`shapeless:FALSE` 保留明确的无序，二者不能互换。分类只描述输入字段；例如 `unknown:TRUE` 会被原样归类，不会由诊断器擅自改值或决定其业务有效性。

输入限于普通非符号链接 ZIP，压缩文件上限 256 MiB、条目 50,000 个、声明解压总量 512 MiB；选中的 JEI JSON 总量上限 64 MiB，单成员上限 16 MiB，并检查声明压缩比。路径穿越、路径大小写别名、ZIP 链接/加密、重复 JSON 键、预算或读取错误返回退出码 1 和 `HOLD_NO_DIAGNOSTIC_CLAIM`，不能将它记为零受影响。检查完成前后核对同一文件身份和 SHA256。正常报告仅含哈希、大小、文档数量与分类计数，不输出配方 ID、标题、正文或个人资料。未选中成员的内容不会被读取校验，因此仍须另做完整包验收。

存量处置按以下顺序进行：

1. 对授权范围内的原包逐一只读诊断，登记输入 SHA256、来源项目/版本、导入任务和修订关联，以及报告。此工具没有执行生产库存扫描；受影响生产包数量仍须以实际授权清单和诊断结果确认。
2. 对缺失、未知或不合法字段保留原始状态。不得批量补成 `false`，也不得改写旧 ZIP、manifest、Schema 标签或已发布修订来掩盖缺失。显式 `NULL` 本身是有效的未知状态，不应被当作必需修补的数据。
3. 具备原始环境时，使用包含生产者修复 `4df379d5129d9c6b100c0d0cc057a5e1a93068da` 的构建，按相同 Minecraft、loader、Mod 版本、配置和资源重新导出。版本字符串可能相同，应另记录构建提交、新包 SHA256 和与旧包的关系。通过正常上传、导入、独立审核建立新修订，再核对公开投影；旧包与旧审计保留。
4. 原始环境或权威依据缺失时，该项保持未知并列出缺口。必要的人工补录也应形成有依据、可审核的新修订，不能让诊断计数替代业务决定。历史已导入数据需由对应后端只读定位后，另行设计限定范围、dry-run、幂等、计数核对及回滚/补偿流程；本工具不执行这些写操作。
5. 重导出或新修订验收失败时，保留新旧证据，按现有权限和审核流程回退到先前有效修订，核对公开指针。不要删除共享资产或审计历史来回滚。回退导出器到修复前代码会再次省略未来导出中的显式 null，不能恢复旧包丢失的值。

本次无需数据库迁移，也不改变 `mcmods-jei-categories/v7` 或集合 v2 的格式标识。兼容窗口内，消费端仍须区分缺失、null 和布尔值；导出器升级只影响后续生成的包。生产数据诊断、补录、重新导入与发布均未由本提交执行。

本工具逐字节采用审计冻结的修正版 source02，SHA256 为 `822f629f29417f53874932ad2505a8bd76673d8cbcf366f35730257919ff4e3c`。旧原型以字符串 `ABSENT` 作缺失哨兵，混淆实际缺失与用户提供的同值字符串；修正版改为检查键是否存在，本提交保留这一修正。两个合成 CLI 回归验证上述状态区分、两个文档族、隐私边界、精确路径拒绝和原包不变；合成 ZIP 不是生产样本。

历史验证还保留了另一项夹具首败：旧受控样本 `2b9b816bb64383be9f5d3f47ac0aa735815afd9a3ec72a2af16b6b373d05ee10` 的两条来源声明均为 `minecraft:crafting_shapeless`，工具准确输出两族各 `{"shapeless:FALSE": 2}`，但最初父测试误期望 `shaped:TRUE` 而退出 1。审计记录 `exporter-sys48-legacy-diagnostic-first-harness-fixture-error-saved-review-20261115.json`（SHA256 `199d850e70f3c0e126a6b202df584c22fd980324f22b82b986946bce73849e71`）保留原失败、更正依据、七种合成摘要状态和四项精确负用例的已保存输出；未补造逐子进程原生状态。该受控样本的正确 false 不能外推为生产库存无影响。

针对本工具的命令是：

```text
python3 -B -m unittest discover -s tools/audit/sys48-contract -p test_diagnose_legacy_ordering.py -v
```

这只覆盖只读诊断 CLI，不替代 exporter JUnit、真实游戏或三仓库发布验收。
