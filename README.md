# MC百科数据导出器

此 Minecraft 客户端 Mod 读取当前世界的注册表、配方、语言和生效资源，用游戏渲染器与 JEI 生成图片和布局，最终在游戏机上写出 ZIP。网站前端负责预检和上传，后端根据项目、版本及权限创建导入修订，公开展示遵循审核流程。[前端](https://github.com/mcmods-cn/mcmods-cn-frontend) · [后端](https://github.com/mcmods-cn/mcmods-cn-backend)

## 安装与导出

使用 **Java 17、Minecraft 1.20.1**，安装对应加载器的发布 JAR、Architectury API、JEI，Fabric 另需 Fabric API。构建目标为 Fabric Loader 0.16.14、Fabric API 0.92.9、Forge 47.4.21、Architectury 9.2.14、JEI 15.20.0.133。完整版本在 [gradle.properties](gradle.properties)。

进入世界，等到配方、标签和 JEI 可用后执行 `/mcmods_export <目标MODID> all`。可用 `/mcmods_export status` 查看进度，`/mcmods_export cancel` 取消。默认产物在游戏目录的 `mcmods-export/`，多命名空间全量模式供离线使用。参数、模块和输出目录见[使用说明](README.zh-CN.md)。

## 构建与验证

在安装 JDK 17 的环境中使用仓库的 Gradle 8.8 wrapper。Architectury Gradle 插件与 Loom 固定在根 `build.gradle`，升级时同时验证两个加载器。首次构建需要访问 Minecraft、Fabric、Forge、Architectury、JEI 以及 Gradle 的依赖仓库。

```sh
./gradlew :common:test build
python -m pip install -r tools/requirements-contracts.txt
python tools/check_export_contracts.py
```

Windows 使用 `gradlew.bat :common:test build`。安装 `fabric/build/libs/` 或 `forge/build/libs/` 中与加载器匹配的发布 JAR。协议校验支持 Python 3.12，默认读取合成样例。向 `--export-root` 提供保留原始 SHA256 的真实全模块导出解包目录，可对照游戏输出。[验证范围与历史样例](docs/contract-validation.md)

从源码启动客户端时，需要将对应加载器的 JEI 完整实现加入开发运行环境。先使用独立单人世界和目标模组生成 ZIP，再以同一文件核验后端解码与网站导入。[开发客户端、诊断与回滚](docs/contract-validation.md#producing-a-package-from-a-checkout)

## 维护入口

| 工作 | 入口 |
| --- | --- |
| 接入客户端事件 | `fabric/`、`forge/` 注册命令，在客户端 tick 结束时调用 `ExportBootstrap.tick`，关闭时交给 `ExportCoordinator.clientStopping` 取消活动任务 |
| 添加导出模块 | 在 common 的 `stage/` 实现阶段，在 `task/ExportPipeline` 定义 profile、选择条件、执行顺序。更新对应 schema 与 fixture，并检验网站消费者 |
| 修改 JEI 展示 | `JeiRecipeGuiStage`、`RecipeLayoutNormalizer`、`JeiSlotSemantics`。[类别、模板、绑定与概率](docs/jei-template-export-v1.md) |
| 输出与错误恢复 | `ExportJob`、`ExportReport`、`ExportArchives`。[manifest 与网站接收范围](docs/export-format-v1.md) |
| 世界生成 | `WorldgenDataStage` 汇总顶层结构与放置规则；内部拼图模板只计数。[结构 v2](docs/worldgen-structure-export-v2.md) |

游戏注册表、资源栈和 JEI 在客户端阶段读取，离屏绘图依赖有效的 OpenGL 上下文。远程服务器上的世界生成数据可能降级为已安装模组的数据。检查 manifest 的阶段与错误列表，并查阅能力声明。生成 ZIP 失败或主动取消时保留暂存目录，修复原因后重新导出。构建无法解析插件时先核实上游仓库可达性与本机 JDK 的 CA 信任配置。
