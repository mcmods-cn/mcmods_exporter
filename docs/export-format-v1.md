# mcmods-export/v1

最终产物是 `<Mod ID>-<时间戳>.zip`，ZIP 根目录的 `manifest.json` 是导出包入口。多个显式目标 ID 用 `+` 连接；全量导出使用 `all-mods`。导入端必须先检查 `schema_version`、`status` 和 `package_format`；只有 `status=complete` 才应自动入库。阶段失败不一定代表整个包不可用，应结合 `stages[].status` 与 `errors[]` 决定是否接收部分数据。

## ID 与路径

- 所有注册对象使用完整的 `namespace:path`。
- JSON 内始终使用 `/`；磁盘路径保留 ID 的子目录结构。
- PNG 路径由类别、尺寸、命名空间和 ID 路径直接组成，不需要转义冒号。
- 客户端资源只保留网页直接使用的生效 blockstate、模型与贴图。配方、进度、Tag 和语言只保留已经解析、合并、规范化后的 JSON；世界生成与战利品表保留规范化索引和必要的生效原始 JSON，结构从 `mcmods-structures/v2` 起只保留顶层注册结构，不再复制内部 NBT 零件。

## 多语言

`translation_key` 是稳定键，`names` 是目标 Mod 当前资源栈内全部已发现语言的解析快照。启动参数指定的语言与自动发现语言取并集；缺少某翻译键时不写空字符串。`translations/languages.json` 是本次实际语言索引，网站可用 `translations/<locale>.json` 补全其他组件中的翻译键。物品的默认普通提示文本已经按语言写入 `registries/items.json` 的 `tooltips.<locale>[]`，网站不应再次启动 Minecraft 生成 Tooltip。

进度/成就除 `advancements.json` 内的 `title_names`、`description_names` 外，还按语言拆分为 `advancements/localized/<locale>.json`，方便网站直接按 locale 导入。

## 物品、工具与方块属性

`registries/items.json` 中的属性来自物品默认 `ItemStack`，已经规范化，网站不应再根据 Java 类名猜测类型：

- 基础字段：`max_stack_size`、`max_damage`、`rarity`、`edible`、`fire_resistant`、`use_animation`、`use_duration_ticks`、`crafting_remainder`；
- `durability`：是否可损坏以及最大耐久；
- `item_types` 与 `primary_type`：剑、弓、弩、镐、斧、锹、锄、剪刀、三叉戟、钓鱼竿、盾牌、盔甲、鞘翅、方块物品、食物等规范类型。除原版基类外，也会从 Forge/NeoForge/Fabric 通用工具 Tag 补充模组工具类型；
- `tool`：工具种类、等级 ID（能确定时）、数值挖掘等级、耐久、挖掘速度、攻击加成、附魔能力和已经展开的修复材料；
- `enchanting`：默认物品能否附魔、附魔能力值，以及逐个调用生效附魔判定得到的 `compatible_enchantments`；
- `attribute_modifiers`：按装备槽输出全部属性、UUID、数值与运算类型。`combat.attack_damage` 和 `combat.attack_speed` 是把主手修饰器应用到默认玩家基础值后得到的网页展示值，同时保留不含玩家基础值的 `*_modifier`；
- `armor`：部位、材料、防御、韧性、击退抗性、附魔能力和修复材料；
- `food`：营养值、饱和度修饰值、肉类/始终可食用/快速食用标记，以及带概率的状态效果；
- `item_tags`：保留运行时最终 Tag，供网站展示自定义类别和无法标准化的模组能力。

`registries/blocks.json` 额外包含 `hardness`、`explosion_resistance`、摩擦/速度/跳跃系数、亮度、活塞反应、战利品表和默认状态的物理标记。挖掘要求同时输出：

- `requires_correct_tool`：是否需要正确工具才能掉落；
- `preferred_tools` 与 `mining_tags`：由 `mineable/*` 等运行时 Tag 规范化出的工具类别和原始依据；
- `required_tier`、`required_mining_level` 与 `tier_tags`：原版等级会输出稳定数字；第三方自定义等级无法可靠映射数字时，只输出等级名和原始 Tag，禁止网站自行猜测；
- `block_tags`：该方块默认状态的全部最终 Tag。

