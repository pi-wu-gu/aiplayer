# AI Player（模组化控制组件）

把 MC AI 玩家的**控制组件**收进单个模组，目标是**丢进任意 1.21.1 NeoForge 整合包的 `mods/` 就能用**。

> 立项背景与分析见：`<PCL2_DIR>/AI/docs/客户端打包为模组_可行性分析.md`（含"把 AI 模块一起打包"的 §9）。

## 版本号规则（2026-10-16 起）

- **`0.1` = 上一版方案**：即 `v0.1 → v0.2 → v0.3 → v0.3.1` 这条线的整体（mod 化摸索期，参照旧 JsMacros 栈做等价替换）
- **`0.2.1.x` = 当前序列**：`0.2` 当前架构（mod 内置控制 + 自建区 + 通用 API），
  末位 `x` = 迭代号（感知 / 交互 / UI 输入 / 容器 / 截图 / 权限表都在这一线递增，不拆中间位）
- 因此当前是 **`0.2.1.45`**（0.2.1.7-12 = **agent sidecar 全链路**：
  部署（首次启动解压 agent 包，跨版本继承 keys/config/logs）→ 拉起（ensure_running 复用不重复拉起）→
  看门狗（探活失败退避重启）→ attach/detach 生命周期（游戏退出杀自己拉起的树/放行）→
  游戏内动作 `agent_status / agent_start / agent_stop`（受 [sidecar].enabled 管辖）；
  keys 绝不入包，加载路径 <root>/config/keys.yaml 与 agent 侧一致。
  0.2.1.13 = 状态诊断字段 + 关闭播报；0.2.1.14 = `online_players` 全服在线名单；
  0.2.1.15 = **聊天时间戳**（chat_log.jsonl 与 /chat 输出带可读 `ts` 字段、重启重载历史不再丢时间戳；
  同包 sidecar 同步框架更新：会话日志逐条带 `ts`/`rel_s`、去除测试阶段开始播报只留结束统计），见下文专节）

**0.2.1.16-45 概览（详见各专节或 `docs/`）**：
| 版本 | 内容 |
|---|---|
| 0.2.1.26 | `connect`/`join` 高级联机立即连接 |
| 0.2.1.28 | `authAutoReply` + `authPassword` 认证服自动登录 |
| 0.2.1.31/32 | `sign_edit`/`sign` 写牌（一次 4 行，禁逐键打字）；`sign_read`/`sign_text` 读双面文本 |
| 0.2.1.33/34 | `be_nbt` 读方块实体 NBT（辉光管板文字唯一途径）；`item_nbt` 读物品组件（★查耐久用它） |
| 0.2.1.35 | **`dig` 挖掘**（走 `MultiPlayerGameMode` 破坏 API，绕开鼠标抓取门禁）+ `look_lock` 视角锁定 + 状态诊断字段 `window_active`/`mouse_grabbed` |
| 0.2.1.36 | `look_lock` 的 `face` 面锁定 |
| 0.2.1.37 | `look_lock` 的 `target`/`target_type` 实体锁定 |
| 0.2.1.38 | `entities` 输出 `id`；`target=<id>` 回填真实类型 |
| 0.2.1.39 | **`place` 放置方块**（走 `MultiPlayerGameMode.useItemOn`，含碰撞箱预检 + 支撑面自动选择 + `side=` 显式面） |
| 0.2.1.40 | `ControlServer` 失败判定修复（92 处 `_err` 误判成功 → 正则 `^[a-z][a-z0-9_]*_err:`）+ JSON 形式失败判定；包名 `net.aiplayer` |
| 0.2.1.41 | `AgentSidecar.deployTo` 顺序重构（部署失败保数据）+ 重部署运行守卫 + 支撑面精确匹配 |
| 0.2.1.43 | 内嵌 agent bundle 重打（关键词记忆 v2 + 待办清单，sidecar 侧关键词触发注入/每轮待办注入） |
| 0.2.1.44 | `get_container` 大输出限量参数（`compact`/`only`/`max_slots`/`find`，治 156 槽全量 dump 烧穿 token） |
| 0.2.1.45 | `get_container` 限量纪律入 agent 提示词 + `_dig_container` 专用摘要器；USER_DATA 跨版本继承修复（每文件回退） |

## 2026-10-18 性能成果：客户端启动 28 分钟 → 66 秒

三个独立根治（详见 `docs/2026-10-18-启动与运行缓慢-根因分析.md`）：

1. **TLM FastPack**（独立小 mod，见仓库 `pi-wu-gu/tlm_fastpack`）：跳过 TLM 默认包
   每次启动的全量重拷（UnionFileSystem 读侧 + Windows `DeleteFile0` 删侧双重慢路径），
   8-19 分钟 → 0.14-0.79 秒；reset/升级走增量同步全在 15 秒内
2. **`glDebugVerbosity:0`**：某模组坏纹理曾每帧刷 GL_INVALID_VALUE（3 小时 532MB 日志）
3. **FTB Chunks 地图锁**：region 数据饿死主线程致动作 20s 超时，已归档并配
   `prune_ftbchunks.py` 定期清理


> **0.2.1.40（已部署）**：`ControlServer` 失败判定修复（`endsWith("_err: 缺少 text")` → 正则 `^[a-z][a-z0-9_]*_err:`，修掉 92 处 `_err` 被误判成成功）；包名改为 `net.aiplayer` 后首次构建；内嵌 bundle 重打（210 条目，含 memory/approval/file_ops/wake_blacklist 四个新模块）。
> 实网验收 34/34（`<AGENT_REPO>/aitest/test_0_2_1_40_live.py`）：三种失败形态均判 `ok=false`、`command_errors` 精确 +3。

> ✅ **0.2.1.41（已部署）**：`AgentSidecar.deployTo` 顺序重构（stash → unzip → restore，修掉
> bundle 默认 config 覆盖继承配置）+ `warnIfPlaceholderConfig()` 占位符日志告警；**部署失败不再丢用户数据**（stash 仅在恢复成功后清理，
> 失败保留备份并 ERROR 日志给出路径；stash 改建在 target 同卷父目录）；**重部署运行守卫**
> （agent 在跑时 WARN 跳过，防删一半/解压一半）；**JSON 形式失败判定**（`{"ok":false,...}`
> 计入 ok 与 `command_errors`，覆盖 dig/place 全部 errJson 失败路径）；`INTERACTIVE_HINTS`
> 改下划线分段精确匹配（`bed` 不再误伤 `bedrock`）。bundle 重打（210 条目）+ 脱敏复扫 0 命中。

> ✅ **0.2.1.43（已部署）**：内嵌 agent bundle 重打——sidecar agent 侧新增**关键词记忆 v2**
> （`config/keywords.json` 短条目按词触发注入，三态填充 full/keyword_only/off，单次运行注入上限 3 条，
> 玩家专有条目在线时无条件带上）与**待办清单**（`config/todo.json`，每轮唤醒无条件注入，
> `finish_run` 之前应为空成纪律）；模组侧无动作变化，随 bundle 升 `mod_version`。
>
> ✅ **0.2.1.44（已部署）**：`get_container` **大输出限量参数**（默认全关=旧行为）：
> `compact=true`（只回非空槽 6 字段）、`only=container|inventory`（分段）、`max_slots=N`（硬裁）、
> `find=物品id子串`（定位）；限量生效追加 `shown`/`truncated`——背景：Sophisticated Backpacks
> 156 槽全量 ≈2.3 万字符，曾把 agent token 烧穿熔断。
>
> ✅ **0.2.1.45（已部署，2026-10-19 编译核验：jar 与部署机 md5 一致）**：agent 侧
> PRIMARY_PROMPT 补 get_container 限量纪律（禁反复全量 dump）+ context 层 `_dig_container`
> 专用摘要器（原错挂 `_dig_inventory` 必抛错回退）；模组侧 USER_DATA 跨版本继承改**每文件回退**
> （整目录回退会连用户改动一起冲掉）。

