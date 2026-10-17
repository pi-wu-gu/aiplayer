# AI Player（模组化控制组件）

把 MC AI 玩家的**控制组件**收进单个模组，目标是**丢进任意 1.21.1 NeoForge 整合包的 `mods/` 就能用**。

> 立项背景与分析见：`<PCL2_DIR>/AI/docs/客户端打包为模组_可行性分析.md`（含"把 AI 模块一起打包"的 §9）。

## 版本号规则（2026-10-16 起）

- **`0.1` = 上一版方案**：即 `v0.1 → v0.2 → v0.3 → v0.3.1` 这条线的整体（mod 化摸索期，参照旧 JsMacros 栈做等价替换）
- **`0.2.1.x` = 当前序列**：`0.2` 当前架构（mod 内置控制 + 自建区 + 通用 API），
  末位 `x` = 迭代号（感知 / 交互 / UI 输入 / 容器 / 截图 / 权限表都在这一线递增，不拆中间位）
- 因此当前是 **`0.2.1.15`**（0.2.1.7-12 = **agent sidecar 全链路**：
  部署（首次启动解压 agent 包，跨版本继承 keys/config/logs）→ 拉起（ensure_running 复用不重复拉起）→
  看门狗（探活失败退避重启）→ attach/detach 生命周期（游戏退出杀自己拉起的树/放行）→
  游戏内动作 `agent_status / agent_start / agent_stop`（受 [sidecar].enabled 管辖）；
  keys 绝不入包，加载路径 <root>/config/keys.yaml 与 agent 侧一致。
  0.2.1.13 = 状态诊断字段 + 关闭播报；0.2.1.14 = `online_players` 全服在线名单；
  0.2.1.15 = **聊天时间戳**（chat_log.jsonl 与 /chat 输出带可读 `ts` 字段、重启重载历史不再丢时间戳；
  同包 sidecar 同步框架更新：会话日志逐条带 `ts`/`rel_s`、去除测试阶段开始播报只留结束统计），见下文专节）

## 当前状态：**v0.3 —— 自建区 + 通用 API 配置 + 内置 AI 网络访问**

**v0.3 实测通过的内容**（真实整合包 `夯空学_新蒸程` / MC 1.21.1 / NeoForge 21.1.236）：

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

**动作名 38 个 / 实现 25 个**，新增两组：
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

**动作名 47 个 / 实现 33 个。**

### 容器操作（0.2.1.2，已实机验证）

| 动作 | 参数 | 实测 |
|---|---|---|
| `get_container`(别名 `container` `cont_info`) | `find`(物品 id 子串)、`slots` | ✅ 背包 `InventoryMenu` 46 槽：`ranges` 分出 **0-4 合成区 / 5-45 背包区**；`cls` 认出 5-8 是 `ArmorSlot`；每槽带 `pick`(mayPickup) / `place`(mayPlace 探测) |
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

**动作名 62 个（含别名）/ 实现 57 个。**

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

**动作名 68 个（含别名）/ 实现 42 个。**（该版本时点数；v0.2.1.26 起为 78/47，selfToolEnabled 开时 80/48）

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

**`gameActionMode` 的三档语义**（模组**没有** place/dig 动作——放方块/挖方块/攻击全都经 `press`/`keys` 触发，
因此"世界修改"面就是输入类）：

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

## v0.2 —— 内嵌控制服务（已替代 Python 桥接）

**v0.2 实测通过的内容**（同环境）：

| 能力 | 结果 |
|---|---|
| 内嵌 HTTP 服务 | ✅ `http://127.0.0.1:8780`（只用 `java.base`，无外部依赖、无嵌套 jar） |
| `GET /state` | ✅ 实时返回 坐标/血量/饥饿/维度/游戏模式/时间/手持/视线方块/附近玩家/聊天尾 |
| `GET /chat?since=&limit=` | ✅ 单调 id + 落盘（`aiplayer/chat/chat_log.jsonl`），支持"只取新消息"游标 |
| `GET /actions` | ✅ 78 个动作名（含别名）/ 47 个实现（按类分组，自省用；当前 selfToolEnabled=false，开则 80/48） |
| `POST /command` | ✅ `{"commands":[{...}]}`，**逐条在主线程执行并等完成**，单条与批量都通 |
| `say` 真实送达 | ✅ 游戏内确实出现 `[world] <<BOT_NAME>>: ...` |
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
| **v0.3** | **自建区（tools/ + docs/ + 文件读写 + 穿越防护）+ 通用 API 配置 + 内置 AI 出站网络（llm_chat/llm_ping）+ 控制面鉴权** | ✅ **本次** |
| v0.4 | 动作补全：容器/合成（`cont_*` `click_slot`）、寻路（`goto`）、挖掘与放置（含反作弊对准时序） | 下一个 |
| v0.5 | 内置大脑：工具循环 + 上下文压缩（把 `llm_chat` 组装成自主决策） | 待做 |
| v0.6 | Capability 探测 + 优雅降级（目标整合包缺 Create/Curios 等依赖时不崩） | 待做 |
| v0.7 | 看门狗 + 日志滚动；JsMacros 兼容 shim（仅当需要跨版本兜底时） | 待定 |

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

## 安装（给别的整合包）

1. 把 `build/libs/aiplayer-<version>.jar` 丢进目标整合包的 `mods/`
2. 启动游戏 → 首次运行会在游戏目录生成 `aiplayer/`
3. 需要时在「模组 → AI Player → 配置」里填服务器地址 / API Key / 开关

对外部环境的要求：**Java 21 + NeoForge 1.21.1**。不需要 JsMacros；Python 也不用自备——
jar 内嵌 **Windows 版** sidecar agent 运行时（首次运行自动解压到游戏目录并拉起）。
Linux/macOS 用户或想改 agent 源码时，才需要自备 Python 3.13+ 并从
[aiplayer-agent](https://github.com/pi-wu-gu/aiplayer-agent) 仓库运行 `run.py`。

开源协议：[MIT](LICENSE)（`TEMPLATE_LICENSE.txt` 为 NeoForged MDK 模板自带，覆盖模板文件）。

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
│   ├── Actions.java           # 动作层（47 个实现 / 78 个名字，含别名；selfToolEnabled 开时 48/80）
│   ├── ChatBuffer.java        # 聊天缓冲（单调 id + 落盘）
│   └── Json.java              # JSON 输出辅助
├── src/main/resources/assets/aiplayer/lang/   # en_us / zh_cn
├── src/main/templates/META-INF/neoforge.mods.toml
└── .template-original/     # 原始模板的示例代码，已移出编译路径，仅作参考
```

## 许可

All Rights Reserved（暂定）。注意：本仓库源自 NeoForge MDK，模板部分见 `TEMPLATE_LICENSE.txt`。