硬度 `-1` 表示不可破坏。工具攻击字段、`combat` 字段和装备属性修饰器含义不同，导入端必须分别保存，不能叠加后覆盖原始值。

`registries/creative_tabs.json` 保存目标 Mod 自建标签页，以及被加入原版/其他 Mod 标签页的目标物品；外部标签页只保留目标物品，避免把整个原版创造物品栏重复导出。`block_entity_types.json` 与 `machines.json` 已将方块、方块实体类型、菜单标题整理为可入库关系。独立 `menu_types.json` 只表示注册 ID，因为 Minecraft 没有稳定公开“MenuType 属于哪个方块”的关系；机器与合成方式应使用 `machines.json` 和 JEI `catalysts` 关联，禁止按 Java 类名猜测。

## 配方

`ingredients` 中每个元素是一格输入；`alternatives` 是 tag 或复合 Ingredient 展开后的可选 ItemStack。`nbt_snbt` 仅在存在 NBT 时出现。导出器在游戏中完成展开和清洗，网站不应再依赖原始配方 JSON。

基础配方与 JEI 配方索引/布局统一输出 `layout_kind`、`ordered` 和 `layout_classification_source`。`layout_kind=shaped` 表示有序工作台配方，`shapeless` 表示无序工作台配方，`not_applicable` 表示熔炉或模组机器等不适用“有序/无序”语义的配方，`unknown` 表示该对象属于工作台配方但无法可靠判断，或 JEI 没有提供 Minecraft `Recipe` 对象。只有 `shaped`/`shapeless` 的 `ordered` 分别为 `true`/`false`，其余情况显式输出 `null`；`layout_classification_source` 说明判断来自配方类、serializer、配方类型或数据不可用。有序配方继续额外输出 `width`、`height`。网站必须保存未知值，不得根据 Java 类名、JEI 槽位坐标或 serializer 名称片段猜测。

JEI GUI 尺寸由类别决定，默认按逻辑尺寸 4 倍输出，以减少网站放大显示时的模糊；`recipes/jei/categories.json` 同时记录逻辑宽高和 `image_scale`。JEI 是强制客户端依赖；若某个低版本 JEI 缺少布局 API，只将对应能力标记为不可用，不生成猜测式或烘焙物品的 GUI 图片。

JEI 使用“合成方式背景、通用槽位模板、具体配方绑定”三层分离结构；完整规范和前端流程见 [`jei-template-export-v1.md`](jei-template-export-v1.md)：

- `recipe_type_id` 是合成方式 ID，可以对应多个 `template_id`；每个模板严格对应一张独立的 `recipes/jei/backgrounds/<namespace>/<type>/<template_id>.png`，背景不烘焙物品；
- 每个 `recipe_type_id` 生成一个 `recipes/jei/templates/<namespace>/<type>.json` 通用模板集合和一个 `recipes/jei/recipes/<namespace>/<type>.json` 配方绑定集合；
- `recipe_id` 是具体配方 ID；具体配方使用 `template_id` 引用模板，并通过 `bindings[].slot_id` 绑定物品，不再重复保存坐标；
- `recipe_id_source` 说明 ID 来源：`minecraft_recipe` 是原版 `Recipe` ID，`jei_category` 是 JEI 类别通过 `getRegistryName` 提供的权威 ID，`generated_index` 表示第三方类别没有注册 ID；
- 每个布局都保证存在 `recipe_id`。只有 `recipe_id_canonical=true` 时才能把它当作跨次导出稳定主键；`generated_index` 只用于本次导出内关联，网站应另用布局内容指纹做去重；
- 普通配方和 JEI 绑定都输出 `source_mod_id`、`source_mod_version`、`source_mod_id_source`。权威配方以 `recipe_id` 命名空间作为来源；只有没有权威 ID 的 JEI 动态条目才回退到类别命名空间；
- 指定 Mod 导出时只保留其权威 `recipe_id` 命名空间下的配方。随后按 `(recipe_id, underlying_recipe_type_id)` 去掉已有原生类别的跨类别兼容投影，避免兼容机器复制工作台全部配方；排除统计在 `categories.json.filter_summary`；
- `canvas`、`content` 和模板中的所有槽位矩形使用 `logical_pixels`；PNG 的实际像素倍率由 `image_scale` 指定；
- `templates[].slots[].role` 是 `input`、`output`、`catalyst` 或 `render_only`；
- `rect` 是可点击材料区域，`visual_rect` 包含槽位自定义背景；
- `bindings[].alternatives` 包含轮换展示的所有 JEI Ingredient，而不只是截图瞬间显示的一个物品；
- 概率从 JEI 最终 tooltip 或类别直接绘制的百分比统一提取到绑定的 `chance*` 字段，并从共享背景中剥离；不依赖特定 Mod 的私有配方 API。非主输出只有在名称/tooltip 明示副产品或取得小于 100% 的概率时才标为 `byproduct`；
- `background_contains_ingredients=false` 可供导入端校验背景没有烘焙物品。
- `template_id` 是完整可容纳槽位的几何指纹；实际使用物品或空缺位置改变会复用模板，只有可用槽位的坐标、角色、尺寸、名称或数量改变时才会在同一模板文件内新增变体；空槽绑定显式使用 `placeholder_item=minecraft:air`，但不计入输入；
- `parameters` 是在严格白名单内从配方对象预处理出的能量、时间、温度、压力、几率等标量。没有公开参数时是空对象，网站不得从标题或类名推断。
- 物品候选只保存配方自身需要的稳定身份、数量和 NBT，不重复嵌入完整翻译表；导入端应通过注册表或 `ingredients/ingredients.json` 解析名称和图标。