## 能力清单（v0.3 起累积至 0.2.1.x）

以下按主题组织：v0.3 的自建区/内置 AI 之后，UI 感知、容器、权限表、dig/place 等均在
0.2.1.x 线持续叠加（版本归属见各节标题）。

### 自建区 + 通用 API 配置（v0.3）

**以下为 v0.3 时点实测通过的内容**（真实整合包 `夯空学_新蒸程` / MC 1.21.1 / NeoForge 21.1.236）：

| 能力 | 结果 |
|---|---|
| **自建工具区** | ✅ 启动自动生成 `aiplayer/tools/` + `tools/index.json`（空注册表 + 字段建议） |
| **自建文档区** | ✅ 启动自动生成 `aiplayer/docs/INDEX.md`（告诉 agent 这块地方归它用） |
| agent 自己写文档 | ✅ `file_write docs/环境.md`（254 字节）→ `file_read` 读回内容一致 |
| **目录穿越防护** | ✅ `../../config/...` 被拒绝：`路径越界（必须在 <gamedir>/aiplayer/ 之内）` |
| 文件列目录 | ✅ `file_list` 返回相对路径 + 字节数 |
| **出站网络（调 LLM）** | ✅ `llm_ping`：HTTPS → `api.deepseek.com/chat/completions`，**HTTP 200 / 1.1 s** |
| **真实对话** | ✅ `llm_chat`：status 200、usage 正常返回 |
| 控制面可选鉴权 | ✅ 配了 `controlToken` 就必须带 `X-AI-Token` 请求头（否则 401） |

**v0.3 时点的动作规模：38 名 / 25 实现**（★这是历史值；**v0.2.1.45 时点见下方「当前规模」节**，
按部署实配口径逐档核对过注册表），当时新增两组：
- **自建区（工具/文档）**：`file_read` `file_write` `file_append` `file_list` `tools` `docs`
- **内置 AI（LLM）**：`llm_chat(llm)` `llm_ping`

**通用 API 配置**（以 **OpenAI 兼容 chat/completions** 为基准，一家配置覆盖 DeepSeek / OpenAI / 各类中转 / vLLM / Ollama）：

| 字段 | 说明 |
|---|---|
| `networkEnabled` | 出站网络总闸（关掉则 `llm_chat` 直接拒绝） |
| `apiProvider` | `openai-compatible`（自动补 `/chat/completions`）｜`deepseek`｜`custom`（base 视为完整 URL） |
| `apiBaseUrl` | 端点；已按现有密钥配成 `https://api.deepseek.com` |
| `apiKeyEnv` | **优先**读这个环境变量取密钥（避免明文落盘） |
| `apiKey` / `model` | 明文密钥与模型名（键名沿用 v0.1，避免旧 toml 失效） |
| `apiHeadersJson` / `apiExtraBodyJson` | 通用兜底：额外请求头 / 额外请求体字段 |
| `apiTimeoutMs` / `apiTemperature` / `apiMaxTokens` | 超时 / 温度 / 上限 |
| `controlToken` | 控制面可选鉴权（入站），非空时必须带 `X-AI-Token` |

> 本能（进食/睡觉/避险/重生/重连/拾取）**不写进模组**——模组只提供原子动作，由 agent 用自建区里的工具与文档自己组装。

### UI 感知（0.2.1.x，已实机验证）

| 动作 | 说明 | 实测 |
|---|---|---|
| `get_screen`(别名 `screen`) | 当前界面的类名 / 标题 / 控件树（含中心坐标与文本）/ 容器槽位（含 screen 坐标与物品）/ 光标物品 / 悬停槽 | ✅ 进服过程读到 `ConnectScreen`（含"取消"按钮），背包读到 `InventoryScreen`（46 槽） |
| `screen_click` | 按 GUI 坐标点击（0左 1右 2中） | ✅ 点槽5(钻石头盔)→`cursor` 拿起、`hovered=5`（证明坐标算准）→再点放回 |
| `screen_click_widget` | 按文本子串或序号点控件 | ✅ |
| `overlay`(别名 `actionbar`) | 读动作栏文本（很多 mod 提示不走聊天栏） | ✅ 读到服务端"还有1次清理"提示 |

**石碑传送已打通**（这是做 UI 感知的直接动机）：
`find_block` → `look`（`looking_at` 确认命中）→ `move` 靠近 → 切空手 → `keys [key.use]` 长按右键
→ `get_screen` 读到 `WaystoneSelectionScreen` + `WaystoneList` → `screen_click` 点列表条目 → **成功传送**
（坐标 200.5,77,-1117.3 → 205.5,77,-1115.5）

⚠️ **石碑语义（用户纠正）**：右键石碑打开的是"选择目的地"列表，里面是**已知的其他石碑**，
**不能传送到你右键的那一个**；未激活的石碑要先激活/命名。完整结论已由 agent 写进
`aiplayer/docs/石碑传送.md`（自建文档区）。

**UI 点击的两个必须照做的细节**（旧 index.js 的"坑15"）：
① 私有 `xpos/ypos` 不随鼠标回调更新 → 点之前必须先同步；
② 1.21.1 没有 `onRelease` → 按下/释放都走 `onPress(long,button,action,mods)`，action 1=按下 0=释放。

### 按键与视角控制（0.2.1.x，已实机验证）

| 动作 | 参数 | 实测 |
|---|---|---|
| `turn` | `dyaw` `dpitch`（相对）或 `absolute:true` + `yaw` `pitch` | ✅ yaw 94.88→139.88、pitch -2.86→17.14，`/state` 复核一致 |
| `look` | `x,y,z` 或 `yaw,pitch` | ✅ |
| `look_lock`(别名 `lock_view` `aim_lock`) | `x,y,z` + `duration_ms`(≤60000，默认5000) + `face`(可选) + `target`/`target_type`(实体，0.2.1.37)；无参=查状态 | ✅ 见下文 0.2.1.35 / 0.2.1.36 / 0.2.1.37 |
| `press` | `key`（绑定名或物理名） | ✅ `key.keyboard.e` 打开背包 |
| `keys` | `keys[]` + `duration`(ms) | ✅ |
| `move` | `forward/back/left/right/jump/sneak/sprint` 或 `dir`，+ `duration` | ✅ 1 秒前进约 3.5 格 |
| `jump` / `sneak` | `duration`(ms) | ✅ |
| `select`(别名 `slot`) | `slot` 0–8 | ✅ 槽0=铁锭×27、槽1=扳手×1，且**同步服务端** |
| `scroll`(别名 `wheel`) | `dir` up/down + `amount` | ✅ |
| `mouse_move` | `x,y`（窗口像素） | ✅ |
| `close_screen` | — | ✅ |

**按住类动作一律用 `KeyMapping.setDown(boolean)`**（和旧 JsMacros 版 `kbSet()` 同一路子）——
持续型按键（forward/use/attack…）必须置 KeyMapping 的 down 状态，只发一次 click 会被 `consumeClick`
的事件闩锁吞掉，这是当年"移动类按键无效"的根因。

