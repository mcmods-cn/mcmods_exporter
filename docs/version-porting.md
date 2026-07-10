# 新 Minecraft 版本适配清单

1. 新建版本分支或版本构建目录，更新 Minecraft、映射、Fabric/Forge/NeoForge 与 Architectury 版本。
2. 先只编译 `common`，按编译错误处理 Recipe/Advancement holder、注册表和 ItemStack 组件变化。
3. 更新 `OffscreenRenderer` 的 framebuffer、投影、GuiGraphics 和截图 API，并用石头、附魔物品、透明物品各做一次人工对照。
4. 更新实体创建、LivingEntityRenderer/EntityModel 访问和 OBJ 顶点消费者；确认普通生物、幼体、透明层生物各一个。
5. 更新 JEI 公共 API 版本；若新版 JEI 尚未发布，JEI stage 不加入任务列表，也不生成可能误导网站的猜测式 GUI。
6. 更新 Fabric/Forge/NeoForge 客户端命令和 tick 事件。公共层不注册服务端网络包。
7. 运行 `build`，再用只含原版、一个内容 Mod、一个带自定义机器配方 Mod 的三套实例做导出。
8. 用 `schema/*.schema.json` 校验 manifest、注册表、配方和 tag 文件，并比较上一 Minecraft 版本的字段集合。

不得因映射名变化修改网站字段名；需要表达新版能力时，新增可选字段或提升 Schema 主版本。
