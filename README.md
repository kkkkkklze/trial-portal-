# Trial Portal（传送门试炼）

Minecraft 1.20.1 / MinecraftForge 模组工程。**当前是工程骨架**：构建与加载已跑通，试炼玩法代码尚未实现。
玩法设计规格来自原作者提供的文档，收录在 `docs/`；本机的实际情况与差异台账见 [docs/开发文档.md](docs/开发文档.md)。

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
| `./gradlew build` | 绿，24s，产物 `build/libs/trial_portal-1.0.0.jar`（5.7 KB，骨架体积；jar 内 `mods.toml` 占位符已正确展开、无 mojibake） |
| `./gradlew runGameTestServer` | 绿，35s，Forge 47.4.23 / MC 1.20.1 服务端完整启动，模组构造函数执行，`All 0 required tests passed :)` |
| `./gradlew runData` | 绿，12s，但**不算加载验证**（见下） |
| `runClient` / `runServer` | 未跑（前者要图形窗口，后者要你自己同意 EULA） |
| 试炼玩法代码 | **未实现**，0 个玩法类、0 份 trial/词条 JSON |

## 常用命令

```bash
./gradlew build              # 编译打包（Git Bash）；Windows CMD/PowerShell 用 gradlew.bat build
./gradlew runGameTestServer  # 加载验证：起服务端、跑模组完整生命周期后退出，不需要 EULA
./gradlew runClient          # 带模组启动客户端（图形窗口）
./gradlew runData            # 数据生成，输出到 src/generated/resources/
./gradlew runServer          # 服务端（--nogui，需先在 run/eula.txt 同意 EULA）
./gradlew clean
```

**加载验证只认 `runGameTestServer`**：`runData` 虽然也会绿，但 datagen 只跑到注册阶段，
**不执行模组构造函数**（`DatagenModLoader#begin()` 只调 `gatherAndInitializeMods` + `GatherDataEvent`），
拿它当「模组能加载」的证据会骗人。细节见 `docs/开发文档.md` §3。
设计文档里的 `checkTrialData` 任务本机**还不存在**，它属于试炼内容实现的一部分。

## 目录结构（当前实际）

```
trial-portal-1.20.1/
├── build.gradle              # MDG Legacy 工具链、国内镜像仓库、run 配置、generateModMetadata
├── gradle.properties         # 版本 + 模组元数据（mod_id / mod_name / 作者 / 描述都在这改）
├── settings.gradle           # 插件仓库（镜像）+ foojay 工具链解析
├── gradle/wrapper/
├── docs/
│   ├── 开发文档.md            # 本机实况、差异台账、目标架构、待办
│   └── 设计文档-*.md          # 原作者提供的三份文档，原样收录
├── verify-logs/               # 2026-09-25 三条命令的留档日志（可弃）
└── src/
    ├── main/java/com/trialportal/
    │   ├── TrialPortal.java          # 主类（注册入口，内容待实现）
    │   └── registry/                 # ModBlocks / ModItems 的 DeferredRegister
    ├── main/resources/               # 空目录（assets/data 随内容实现时建）
    ├── main/templates/
    │   ├── META-INF/mods.toml        # 元数据模板，构建时由 generateModMetadata 展开占位符
    │   └── pack.mcmeta
    └── generated/resources/          # runData 输出，已在 build.gradle 注册为资源目录，勿手改
```

## 加内容时的入口

1. `gradle.properties` 改元数据（`mod_id` 改动要同步主类 `MODID`、包目录、`assets/<mod_id>/`、`data/<mod_id>/`）。
   **注意**：`gradle.properties` 被 Gradle 按 ISO-8859-1 读取，这个文件里**不要写中文**，中文名走 lang 文件。
2. 方块/物品/实体：在 `com.trialportal.registry` 下的 `ModBlocks` / `ModItems` 里 `register(...)`。
3. 资源：`src/main/resources/assets/trial_portal/`（模型、方块状态、lang）、`data/trial_portal/`（配方、战利品表、标签）。
4. 第三方模组依赖：`build.gradle` 的 `dependencies` 里用 `modCompileOnly` / `modLocalRuntime`（MDG 的 remapping 配置），不是 FG 的 `fg.deobf(...)`。

## 构建坑（本机既有经验，写在这是为了不重复踩）

- **项目路径含中文**（`Documents\开发\...`）：编译打包不受影响，但 Gradle 的 `Test` 任务会因非 ASCII 路径加载不了测试类。
  要跑自动化校验就用 `JavaExec` 自建任务（设计文档里的 `checkTrialData` 正是这个模式），**不要引入 `src/test/java` + `Test`**。
- **产物只用 `build/libs/`**（已重混淆），`build/devlibs/` 只用于开发环境，不要发布。
- **`gradlew` 没有 Unix 可执行位**：zip 分发和 Windows 提交都是这样，Linux CI 上需要先 `chmod +x gradlew`。
- **首次在新机器构建**要下载并反编译 Minecraft（5~15 分钟）；本机缓存已在，`build` 全量 24 秒。