**用 AccessTransformer 取代反射**：新增 `src/main/resources/META-INF/accesstransformer.cfg`，把
`MouseHandler.onScroll/onMove/onPress` 与 `xpos/ypos` 合法放通（签名已用 javap 对
`neoforge-21.1.236.jar` 核实）。旧 index.js 里那 23 处反射从此变成**编译期可见**——
映射一变就编译不过，而不是运行期静默失效。MDG 会自动把 AT 打进产物 jar。

**动作名 47 个 / 实现 33 个。**（该版本时点数）

### 容器操作（0.2.1.2，已实机验证）

| 动作 | 参数 | 实测 |
|---|---|---|
| `get_container`(别名 `container` `cont_info`) | `find`(物品 id 子串)、`compact=true`(只回非空槽 index/id/count)、`only=container\|inventory`(只看一段)、`max_slots=N`(槽数上限，200 硬顶内再收紧) | ✅ 背包 `InventoryMenu` 46 槽：`ranges` 分出 **0-4 合成区 / 5-45 背包区**；`cls` 认出 5-8 是 `ArmorSlot`；每槽带 `pick`(mayPickup) / `place`(mayPlace 探测)；★0.2.1.44 起四个限量参数治大输出（Sophisticated Backpacks 156 槽全量 ≈2.3 万字符，曾把 agent token 烧穿熔断）——限量生效时追加 `shown`/`truncated`，`truncated=true` 必须换 find/only 分段读 |
| `click_slot`(别名 `slot_click` `cont_click`) | `slot`(索引，-1=界面外) `button`(0左1右2中) `shift` `hotbar` `drop` `mode`(auto/menu/mouse) | ✅ 左键拿起铁锭×27→再点放回；右键分半（云杉板 62→31/31）；QUICK_MOVE 与 vanilla 合并语义一致；`slot=-1` 丢光标；`drop=true` 丢槽位物品 |
| `cont_button`(别名 `menu_button`) | `id` | 协议走通（`handleInventoryButtonClick`），按钮菜单实测待补 |

**设计要点**：
- **默认走发包路径** `MultiPlayerGameMode.handleInventoryMouseClick`（与真人鼠标点击同一条网络路径）：
  不依赖坐标/缩放换算，槽位滚出视野也能点；创造模式菜单索引语义不同 → 自动回退坐标点击
- **槽位分区**用 `Slot.container`（公开字段，无需 AT）：是不是玩家背包 → 容器区/背包区，压成连续区间 `ranges`
- **成品槽识别**：`cls=ResultSlot` / `FurnaceResultSlot` + `place=false`（mayPlace 探测，空槽也能识别）——
  合成/熔炼取成品就靠这个

**★点击效果必须异步读**（新踩坑）：容器点击的真实效果要等服务端回包
（`ClientboundContainerSetSlotPacket` 等），而**回包是在客户端主线程处理的**——动作本身就运行在主线程，
在主线程 sleep 等回包 = 把自己锁死。解法：scheduler 排延迟任务 → `mc.execute` 写 `postClickInfo`
→ `get_container` 带 `post_click_age_ms` / `post_click` 字段（光标/悬停/非空槽数）。
**v0.2.1.3 起改两段式（350ms + 900ms）**：本地/近端服务器 350ms 那帧就收敛，但 bot 连的是远端服务器
（实测 RTT 可达数百 ms），900ms 二帧兜底；后写的覆盖先写的，`post_click` 收敛到最终态。
连发点击前必须先 `get_container` 确认光标状态（结果 note 里有连发警告）。

**★`slot=-1` 丢弃是 `PICKUP`+`-999`，不是 `THROW`**（实测教训 + 反字节码核实）：
`THROW` 是"丢槽位里的物品"（Q 键语义，对应 `drop=true` 参数，**要求光标为空**否则静默无效）；
界面外点击（丢光标物品）是 `PICKUP` + slotId=-999。
第一版用错成 THROW，光标物品原封不动——服务端静默忽略，不回错。
按钮语义（`doClick` 字节码 594-655）：`slot=-1` 时 **button 0=丢整堆、button 1=丢一个**；
`drop=true` 时 **button 0=丢一个、button 1=丢整堆**。光标为空时会显式报错而不是静默成功。

**QUICK_MOVE 合并语义与 vanilla 完全一致**：shift 点 62 个云杉板，目标热键栏 43 槽已有 19 个
→ 先补满 43 到 64，剩余 17 进空槽 38（不是整堆搬）。agent 按 vanilla 规则预期即可。

**动作名 62 个（含别名）/ 实现 57 个。**（该版本时点数）

### GUI 文本输入（0.2.1.3）

| 动作 | 参数 | 说明 |
|---|---|---|
| `screen_type`(别名 `type_text` `ui_type`) | `text`(必填，`""`=清空) `index`(控件序号) `x,y`(GUI 坐标) | 往当前界面的 EditBox 写文本 |

**设计要点**：
- **直接写值，不合成键盘事件**：反字节码核实 `EditBox.setValue` 末尾会调用 `onValueChange`（responder），
  模组的搜索/过滤 responder **立即生效**（维度网络 GUI 的 containerTick 还会在下一 tick 比对
  `lastSearchText` 兜底）。而 `press` 的 `KeyMapping.click` 只累加 clickCount，到不了 EditBox 的
  keyTyped——合成键盘那条路是死的
- **目标框选取优先级**：`index`（get_screen 的控件序号 i）> `x,y` 命中 > 当前焦点 > 界面第一个 EditBox
- **通用**：维度网络搜索框 / JEI 搜索 / 告示牌与书魔命名框都走同一条路

**动作名 68 个（含别名）/ 实现 42 个。**（该版本时点数）

> **当前规模（v0.2.1.45）**：源码注册调用共 **57 个**（含全部条件闸全开）。
> 按**部署实配**（`sidecar.enabled=true` / `selfToolEnabled=false` / `commandEnabled=true` /
> `fileMode=full` / `networkEnabled=true`）实测口径：**full = 56 实现 / 103 动作名（含别名）**；
> `gameActionMode` 逐档摘除后 **safe = 48 / 85**、**observe = 45 / 82**。
> `selfToolEnabled=true` 时全开 = 57 实现 / 105 名；出厂默认（`sidecar.enabled=false`）= 53 实现 / 98 名。
> （口径说明：条件闸在装配期生效，`{"type":"actions"}` 的 count 随配置变化；以上数字按
> `Actions.java` 注册调用逐档展开核算，与 `/actions` 自省一致。）

### 截图（0.2.1.4）

| 动作 | 参数 | 说明 |
|---|---|---|
| `screenshot`(别名 `shot` `f2`) | `mode`(`f2` 默认 / `grab`) · `query=true`(只查上次结果) | 截当前画面存 `<gamedir>/screenshots/`，并记录文件名供回查 |

**设计要点**：
- **★为什么不能复用 `press`**：1.21.1 的 F2 截图在 `KeyboardHandler#keyPress` 的**原始按键事件**分支里触发；
  而 `press` 只做 `KeyMapping.click()`（累加 clickCount）、`keys` 只做 `setDown()`，两条都到不了那个分支
  —— **实测三条路径（`press key.screenshot` / `press key.keyboard.f2` / `keys key.screenshot`）均无输出文件**
- `mode=f2`（默认）：反射调 `mc.keyboardHandler.keyPress(win, GLFW_KEY_F2, 0, 1, 0)` 再补一个 release，
  **等价于真人按下 F2**，顺便验证"按键注入链"是否够真实；反射失败自动回退 `Screenshot.grab`
