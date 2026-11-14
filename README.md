# DialogMenu

性能测试见 [BENCHMARKS.md](BENCHMARKS.md)：`gradle benchmarkSmoke` 校验测试场景，`gradle benchmark` 运行独立的 JMH 基准；测试代码和依赖不进入插件 JAR。

基于原版 Dialog 的 Minecraft 菜单插件。使用 YAML 配置文字、图片、点击动作和动态内容，支持 Java API、共享字库、图片染色及图片/文字拉伸。普通玩家无需安装 Mod，需接受服务器资源包。
初期阶段，性能释放材质重载方案和实现方式并不好看，请见谅

## 许可证

采用 [Apache License 2.0 + Commons Clause 1.0](LICENSE)，两部分须同时遵守：

- 允许遵守条款后使用、修改和免费分发；分发时保留许可与归属信息并标明修改。
- 不授予 Commons Clause 定义的“Sell”权利：收费产品或服务的价值全部或主要来自本插件功能时，不在授权范围内，涵盖符合该定义的托管、咨询和支持服务。
- 这不是纯 Apache 2.0 授权，不能笼统理解为所有盈利服务器或收费服务都可用。
- 第三方依赖和资源仍遵守各自条款。完整授权以 `LICENSE` 为准。

## 环境

- Java 21 起，Paper 系服务端，版本为1.21.7-26.3，26.3为实际开发版本
- 适配插件：Skript、PlaceholderAPI、CraftEngine

## 构建与使用

安装 Gradle 9.6.1 和 Java 21，在项目目录执行：

```sh
gradle build
```

产物：`build/libs/DialogMenu-<version>.jar`，版本取 `gradle.properties`。

1. 正常停服，替换原 DialogMenu JAR，保留 `plugins/DialogMenu/` 后启动；不要同时安装多个版本。
2. 首次安装或更换插件 JAR 后，手动执行 `/dialogmenu reload all` 准备资源。有 CraftEngine 时自动登记生成的资源包；否则用 `/dialogmenu resources` 导出后自行合包分发。合包需保留 `pack.mcmeta` 的 overlays 和 `dialogmenu_*` 目录。
3. 客户端接收资源包后，执行 `/dialogmenu open layout-demo`。默认附带两个示例，可在 `plugins/DialogMenu/layouts/` 中修改。

常用命令：

- `/dialogmenu reload` 或 `reload yaml`：只重载 YAML，缺少预备资源时保留原布局。
- `/dialogmenu reload <菜单名>`：只重载该页面所属的 YAML 文件，也可使用不带 `.yml` 的文件名。
- `/dialogmenu reload resource`：只更新当前已加载布局的资源。
- `/dialogmenu reload all`：同时更新 YAML 与资源；增量准备并复用未变化字形/Shader模型。
- `/dialogmenu reload full`：强制完整重渲染，重新生成字形与Shader模型资源。
- `/dialogmenu scale <倍率>`：调整个人画布倍率，Tab 读取当前 `canvas-scales`。
- `/dialogmenu textscale <倍率>`：调整个人文字倍率，Tab 读取当前 `text-scales`。
- `/dialogmenu give <入口ID> [玩家]`：管理员发放配置的入口物品。

新装的 `canvas-scales`、`text-scales` 默认均为 `[0.8, 1, 2]`，默认选择为 `1`。升级不自动重写现有 `config.yml` 或玩家倍率文件；旧配置缺少 `canvas-scales` 时保持旧画布选项 `[0.75, 1]`。新增倍率需 `reload all` 后重新接收资源包；已有菜单仍受实际画布/像素预算限制，超限会拒绝选择，不自动缩放或重排。

资源准备由后台协调线程分阶段并行处理，`resource-build-threads` 可设为 `1..8`，默认最多4路并预留1个逻辑处理器。`resource-build-cpu-percent` 默认 `80`，可设为 `1..100`，表示每个构建线程的平均 CPU 预算，不是进程总上限，不限制 GC 或 CraftEngine。
编译结果自动保存。默认 `reuse-compiled-cache: false`，开服恢复缓存后只重载 YAML，不重新编译资源；开启后连 YAML 也只复用缓存，CE 重载不再自动准备 Dialog 资源。缓存缺失或不兼容时提示手动 `reload all`，不会自动全量编译。
玩家发起重载时默认显示阶段百分比 BossBar；`reload-progress-bossbar: false` 可关闭。控制台每5秒记录重载阶段、百分比与耗时，结束反馈菜单数及耗时。

CE 图片可写 `source: craftengine:资源包目录/textures后的路径.png`，`.png` 可省略。旧 image/item/icon ID 需在 `craftengine-definitions` 列出定义文件，不自动扫描全部 CE 配置。

## 26.2 模型兼容

26.2 的单一 `items` 图集使用独立的 `dialogmenu:model/` 和 `dialogmenu:native/` sprite 名称。模型表、图集条目与必要的纹理随字体快照一起导出；原版世界图集不会随模型包注入。GLSL 330 分支在原生布局收尾前处理模型几何，支持既有菜单的点击与缩放路径。

继续使用经校验的外置原版资源库，无需下载另一套 26.2 库。对于 26.2 缺少的新版图标和模型纹理，导出插件命名空间中的像素回退；已有客户端纹理继续按原路径复用。合包时保留 `pack.mcmeta` 的 overlays 及 `dialogmenu_*` 目录。

## 动态文本与重载

