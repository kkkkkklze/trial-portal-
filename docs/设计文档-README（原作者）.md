> **文档来源**：本文件由原作者提供（原作者机器路径为 `<原作者机器路径>`，不是本机），作为设计规格与现状快照**原样收录、未作改写**。
> 本机的事实（工具链版本、构建命令与验证结果、实现进度）以仓库根的 `README.md` 与 `docs/开发文档.md` 为准；两者冲突时以那两份为准。

# Trial Portal

Minecraft 1.20.1 / Forge 47.4.10 模组开发环境。

## 环境信息

| 项目 | 值 |
|------|-----|
| Minecraft | 1.20.1 |
| Forge | 47.4.10 |
| Java | 17（Temurin 17.0.15） |
| 构建工具 | ForgeGradle 6（`net.minecraftforge.gradle`） |
| Gradle | 8.8（wrapper 已包含，无需单独安装） |
| 映射 | `official` / 1.20.1（Mojang 官方字段名） |
| 主类 | `com.trialportal.TrialPortal` |
| mod id | `trial_portal` |
| 产物 | `build/libs/trial_portal-1.0.0.jar` |

## 已验证状态

- `gradlew build` 通过，产物 jar 的 `mods.toml` 占位符已正确展开。
- `gradlew runData` 通过：Minecraft 1.20.1 + Forge 47.4.10 带本模组完整加载并正常退出。
  （日志里的 `Assets URL 'union:/...' uses unexpected schema` 是 ForgeGradle 开发环境的已知无害警告。）
- `gradlew checkTrialData` 通过：3 份试炼与 8 条 BOSS 词条全部解析成功，无字段缺失、无失效引用。
- `gradlew runGameTestServer` 通过：服务端完整启动，日志确认
  `已加载 3 份试炼定义` / `已加载 8 条 BOSS 词条`，即数据包重载链路（`AddReloadListenerEvent`）真实跑通。

**尚未在游戏内实操验证的部分**：波次推进、怪物生成、词条贴到 BOSS 身上、死亡清奖励这些运行时行为。
要验证请 `gradlew runClient` 建一个世界，然后用第 7 节的 `/tei` 指令（`/tei trial start`、`/tei trial profile`、
`/tei trial status`）手动推一遍，不需要真刷一场。注意 `runServer` 需要先在 `run/eula.txt` 里把
`eula=false` 改成 `true`（除非你已同意 Mojang EULA，我没有代你修改）。

## 常用命令

在项目根目录执行（Git Bash 下用 `./gradlew`，CMD / PowerShell 下用 `gradlew.bat`）：

```bash
./gradlew build              # 编译并打包（含下面的 checkTrialData），产物在 build/libs/
./gradlew checkTrialData      # 只校验 data/ 下的试炼与词条 JSON，不开游戏
./gradlew runClient           # 启动带模组的客户端（调试用，会打开游戏窗口）
./gradlew runServer           # 启动带模组的服务端（--nogui，需要先同意 EULA）
./gradlew runData             # 运行数据生成器，输出到 src/generated/resources/
./gradlew runGameTestServer   # 启动 GameTest 服务端跑测试后退出（不需要 EULA，适合验证加载）
./gradlew genIntellijRuns     # 重新生成 IDEA 运行配置
./gradlew genEclipseRuns      # 重新生成 Eclipse 运行配置
./gradlew clean               # 清理构建产物
```

注意：`build/libs/` 下是**重混淆后**的 jar（`reobfJar` 任务生成），可直接放进整合包的 `mods` 目录；
开发调试请用 `runClient`，不要用 `build/devlibs/`。

## 传送门试炼系统

三种玩法共用一套数据驱动框架，内容全部写在数据包 JSON 里，改内容不用编译：

| 玩法 | 传送门方块 | 特点 |
|---|---|---|
| 闯关 | `challenge_portal` | 每波打完停下等玩家决定继续/离场，死亡清空累计奖励 |
| 爬塔 | `tower_portal` | 无尽，按层缩放，层数记录按玩家持久化，续关从上次位置继续 |
| 试炼 | `trial_portal` | 采集玩家属性画像，生成带克制词条的 BOSS，掉落质变材料 |

交互：**空手右键** = 开始 / 继续下一波，**潜行右键** = 结算离场领奖励。

**完整配置说明见 [docs/传送门试炼配置指南.md](docs/传送门试炼配置指南.md)** ——
包含全部 JSON 字段表、判据与效果写法、TOML 配置项、`/tei` 指令、以及已知限制。

