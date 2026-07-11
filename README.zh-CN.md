# mcmods_exporter

这是一个为百科网站批量建档准备数据的客户端 Mod。它在游戏内把运行时对象整理为稳定、精简的数据结构，并把当前生效资源和真实客户端渲染结果打包成 ZIP，避免上传后再反编译 Mod、猜测资源覆盖关系或重复做格式转换。

当前首发目标：Minecraft 1.20.1、Java 17、Fabric 与 Forge。Fabric 需要 Fabric API，两端都需要 Architectury API。JEI 是可选依赖；安装后会额外导出 JEI 注册的真实配方类别布局。

## 已实现的内容

| 类别 | 输出 |
| --- | --- |
| 物品、方块 | 注册 ID、网页需要的属性、多语言名称，以及 32/128/256 PNG |
| 方块模型贴图 | 生效资源包中的 blockstate、全部模型与贴图原文件，保留命名空间和路径 |
| 进度/成就网 | 从单人服务器完整列表（远程服回退客户端同步列表）导出根节点、父子关系、显示坐标、条件、要求组、图标，并按每种已发现语言输出标题和描述 |
| 标签 | 每个运行时注册表的 tag ID 及游戏已经解析完成的最终成员列表 |
| 药水 | 药水类型、效果、时长、等级、颜色和四种载体的 32/128/256 PNG |
| 流体与类似介质 | 原版/模组流体注册信息；通过 JEI 通用 Ingredient API 导出流体，以及 Mekanism 气体、灌注类型、颜料、浆液等第三方介质的 ID、数量、Tag、多语言名称和 32/128/256 PNG |
| 配方 | 游戏已解析后的规范化输入候选、输出、类型和可用的序列化器 ID |
| 配方 GUI | 每个 JEI 合成方式（RecipeType ID）只导出一张无物品 GUI 背景；每条配方只导出输入/输出/催化剂/仅展示槽位的坐标、数量与候选材料 JSON，由网页叠加物品图标 |
| 生物实体 | 注册信息、透明背景且使用斜视角的真实渲染器 32/128/256 PNG、静态 idle 基础模型 OBJ、UV、法线、纹理引用 |
| 附魔 | 类型、稀有度、等级、类别、宝藏/诅咒/交易/发现属性和多语言名称 |
| 热键 | 翻译键、类别、默认绑定、当前绑定、是否解绑和多语言名称 |
| Mod 元数据 | Mod ID、版本、作者、许可证、主页、源码和问题链接 |

## 安装和手动导出

1. 安装与 Minecraft 1.20.1 对应的导出器、Architectury API；Fabric 还需 Fabric API。
2. 把需要采集的 Mod、资源包、数据包和 i18n 自动汉化更新 Mod 正常装入客户端。
3. 进入一个单人世界或服务器，等待配方、进度与标签同步完成。
4. 执行命令：

```text
/mcmods_export examplemod all
/mcmods_export examplemod data
/mcmods_export examplemod images
/mcmods_export all all
/mcmods_export status
/mcmods_export cancel
```

默认输出到游戏目录的 `mcmods-export/`，最终文件名为 `<导出的Mod ID>-<yyyyMMdd-HHmmss-SSS>.zip`；多个 ID 使用 `+` 连接，例如 `create+mekanism-20260710-153012-123.zip`。导出过程中使用的隐藏暂存目录会在 ZIP 成功生成后删除。命名空间支持命令补全；`all` 会导出所有命名空间（导出器自身除外），文件名前缀为 `all-mods`。

## 启动参数自动导出

以下均为 JVM 参数，应填在启动器的“额外 JVM 参数”中：

```text
-Dmcmods.export.auto=true
-Dmcmods.export.namespaces=examplemod,anothermod
-Dmcmods.export.output=mcmods-export
-Dmcmods.export.languages=zh_cn,en_us,ja_jp
-Dmcmods.export.profile=all
-Dmcmods.export.sizes=32,128,256
-Dmcmods.export.tickBudgetMs=12
-Dmcmods.export.exit=true
```

参数说明：

- `auto`：进入世界且资源同步完成后自动开始。
- `namespaces`：逗号分隔；留空或 `all` 表示全部。
- `output`：相对路径以游戏目录为基准，也可用绝对路径。
- `languages`：额外要求输出的语言；导出器还会自动发现目标 Mod 当前资源栈内的全部 `lang/*.json`，`en_us` 总会作为回退语言加入。
- `profile`：`data`、`images` 或 `all`。
- `sizes`：会保留用户增加的尺寸，同时始终包含需求规定的 32、128、256。
- `tickBudgetMs`：每个客户端 tick 的软时间预算，范围 1–100 ms。
- `exit`：仅自动模式生效，ZIP 成功生成后关闭客户端；打包失败时保留客户端与暂存目录，便于排查。

若要做到完全无人值守，可再使用 Minecraft 1.20.1 的游戏参数：

```text
--quickPlaySingleplayer ExportWorld
```

它会打开名为 `ExportWorld` 的测试世界，导出器随后自动工作并按 `mcmods.export.exit` 退出。渲染依赖 OpenGL，因此不能在纯服务端或没有图形上下文的 headless 环境运行。