传统 16 色已预置；动态文字使用额外 HEX 色时，在 `text-colors: ["#123456"]` 中声明后执行 `reload all` 并重新分发资源包。支持粗体 `&l`、斜体 `&o`、下划线 `&n`、删除线 `&m`、混淆 `&k` 和重置 `&r`，也支持 `§`。例如 `&c&o&n红色斜体下划线&r`；legacy 颜色码会重置此前装饰，应先写颜色再写装饰。混淆由客户端等宽替换字形，不需要服务器定时刷新。下划线/删除线跟随实际字号与缩放，不增加点击区域；首次使用新实现需 `reload all` 并更新资源包。

YAML 重载复用当前字库，缺少动态字符范围时拒绝应用。已应用的 YAML 与已构建的资源分别记录，后续 `reload resource/all` 会按新输入增量重建。`reload full` 无视可复用缓存，强制重建字形与 Shader 模型资源。缓存使用内容摘要，保留文件大小和时间戳的修改同样可发现；构建期间变化的输入会导致本轮失败，保留上一版可用 ZIP。

## 头像、目录说明与单行文字

支持原生玩家对象组件的服务端可在 `frontend` 中使用 `type: player-head`，显示当前玩家的真实头像，包含帽子层和已登录资料中的皮肤属性；不会同步查询皮肤网络接口。不支持该组件的服务端会在加载布局时明确拒绝此节点。

```yaml
frontend:
  avatar:
    type: player-head
    offset: {x: -40, y: 20}
    tooltip: '查看玩家资料'
    actions: 'command: profile'
  name:
    type: text
    content: '{player}'
    size: 10
    fit-width: 100
```

头像固定为客户端文字坐标中的 8×8 像素，不随画布倍率变大，不支持旋转、染色、自定义 `source` 或节点缩放。垂直位置会吸附到最近的 9 像素文本行，悬浮与点击区域跟随实际位置；吸附后越界会拒绝显示。布局文件原有坐标系规则仍然适用。

`fit-width` 将单行文字按整数调整字号，超宽时逐步缩小，短文字保持原字号。它不截断文字，不能与 `line-width`、`max-lines` 混用；动态值含换行或最小字号仍放不下时会明确报错。

`entries.<ID>.tooltip` 可填写说明字符串或多行字符串列表，网格背景、图标与标签使用同一说明。不填写时仍使用 `label`，显式设置 `[]` 可关闭说明。

## 菜单打开入口

在 `config.yml` 的可选 `openers` 配置入口。默认 `{}`，不自动给已有服务器添加快捷键或物品。

```yaml
openers:
  handbook:
    menu: layout-demo
    permission: dialogmenu.use
    commands: [handbook]
    keys: [SHIFT+F]
    item:
      material: BOOK
      name: '&6菜单手册'
      lore: ['&7右键打开']
      nbt: '{dialogmenu:{entry:"handbook",version:1b}}'
      action: RIGHT_CLICK
```

- `commands` 注册玩家指令，例如 `/handbook`；不接收参数。已被其他插件占用时不覆盖，改用 `/dialogmenu:handbook`，控制台会提示。
- `keys` 是游戏动作组合：`F` 换手、`Q` 丢弃、`LEFT_CLICK`、`RIGHT_CLICK`；可加 `SHIFT`（潜行）或 `SPRINT`（冲刺状态），如 `SHIFT+F`、`SHIFT+RIGHT_CLICK`。它们不是原始键盘监听，玩家改绑后跟随游戏动作；不支持任意字母或 Alt/Ctrl 物理按键。
- `commands`、`keys`、`item` 是独立打开方式。物品仅响应主手，默认右键；需要潜行使用时设置 `action: SHIFT+RIGHT_CLICK`。物品不消耗；打开成功才取消对应换手、丢弃或物品使用动作，已被其他插件禁止的动作不抢占。
- `item` 的 `material` 与 `craftengine` 二选一。CE 写完整 ID，例如 `craftengine: 'example:handbook'`。可覆盖 `name`、`lore` 和自定义 NBT；不填写的名称/Lore 不参与匹配。
- `nbt` 是 `minecraft:custom_data` 内的 SNBT 复合标签，不是整件物品存档。按嵌套子集匹配，列表/数组及数值类型精确匹配；例如 `1b` 不等于 `1`。发放时递归合并，保留其余 CE/PDC 数据。
- 管理员执行 `/dialogmenu give handbook [玩家]` 获取物品。所有入口都检查 `dialogmenu.use`、入口 `permission` 和菜单权限；只打开菜单，不代替业务校验。
- 修改入口后执行全局 `/dialogmenu reload yaml`；不需要仅为指令/物品/按键规则重新生成资源包。重复指令/组合键或不存在的菜单会拒绝应用并保留旧快照。多个物品规则同时匹配时按配置顺序使用第一个有权限且打开成功的入口。

## 外部发布接口

`resource-pack-external-publisher` 默认关闭。开启后，CraftEngine 缓存事件只登记候选 ZIP，服务端字体映射在显式提交后更新；首次提交前菜单不可用。

外部发布方可在主线程通过 `DialogMenuApi.preparedResources()` 获取候选代次与 ZIP，在对应资源包发布成功后调用 `commitResources(generation)`。失败时不提交，过期代次会被拒绝。管理员也可通过 `/dialogmenu status` 查询状态、通过 `/dialogmenu publish <版本>` 提交候选；提交操作不代表客户端已加载资源包。

`gradle check` 包含离线资源回归检查；这些检查不代表真实客户端渲染或显存验收通过。