- 落盘是**异步**的（MC 丢给 ioPool 写 PNG），主线程不能阻塞等 → 立即返回；
  600/1600ms 后由调度器回查目录把新文件名写进 `lastShotFile`，用 `{"type":"screenshot","query":true}` 取回
- 实测：`mode=f2` 与 `mode=grab` 均产出 **854×480 PNG**；`grab` 还能拿到 MC 原生提示
  `已将截图保存为<文件名>.png`
- **★设计边界（用户明确要求，2026-10-16）**：截图是**纯按需可调用能力** ——
  **不写入 `state`、不随轮次/定时自动采集、不参与任何决策回路或自动流程**；
  **是否调用完全由 agent 自行决定**。实现上据此保持隔离：`Actions.java` 里除注册与自身实现外
  **无任何引用点**（`state()` / 聊天 / LLM / 轮次逻辑均不触碰）
- **意义**：截图以 PNG 落盘，**agent 侧可直接读图** → 真正的视觉感知（不必再依赖外部窗口抓帧 + 格式转换）

### 权限表（0.2.1.4 起 · 0.2.1.5 增第五轴 · 0.2.1.6 自建工具生效）

一次收口的权限开关，配置都在 `config/aiplayer-common.toml`。

| 配置项 | 取值 | 管什么 |
|---|---|---|
| `permissions.fileMode` | `full` / `readonly` / `off` | `file_read` `file_write` `file_append` `file_list` `tools` `docs` |
| `permissions.commandEnabled` | bool（默认 true） | 是否允许 `command` 动作（向游戏发 `/命令`） |
| `permissions.selfToolEnabled` | bool（默认 false） | **自建工具执行**（v0.2.1.6 起生效）：开=注册 `tool_run`，可跑 `tools/index.json` 里的宏；关=动作不注册 |
| `permissions.gameActionMode` | `full` / `safe` / `observe`（默认 full） | **v0.2.1.5 新增**：游戏内动作——`safe` 摘掉改世界/改物品的输入类（`press`/`keys`/`click_slot`/`cont_button`/`screen_click*`），`observe` 再摘掉 `move`/`jump`/`sneak` |
| `networkEnabled`（既有键） | bool（默认 true） | 出站网络：`llm_chat` / `llm_ping` |

**`gameActionMode` 的三档语义**（dig(0.2.1.35)/place(0.2.1.39) 是 API 路径的改世界动作，与 press/keys 同轴被摘；
其余"世界修改"面仍是输入类）：

| 档位 | 保留 | 摘掉 |
|---|---|---|
| `full`（默认） | 全部 | —— |
| `safe` | 移动 / 视角 / 感知 / 查询 / **界面与容器读取**（`get_screen` `get_container` `screen_type`） | `press` `keys`（放/挖/攻击/用物品/开界面）、`click_slot` `cont_button`、`screen_click` `screen_click_widget` |
| `observe` | 视角 / 感知 / 查询 | 再摘 `move` `jump` `sneak`（纯观察 + 问答，不走动） |

**设计要点**：
- **闸门在装配期，不在调用期**：被禁的能力**根本不进注册表**，`/actions` 的 `groups` 里看不到它，
  模型也就不会浪费轮次去试。提示词只是建议，装配期才是硬边界。
- **配置热重载自愈**：每次 `dispatch` 前比对权限快照，变了就重建注册表 —— 改 toml **无需重启客户端**
  （实测 fileMode：`full 68 名 → readonly 66 → off 62 → full 68`；
  gameActionMode：`full 68 → safe 56 → observe 53`，均即时生效）
- **兼容性优先**：新增键**默认值 = 旧行为**（`fileMode="full"`、`commandEnabled=true`），
  旧 toml 由 NeoForge 自动补键，既有配置不失效；沿用 `COMMON` 类型与 `aiplayer-common.toml` 文件名。
- **被禁 ≠ 拼错**：受权限表管辖的动作缺席时返回 `denied: xxx 被权限表禁用…`（而非 `unknown`），
  便于审计区分"探测被禁能力"与"写错名字"。
- **审计**：越界路径与被禁动作的调用尝试都会**计数并落盘** `<datadir>/logs/denied_access.log`；
  `GET /stats` 的 `permission_denied` 同步可见。
- `GET /actions` 新增 `permissions` 字段，回显当前实际模式与拒绝计数 —— agent 能自查"为什么某个动作没了"。

### 路径硬化（0.2.1.4）

`AiPlayerPaths.resolveInside` 由"只做 `normalize()`"升级为三道校验：

1. 字面穿越（`..`、根外绝对路径）→ 拒绝
2. **尽力 realpath**（`toRealPath` 失败必须回退 —— 写新文件时路径尚不存在）→ 二次包含判定
3. realpath 拿不到但路径上存在**符号链接 / junction** → 保守拒绝

包含判定在 Windows 下按小写比较（大小写不敏感）。实测：`../../../config/...` 与 `C:/Windows/win.ini`
均被拒绝并计入审计。

### 自建工具执行 tool_maker（0.2.1.6）

把 `permissions.selfToolEnabled`（默认 **false**）从"预留开关"落成真功能：开启后注册 `tool_run` 动作，
agent 可以**自己定义宏**（一串已有动作的序列）并一次性执行。

**怎么定义**：在 `<datadir>/aiplayer/tools/index.json` 里加条目，并在 `tools/<name>/` 下写入口文件。

```jsonc
// tools/index.json
{ "version": 1, "tools": [
  { "name": "greet", "entry": "main.json", "args": ["who"],
    "desc": "看向玩家并打招呼", "enabled": true, "risk": "只读" }
]}

// tools/greet/main.json
{ "steps": [
  { "type": "say", "text": "你好 ${who}" },
  { "type": "look", "pitch": 0 }
]}
```

调用：`tool_run name="greet" who="<PLAYER_A>"`（`${who}` 会被替换成实参）。

**★安全边界**（这是它默认关闭的原因）：

| 约束 | 做法 |
|---|---|
| 不能越权 | 步骤里的动作必须**在当前注册表内** → 权限表**自动级联**（`gameActionMode=safe` 下含 `press` 的宏直接判定不可用；`fileMode=off` 下含 `file_write` 的同理） |
| 不能递归 | 禁止宏里出现 `tool_run`（防死循环），也不允许宏调用宏 |
| 不能拖死 | 步数 ≤64、单步 `repeat` ≤20；结果按 200 字符截断 |
| 不能越界 | 入口路径走 `AiPlayerPaths.resolveInside` 硬化；`entry` 不允许含路径分隔符或 `..` |
| 可审计 | `tools` 动作回显每个条目的 `ok`/`issues`（引用不可用动作会被列出来）、`risk`（只读/写世界/破坏） |

执行全程走 `dispatch()`，与外部命令同一条路 —— 权限与审计天然一致（被禁动作在宏里同样返回 `denied`，
宏整体 fail-fast 并报告 `failed_at`）。

---

### 状态诊断字段与关闭播报（0.2.1.13）

**背景（2026-10-16 实机事故）**：bot 开着物品栏界面 30+ 分钟——原版 MC 界面开着时玩家实体不采样移动输入，
`move/keys/jump` 全部静默失效（位置像素级钉死），而 `look`/`press` 不受影响；模组 `state()` 当时没有
`screen` 字段，agent 拿不到任何失败信号，反复重试到 token 熔断（白烧 87 万）。

