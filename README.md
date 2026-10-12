# Trial Portal（传送门试炼）

Minecraft 1.20.1 / MinecraftForge 模组工程：数据驱动的传送门试炼（闯关 / 爬塔 / 试炼共用一套框架），
外加第四种玩法**守塔**（`defenses/*.json`：怪从结界外缘一波波压进来，塔血归零或玩家死亡都算输）。
内容都跑在自建维度 `trial_portal:arena` 里。进出只有一条通道：**星罗·试炼往返门**（`arena_exit`，
8 哭泣黑曜石 + 末影珍珠合出来），野外点它传进大厅平台，大厅/隔间里点它回进入前的位置并结算。
进了大厅再点北沿三座拱门选玩法（闯关 / 爬塔 / 试炼），守塔点南沿正中那座守塔之枢——`defenses/` 里只有一场就直接开局，
有几场就弹选单（与闯关那张同一张界面）。那三座门与塔本身
按裁定**没有生存配方**，只能从创造栏或 `/give` 拿到。玩法语义规格来自原作者提供的设计文档（收录在 `docs/`），
本机事实、验证结果与差异台账见 [docs/开发文档.md](docs/开发文档.md)。
内容全在数据包 JSON 里：改出什么怪、给什么奖励、BOSS 克制什么都不用重新编译。

## 与「恶意」(l2hostility) 联动

整合包里装了 L2 Hostility（modid `l2hostility`）时，本模组生成的怪会被它那套区域难度接管——玩家报告的
整合包实况是给到 1500 级，我们自己的血量阶梯就被抹平了。联动的做法是**由本模组按阶梯改写它的恶意等级**
（2026-10-12 裁定：等级跟层数/档位走并封顶，这样"第几层"仍然读得出来）：

| 配置（`malice` 组） | 默认 | 含义 |
| --- | --- | --- |
| `enabled` | `true` | 关掉就完全不碰它的等级；没装恶意时这一项不起作用 |
| `base_level` | `0` | 阶梯起点：第 1 层 / 第 1 波 / 最低档 |
| `level_per_stage` | `1` | 每上一层 / 每波 / 每档加多少 |
| `max_level` | `30` | 封顶。恶意那侧还有它自己的上限，最终取最小的那个 |
| `reassert_ticks` | `40` | 生成后最多再观察多少 tick 去落笔 |

- 序号的来源：闯关看难度档（浅/中/深 = 0/1/2），爬塔看层号，试炼与守塔看波号。
- 单只怪可以在 JSON 里写 `"malice_level": 6` 覆盖阶梯；写 `0` 是「这一档明说不带恶意」，不写才是「跟着阶梯」。
- 为什么不是生成那一刻写完：**它的等级是怪出生后的第一个 tick 才从区块难度初始化好的**
  （`MobTraitCap#tick` → `ChunkDifficulty.at`），而我们的怪走 `EntityType#create` + `addFreshEntity`，
  不触发它挂在 `FinalizeSpawn` 上的那条初始化。出生瞬间写进去会被它的 `init` 覆盖，所以要等一拍再落笔，
  而且只落一次——之后玩家用 `/hostility mobs ... level set` 手动改那只怪，我们不抢回来。
- 现场自检：`/tei malice status`（在不在场、配置、阶梯前 8 级、生成点请求台账、排队数）、
  `/tei malice probe [等级]`（就近一只怪：能不能拿到等级、当前几级、写下去读不读得回来）。
  这两条要在**装了恶意的整合包**里跑——无头的门上那个模组不在场，判据只能钉「该写多少、该不该写」。

## 模组身份

| 项目 | 值 |
| --- | --- |
| mod id | `trial_portal` |
| 显示名 | Trial Portal（游戏内中文名随内容实现时进 lang 文件） |
| 主类 | `com.trialportal.TrialPortal` |
| 包名 | `com.trialportal` |
| 作者 | kkkkkklze |
| 版本 / 许可 | 1.0.0 / **MIT**（见 `LICENSE`；MDK 模板文件另有其自带 MIT，见 `TEMPLATE_LICENSE.txt`；`docs/` 里三份《…（原作者）》设计文档不在这个授权范围内） |

