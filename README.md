# DialogMenu

基于原版 Dialog 的 Minecraft 菜单插件。使用 YAML 配置文字、图片、点击动作和动态内容，支持 Java API、共享字库、图片染色及图片/文字拉伸。普通玩家无需安装 Mod，需接受服务器资源包。

本项目已统一采用 RGBA 着色器渲染，旧原生版本不再维护。

## 许可证

采用 [Apache License 2.0 + Commons Clause 1.0](LICENSE)，两部分须同时遵守：

- 允许遵守条款后使用、修改和免费分发；分发时保留许可与归属信息并标明修改。
- 不授予 Commons Clause 定义的“Sell”权利：收费产品或服务的价值全部或主要来自本插件功能时，不在授权范围内，涵盖符合该定义的托管、咨询和支持服务。
- 这不是纯 Apache 2.0 授权，不能笼统理解为所有盈利服务器或收费服务都可用。
- 第三方依赖和资源仍遵守各自条款。完整授权以 `LICENSE` 为准。

## 环境

- Java 21 起，Paper 系服务端；适配目标为 Minecraft 1.21.7–26.3，尚未逐版完成实机验证。
- Skript、PlaceholderAPI、CraftEngine 为按需使用的可选集成。
- 其他修改文字核心着色器的资源包需自行检查兼容性。

## 构建与使用

安装 Gradle 9.6.1 和 Java 21，在项目目录执行：

```sh
gradle build
```

产物：`build/libs/DialogMenu-0.1.7.jar`。

1. 正常停服，替换原 DialogMenu JAR，保留 `plugins/DialogMenu/` 后启动；不要同时安装多个版本。
2. 有 CraftEngine 时自动登记生成的资源包；否则用 `/dialogmenu resources` 导出后自行合包分发。合包需保留 `pack.mcmeta` 的 overlays 和 `dialogmenu_*` 目录。
3. 客户端接收资源包后，执行 `/dialogmenu open layout-demo`。默认附带两个示例，可在 `plugins/DialogMenu/layouts/` 中修改。

常用命令：

- `/dialogmenu reload` 或 `reload yaml`：只重载 YAML，缺少预备资源时保留原布局。
- `/dialogmenu reload <菜单名>`：只重载该页面所属的 YAML 文件，也可使用不带 `.yml` 的文件名。
- `/dialogmenu reload resource`：只更新当前已加载布局的资源。
- `/dialogmenu reload all`：同时更新 YAML 与资源。
- `/dialogmenu scale <0.75|1>`：调整个人画布倍率。

旧版 `backend` 点击区域需迁移到 `frontend` 的文字或图片元素。新增字符、图片或字号后使用 `reload all`，并重新分发资源包。

资源准备使用单后台线程，`resource-build-cpu-percent` 默认 `80`，可设为 `1..100`，表示平均单线程 CPU 预算，不限制 GC 或 CraftEngine。
玩家发起重载时默认显示阶段百分比 BossBar；`reload-progress-bossbar: false` 可关闭。控制台每5秒记录重载阶段、百分比与耗时，结束反馈菜单数及耗时。

CE 图片可写 `source: craftengine:资源包目录/textures后的路径.png`，`.png` 可省略。旧 image/item/icon ID 需在 `craftengine-definitions` 列出定义文件，不自动扫描全部 CE 配置。

## 动态文本与重载

传统 16 色已预置；动态文字使用额外 HEX 色时，在 `text-colors: ["#123456"]` 中声明后执行 `reload all` 并重新分发资源包。RGBA 模式不支持斜体、下划线、删除线和混淆装饰，准备期与运行时执行相同校验。

YAML 重载复用当前字库，缺少动态字符范围时拒绝应用。已应用的 YAML 与已构建的资源分别记录，后续 `reload resource/all` 会按新输入重建。缓存使用内容摘要，保留文件大小和时间戳的修改同样可发现；构建期间变化的输入会导致本轮失败，保留上一版可用 ZIP。

## 外部发布接口

`resource-pack-external-publisher` 默认关闭。开启后，CraftEngine 缓存事件只登记候选 ZIP，服务端字体映射在显式提交后更新；首次提交前菜单不可用。

外部发布方可在主线程通过 `DialogMenuApi.preparedResources()` 获取候选代次与 ZIP，在对应资源包发布成功后调用 `commitResources(generation)`。失败时不提交，过期代次会被拒绝。管理员也可通过 `/dialogmenu status` 查询状态、通过 `/dialogmenu publish <版本>` 提交候选；提交操作不代表客户端已加载资源包。

`gradle check` 包含离线资源回归检查；这些检查不代表真实客户端渲染或显存验收通过。