**修复①：`state()` 补诊断字段**（v0.2.1.13 起）：

| 新字段 | 内容 |
|---|---|
| `screen` | 有无界面开着：`null`=无；有界面=`{class, title, pause}`。**界面开着=移动全废的第一信号** |
| `onGround` | 是否着地（false=掉落中） |
| `sprinting` / `flying` | 疾跑 / 飞行状态 |
| `vehicle` | 骑乘实体类型（未骑乘=null） |
| `effects` | 状态效果 `{id, amp, left秒}`（slow 等直接改变移动表现；无效果时省略） |

配套 agent 侧修复（框架 `mc_bridge.py`，随 sidecar bundle 分发）：`mc_state` 未知字段回
`_unknown_fields`+`_hint`（不再静默丢弃）；移动类动作执行后自动校验位移，零位移返回 `_warn`
（头号根因=界面开着→`close_screen`；无界面无墙=输入起步延迟→换 `duration>=1000ms` 重试一次）。

**修复②：客户端关闭自动播报**（用户 2026-10-16 约定的服务器礼仪）：

- 窗口关闭等正常退出路径：JVM shutdown hook 自动向服务器发一条聊天（配置项 `shutdownMessage`，
  默认 `[自动] 客户端关闭中，稍后回来`，留空=不播报；单人/主菜单不播报）
- ★硬杀进程（部署换 jar 的 `taskkill`）不走 hook——那类重启必须**先用 `say` 动作说明原因再杀**
  （已写进 agent 的 PRIMARY_PROMPT 硬性红线第 9 条）

---

### 全服在线名单 online_players（0.2.1.14）

**背景**：`players` 动作与 `state.players` 只返回**附近已加载玩家**（遍历 `level.players()`，上限 10 个）——
bot 身边没人时列表为空，曾被误读成"服务器没人、某玩家不在线"。原版客户端 Tab 列表
（`connection.getOnlinePlayers()`）本就有服务端下发的**全服在线名单**（含其它维度/远距离玩家），
此前未暴露成动作。

**新增动作** `online_players`（别名 `online` / `player_list` / `tab_list`；只读，不受权限表管辖）：

```
{"type":"online_players"}
→ {"count":3,"players":[{"name":"<PLAYER_A>","ping":32},
   {"name":"universal_0913","ping":88},{"name":"Qoder_Bot","ping":120}]}
```

按延迟升序；未连接服务器返回 `err`。与 `players`（身边有谁、多远）互补：**打招呼/等人之前先查它**。
框架侧同步：`mc_act` 清单标注 `players(仅附近)`，PRIMARY_PROMPT 新增【谁在线】段。

---

### 聊天时间戳（0.2.1.15）

**背景**：`chat_log.jsonl` 落盘只有 unix 秒 `t`（人读不便），且重启重载历史时 `t` 被丢成 0
（历史消息在 /chat 里永远显示 `--:--:--`）。

**改动**：
- `chat_log.jsonl` 每行新增可读 `ts` 字段（`yyyy-MM-dd HH:mm:ss`，本机时区）：`{"id":N,"t":…,"ts":"2026-10-16 22:30:00","text":"…"}`
- 重启重载历史时解析 `t`（新增 `extractDouble`），**时间戳跨重启不丢**
- `GET /chat` 输出同步带 `ts`
- 同包 sidecar 框架同步（agent 侧）：`session_*.jsonl` 每条记录带 `ts`（毫秒）+ `rel_s`（会话内相对秒）；
  聊天注入跨天消息自动带日期；**去除测试阶段开始播报**（不再发"[自动] 测试开始"，也不进系统提示），
  系统提示只保留**结束收尾统计**（session_stats）；重启前 say 原因的红线保留不变

---

### 挖掘 dig 与视角锁定 look_lock（0.2.1.35）

**背景（2026-10-17 实锤的诊断）**：旧挖矿法是 `look` 瞄准 + `keys ["key.attack"]` 长按——
即"注入真实按键等原版链路破坏方块"。实测**在窗口后台时零进度**：1.21.1
`Minecraft.handleKeybinds()` 末尾四门禁（`screen==null && !flag2 && keyAttack.isDown() &&
mouseHandler.isMouseGrabbed()`）中，`isMouseGrabbed` 依赖 `grabMouse()`，而 `grabMouse()` 前置
`isWindowActive()`——**游戏窗口不是前台时静默 no-op**。四层证据（源码门禁 / 前台窗口=Explorer /
物理鼠标微动 20px 摄像机零响应 / 排除项全清）见运行时目录
`<gamedir>/aiplayer/docs/记忆/day/2026-10-17-挖方块诊断.md`（该目录由 agent 在运行期维护，不在本仓库内）。
**其他动作不受影响**是因为 `look/press/keys/move` 全是模组直设 `KeyMapping` 状态或发包，
不过原版的"鼠标抓取"门禁。

**新增动作 `dig`**（别名 `break` `mine`；受权限表第五轴管辖，`gameActionMode=safe` 起缺席）：

```
{"type":"dig","x":196,"y":77,"z":-1106,"timeout_ms":15000}
→ {"ok":true,"result":"dig_started","block":"minecraft:oak_log","pos":[196,77,-1106],"timeout_ms":15000,
   "hint":"破坏进行中（跨 tick 驱动）；必须轮询 dig_status 直至 dig_broken/dig_timeout/dig_failed 终态，勿重复发起"}
```

- **走 `MultiPlayerGameMode` 破坏 API**（`startDestroyBlock` + 每 tick `continueDestroyBlock`），
  **不经按键注入链路** → 不受鼠标抓取/窗口前台门禁影响；`getDestroyStage()` 提供 0-9 进度
- **三段式异步范式**（复用 sign_edit 的 pending 模式）：动作体只发起立即返回 →
  `ClientTickEvent.Post` 每 tick 推进 → 终态写墓碑 + `ChatBuffer.add`（模型从聊天尾部可见）
  （★为什么必须拆：`ControlServer.runOnClientThread` 把动作体放主线程并等返回，
  动作体若阻塞等 tick 会死锁——tick 永不来）
- **每 tick 复检**：玩家/世界丢失、死亡、界面打开、使用物品中、走出 6 格距离 → 立即失败并说明原因
- **完成判定**：连续 3 tick 该位置为空气才算 `dig_broken`——客户端破块是先斩后奏（预测发包），
  服务端可能驳回，单 tick 空气会误报
- **超时报错附诊断**：`progress_stage=0` 且耗时明显低于徒手工时 → 工具不对（先 `select`）或
  方块不可破坏（基岩/硬度-1）；进度有累积没到顶 → 加大 `timeout_ms`
- `dig_status`（别名 `dig_state`；只读常驻）：idle / running（带 `progress_stage`）/ 终态墓碑
  （附 `ended_age_ms` 防止模型拿陈旧结果误判）
- `stop=true` 取消进行中的 dig

**新增动作 `look_lock`**（别名 `lock_view` `aim_lock`；纯视角，与 look/turn 同权限待遇）：

```
{"type":"look_lock","x":196,"y":78,"z":-1106,"duration_ms":5000}
→ {"ok":true,"result":"look_lock_started","aim":[196.0,78.0,-1106.0],"yaw":…,"pitch":…,"duration_ms":5000}
```

- 每 tick 重算眼睛→目标朝向并写 `yRot/xRot`（连带 `yRotO/xRotO` 防渲染插值抖动）；
  玩家移动时自动跟随（解决"look 一次后边移动边对准"的漂移）