## i18n 自动汉化更新兼容方式

语言导出不是只读取 Mod jar 中的静态文件，而是扫描目标命名空间当前资源栈内的全部 `lang/*.json`，再按照 Minecraft 的低到高优先级逐层合并每种语言。因此 Mod 自带的中文、英语、俄语等语言会全部自动输出，i18n Mod 下载并加载的汉化资源包也会覆盖原始翻译。每个注册对象同时带有：

```json
{
  "id": "examplemod:copper_machine",
  "translation_key": "block.examplemod.copper_machine",
  "names": {
    "zh_cn": "铜制机器",
    "en_us": "Copper Machine"
  }
}
```

`translations/<locale>.json` 只保存网站实际需要的最终翻译映射，不保留资源包内部信息。

## ZIP 内目录

```text
manifest.json
registries/*.json
translations/<locale>.json
tags/tags.json
ingredients/ingredients.json
ingredients/<ingredient type>/<size>/<namespace>/<id>--<hash>.png
recipes/recipes.json
recipes/jei/layouts/<category namespace>/<category>/<recipe>.json
recipes/jei/backgrounds/<category namespace>/<category>.png
advancements/advancements.json
advancements/localized/<locale>.json
icons/items/<size>/<namespace>/<id>.png
icons/blocks/<size>/<namespace>/<id>.png
potions/<form>/<size>/<namespace>/<id>.png
entities/renders/<size>/<namespace>/<id>.png
entities/models/<namespace>/<id>.obj
entities/models/<namespace>/<id>.json
assets/<namespace>/blockstates/...
assets/<namespace>/models/...
assets/<namespace>/textures/...
resources/index.json
```

所有 JSON 使用 UTF-8、稳定字段名和显式 `schema_version`。核心 Schema 位于 `schema/`，完整语义见 [docs/export-format-v1.md](docs/export-format-v1.md)。`manifest.json` 记录各阶段计数和逐对象错误；某个第三方对象渲染失败不会中止整个导出。

## 实体模型边界

OBJ 是实体在 idle 姿态下的基础 `EntityModel` 静态快照，含顶点、四边形、UV 和法线。代码动画、骨骼约束、装备、发光层、粒子与第三方自定义渲染管线无法无损放进 OBJ，因此：

- PNG 使用实体真实 renderer，包含能正常绘制的渲染层；
- JSON 记录网站渲染需要的模型能力和纹理引用，不保存实现类名；
- OBJ 明确标记 `base_model_only=true`，不会假装是可编辑的完整动画工程；
- 无法实例化或不是 `LivingEntity` 的对象会记入 manifest 的跳过/错误统计。

## 配方 GUI 策略

安装 JEI 后，`jei_recipe_gui` 阶段通过 JEI 公共 API 获取全部 RecipeType、隐藏配方和类别 drawable。这里严格区分两个 ID：

- `recipe_type_id`：合成方式/类别 ID，例如某台机器的合成类别；它唯一对应 `recipes/jei/backgrounds/...png`；
- `recipe_id`：某一条具体配方 ID；它只对应 `recipes/jei/layouts/...json`。

类别背景取该 RecipeType 的第一条可导出配方创建 JEI layout，再给全部槽位设置空显示覆盖后渲染。因此背景保留空槽位、机器 GUI、箭头、能量条等类别视觉，但不包含任何输入或输出物品。`layout.json` 保存每条配方的 `rect`、`visual_rect`、角色、数量、Tag 与全部轮换候选材料。网页显示同一个类别背景，再按坐标叠加 `icons/items`、`icons/blocks` 或其他 Ingredient 图标。

## 构建

```powershell
$env:JAVA_HOME='C:\path\to\jdk-17'
./gradlew.bat clean build
```

产物：

- `fabric/build/libs/mcmods_exporter-0.5.0.jar`
- `forge/build/libs/mcmods_exporter-0.5.0.jar`

## 后续版本适配

公共的配置、任务调度、Schema、错误隔离、OBJ 写入和大部分导出规则位于 `common`。加载器入口只负责命令与 tick。升级 Minecraft 时，优先只调整以下 API 边界：

- `render/OffscreenRenderer.java`：RenderTarget、GuiGraphics、投影矩阵；
- `stage/JeiRecipeGuiStage.java`：JEI 类别空白背景与配方槽位 API；
- `stage/RecipeDataStage.java`、`AdvancementStage.java`：新版 RecipeHolder/AdvancementHolder；
- `stage/RegistryDataStage.java`：数据驱动附魔和新版组件系统；
- Fabric/Forge 子模块的事件和元数据。

导出 Schema 采用独立版本号，不跟随 Minecraft 版本；网站导入器无需因为 Minecraft 升级而同步重写。详细清单见 [docs/version-porting.md](docs/version-porting.md)。

## 参考项目与许可证说明

架构和技术调研参考了 RecipeRenderer、IconExporter、resoureexporter 和 WikiZoomer。此工程为独立实现，没有复制无许可证项目的源码；参考项目的许可证与具体影响记录在 [NOTICE.md](NOTICE.md)。本工程使用 MIT 许可证。