导出器已经完成布局去重、稳定槽位编号和 Ingredient 绑定，导入端不得重新按坐标猜测或精简布局。网站只需按 `recipe_type_id + template_id + slot_id` 建立关系，并在写入版本时计算业务语义指纹、输入/输出/催化剂关联、槽位 Tag 和版本统计快照。跨模组物品来源会随其他模组的活跃版本变化，不应固化在导出包中，应由网站批量解析并缓存。

`recipes/jei/categories.json` 中每个类别还包含类别级 `catalysts`。它来自 JEI 为该 `RecipeType` 正式注册的 Recipe Catalyst，表示可打开、执行或关联这个合成方式的工作站，例如工作台、熔炉或模组机器方块。每项使用与配方槽相同的规范 Ingredient 结构，包含 `type`、`resource_location`、`unique_id`，物品型 Catalyst 还包含 `item`、NBT、多语言名称和数量。`catalyst_count` 是去重后的数量；同一合成方式可以对应多个机器。

类别级 `catalysts` 与布局中的 `slots[].role=catalyst` 含义不同：前者是整个 `recipe_type_id` 的工作站，后者只是某条配方画布中的催化剂材料槽。若第三方模组没有向 JEI 注册 Recipe Catalyst，则类别输出空数组，导出器不会根据类别名称或背景猜测机器。

网站把模板背景的 CSS 尺寸设为 `canvas.width × canvas.height`，再用配方 `template_id` 找到对应背景和模板，通过 `bindings[].slot_id` 取得 `slots[].rect` 并叠加物品或其他 Ingredient 图标与数量文字。固定容量类别的配方共享模板；动态容量类别按模板分别使用背景。

## 流体与非物品 Ingredient

`registries/fluids.json` 只保存 Minecraft 流体注册表中的源流体，不再把流动变体当成独立流体类型。名称优先使用 `fluid.<namespace>.<path>`，只把桶 ID 当作可选关联字段，绝不再用“某某桶”作为流体显示名。

`ingredients/ingredients.json` 使用 JEI 的流体 Ingredient renderer 输出流体本身的纹理，不渲染桶。类型和条目都带 `ingredient_kind=fluid`，网站应以 `resource_location` 和 `registries/fluids.json` 关联，并直接使用其多语言 `names` 与 32/128/256 透明 `icons`。图标文件名附带 `unique_id` 的短哈希，以免同一流体的不同组件状态互相覆盖。

Mekanism 1.20.1 的四套 ChemicalStack 全部保留，但必须分开入库：`mekanism_gas`、`mekanism_infuse_type`、`mekanism_pigment`、`mekanism_slurry`。类型和条目同时输出 `chemical_family=mekanism`，以及 `chemical_type=gas/infuse_type/pigment/slurry`。网站不得把四类合并成一个无类型的“化学品”列表，也不需要根据 Java 类名猜测。

## 状态效果