- **中断检测**：本 tick 朝向 ≠ 上 tick 写入值（`Mth.wrapDegrees` 差值 > 0.05°）→ 立即结束并报告
  `interrupted: 视角被外部操作改变(dyaw=…, dpitch=…)`——真实鼠标/look/turn/mouse_move 全覆盖；
  `look`/`turn`/`mouse_move` 动作末尾还显式调用取消（原因更即时明确）
- 到期 `duration_end`（非错误）；`look_lock` 无参查状态（running 带 `remaining_ms` / ended 带原因）
- 整数坐标瞄方块中心（+0.5），小数坐标瞄原点点（与 `look` 语义一致）

**look_lock 面锁定 `face`（0.2.1.36 新增，可选参数）**：

```
{"type":"look_lock","x":196,"y":76,"z":-1106,"face":"up","duration_ms":5000}
→ {"ok":true,"result":"look_lock_started","aim":[196.5,77.0,-1105.5],"face":"up","yaw":…,"pitch":…}
```

- `face` 六选一：`up`/`down`/`north`/`south`/`east`/`west`（别名 `top`→up、`bottom`→down；
  `center`/`auto`/省略=默认瞄方块中心）——把瞄准点从方块中心改为**该面的面中心**
- ★**只对整数方块坐标有效**：小数坐标=精确点模式，与 face 二选一（同时给非 center 的 face 报错）
- 典型用途：放置方块时锁定目标格**下方地板的顶面**（配合 `looking_at.face=="up"` 确认后 `key.use`）
- 面中心偏移：up=(+.5,+1,+.5) / down=(+.5,0,+.5) / north=(+.5,+.5,0) / south=(+.5,+.5,+1)
  / east=(+1,+.5,+.5) / west=(0,+.5,+.5)
- 返回与状态查询均回显 `face` 字段；锁定维持/中断语义与无 face 时完全一致

**look_lock 实体锁定 `target`（0.2.1.37 新增）**：

```
{"type":"look_lock","target":812,"duration_ms":5000}
→ {"ok":true,"result":"look_lock_started","target":{"id":812,"type":"minecraft:skeleton","name":"Skeleton","aim":"center","dist":17.2},"yaw":…,"pitch":…}
{"type":"look_lock","target_type":"minecraft:cow"}        ← 64 格内最近的一只
{"type":"look_lock","target":812,"aim":"eyes"}            ← 瞄眼睛高度（默认 center=碰撞箱中心）
```

- `target`（实体 id，来自 `entities` 动作返回）或 `target_type`（类型 id）**二选一**，且与 `x,y,z`/`face`
  **互斥**（实体没有面的概念；偏瞄用 `aim`）
- 每 tick 按 id 重新取实体（`level.getEntity(id)`，不缓存实体对象防卸载/复用）并重算朝向——实体移动自动跟随
- 结束条件新增：`target_lost: 实体消失或死亡` / `target_lost: 实体超距(>8 格)`
- 典型用途：盯着生物/玩家持续对准（移动战）、警惕某个方向
- 状态查询与墓碑回显 `target{id,type,aim}`

**`entities` 输出实体 id + id 直锁类型回填（0.2.1.38 新增）**：

```
{"type":"entities"}
→ [{"id":262134,"type":"minecraft:player","dist":7.4,"pos":[…]}, …]
```

- 背景：0.2.1.37 的 `target=<id>` 直锁模式的报错提示"先用 entities 拿有效 id"，
  但 `entities` 当时**不返回 id**——模式实际不可用；本版补齐：每项新增 `id` 字段（`e.getId()`）
- 同时修复：`target=<id>` 启动时把实体真实类型回填进锁定任务（此前查询/墓碑回显 `type:"null"`）
- 实测（0.2.1.38 补测）：`entities` 返回 id=262134（玩家 <PLAYER_A>）→ `look_lock target=262134`
  直锁成功；无效 id 报 `look_lock_err: 找不到实体 id=999999（已卸载/超距/维度不同；先用 entities 动作拿有效 id）`

**新增动作 `place` 放置方块（0.2.1.39）**：

```
{"type":"place","x":6,"y":71,"z":-14}
→ {"ok":true,"result":"place_sent","item":"minecraft:dirt","target":[6,71,-14],
   "support":[6,70,-14],"support_block":"minecraft:grass_block","face":"up","hand":"main",
   "support_dist":1.2,"player":{"pos":[…],"aabb":[[…],[…]]},
   "hint":"已发起…约 2-3 tick 后用 place_status 收确认，或 block_at 复核目标格"}
{"type":"place_status"}
→ {"ok":true,"result":"place_placed","landed":"minecraft:dirt","elapsed_ms":148,…}
```

- **背景（2026-10-17/18 实锤）**：旧放置法是 `look` 瞄准 + `keys ["key.use"]`——实测**窗口非前台时
  完全失效**（`press`/`keys`/物理名/长按/look_lock 全试过）。根因：use 键依赖的 `mc.hitResult`
  与 `looking_at`（模组自算 `p.pick(6.0,0,false)`）**不是一套**（相机插值/窗口状态耦合）；
  对照实验 `press key.inventory` 能正常开背包，说明 `KeyMapping.click` 机制本身没问题——是
  use 这条链路不可靠。与 dig 同源同解：改走 **`MultiPlayerGameMode.useItemOn` 发包路径**
  （与真实玩家右键同一条链路，服务端照常跑事件/保护/距离/碰撞校验）
- **两段式协议**（同 dig）：`place` 立即返回 `place_sent` → 约 3 tick 后自动确认 →
  `place_status`（别名 `place_state`；只读常驻）查 `place_placed`（附 `landed` 落块 id）/
  `place_failed`（附 `detail` 诊断）/ `place_unverified`；也可 `block_at` 复核。
  ★禁止放置后不等确认就继续依赖该方块的操作
- **★玩家碰撞箱约束（用户 2026-10-18 提醒后固化）**：玩家不是质点（AABB 宽 0.6 高 1.8）——
  目标格与玩家身体重叠时服务端实体碰撞校验必然驳回。发起前**预检并直接报错**（错误里带
  目标格/玩家双方 AABB 数值与脚底坐标），此时应 `move` 走开/跳起或换目标格，重试同一格无用。
  实测场景：玩家脚底 y=71 时目标格 (6,71,-14) 与身体重叠 → 旧法（按键）与 API 路径都会失败
- **支撑面自动选择**：省略 `side` 时按 下方地板(up) → 四周墙(north/south/east/west) → 上方天花板(down)
  顺序找第一个可用支撑格（跳过空气/流体/无碰撞/带界面方块/常见交互方块），距离用服务端
  `canInteractWithBlockAt` 同款尺子（脚底→支撑格底中心 ≤ `block_interaction_range`+1）；
  `side=` 可显式指定被点击面（放置位 = 支撑格 + 该面方向）；响应回传 `support`/`face`/
  `support_dist`/`support_interactive`（可交互支撑面会警告"右键可能先开界面"）
- **发起前校验**（全部 `place_err:` 明确报错）：界面打开中 / 使用物品中 / 已死亡 /
  手持非方块（回显当前手持）/ 物品冷却中 / 冒险·旁观模式 / 目标格已有方块（可替换物除外）/
  支撑格是空气 / 支撑格超距
