# JEI 通用模板导出格式 v1

本文档描述 `mcmods_exporter 0.8.0` 的 JEI 配方 GUI 导出方式。该格式把配方类别、空白 GUI、槽位几何和具体配方材料拆开，使网站能够用同一模板渲染多条配方，并让 GUI 上的物品保持可点击。

## 1. 数据分层

导出数据分为四层：

1. 类别索引：记录 `recipe_type_id`、本地化标题、Recipe Catalyst、模板集合及配方集合路径。
2. 通用模板：记录空白背景、画布尺寸和全部可容纳槽位的坐标，不包含任何材料。
3. 配方绑定：记录具体配方使用哪个 `template_id`，以及每个 `slot_id` 应显示的材料候选。
4. 图像资源：每个模板对应一张独立的高分辨率空白背景 PNG。

文件结构：

```text
recipes/jei/categories.json
recipes/jei/templates/<namespace>/<recipe_type_path>.json
recipes/jei/recipes/<namespace>/<recipe_type_path>.json
recipes/jei/backgrounds/<namespace>/<recipe_type_path>/<template_id>.png
```

对应 Schema：

- `schema/jei-categories.schema.json`
- `schema/jei-templates.schema.json`
- `schema/jei-recipes.schema.json`

旧的 `recipes/jei/layouts/**` 和 `schema/jei-layout.schema.json` 不再导出或维护。

## 2. ID 与作用域

### `recipe_type_id`

JEI 合成类别 ID，例如 `minecraft:crafting`。它表示“使用哪一种合成方式”，不是某一条具体配方。一种合成方式可以有多个模板。

### `template_id`

模板几何指纹，格式为 `template_<16 位十六进制>`。指纹只由排序后的槽位角色、名称、坐标、尺寸和完整槽位数量生成，不包含具体材料，因此材料变化不会产生新模板。

`template_id` 的业务作用域是 `recipe_type_id`。网站和数据库必须使用：

```text
(recipe_type_id, template_id)
```

作为模板唯一键，不应假设 `template_id` 单独全局唯一。

如果两个模板的背景像素碰巧相同，但 `template_id` 不同，导出器仍会分别输出两张背景图，确保一个背景文件只属于一个模板，避免网站导入时建立额外的多对多关系。

### `recipe_id` 与 `recipe_key`

- `recipe_id` 是原版或 JEI 提供的配方 ID；无法取得权威 ID 时会生成包内 ID。
- `recipe_key` 在一次导出中唯一，用于处理 JEI 返回重复 `recipe_id` 的情况。
- `recipe_id_source` 表示 ID 来自 `minecraft_recipe`、`jei_category` 或 `generated_index`。
- `recipe_id_canonical` 表示该 ID 是否适合作为跨导出包的稳定业务 ID。

## 3. 类别索引

`recipes/jei/categories.json` 使用 `mcmods-jei-categories/v5`。顶层包含：

- `categories[]`：每种合成方式的标题、尺寸、Catalyst、模板数和导出配方数。
- `recipes[]`：配方快速索引，包含配方身份、规范化的有序/无序信息、模板与背景引用。

类别中的重要字段：

```json
{
  "recipe_type_id": "minecraft:crafting",
  "template_collection": "recipes/jei/templates/minecraft/crafting.json",
  "recipe_collection": "recipes/jei/recipes/minecraft/crafting.json",
  "template_count": 1,
  "background_count": 1,
  "recipe_count": 1200,
  "exported_recipe_count": 1200,
  "image_scale": 4,
  "catalysts": []
}
```

`catalysts` 来自 JEI 正式注册的 Recipe Catalyst，用于表示打开或执行这种合成方式所需的工作台、机器或其他入口。不得通过类别标题或背景图片猜测 Catalyst。

## 4. 模板集合

每个 `recipe_type_id` 有一个模板集合文件，Schema 为 `mcmods-jei-template-collection/v1`：

```json
{
  "schema_version": "mcmods-jei-template-collection/v1",
  "recipe_type_id": "minecraft:crafting",
  "coordinate_space": "logical_pixels",
  "image_scale": 4,
  "canvas": { "x": 0, "y": 0, "width": 140, "height": 80 },
  "image_pixels": { "x": 0, "y": 0, "width": 560, "height": 320 },
  "content": { "x": 8, "y": 8, "width": 124, "height": 64 },
  "template_count": 1,
  "templates": []
}
```

- `canvas`、`content` 和槽位坐标使用 JEI 逻辑像素。
- `image_pixels` 是实际 PNG 像素尺寸。
- 当前 `image_scale` 为 `4`，网页应按 `canvas` 尺寸显示背景，而不是直接使用 PNG 的物理像素尺寸。

单个模板结构：

```json
{
  "schema_version": "mcmods-jei-layout-template/v1",
  "template_id": "template_0123456789abcdef",
  "background": "recipes/jei/backgrounds/minecraft/crafting/template_0123456789abcdef.png",
  "background_contains_ingredients": false,
  "slot_count": 10,
  "slots": [
    {
      "slot_id": "input_0",
      "role": "input",
      "coordinates_available": true,
      "rect": { "x": 18, "y": 18, "width": 18, "height": 18 },
      "visual_rect": { "x": 18, "y": 18, "width": 18, "height": 18 }
    }
  ]
}
```