导出器只把 `MobEffect` 作为百科状态效果导出到 `registries/mob_effects.json`，包含 ID、多语言名称、类别、颜色、是否即时生效和是否有益。效果自身在客户端效果纹理图集中的 Sprite 会输出为 `icons/mob_effects/<size>/<namespace>/<id>.png`，尺寸为 32、128、256 或用户额外配置的尺寸，背景保留透明 Alpha。

不再导出 `registries/potions.json`，也不生成普通药水、喷溅药水、滞留药水或药箭的瓶体渲染图。它们如果作为某条配方的真实输入或输出，仍会按普通物品出现在该配方布局中。

## 实体

三个 PNG 目录分别是 32×32、128×128、256×256。物品和实体 PNG 保留 Alpha 透明通道；实体采用水平和垂直均有偏角的斜视角，以显示体积。OBJ 的一个 `f` 对应 Minecraft ModelPart 的一个四边形；顶点、UV、法线索引一一对应。实体 JSON 的 `base_model_only` 用于提醒导入端不要把它标记成带骨骼动画的完整模型。

`registries/entity_types.json` 还包含默认实体实例可稳定取得的眼高、空气值、生物分类、最大生命、护甲、MobType、默认属性、默认装备、战利品表、刷怪蛋和繁殖材料。无法在当前世界创建实体时 `runtime_properties_available=false`，字段缺失表示未知而不是 0。`worldgen/entity_spawns.json` 以群系为单位输出刷怪权重和数量；当目标实体被注入原版群系时，会只带上该目标实体的外部群系行，不会复制原版群系的全部无关生物。

`registries/mob_effects.json` 的 `effect_attribute_modifiers` 已包含效果对属性的运算类型及 0/1 级数值。`registries/villager_trades.json` 是代码注册交易的确定性运行时样本：原始 `ItemListing` 不公开随机范围，因此 `complete=false`，网站可展示样本但不得把它当成全部随机结果。

## 方块实体模型与六面 UV

箱子、末影箱等方块不使用普通 `blockstates → models/block → textures` 模型链，而由 `BlockEntityRenderer` 在运行时绘制。它们的模型和贴图入口位于 `block_entities/index.json`，Schema 为 `mcmods-block-entity-models/v1`：

- 每个目标方块记录 `block_id`、`block_entity_type_id` 和全部模型变体；
- 箱子导出 `single`、`left`、`right` 三个变体，末影箱导出 `single`；单箱和双箱不会错误共用一套 UV；
- 每个变体同时提供 OBJ 和 `*.mesh.json`。OBJ 的 `vt` 可直接用于常规 3D 渲染；mesh JSON 把每个四边形的四个位置、UV、法线、六面方向和 `uv_bounds` 展开，网站无需再解析 Minecraft `ModelPart`；
- `uv_space=source_texture_normalized` 表示 UV 已对应导出的原始 PNG，`uv_origin=bottom_left`；箱子材质即使在游戏中经过 chest atlas，也不需要网站再次做图集换算；
- 贴图保存到 `assets/<namespace>/textures/entity/**`，索引中保留 `texture_id`、文件路径、宽高、外观变体和 atlas 来源。箱子的默认、圣诞、普通/陷阱、单箱/左右双箱材质分别记录；
- 其他 Mod 方块实体会尝试从实际运行时渲染器捕获网格和材质。若它使用材质图集，`uv_space=render_target_normalized` 且 `uv_transform_required=true`，导入端应保留该状态，不能假装它已经是原始贴图 UV。

方块实体的六面 UV 与 `icons/blocks/**` 的百科缩略图用途不同：缩略图只用于列表展示，不能反推模型或贴图坐标。数据库应保存规范化面数据和材质引用，PNG/OBJ 作为对象存储文件保存。

## 维度、自然生成、结构与战利品表

`worldgen/registries.json` 是面向网站的运行时动态注册表快照，覆盖 `dimension`、`dimension_type` 与公开的 `worldgen/*` 注册表；结构集、模板池和处理器列表会在导出器内归并或过滤，不再作为独立百科类别。网站常用的数据已经按用途拆分：