- **失败诊断**（`place_status` 的 `detail`）：手持已变/冷却中/模式不允许/界面开着/支撑格被挖/
  支撑面带界面/玩家放置期间挪进目标格/服务端驳回（距离·碰撞·保护·特殊放置规则）

**配套 `state()` 新诊断字段**（0.2.1.35）：`window_active`（游戏窗口是否前台）/
`mouse_grabbed`（鼠标是否被游戏抓取）——排查"该生效却没反应"的怪现象先看这两个。

---

## v0.2 —— 内嵌控制服务（已替代 Python 桥接）

**v0.2 实测通过的内容**（同环境）：

| 能力 | 结果 |
|---|---|
| 内嵌 HTTP 服务 | ✅ `http://127.0.0.1:8780`（只用 `java.base`，无外部依赖、无嵌套 jar） |
| `GET /state` | ✅ 实时返回 坐标/血量/饥饿/维度/游戏模式/时间/手持/视线方块/附近玩家/聊天尾 |
| `GET /chat?since=&limit=` | ✅ 单调 id + 落盘（`aiplayer/chat/chat_log.jsonl`），支持"只取新消息"游标 |
| `GET /actions` | ✅ 按类分组返回全部已注册动作（v0.2 时点 29 名字/18 实现；**当前规模见下方「当前规模」节**） |
| `POST /command` | ✅ `{"commands":[{...}]}`，**逐条在主线程执行并等完成**，单条与批量都通 |
| `say` 真实送达 | ✅ 游戏内确实出现 `[world] <BOT_NAME>: ...` |
| `find_block`（地形工具） | ✅ 搜到 2 个石碑、最近 (202,77,-1117) 距离 8，**reads 12701 / 5 ms**（JsMacros 版同参数 208 ms） |
| `get_inventory` / `time` / `block_at` / `look` / `press` / `keys` / `entities` / `players` | ✅ 均可用 |

**协议与旧 Python 桥接（bridge_server.py）对齐**：请求体、`last_action` 语义、`/state` `/chat` 字段习惯保持一致，
上层 agent 只需把 base_url 从 `8765` 换成 `8780` 即可切换（迁移期两者可并存）。

**为什么手写 HTTP 而不用 `com.sun.net.httpserver`**：后者在 `jdk.httpserver` 模块，能否被 FML 模块层里的 mod 读到有不确定性，
而每次"部署→重启客户端→验证"约 4 分钟，不值得为省几十行代码去赌；`java.net.ServerSocket`（`java.base`）行为完全可控。

**v0.2 实现的动作（29 个名字 / 18 个实现）**：自省与状态（`actions` `state` `ping` `time`）、
世界/地形查询（`block_at` `blockstate` `scan_region` `find_block`）、通信（`say` `command`）、
感知（`get_inventory` `players` `entities`）、视角与输入（`look` `press` `keys`）、其它（`close_screen` `reload_chunks`）。

## 里程碑

