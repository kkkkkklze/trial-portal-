# Trial Portal（传送门试炼）

Minecraft 1.20.1 / MinecraftForge 模组工程：数据驱动的传送门试炼（闯关 / 爬塔 / 试炼三种玩法共用一套框架）。
玩法语义规格来自原作者提供的设计文档（收录在 `docs/`），本机事实、验证结果与差异台账见 [docs/开发文档.md](docs/开发文档.md)。
内容全在数据包 JSON 里：改出什么怪、给什么奖励、BOSS 克制什么都不用重新编译。

## 模组身份

| 项目 | 值 |
| --- | --- |
| mod id | `trial_portal` |
| 显示名 | Trial Portal（游戏内中文名随内容实现时进 lang 文件） |
| 主类 | `com.trialportal.TrialPortal` |
| 包名 | `com.trialportal` |
| 作者 | kkkkkklze |
| 版本 / 许可 | 1.0.0 / All Rights Reserved |

## 环境（本机）

| 项目 | 值 |
| --- | --- |
| Minecraft | 1.20.1 |
| MinecraftForge | **47.4.23**（`forge_version_range=[47,)`） |
| 构建工具链 | ModDevGradle Legacy `net.neoforged.moddev.legacyforge` 2.0.147 |
| Gradle | 8.14.3（wrapper，腾讯云镜像，本机已缓存） |
| Java | 17：`<本机 JDK17 目录>`（写死在 `gradle.properties`） |
| 映射 | Mojang 官方映射（MDG 默认，字段名即 `official`） |
| 产物 | `build/libs/trial_portal-1.0.0.jar`（`reobfJar` 已做 SRG 重混淆，可直接进 `mods/`） |

> 这份表和设计文档里的表**不一致**（对方那台机器是 ForgeGradle 6 + Forge 47.4.10 + Gradle 8.8）。
> 已按「工程文件以本地为准」采用本机构的 MDG 工具链，差异逐条列在 `docs/开发文档.md` §2。

## 当前状态（2026-09-25 实测）

| 命令 | 结果 |
| --- | --- |
| `./gradlew build` | 绿。含 `> Task :checkTrialData`（8 条词条 + 3 份试炼全通过）。产物 `build/libs/trial_portal-1.0.0.jar`（106 KB，38 个 class） |
| `./gradlew checkTrialData` | 绿；两次反向测试（写坏 JSON）都以非 0 退出并点名条目 |
| `./gradlew runGameTestServer -PselfTest` | 绿，**32 条运行期断言全通过**（数据包链路、BOSS 层取 mobs[0]、层段词条池生效、免疫真的取消伤害、属性修饰符随实体存档、NBT 往返一致、方块/物品注册、`/tei trial list` 真执行） |
| `./gradlew runGameTestServer` | 绿，服务端完整启动 + `已加载 3 份 试炼 定义 / 8 份 BOSS 词条 定义`，不需要 EULA |
| `./gradlew runData` | 绿，但**不算加载验证**（见下） |
| `runClient` + playtest-bridge | 绿：真客户端跑完「右键开局 → 波次推进 → 潜行结算发奖（`/clear` 数到 5 件铁锭）→ 死亡转存 → bind 换爬塔 → 续关第 13 层 → BOSS 三词条 79.2 血」 |
| `runServer` | 未跑（要你自己同意 EULA） |

玩法实现：23 个类 / 3,478 行 + 1 个工具类，数据包 3 份试炼 + 8 条词条 + 3 份掉落表 + 12 份资源。
**还没有**：配方、客户端信息面板、真正的可穿越传送门、组队、原版 GameTest（缺结构模板，走 `-PselfTest` 通道）。

## 常用命令

```bash
./gradlew build              # 编译打包 + checkTrialData 数据闸门（Git Bash；CMD/PowerShell 用 gradlew.bat）
./gradlew checkTrialData     # 只校验 data/ 下的试炼与词条 JSON，不开游戏
./gradlew runGameTestServer -PselfTest   # 加载 + 32 条运行期断言（无玩家能跑的那部分），不需要 EULA
./gradlew runGameTestServer  # 只验加载与数据包链路
./gradlew runClient          # 带模组启动客户端（图形窗口）
./gradlew runData            # 数据生成，输出到 src/generated/resources/
./gradlew runServer          # 服务端（--nogui，需先在 run/eula.txt 同意 EULA）
./gradlew clean
```