`slot_id` 按角色和屏幕位置稳定编号，例如 `input_0`、`input_1`、`output_0`。网站不得重新按照数组下标或材料数量生成槽位 ID。

槽位角色可能为：

- `input`：输入材料；
- `output`：输出材料；
- `catalyst`：配方内部催化剂槽位，与类别级 Recipe Catalyst 不同；
- `render_only`：只用于展示的数据。

`rect` 是交互点击区域，`visual_rect` 是图标实际绘制区域。网页定位图标优先使用 `visual_rect`，建立点击热区时使用 `rect`。`coordinates_available=false` 时不得猜测坐标。

## 5. 配方绑定集合

每个 `recipe_type_id` 有一个绑定集合文件，Schema 为 `mcmods-jei-recipe-collection/v1`：

```json
{
  "schema_version": "mcmods-jei-recipe-collection/v1",
  "recipe_type_id": "minecraft:crafting",
  "template_collection": "recipes/jei/templates/minecraft/crafting.json",
  "count": 1200,
  "recipes": []
}
```

单条配方只引用模板并绑定材料，不重复保存坐标：

```json
{
  "schema_version": "mcmods-jei-recipe-bindings/v1",
  "recipe_type_id": "minecraft:crafting",
  "recipe_id": "minecraft:stick",
  "recipe_id_source": "minecraft_recipe",
  "recipe_id_canonical": true,
  "recipe_key": "minecraft:stick#000001",
  "template_id": "template_0123456789abcdef",
  "render_locale": "zh_cn",
  "layout_kind": "shaped",
  "ordered": true,
  "layout_classification_source": "recipe_class",
  "parameter_count": 0,
  "parameters": {},
  "binding_count": 10,
  "bindings": [
    {
      "slot_id": "input_0",
      "ingredient_present": true,
      "clickable": true,
      "alternatives": [
        { "type": "minecraft:item_stack", "item": "minecraft:oak_planks", "count": 1 }
      ]
    },
    {
      "slot_id": "input_1",
      "ingredient_present": false,
      "clickable": false,
      "placeholder_item": "minecraft:air",
      "alternatives": []
    }
  ]
}
```

`alternatives` 保存同一槽位可轮换展示的全部 Ingredient。网站可以按时间轮换，也可以展开成材料列表。可识别为物品 Tag 时，绑定还可能带有 `item_tag_equivalent`。

## 6. 固定容量与动态容量

模板是否复用只看完整槽位结构，不看本配方实际用了几个槽位：

- 工作台始终容纳 3×3 输入槽和一个输出槽。木棍只使用两个输入槽，木门使用六个输入槽，但其余位置以 `minecraft:air` 绑定，因此二者共用一个模板。
- 机械动力 Mechanical Crafter 等动态布局可能因配方形状或机器容量产生不同的槽位坐标和数量，因此同一个 `recipe_type_id` 可以对应多个 `template_id`。
- 材料、数量、NBT 或 Tag 变化只改变 `bindings`，不会改变模板。

## 7. 空白背景规则

导出器先让 JEI 创建真实配方布局，再把每个槽位的显示内容覆盖为空，最后以 4 倍分辨率绘制背景。因此背景保留：

- GUI 面板和槽位框；
- 箭头、进度条、能量条和机器装饰；
- 由配方布局决定的动态槽位位置；

但不包含输入、输出或催化材料图标。`background_contains_ingredients` 固定为 `false`。

## 8. 网站导入与渲染流程

建议导入顺序：

1. 读取 `categories.json`，以 `recipe_type_id` 建立类别。
2. 读取类别的模板集合，以 `(recipe_type_id, template_id)` 建立模板记录。
3. 把背景 PNG 存入对象存储或静态资源系统，数据库只保存资源引用和尺寸。
4. 读取配方集合，以 `recipe_key` 保存导出包内唯一配方记录。
5. 用 `(recipe_type_id, template_id, slot_id)` 校验每个绑定；引用不存在时应拒绝该配方或记录导入错误。
6. 网页按逻辑像素显示背景，再在对应槽位上叠加已导出的物品、流体或自定义 Ingredient 图标。

渲染伪代码：

```ts
const template = templates.get(`${recipe.recipe_type_id}:${recipe.template_id}`)

for (const binding of recipe.bindings) {
  if (!binding.ingredient_present) continue
  const slot = template.slotsById.get(binding.slot_id)
  const rect = slot.visual_rect ?? slot.rect
  drawIngredient(binding.alternatives[currentFrame], rect)
  registerClickableArea(slot.rect, binding.alternatives)
}
```

可合并模板的检查只能作为管理工具提示，不能在导入时自动合并。只有确认槽位角色、名称、坐标、尺寸、背景语义和机器交互完全等价后，网站侧才可建立人工别名；原始 `(recipe_type_id, template_id)` 必须保留，以便重现导出结果和处理后续版本差异。