- `dimensions.json`：维度和维度类型，维度带多语言名称；
- `biomes.json`：生物群系定义与多语言名称；
- `entity_spawns.json`：目标群系完整刷怪表，以及外部群系中与目标实体有关的刷怪行；
- `natural_generation.json`：`mcmods-natural-generation/v2` 面向百科的规范化自然生成目录。以放置地物为主键，将配置地物、生成结果、替换目标、放置参数、生成阶段、运行时群系引用和 Forge/NeoForge Biome Modifier 合并成一条记录；洞穴/峡谷等配置雕刻器作为 `configured_carver` 条目。噪声、密度函数和世界预设等技术注册表不会成为公开目录条目；
- `structures.json`：`mcmods-structures/v2` 顶层结构大类目录。每项对应一个 `worldgen/structure` 注册 ID，并包含已规范化的群系、生成阶段、地形适配、起始结构池和结构集放置参数；
- `loot_tables.json`：完整战利品表、谓词与物品修饰器定义；每张表带 `category=block/chest/entity/fishing/...`，其中原版 `gameplay/fishing/**` 会规范化为 `fishing`，根节点包含 `counts_by_category`。`possible_item_ids`/`possible_item_tags` 已递归合并嵌套表的静态候选产物，但条件、动态表与函数可能改变结果，因此固定标记 `possible_items_complete=false`；
- `data_files.json`：全部原始服务端数据文件的逻辑 ID、ZIP 路径、类型和大小。

单人世界使用集成服务器的当前生效资源栈，因此数据包覆盖也会反映在导出结果中。连接远程服务器时，Minecraft 不会把完整服务端数据包下发给客户端，导出器只能回退扫描已安装 Mod 自带的 `data/`；此时 `data_files.json` 的 `resource_source=installed_mod_fallback` 且 `remote_server_data_complete=false`，网站不得把它误认为远程服务器完整数据快照。

单人世界中的标准动态注册对象还会通过 Minecraft 自身 Codec 重新编码为 `runtime_definition`。它反映注册完成后的实际对象，可保留 Fabric 等加载器通过代码注入到生物群系的自然生成配置；`definition` 则是当前资源栈中的生效原始数据文件。网站应优先展示 `runtime_definition`，并把 `definition`/原文件作为来源与下载数据。远程服务器不会提供完整动态注册对象，因此不能保证存在该字段。

`natural_generation.json` 不再要求网站解析上述原始定义。网站应以 `generation_id` 作为唯一键，直接读取 `category`、`feature_type`、`outputs`、`targets`、`placement`、`generation_steps`、`resolved_biome_ids` 和 `dimension_ids`。无法完全解析的自定义地物保留为 `normalization_status=partial`，不会被静默丢弃。完整约定见 [`worldgen-natural-generation-v2.md`](worldgen-natural-generation-v2.md)。

村庄房屋、道路、远古城市房间等 `data/<namespace>/structures/*.nbt` 属于拼图结构内部实现，不再复制进 ZIP，也不会成为百科结构条目。`structures.json` 根节点通过 `omitted_internal_template_count` 记录跳过数量；结构集被转换成各结构的 `structure_set_ids` 和 `structure_set_references`。完整约定见 [`worldgen-structure-export-v2.md`](worldgen-structure-export-v2.md)。Mod 自己打包在 `data/minecraft` 下的覆盖资源仍按来源 Mod 识别，不会因命名空间不同而漏掉顶层结构定义。

## 资源整理

`resources/index.json` 使用 `registered_content_dependency_closure`：从已注册方块、物品、实体默认纹理和方块实体运行时材质出发，递归追踪 blockstate、父模型、模型贴图、Forge/NeoForge/Fabric composite 子模型、OBJ、MTL 与贴图元数据。引用到原版或依赖 Mod 的必要父模型、实体贴图会以 `selected_namespace=false` 保留；目标命名空间中没有被注册内容引用的测试贴图、废弃模型不会导出。来源包、实现类、异常堆栈、重复原始语言文件等网站不需要的内容不会进入最终包。

`compatibility/capabilities.json` 明确记录当前包中各功能是 `available`、`degraded` 还是 `unavailable`。网站必须按能力状态导入，不能把低版本没有某 API 导致的缺失误判成“该 Mod 没有此内容”。

## 向后兼容

- 同一 Schema 主版本内只能新增可选字段。
- 删除、改名或改变字段语义时提升 `/vN`。
- 导入端应忽略未知字段。
- Minecraft 版本和加载器版本只属于 manifest 元数据，不改变 Schema 版本。