## 环境（本机）

| 项目 | 值 |
| --- | --- |
| Minecraft | 1.20.1 |
| MinecraftForge | **47.4.23**（`forge_version_range=[47,)`） |
| 构建工具链 | ModDevGradle Legacy `net.neoforged.moddev.legacyforge` 2.0.147 |
| Gradle | 8.14.3（wrapper，腾讯云镜像，本机已缓存） |
| Java | 17。仓库里不写机器路径：Gradle 自动发现即可；发现不到时（例如 JDK 放在 `$GRADLE_USER_HOME/jdks` 里又嵌套了一层）把 `org.gradle.java.installations.paths=<你的 JDK17 目录>` 写进 `~/.gradle/gradle.properties` |
| 映射 | Mojang 官方映射（MDG 默认，字段名即 `official`） |
| 产物 | `build/libs/trial_portal-1.0.0.jar`（`reobfJar` 已做 SRG 重混淆，可直接进 `mods/`） |

> 这份表和设计文档里的表**不一致**（对方那台机器是 ForgeGradle 6 + Forge 47.4.10 + Gradle 8.8）。
> 已按「工程文件以本地为准」采用本机构的 MDG 工具链，差异逐条列在 `docs/开发文档.md` §2。

## 当前状态（2026-10-12 实测）

| 命令 | 结果 |
| --- | --- |
| `./gradlew build` | 绿。含 `> Task :checkTrialData`（8 条词条 + 3 份试炼 + 5 间房 + 2 份副本 + **3 份守塔** + 1 条配方 + 4 份掉落表 + **语言键 20 个定义 / 5 处引用** + **配置台账 29 项 6 组** + **生成点 3 处都已接恶意联动**全通过，未知字段一律算错）。产物 `build/libs/trial_portal-1.0.0.jar`（333,414 字节，87 个 class + 46 份 JSON，本次构建 sha256 前缀 `d844bb8c5a`；交接包 `dist/trial-portal-1.0.0.zip` 113 条目 / 779,890 字节 / sha256 `7e8e2cf7…`，`tools/verify-dist.py` 独立验证 PASS；**jar 不是逐字节可复现的**——135 个条目的时间戳随构建变化，同一份源码两次构建 sha 会不同，认身份要看**逐条目 CRC**（2026-10-12 实测：与 `2b0194865f` 那份 173 个条目 CRC 全等） |
| `./gradlew checkTrialData` | 绿；反向测试（写坏 JSON / 写错 id / 留空目录）都以非 0 退出并点名条目 |
| `./gradlew selfTest -PselfTest`（= runGameTestServer + 标记对账） | 绿，**517 条运行期断言全通过**（数据包链路、BOSS 层取 mobs[0]、层段词条池生效、免疫真的取消伤害、属性修饰符随实体存档、NBT 往返一致、方块/物品注册、`/tei trial list` 真执行、房间几何与走廊连通、隔间记账与拆场、门方块掉得出自己、**配方两头对账（玩法门一条不许有 + 通道恰好一条）与往返门方向**、守塔的塔与结界场地、守塔波次阶梯与四条分支裁决、拆塔 goal 的扣血对账与双目标让路、创造配置口的回滚与注册表核对、**守塔多关卡（清单行数/摘要可分辨/零-一-多场各走哪条支/点确认发得出去/清单编解码往返）**、**恶意联动（阶梯算法表、序号来源表、诊断口与判据同源、没装恶意时整条路关死、malice_level 不被会话副本吃掉，外加用假玩家真开三场回读生成点台账：守塔第 1 波该报 4 次就报 4 次、hard 档那批序号全是 2、写 malice_level 的那只确实顶掉阶梯）**、大厅四门布局与残留清理、**三种终端的右键契约**（手持方块点得动 / 能被镐判定 / 自动机点不动 / 没回返点不乱甩人）。这道门不认退出码，只认 `run/selftest-result.txt` 里的 `PASS/FAIL` |
| `./gradlew runGameTestServer` | 绿，服务端完整启动 + `已加载 3 份 试炼 定义 / 8 份 BOSS 词条 定义`，不需要 EULA |
| `./gradlew runData` | 绿，但**不算加载验证**（见下） |
| `runClient` + playtest-bridge | 绿：真客户端跑完「右键开局 → 波次推进 → 潜行结算发奖（`/clear` 数到 5 件铁锭）→ 死亡转存 → bind 换爬塔 → 续关第 13 层 → BOSS 三词条 79.2 血」。**这条门不可复现**：它靠的是作者机器上的一个兄弟工程（`../playtest-bridge-1.20.1`，一个把键鼠/命令/截图做成 HTTP 接口的模组）与本地实例，两者都不随本仓库发布。接手人要复现这一类判据，得自己搭一个同类的客户端驱动器，或按 §3 描述的手法手工操作。 |
| `runServer` | 未跑（要你自己同意 EULA） |