**要接着开发或排查问题，先看 [docs/模组现状与注意事项.md](docs/模组现状与注意事项.md)** ——
包含当前内容清单、代码结构与调用链、已验证/未验证范围，以及实现与文档不一致的坑（如
`tiers[].boss_affix_pool` 不生效、`multiply_total` 区块重载后会丢）。

## 目录结构

```
Trial_Portal/
├── build.gradle              # 构建脚本（ForgeGradle、run 配置、占位符展开、checkTrialData 任务）
├── gradle.properties         # 版本与模组元数据（mod_id / mod_name / 作者 / 描述等都在这里改）
├── settings.gradle           # 插件仓库 + foojay 工具链解析
├── gradle/                   # Gradle wrapper（含 gradle-wrapper.jar）
├── docs/                     # 配置指南、现状与注意事项
└── src/
    ├── main/java/com/trialportal/
    │   ├── TrialPortal.java   # 主类（注册、数据包挂载、指令挂载）
    │   ├── block/                       # 传送门方块与方块实体
    │   ├── boss/                        # 玩家画像、BOSS 词条、词条运行时事件
    │   ├── command/                     # /tei 调试指令
    │   ├── config/                      # TOML 全局配置
    │   ├── data/                        # 通用 JSON 重载监听器
    │   ├── registry/                    # 方块 / 物品 / 创造栏注册
    │   └── trial/                       # 试炼定义、库、会话、状态机、反馈
    ├── main/resources/
    │   ├── assets/trial_portal/   # 方块状态、模型、语言文件
    │   ├── data/trial_portal/
    │   │   ├── loot_tables/blocks/     # 传送门掉落
    │   │   ├── trials/                 # 试炼定义（示例 3 份）
    │   │   └── boss_affixes/           # BOSS 词条池（示例 8 条）
    │   ├── META-INF/mods.toml          # 模组元数据模板（占位符由 build.gradle 展开）
    │   └── pack.mcmeta                 # 资源包描述（pack_format 15 = 1.20.1）
    └── tools/java/                     # checkTrialData 的数据校验工具（不会打进 jar）
```

`src/generated/resources/` 是数据生成器的输出目录，已注册为资源目录，**不要手动编辑**。

## 加内容时的入口

- **注册方块/物品/实体**：在 `com.trialportal.registry` 下建 `ModBlocks` / `ModItems` 等
  持有 `DeferredRegister` 的类，然后在主类构造函数里对 `modEventBus` 调用它们的 `register(...)`。
  主类里已经留好了对应的注释位置。
- **资源**：`assets/trial_portal/`（贴图、模型、语言文件）、
  `data/trial_portal/`（配方、战利品表、标签）。
- **第三方模组依赖**（JEI、GeckoLib 等）：在 `build.gradle` 的 `repositories` 和 `dependencies`
  两处取消对应注释；JEI 用 `compileOnly fg.deobf(...)` 只做编译期依赖，不打包进 jar。

## 环境相关的注意事项

- **JDK 路径**：`gradle.properties` 里的
  `org.gradle.java.installations.paths=<原作者 JDK17 目录>`
  指向本地 JDK 17。换机器或换 JDK 安装位置时必须同步修改，否则 Gradle 会因为找不到
  工具链而尝试从 GitHub 下载（国内网络通常失败）。
- **项目路径含中文**：本项目位于 `...\整合包\自制模组\` 下。ForgeGradle 编译打包不受影响，
  但如果以后添加 `src/test/java` 单元测试，Gradle 的 Test worker 会因非 ASCII 路径导致
  类加载失败（classpath 参数文件的编码问题）。届时请把项目放在纯 ASCII 路径下再跑测试，
  或参考 Nextcard 项目的 `logicTest` 做法改用 `JavaExec`。
- **依赖缓存**：Gradle 8.8 发行版、ForgeGradle 插件、Forge 1.20.1-47.4.10 的 userdev 与
  official 映射产物、Minecraft 客户端 jar 和资源文件均已在本机
  `<原作者机器上的 Gradle 用户目录>/caches/` 中，因此首次构建无需大量下载。
  若需强制刷新：`./gradlew --refresh-dependencies`。
- **改 mod id / 包名**：需要同步修改 `gradle.properties` 的 `mod_id` 与 `mod_group_id`、
  主类的 `MODID` 常量、`@Mod(...)` 注解参数，以及 `src/main/java` 下的包目录名。
  资源目录 `assets/<mod_id>/`、`data/<mod_id>/` 也要一起改名。
- **Parchment 映射**（可选，能获得可读的参数名）：在 `gradle.properties` 把
  `mapping_channel` 改成 `parchment`、`mapping_version` 改成 `2023.09.03`。
  Parchment 的下载会被重定向到 Google Storage，国内网络可能不通，此时保持 `official` 即可。