| 版本 | 内容 | 状态 |
|---|---|---|
| v0.1 | 运行数据目录 + 配置 + 实例锁 | ✅ |
| v0.2 | 内嵌控制服务（`/state` `/chat` `/actions` `/command`）+ 聊天缓冲 + 18 个动作 | ✅ |
| **v0.3** | **自建区（tools/ + docs/ + 文件读写 + 穿越防护）+ 通用 API 配置 + 内置 AI 出站网络（llm_chat/llm_ping）+ 控制面鉴权** | ✅ |
| **0.2.1.x（现行线）** | **动作补全 + sidecar 外挂 agent 全链路 + 权限表五轴 + UI/容器感知 + dig/place API 路径** | ✅ 进行中（当前 0.2.1.45） |
| └ 容器/合成 | `get_container`/`click_slot`/`cont_button`（0.2.1.2）+ 限量参数（0.2.1.44） | ✅ 已完成 |
| └ 挖掘/放置 | `dig`（0.2.1.35）/ `place`（0.2.1.39），均走 gameMode API 发包绕开前台门禁 | ✅ 已完成 |
| └ sidecar 全链路 | 部署/拉起/看门狗/attach-detach/agent_status 等（0.2.1.7-12）+ 部署保数据（0.2.1.41） | ✅ 已完成 |
| └ 决策层大脑 | **改道**：不内置，由 sidecar 外挂 agent（[aiplayer-agent](https://github.com/pi-wu-gu/aiplayer-agent)）实现；模组只提供原子接口 | ✅ 已改道 |
| 寻路 `goto` | 自动寻路（A*/baritone 类） | 待做 |
| Capability 探测 + 优雅降级 | 目标整合包缺 Create/Curios 等依赖时不崩 | 待做 |
| 日志滚动 | 长期挂机的日志体积治理 | 待做 |
| JsMacros 兼容 shim | 仅当需要跨版本兜底时 | 待定 |

### v0.1 已实现（运行数据目录，可独立验证，不依赖任何外部运行时）

- 在**游戏目录下**维护运行数据目录 `<gamedir>/aiplayer/`（不放在 `config/` 下，避免被整合包清理工具动到）
- 目录骨架：`logs/` `chat/` `sessions/` `capabilities/` + `state.json` + `README.txt`
- 原子写（先写 `.tmp` 再移动），避免崩溃留下半截 JSON
- **实例锁**：同一数据目录近期有另一个实例运行时告警（两个客户端会互相覆盖状态）
- 用户配置走 NeoForge 标准位置 `<gamedir>/config/aiplayer-common.toml`，游戏内「模组 → AI Player → 配置」可直接改
- `enabled` / `brainEnabled` 两个总闸：出事时一键关掉 AI，不影响游戏本体

## 构建

```bash
cd "<MOD_REPO>"
./gradlew build          # 产物在 build/libs/aiplayer-<version>.jar
./gradlew runClient      # 开发环境直接起游戏（首启较慢）
```

- 目标版本：**MC 1.21.1 / NeoForge 21.1.236 / Java 21**
  - ★ `neo_version` 必须与目标整合包实际运行的 NeoForge 一致（当前整合包为 21.1.236）。
    编译版本比运行时新会在运行期抛 `NoSuchMethodError`。
- 构建脚本里已配置国内可达的镜像源（`neoforged.forgecdn.net` 等），沿用 `testmod-template-1.21.1` 中已验证的配置。

**★改了 agent 源码（`<AGENT_REPO>/`）之后的完整部署链（四步，缺一不可）**：

```bash
# 1) 重打 sidecar 包（含最新 core/tools 源码 + 内嵌运行时，自动跑 import 烟测）
cd <AGENT_REPO> && python scripts/build_agent_bundle.py
# 2) 送进模组资源目录（gradle 只认这里，不会自动去拉 agent 仓库）
cp agent_bundle.zip "<MOD_REPO>/src/main/resources/assets/aiplayer/agent_bundle.zip"
# 3) 构建 jar
cd "<MOD_REPO>" && ./gradlew build
# 4) 部署：复制 build/libs/aiplayer-<v>.jar 到 mods/（删旧版本 jar）→ 杀 supervisor 进程树 → 重启
```

⚠️ **只跑 `./gradlew build` 不会带上 agent 源码改动**——jar 内嵌的是 `assets/aiplayer/agent_bundle.zip`
这个静态文件；2026-10-17 实测漏过这一步：提示词/工具描述改了没生效，daemon 跑的 sidecar 还是旧版
（排查现象：`aiplayer-agent/<版本>/core/runner.py` 内容与 `<AGENT_REPO>/core/runner.py` 不一致）。
验证方法：部署后对比两边文件的关键串（如 `grep -c look_lock <sidecar>/core/runner.py` 应 >0）。

**★只改 agent 的 Python 源码时，不必走上面四步**（不用重打 bundle、不用重启 MC）：
模组按版本目录缓存 sidecar（`<gamedir>/aiplayer-agent/<mod_version>/`），直接

```bash
cp <AGENT_REPO>/core/*.py "<sidecar>/core/"        # 以及 tools/builtin/*.py
rm -rf "<sidecar>/core/__pycache__" "<sidecar>/tools/builtin/__pycache__"
# 确认 8770 /status 为 idle → 杀掉 run.py --daemon 进程 → 重新拉起
cd "<sidecar>" && ./runtime/python/python.exe run.py --daemon
```

约 15 秒即可生效（实测 2026-10-18）。**只有 bundle 本身变了才需要升 `mod_version` 重解压。**
⚠️ 模组侧 watchdog 不一定自动拉起，杀完要手动确认进程在跑。

## 安装（给别的整合包）

1. 把 `build/libs/aiplayer-<version>.jar` 丢进目标整合包的 `mods/`
2. 启动游戏 → 首次运行会在游戏目录生成 `aiplayer/`
3. 需要时在「模组 → AI Player → 配置」里填服务器地址 / API Key / 开关

对外部环境的要求：**Java 21 + NeoForge 1.21.1**。不需要 JsMacros；Python 也不用自备——
jar 内嵌 **Windows 版** sidecar agent 运行时（首次运行自动解压到游戏目录并拉起）。
Linux/macOS 用户或想改 agent 源码时，才需要自备 Python 3.13+ 并从
[aiplayer-agent](https://github.com/pi-wu-gu/aiplayer-agent) 仓库运行 `run.py`。

开源协议：All Rights Reserved（保留所有权利；`gradle.properties` 的 `mod_license` 同此。
`TEMPLATE_LICENSE.txt` 为 NeoForged MDK 模板自带，仅覆盖模板文件）。

## 已踩的坑（实测记录）

1. **`ModConfigSpec` 的值不能在 `@Mod` 构造函数里读**
   构造函数执行时配置尚未加载，读取会抛
   `IllegalStateException: Cannot get config value before config is loaded.`，
   而 FML 会直接判定 `AI Player (aiplayer) has failed to load correctly`，**客户端卡在错误界面**。
   → 所有配置读取必须放到 `FMLCommonSetupEvent` / `ModConfigEvent` 之后，并包 try-catch。
   （v0.1 第一次部署就是这么挂的，见 commit 记录与该行注释）

2. **`neo_version` 必须与目标整合包实际运行的 NeoForge 完全一致**
   目标整合包跑的是 **21.1.236**；若按 MDK 默认的 21.1.259 编译，
   用到了新版本才有的 API 时会在运行期抛 `NoSuchMethodError`。

3. **纯客户端 mod 要显式声明 dist**
   `@Mod(..., dist = Dist.CLIENT)` + `neoforge.mods.toml` 里 `side="CLIENT"`，
   否则专用服务器上会尝试加载并报错。

4. **主线程动作必须"派发 + 等完成"，不能只派发**
   `Minecraft.execute()` 是异步的（就是把任务塞进主线程队列），只发不等会读到旧状态。
   `ControlServer.runOnClientThread()` 用 `CountDownLatch` 等结果 —— 这正是 JsMacros 版
   "主线程写操作后必须轮询完成标志"那条经验的 Java 版本。

5. **`say` 会被服务器防刷屏静默丢弃**
   刚进世界或短时间内连发时，服务端会回 `请不要刷屏` 而消息不出现；
   本地 `sendChat` 不报错，所以**不能只看返回值判断送达**，要靠 `GET /chat` 或客户端日志核对。

6. **不要在 mod 里用 `com.sun.net.httpserver`（保险起见）**
   它在 `jdk.httpserver` 模块，能否被 FML 模块层里的 mod 读到有不确定性；
   每次部署验证要 4 分钟，不值得赌。本项目用 `java.net.ServerSocket`（`java.base`）手写最小 HTTP。

7. **动作结果串不能原样嵌入 JSON（v0.3 已修）**
   以前 `Json.kv("result", 结果串)` 一律原样嵌入，遇到 `say: 你好` 这类纯文本就产出
   `{"result":say: 你好}` —— **不是合法 JSON**，任何客户端解析都会失败。
   现在走 `Json.maybeJson()`：能解析成对象/数组就嵌套，否则当字符串加引号。

8. **推理模型的正文在 `reasoning_content`，不在 `content`**
   实测 `deepseek-flash`：HTTP 200、usage 里 `completion_tokens` 全是 `reasoning_tokens`，
   而 `message.content` 是空的 —— 表现为"调用成功但回复是空的"。
   `LlmClient` 现在同时取两处并在结果里给出 `reasoning` 字段。

9. **给 agent 的文件访问必须做目录穿越防护**
   路径一律 `AiPlayerPaths.resolveInside()` 解析：normalize 后必须以数据目录为前缀，
   否则拒绝。实测 `../../config/...` 已被拦下。

10. **`press` 必须走 `KeyMapping.click(key)`，不能只 `setDown`**
    开背包 / 丢弃 / 切换手这类是**事件闩锁型**（`consumeClick()`），只 `setDown` 不会触发 ——
    实测 setDown 版本的 press 开不了背包，改成 `KeyMapping.click(key)` 才正常。
    反过来，**移动 / 攻击 / 使用 / 跳跃是持续型**，必须用 `keys` 或 `move` 按住（`setDown`）。

11. **配置项键名不要随便改**
    `apiKey` / `model` 沿用 v0.1 的键名；若新加同义字段（如 `apiModel`）会导致
   ①旧的 `aiplayer-common.toml` 失效 ②字段重复定义编译失败（v0.3 就撞过一次）。

## 目录说明

```
aiplayer/
├── build.gradle            # ModDevGradle 2.0.148 + 国内镜像源
├── gradle.properties       # mod_id / mod_name / neo_version(21.1.236) 等
├── src/main/java/net/aiplayer/
│   ├── AiPlayer.java          # 模组入口（纯客户端）
│   ├── AiPlayerClient.java    # 客户端入口：配置界面 / 聊天采集 / 启动控制服务 / 退出钩子
│   ├── AiPlayerConfig.java    # ModConfigSpec 用户配置
│   ├── AiPlayerPaths.java     # <gamedir>/aiplayer/ 解析、骨架、原子写、实例锁
│   ├── ControlServer.java     # 内嵌 HTTP 服务（/state /chat /actions /command /health /stats）
│   ├── Actions.java           # 动作层（57 个注册调用，含全部条件闸；部署实配 full=56/103，见「当前规模」节）
│   │                          #   ★文件 3375 行，占全仓 ~46%，后续按能力域拆分
│   ├── ChatBuffer.java        # 聊天缓冲（单调 id + 落盘）
│   └── Json.java              # JSON 输出辅助
├── src/main/resources/assets/aiplayer/lang/   # en_us / zh_cn
├── src/main/templates/META-INF/neoforge.mods.toml
└── .template-original/     # 原始模板示例代码，已移出编译路径且**不入库**（含本机用户名包名）
```

## 许可

本项目代码：**All Rights Reserved**（保留所有权利，`gradle.properties` 的 `mod_license` 同此）。
注意：本仓库源自 NeoForge MDK，**模板部分**（`LICENSE` / `TEMPLATE_LICENSE.txt` 覆盖的文件）为 MIT。
