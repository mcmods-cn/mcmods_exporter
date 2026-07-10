# mcmods-export/v1

最终产物是 `<Mod ID>-<时间戳>.zip`，ZIP 根目录的 `manifest.json` 是导出包入口。多个显式目标 ID 用 `+` 连接；全量导出使用 `all-mods`。导入端必须先检查 `schema_version`、`status` 和 `package_format`；只有 `status=complete` 才应自动入库。阶段失败不一定代表整个包不可用，应结合 `stages[].status` 与 `errors[]` 决定是否接收部分数据。

## ID 与路径

- 所有注册对象使用完整的 `namespace:path`。
- JSON 内始终使用 `/`；磁盘路径保留 ID 的子目录结构。
- PNG 路径由类别、尺寸、命名空间和 ID 路径直接组成，不需要转义冒号。
- 只保留网页直接使用的生效客户端资源：`assets/<namespace>/blockstates`、`models` 与 `textures`。配方、进度、Tag 和语言只保留已经解析、合并、规范化后的 JSON，不重复保存原始 data/lang 文件。

## 多语言

`translation_key` 是稳定键，`names` 是目标 Mod 当前资源栈内全部已发现语言的解析快照。启动参数指定的语言与自动发现语言取并集；缺少某翻译键时不写空字符串。`translations/languages.json` 是本次实际语言索引，网站可用 `translations/<locale>.json` 补全其他组件中的翻译键。

进度/成就除 `advancements.json` 内的 `title_names`、`description_names` 外，还按语言拆分为 `advancements/localized/<locale>.json`，方便网站直接按 locale 导入。

## 配方

`ingredients` 中每个元素是一格输入；`alternatives` 是 tag 或复合 Ingredient 展开后的可选 ItemStack。`nbt_snbt` 仅在存在 NBT 时出现。导出器在游戏中完成展开和清洗，网站不应再依赖原始配方 JSON。

JEI GUI 尺寸由类别决定，按逻辑尺寸 2 倍输出，并在 `recipes/jei/categories.json` 记录逻辑宽高。未安装 JEI 时不生成猜测式或烘焙物品的 GUI 图片。

JEI 使用“合成方式背景”和“具体配方数据”分离的结构：

- `recipe_type_id` 是合成方式 ID，每个 ID 只对应一张 `recipes/jei/backgrounds/...png`；背景中的 JEI 槽位为空，不烘焙物品；
- `recipe_id` 是具体配方 ID，每条配方对应一个 `recipes/jei/layouts/...json`；
- `canvas`、`content` 和所有槽位矩形使用 `logical_pixels`；PNG 的实际像素倍率由 `image_scale` 指定；
- `slots[].role` 是 `input`、`output`、`catalyst` 或 `render_only`；
- `rect` 是可点击材料区域，`visual_rect` 包含槽位自定义背景；
- `alternatives` 包含轮换展示的所有 JEI Ingredient，而不只是截图瞬间显示的一个物品；
- `background_contains_ingredients=false` 可供导入端校验背景没有烘焙物品。

网站把类别背景的 CSS 尺寸设为 `canvas.width × canvas.height`，再按 `slots[].rect` 叠加物品或其他 Ingredient 图片与数量文字。多个具体配方共享同一个 `recipe_type_id` 背景，仅叠加数据不同。

## 流体与非物品 Ingredient

`registries/fluids.json` 保存 Minecraft 流体注册表中已经注册的流体、源状态、默认量和可用的桶物品。`ingredients/ingredients.json` 则使用 JEI 的通用 Ingredient API 枚举全部非物品类型，不绑定某个加载器或某个工业 Mod 的 Java 类。因此只要模组向 JEI 注册了介质类型，就可以统一覆盖 Forge/Fabric 流体及 Mekanism 的气体、灌注类型、颜料、浆液等内容。

每条介质记录已经规范化为 `ingredient_type`、`resource_location`、用于配方判等的 `unique_id`、可选 `amount`、`tags`、多语言 `names` 和透明 PNG `icons`。图标文件名附带 `unique_id` 的短哈希，以避免同一注册 ID 的不同组件或 NBT 状态互相覆盖。未安装 JEI 时仍会导出流体注册表和原始生效贴图，但不会猜测第三方介质的私有注册系统。

## 实体

三个 PNG 目录分别是 32×32、128×128、256×256。物品和实体 PNG 保留 Alpha 透明通道；实体采用水平和垂直均有偏角的斜视角，以显示体积。OBJ 的一个 `f` 对应 Minecraft ModelPart 的一个四边形；顶点、UV、法线索引一一对应。实体 JSON 的 `base_model_only` 用于提醒导入端不要把它标记成带骨骼动画的完整模型。

## 资源整理

`resources/index.json` 只索引当前客户端资源栈最终生效的 blockstate、模型和贴图，并直接给出 ZIP 内路径。来源包、实现类、异常堆栈、原始语言/data 文件等网站不需要或可由规范化结果替代的内容不会进入最终包。连接远程服务器时，仍导出客户端同步后可见的规范化配方、进度和 Tag。

## 向后兼容

- 同一 Schema 主版本内只能新增可选字段。
- 删除、改名或改变字段语义时提升 `/vN`。
- 导入端应忽略未知字段。
- Minecraft 版本和加载器版本只属于 manifest 元数据，不改变 Schema 版本。