**加载验证只认 `runGameTestServer`**：`runData` 虽然也会绿，但 datagen 只跑到注册阶段，
**不执行模组构造函数**（`DatagenModLoader#begin()` 只调 `gatherAndInitializeMods` + `GatherDataEvent`），
拿它当「模组能加载」的证据会骗人。细节见 `docs/开发文档.md` §3。

## 目录结构（当前实际）

```
trial-portal-1.20.1/
├── build.gradle              # MDG Legacy 工具链、镜像仓库、run 配置（含 -PselfTest 开关）、generateModMetadata
├── gradle.properties         # 版本 + 模组元数据（mod_id / mod_name / 作者 / 描述都在这改，保持 ASCII）
├── settings.gradle           # 插件仓库（镜像）+ foojay 工具链解析
├── gradle/wrapper/
├── docs/
│   ├── 开发文档.md            # 本机实况、差异台账、逐包状态、待办
│   └── 设计文档-*.md          # 原作者提供的三份（README / 配置指南 / 现状与注意事项），原样收录
├── verify-logs/               # 跑门留下的日志（已 gitignore，可重生成）
└── src/
    ├── main/java/com/trialportal/
    │   ├── TrialPortal.java          # 主类：注册、数据包监听、/tei 挂载、潜行右键放行
    │   ├── registry/                 # ModBlocks / ModItems / ModBlockEntities / ModCreativeTabs
    │   ├── block/  block/entity/     # TrialPortalBlock + SneakInteractBlock + 方块实体
    │   ├── trial/                    # 定义模型 / 库 / 会话 / 状态机 / 事件 / 播报 / 自测
    │   ├── boss/                     # 词条模型 / 库 / 运行期生效点 / 玩家画像
    │   ├── command/  config/  data/  # /tei 指令树 / 16 项 TOML / 通用 JSON 重载监听器
    ├── main/resources/
    │   ├── assets/trial_portal/      # 方块状态、模型（复用原版贴图）、中英文 lang
    │   └── data/trial_portal/
    │       ├── trials/               # 3 份试炼定义（challenge / tower / trial）
    │       ├── boss_affixes/         # 8 条 BOSS 词条
    │       └── loot_tables/blocks/   # 三个方块掉自己
    ├── main/templates/
    │   ├── META-INF/mods.toml        # 元数据模板，构建时由 generateModMetadata 展开占位符
    │   └── pack.mcmeta
    ├── generated/resources/          # runData 输出，已在 build.gradle 注册为资源目录，勿手改
    └── tools/java/                   # TrialDataCheck：checkTrialData 的校验程序，不打进 jar
```

## 加内容时的入口

1. `gradle.properties` 改元数据（`mod_id` 改动要同步主类 `MODID`、包目录、`assets/<mod_id>/`、`data/<mod_id>/`）。
   **注意**：`gradle.properties` 被 Gradle 按 ISO-8859-1 读取，这个文件里**不要写中文**，中文名走 lang 文件。
2. 方块/物品/实体：在 `com.trialportal.registry` 下的 `ModBlocks` / `ModItems` / `ModBlockEntities` / `ModCreativeTabs` 里加。
   新增运行期断言就加进 `trial/TrialSelfTest.run(...)`，它会在 `-PselfTest` 那条门上变红。
3. 资源：`src/main/resources/assets/trial_portal/`（模型、方块状态、lang）、`data/trial_portal/`（配方、战利品表、标签）。
4. 第三方模组依赖：`build.gradle` 的 `dependencies` 里用 `modCompileOnly` / `modLocalRuntime`（MDG 的 remapping 配置），不是 FG 的 `fg.deobf(...)`。

## 构建坑（本机既有经验，写在这是为了不重复踩）

- **项目路径含中文**（`Documents\开发\...`）：编译打包不受影响，但 Gradle 的 `Test` 任务会因非 ASCII 路径加载不了测试类。
  要跑自动化校验就用 `JavaExec` 自建任务（设计文档里的 `checkTrialData` 正是这个模式），**不要引入 `src/test/java` + `Test`**。
- **产物只用 `build/libs/`**（已重混淆），`build/devlibs/` 只用于开发环境，不要发布。
- **`gradlew` 没有 Unix 可执行位**：zip 分发和 Windows 提交都是这样，Linux CI 上需要先 `chmod +x gradlew`。
- **首次在新机器构建**要下载并反编译 Minecraft（5~15 分钟）；本机缓存已在，`build` 全量 24 秒。
