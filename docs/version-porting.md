# Minecraft 多版本适配约束

Minecraft Mod 的二进制不能跨大版本通用，因此每个 Minecraft 版本必须单独构建 JAR；“兼容低版本”指不同版本构建保持同一导出格式，而不是把 1.20.1 JAR 强行放进旧版本。ZIP 内 `compatibility/capabilities.json` 会告诉网站每项能力是可用、降级还是不可用。

当前发行目标是 Java 17 上的 Minecraft 1.20.1 Fabric 和 Forge。面向其他版本的条目属于移植检查清单。[实际构建与验收范围](contract-validation.md)

## 稳定层与版本层

以下内容属于稳定导出契约，移植时不得因映射名变化而更改网站字段：ID、多语言、物品/方块规范属性、Tag、配方规范结构、进度树、世界生成索引、战利品索引、顶层结构目录、PNG/OBJ 路径、ZIP 与 manifest。结构目录从 `mcmods-structures/v2` 起不再包含内部 NBT 模板。

以下实现属于版本敏感层，应在对应版本源码集替换：

- 注册表与 Holder/RegistryAccess 访问；
- ItemStack 数据组件、Tooltip 与属性 API；
- Advancement/AdvancementRewards；
- Biome 刷怪表与动态注册对象 Codec；
- framebuffer、GuiGraphics、实体 renderer/model；
- Fabric/Forge/NeoForge 命令和客户端 tick 生命周期；
- JEI 公共 API。

配方能量、时间、温度等可选标量使用受限反射。村民交易通过 1.20.1 可重映射的 `VillagerTrades.ItemListing` 采样。单项采样失败会记录 manifest 错误并继续。资源阶段按依赖闭包工作，新加载器所用的模型与贴图引用应进入这条闭包。

## JEI 降级规则

JEI 是强制客户端依赖。当前 1.20.1 构建要求 JEI 15.20+；旧 Minecraft 构建应强制依赖该 Minecraft 版本实际可用的 JEI，而不是错误地要求 JEI 15.20。旧 JEI API 若没有 `getRegistryName`、Recipe Catalyst lookup、可绘制布局或空槽 override，则对应版本构建必须：

1. 保留原版/模组 RecipeManager 配方数据；
2. 能取得的 JEI 能力正常导出；
3. 不能取得的能力在 `capabilities.json` 标记 `degraded` 或 `unavailable`；
4. 不生成猜测式背景、假 Catalyst、假坐标或伪造的稳定 recipe_id。

因此低版本必须安装其对应 JEI；即使该 JEI 没有 15.20 的类别布局能力，物品、方块、实体、语言、Tag、配方、进度、结构、战利品和世界生成等非布局阶段仍应工作。

## 每个版本的验收清单

1. 新建版本分支或版本构建目录，更新 Minecraft、映射、加载器与 Architectury；先编译非 JEI common，再接入该版本 JEI bridge。
2. 运行单元测试，校验所有 JSON Schema，确认 `compatibility/capabilities.json` 与实际输出一致。
3. 使用三套实例：纯原版、普通内容 Mod、带自定义机器/流体/化学品/OBJ 或 composite 模型的 Mod。
4. 检查透明 PNG、32/128/256 尺寸、斜视角实体、方块 3D 模型、OBJ/MTL/贴图闭包。
5. 检查原版群系中的模组生物、原版创造标签页中的模组物品、`data/minecraft` 覆盖、结构引用的外部宝箱表。
6. 检查每个类别有独立布局集合，每个模板有一张不含材料的背景，Catalyst 与可点击槽位坐标正确。
7. 比较上一版本字段集合：同一 Schema 主版本只能新增可选字段；删除、改名或改变语义必须提升 `/vN`。
8. 分别记录 remapped JAR 构建、加载器识别以及进入世界后的导出。将同一个原 ZIP 交给网站完成上传、导入、独立审核和匿名展示。[验证步骤](contract-validation.md)

不要导出 Java 类名、Mixin、栈追踪、未引用整目录素材或伪造关系。版本 API 取不到的数据应明确标记能力不足，而不是填 0、空数组或猜测值。