全工程 43 个 .java / 13,627 行 + 1 个离线校验工具类（1,121 行，不打进 jar）；JSON 46 份＝数据包 29（3 试炼 +
8 词条 + 5 间房 + 2 副本 + 3 守塔 + 1 配方 + 4 掉落表 + 维度与维度类型 2 + 方块标签 1）+ 资源 17（blockstate 5 +
block model 5 + item model 5 + lang 2）。
**还没有**：客户端信息面板、真正的可穿越传送门、组队、原版 GameTest（缺结构模板，走 `-PselfTest` 通道）。
**当前卡住的一环**：`run/eula.txt` 不在 `run/` 里，本工程自带的客户端起不来 ⇒ 需要真机在场的那类判据走
作者机上的一个整合包实例 + playtest-bridge（`docs/开发文档.md` §3；换 jar 要先把那个实例关掉），
不会替你点同意 EULA。

## 常用命令

```bash
./gradlew build              # 编译打包 + checkTrialData 数据闸门（Git Bash；CMD/PowerShell 用 gradlew.bat）
./gradlew build -PpackOverlay=xb   # 同上，但把 packOverlays/xb 的内容（整合包专用血量表/100 层爬塔）打进 jar
                                    # —— jar 自带 data 是全局的，所以"新建存档开箱就有内容"必须走这条，
                                    # 装在世界 datapacks/ 里的包只对该世界生效，且会盖住 jar 里的新版本
python tools/census_to_pack.py --census <实例>/mob-census.tsv --out build/ladder --id-prefix xb \
       --label "星罗棋布" --floors 100 --emit-table build/ladder/爬塔100层 --overlay xb   # 由普查表生成叠加层与逐层表
./gradlew checkTrialData     # 只校验 data/ 下的试炼与词条 JSON，不开游戏
./gradlew selfTest -PselfTest        # 加载 + 全部运行期断言，并强制核对 run/selftest-result.txt 的 PASS/FAIL
./gradlew runGameTestServer -PselfTest   # 同上但不回读标记（自测红时退出码可能仍是 0，别用它当验收）
./gradlew runGameTestServer -PmobCensus  # 顺带把注册表里每种可刷怪的真实血量/护甲/攻击力写成 run/mob-census.tsv
./gradlew runGameTestServer  # 只验加载与数据包链路
python tools/census_to_pack.py --census run/mob-census.tsv --out build/ladder   # 按血量分档生成递进难度 datapack（要先跑上面那条 -PmobCensus 才有 tsv；Windows 上 `py` 也可用，那是启动器别名）
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
    │   ├── block/  block/entity/     # TrialPortalBlock / ArenaExitBlock / WardPylonBlock + 方块实体
    │   ├── trial/                    # 定义模型 / 库 / 会话 / 状态机 / 事件 / 播报 / 自测
    │   ├── boss/                     # 词条模型 / 库 / 运行期生效点 / 玩家画像
    │   ├── arena/ArenaService.java   # 大厅与隔间：建台/拆台/cell 分配/进出与回返
    │   ├── command/  config/  data/  # /tei 指令树 / 29 项 6 组 TOML / 通用 JSON 重载监听器
    ├── main/resources/
    │   ├── assets/trial_portal/      # 方块状态、模型（复用原版贴图）、中英文 lang
    │   └── data/trial_portal/
    │       ├── dimension_type/arena.json   # 虚空场地
    │       ├── dimension/arena.json        # flat 生成器，零方块层
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
