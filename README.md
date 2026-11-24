# MC百科数据导出器

此 Minecraft 客户端 Mod 读取当前世界的注册表、配方、语言和生效资源，用游戏渲染器与 JEI 生成图片和布局，最终在游戏机上写出 ZIP。网站前端负责预检和上传，后端根据项目、版本及权限创建导入修订，公开展示遵循审核流程。[前端](https://github.com/mcmods-cn/mcmods-cn-frontend) · [后端](https://github.com/mcmods-cn/mcmods-cn-backend)

## 安装与导出

使用 **Java 17、Minecraft 1.20.1**，安装对应加载器的发布 JAR、Architectury API、JEI，Fabric 另需 Fabric API。验证使用 Fabric Loader 0.16.14、Fabric API 0.92.9+1.20.1 或 Forge 47.4.21，以及 Architectury 9.2.14、JEI 15.20.0.133。[构建版本](gradle.properties)

进入世界，等到配方、标签和 JEI 可用后执行 `/mcmods_export <目标MODID> all`。可用 `/mcmods_export status` 查看进度，`/mcmods_export cancel` 取消。默认产物在游戏目录的 `mcmods-export/`，多命名空间全量模式供离线使用。[安装、参数与输出说明](README.zh-CN.md) · [English build and validation](docs/contract-validation.md)

## 构建与验证

在安装 JDK 17 的环境中使用仓库的 Gradle 8.8 wrapper。Architectury Gradle 插件与 Loom 固定在根 `build.gradle`，升级时同时验证两个加载器。Architectury API 从官方 Maven 仓库解析。首次构建需要访问 Minecraft、Fabric、Forge、Architectury、JEI 以及 Gradle 的依赖仓库。

```sh
./gradlew --no-daemon clean :common:test :fabric:build :forge:build
python -m pip install -r tools/requirements-contracts.txt
python tools/check_export_contracts.py
```

Windows PowerShell 用 `.\gradlew.bat` 执行相同的 Gradle 参数。安装 `fabric/build/libs/` 或 `forge/build/libs/` 中与加载器匹配的发布 JAR。协议校验使用 Python 3.12，默认读取合成样例。核验真实输出时保留原 ZIP 和 SHA-256，向 `--export-root` 提供解包目录。[验证命令与历史样例](docs/contract-validation.md) · [GitHub Actions](https://github.com/mcmods-cn/mcmods_exporter/actions/workflows/contracts.yml)

源提交 `5d058d3` 在 JDK 17 上通过 77 项 JUnit 测试并构建两种发布 JAR。标准 Fabric、Forge 配置均识别了各自的 JAR。开发客户端导出的真实原包通过 54 项合成与实包检查，并在隔离网站中完成上传、独立审核和匿名展示。使用正式 JAR 进入世界会话并重新导出的验收仍待执行。[验证提交、产物哈希与验收范围](docs/contract-validation.md#validated-source-and-artifacts)

从源码启动客户端时，需要将对应加载器的 JEI 完整实现加入开发运行环境。先使用独立单人世界和目标模组生成 ZIP，再以同一文件核验后端解码与网站导入。[开发客户端、诊断与回滚](docs/contract-validation.md#producing-a-package-from-a-checkout)

## 维护入口

| 工作 | 入口 |
| --- | --- |
| 接入客户端事件 | `fabric/`、`forge/` 注册命令，在客户端 tick 结束时调用 `ExportBootstrap.tick`，关闭时交给 `ExportCoordinator.clientStopping` 收尾 |
| 添加导出模块 | 在 common 的 `stage/` 实现阶段，在 `task/ExportPipeline` 定义 profile、选择条件、执行顺序。更新对应 schema 与 fixture，并检验网站消费者 |
| 修改 JEI 展示 | `JeiRecipeGuiStage`、`RecipeLayoutNormalizer`、`JeiSlotSemantics`。[类别、模板、绑定与概率](docs/jei-template-export-v1.md) · [按类别选择导出模式](docs/recipe-type-export-policy.md) |
| 输出与错误恢复 | `ExportJob` 负责客户端生命周期，`ExportPackage` 负责后台打包，`ExportArchives` 负责校验、发布和暂存目录。[manifest 与网站接收范围](docs/export-format-v1.md) |
| 世界生成 | `WorldgenDataStage` 汇总运行时定义与数据包规则。[自然生成 v2](docs/worldgen-natural-generation-v2.md) · [结构 v2](docs/worldgen-structure-export-v2.md) · [战利品表 v2](docs/worldgen-loot-tables-v2.md) |
| 更新协议或移植版本 | [Schema 样例](schema/fixtures/README.md) · [版本适配清单](docs/version-porting.md) · [历史 JEI 顺序字段诊断](docs/legacy-jei-ordering.md) |

游戏注册表、资源栈和 JEI 在客户端阶段读取，离屏绘图依赖有效的 OpenGL 上下文。注册表按对象采集，资源按文件扫描并逐条组装索引，JSON 仍以整文件原子写入。导出在相邻步骤之间检查软时间预算，第三方回调、整文件编码和绘图可能单独超过预算。远程服务器上的世界生成数据可能降级为已安装模组的数据。检查 manifest 的阶段、错误列表和能力声明。[资源阶段实测](docs/contract-validation.md#resource-scheduling-comparison)

打包和完整性校验在后台执行。输出目录所在文件系统须支持同目录硬链接，才能原子发布已完成的 ZIP 并保护同名文件。打包失败或发布前取消时保留暂存目录。构建无法解析插件时先核实上游仓库可达性与本机 JDK 的 CA 信任配置。
