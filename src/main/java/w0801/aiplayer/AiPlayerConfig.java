package net.aiplayer;

import java.util.Locale;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * mod 的用户配置（NeoForge ModConfigSpec）。
 *
 * <p>写入位置：{@code <gamedir>/config/aiplayer-common.toml}，
 * 可在游戏内「模组 → AI Player → 配置」界面里直接改（见 {@link AiPlayerClient}）。
 *
 * <p>注意这里只放<strong>用户设置</strong>；运行期数据（状态/日志/聊天缓冲/会话）统一放
 * {@code <gamedir>/aiplayer/}，由 {@link AiPlayerPaths} 管理。
 */
public final class AiPlayerConfig {

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = B
            .comment("总开关。关闭后 AI 模块完全不启动，游戏保持原样（出事时的第一道闸）")
            .define("enabled", true);

    public static final ModConfigSpec.BooleanValue BRAIN_ENABLED = B
            .comment("大脑（LLM 决策循环）开关。默认关闭：先只跑客户端本能与动作层",
                     "搞坏东西时把它关掉即可，不影响游戏本体",
                     "⚠注意：当前版本内置大脑未接线，本开关暂无实际效果；",
                     "  实际决策由 sidecar 外挂 agent 完成（见 sidecar 段）。")
            .define("brainEnabled", false);

    public static final ModConfigSpec.ConfigValue<String> DATA_DIRECTORY = B
            .comment("运行数据目录。相对路径 = 相对游戏目录",
                     "默认 aiplayer 即 <gamedir>/aiplayer/",
                     "想避免整合包重装时被清掉，可以填绝对路径（如 D:/mc-ai/aiplayer）",
                     "★改目录不会迁移旧数据（记忆/日志/agent 部署都留在原处），要迁移请手动。")
            .define("dataDirectory", AiPlayerPaths.DEFAULT_DIR_NAME);

    public static final ModConfigSpec.ConfigValue<String> SERVER_ADDRESS = B
            .comment("目标服务器 address:port（留空 = 不自动连接）",
                     "★自动登录唯一入口就在这：autoLogin（进主菜单自动连）/ autoReconnect（断线重连）",
                     "都连这个地址。启动器层（launcher/config 的 auto_join）已于 2026-10-17 停用。")
            .define("serverAddress", "");

    public static final ModConfigSpec.BooleanValue AUTO_LOGIN = B
            .comment("【自动登录】启动进主菜单后自动连接 serverAddress 指向的服务器。",
                     "默认 true；想手动选服/进单人世界就把它关掉（serverAddress 留空同样等于关）。")
            .define("autoLogin", true);

    public static final ModConfigSpec.BooleanValue AUTO_RECONNECT = B
            .comment("【自动重连】断线（服务器崩/踢线）后自动重连到 serverAddress。默认 false。",
                     "★开启后服务器恢复时 bot 能自己回去，不需要人工重启客户端。",
                     "重连间隔见 reconnectDelaySec；连的是 serverAddress，与 autoLogin 共用同一地址。")
            .define("autoReconnect", false);

    public static final ModConfigSpec.IntValue RECONNECT_DELAY = B
            .comment("【自动重连】两次重连之间的间隔（秒），避免服务器宕机时疯狂刷连接。")
            .defineInRange("reconnectDelaySec", 20, 5, 600);

    public static final ModConfigSpec.IntValue AUTO_CONNECT_MAX_ATTEMPTS = B
            .comment("【自动连接】自动登录/重连的最大尝试次数（同一进程内累计，成功进世界后重置）。",
                     "用尽后：若停在断开界面→自动回主界面，且不再自动连接；重启客户端重新计数。",
                     "默认 2：服务器宕机时试 2 次就停，不会无限刷连接。调大=更执着，1=只试一次。")
            .defineInRange("autoConnectMaxAttempts", 2, 1, 20);

    // 注：apiKey / model 的**配置项键名**沿用 v0.1（apiKey、model），
    //     新的通用 API 配置段（v0.3）里复用这两个键，避免已生成的 toml 失效。

    public static final ModConfigSpec.IntValue POLL_INTERVAL_MS = B
            .comment("轮询间隔（毫秒）：聊天/状态采集频率",
                     "⚠注意：当前版本未接线，修改无效；采集由 sidecar agent 按自己的节奏进行。")
            .defineInRange("pollIntervalMs", 500, 100, 10000);

    public static final ModConfigSpec.IntValue LOG_KEEP_DAYS = B
            .comment("行为日志保留天数（超出自动清理，避免日志无限增长）",
                     "⚠注意：当前版本未接线，修改无效；日志清理由 sidecar agent 负责。")
            .defineInRange("logKeepDays", 7, 1, 365);

    public static final ModConfigSpec.BooleanValue VERBOSE = B
            .comment("详细日志")
            .define("verbose", true);

    // ---------------- v0.2 内嵌控制服务 ----------------

    public static final ModConfigSpec.BooleanValue CONTROL_ENABLED = B
            .comment("内嵌 HTTP 控制服务开关（相当于把 Python 的 bridge_server 收进 mod）")
            .define("controlEnabled", true);

    public static final ModConfigSpec.ConfigValue<String> CONTROL_HOST = B
            .comment("控制服务监听地址。★务必保持回环地址，别开放到公网（它能直接驱动游戏动作）")
            .define("controlHost", "127.0.0.1");

    public static final ModConfigSpec.IntValue CONTROL_PORT = B
            .comment("控制服务端口。默认 8780，避开 Python 桥接的 8765 与控制台的 8770")
            .defineInRange("controlPort", 8780, 1024, 65535);

    public static final ModConfigSpec.ConfigValue<String> CONTROL_TOKEN = B
            .comment("控制服务的可选鉴权 token。非空时，请求必须带请求头 X-AI-Token；",
                     "空 = 不校验（仅监听回环地址时够用）。对外发布建议设置。")
            .define("controlToken", "");

    public static final ModConfigSpec.ConfigValue<String> SHUTDOWN_MESSAGE = B
            .comment("客户端关闭时向服务器自动播报的消息（服务器礼仪，2026-10-16 用户约定；留空=不播报）。",
                     "★单条 ≤100 字：服务端聊天硬限制，超长直接截断不报错。",
                     "★注意：硬杀进程（如部署换 jar 的 taskkill）不会触发本播报——那类重启",
                     "  请按 SOP 先用 say 动作说明原因再杀。")
            .define("shutdownMessage", "[自动] 客户端关闭中，稍后回来");

    // ---------------- v0.3 内置 AI：通用 API 配置 + 网络访问 ----------------

    public static final ModConfigSpec.BooleanValue NETWORK_ENABLED = B
            .comment("内置 AI 的**出站**网络访问总闸（调 LLM API 需要）。关掉后 llm_chat 直接拒绝，",
                     "游戏本体不受影响。")
            .define("networkEnabled", true);

    public static final ModConfigSpec.ConfigValue<String> API_PROVIDER = B
            .comment("provider：openai-compatible（默认，自动在 base_url 后补 /chat/completions）",
                     "| deepseek | custom（base_url 视为完整 URL，不再拼接）")
            .define("apiProvider", "openai-compatible");

    public static final ModConfigSpec.ConfigValue<String> API_BASE_URL = B
            .comment("兼容端点 Base URL：",
                     "  DeepSeek  = https://api.deepseek.com",
                     "  OpenAI    = https://api.openai.com/v1",
                     "  本地 vLLM = http://127.0.0.1:8000/v1")
            .define("apiBaseUrl", "https://api.deepseek.com");

    public static final ModConfigSpec.ConfigValue<String> API_KEY_ENV = B
            .comment("★优先读这个环境变量取密钥（避免明文落盘）；取不到才用下面的 apiKey")
            .define("apiKeyEnv", "AI_PLAYER_API_KEY");

    public static final ModConfigSpec.ConfigValue<String> API_KEY = B
            .comment("API Key（明文存放，建议留空改用环境变量）。本 mod 在日志中永不回显该值")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> API_MODEL = B
            .comment("模型名（配置键沿用 v0.1 的 model）")
            .define("model", "deepseek-flash");

    public static final ModConfigSpec.ConfigValue<String> API_HEADERS_JSON = B
            .comment("额外请求头（JSON 对象），通用兜底，例如 {\"HTTP-Referer\":\"https://example.com\"}")
            .define("apiHeadersJson", "");

    public static final ModConfigSpec.ConfigValue<String> API_EXTRA_BODY_JSON = B
            .comment("额外请求体字段（JSON 对象），各家奇葩参数都塞这里，会覆盖同名默认字段")
            .define("apiExtraBodyJson", "");

    public static final ModConfigSpec.IntValue API_TIMEOUT_MS = B
            .comment("单次调用超时（毫秒）")
            .defineInRange("apiTimeoutMs", 20000, 1000, 120000);

    public static final ModConfigSpec.DoubleValue API_TEMPERATURE = B
            .comment("温度；-1 = 不发给服务端（用服务端默认）")
            .defineInRange("apiTemperature", 0.7, -1.0, 2.0);

    public static final ModConfigSpec.IntValue API_MAX_TOKENS = B
            .comment("单次最大输出 token；0 = 不限制")
            .defineInRange("apiMaxTokens", 1024, 0, 32000);

    // ---------------- v0.2.1.4 权限表（一次收口：文件 / 命令 / 自研工具；网络见 networkEnabled） ----------------

    public static final ModConfigSpec.ConfigValue<String> FILE_MODE = B
            .comment("【权限表】文件访问模式：full（读写）| readonly（只读）| off（完全禁用）",
                     "★装配期生效：off / readonly 会把对应动作**直接从注册表摘掉**（不是注册后报错），",
                     "  模型看不到该能力，也就不会浪费轮次去试。",
                     "留空或无法识别的值 → 按 full 处理（保证旧配置行为不变）")
            .push("permissions")
            .define("fileMode", "full");

    public static final ModConfigSpec.BooleanValue COMMAND_ENABLED = B
            .comment("【权限表】是否允许 command 动作（向游戏发送 /命令）。默认 true 保持既有行为；",
                     "★远端服的 /login 登录流程依赖它，关掉之前先确认自动登录走的是哪条路")
            .define("commandEnabled", true);

    public static final ModConfigSpec.BooleanValue SELF_TOOL_ENABLED = B
            .comment("【权限表】自研工具（tool_maker）执行开关（v0.2.1.6 起真正生效）。默认 false。",
                     "开启后才注册 tool_run 动作：agent 可在 tools/<名字>/ 下写『宏』（一串已有动作的序列），",
                     "再用 tool_run name=<名字> 一次性执行。★安全边界：",
                     "  ① 宏步骤只能引用**当前注册表里存在的**动作 → 权限表自动级联（safe 档下含 press 的宏直接不可用）；",
                     "  ② 禁止宏里嵌套 tool_run（防递归死循环）；③ 步数 ≤64、单步 repeat ≤20；④ 入口路径做穿越硬化。",
                     "★仍属高风险面：宏能做的，都能用已有动作直接做——开启等于允许 agent 把动作串成固定套路批量跑。")
            .define("selfToolEnabled", false);

    public static final ModConfigSpec.ConfigValue<String> GAME_ACTION_MODE = B
            .comment("【权限表】游戏内动作模式：full | safe | observe（v0.2.1.5 新增第五轴）",
                     "  full    = 全部动作（默认，行为与旧版一致）",
                     "  safe    = 摘掉『改变游戏/物品状态』的输入类动作——press/keys（放方块/挖方块/攻击/用物品/开界面）、",
                     "            click_slot/cont_button（改容器）、screen_click(_widget)（改界面里的物品）；",
                     "            保留 移动 / 视角 / 感知 / 查询 / 界面与容器**读取**（get_screen / get_container / screen_type）",
                     "  observe = 在 safe 基础上再摘掉 move/jump/sneak（连自主移动都不给，纯观察 + 问答）",
                     "★与 fileMode 一样是**装配期**生效：被摘的动作既不在注册表也不在 groups 里，",
                     "  调用时若动作缺席且有别名残留，会返回 denied（区别于拼错的 unknown）")
            .define("gameActionMode", "full");

    static {
        B.pop();        // 关闭 permissions 段（字段按声明顺序初始化，故此处安全）
        B.push("sidecar");  // agent 外挂进程段（静态初始化按文本顺序，先 push 再定义字段）
    }

    // ---------------- sidecar（agent 外挂进程的部署/维护；v0.2.1.7 实验段） ----------------

    /** sidecar 总开关（部署 + 后续拉起/看门狗都挂在它下面）。默认 false——纯可选能力。 */
    public static final ModConfigSpec.BooleanValue SIDECAR_ENABLED = B
            .comment("【sidecar】agent 外挂进程整合开关。默认 false。",
                     "开启后模组在首次启动时把内置的 agent 包解压部署到 <游戏目录>/aiplayer-agent/<版本>/",
                     "（升级保留 keys.yaml / config/config.yaml / logs/；keys 绝不打包进 mod jar）。",
                     "★★双客户端注意：如果观看端也装了本 mod，务必只在 AI 端开启，否则会各自部署/拉起一份 agent。")
            .define("enabled", false);

    /** agent 部署根（相对游戏目录；一般无需修改）。 */
    public static final ModConfigSpec.ConfigValue<String> SIDECAR_DIR = B
            .comment("【sidecar】agent 部署目录（相对游戏目录）。一般无需修改。")
            .define("deployDir", "aiplayer-agent");

    /** 游戏启动时自动拉起 agent（ensure_running 语义：已在跑则复用）。 */
    public static final ModConfigSpec.BooleanValue SIDECAR_AUTO_START = B
            .comment("【sidecar】游戏启动时自动拉起 agent（--daemon）。默认 false。",
                     "★ensure_running 语义：8770 控制口已有实例（比如你用 启动agent.bat 独立启动的）→ 复用不重复拉起。",
                     "配合 launchMode=attach 时游戏退出会一并关闭自己拉起的实例；独立启动的不受影响。")
            .define("autoStart", false);

    /** attach = 随游戏退出关闭自己拉起的 agent；detach = 游戏退出后 agent 独立存活。 */
    public static final ModConfigSpec.ConfigValue<String> SIDECAR_LAUNCH_MODE = B
            .comment("【sidecar】launchMode：attach（游戏退出时关闭自己拉起的 agent，生命周期同游戏）|",
                     "detach（游戏退出后 agent 独立存活，可离游戏整理记忆/定时调研）。默认 attach。")
            .define("launchMode", "attach");

    /** python 解释器（裸名走 PATH；或绝对路径）。 */
    public static final ModConfigSpec.ConfigValue<String> SIDECAR_PYTHON = B
            .comment("【sidecar】python 解释器：裸程序名（走 PATH）或绝对路径。默认 python。")
            .define("pythonPath", "python");

    /** 看门狗探活间隔（秒）；0=关闭。死亡自动重启（带退避）。 */
    public static final ModConfigSpec.IntValue SIDECAR_WATCHDOG = B
            .comment("【sidecar】看门狗探活间隔（秒），0=关闭。探活失败自动重启 agent（带退避，上限60s）。")
            .defineInRange("watchdogInterval", 30, 0, 3600);

    static {
        B.pop();   // 关闭 sidecar 段
    }

    public static boolean sidecarEnabled() {
        try {
            return SIDECAR_ENABLED.get();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String sidecarDir() {
        try {
            String d = SIDECAR_DIR.get();
            return (d == null || d.isBlank()) ? "aiplayer-agent" : d.trim();
        } catch (Throwable t) {
            return "aiplayer-agent";
        }
    }

    public static boolean sidecarAutoStart() {
        try {
            return SIDECAR_AUTO_START.get();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String sidecarLaunchMode() {
        try {
            String m = SIDECAR_LAUNCH_MODE.get();
            return "detach".equalsIgnoreCase(m) ? "detach" : "attach";
        } catch (Throwable t) {
            return "attach";
        }
    }

    public static String sidecarPython() {
        try {
            String p = SIDECAR_PYTHON.get();
            return (p == null || p.isBlank()) ? "python" : p.trim();
        } catch (Throwable t) {
            return "python";
        }
    }

    public static int sidecarWatchdog() {
        try {
            return SIDECAR_WATCHDOG.get();
        } catch (Throwable t) {
            return 30;
        }
    }

    public static final ModConfigSpec SPEC = B.build();

    private AiPlayerConfig() {
    }

    /**
     * 文件访问模式，已归一为 {@code full / readonly / off} 三者之一。
     *
     * <p>容错策略：配置尚未加载（构造期读 spec 会抛）或值无法识别时一律返回 {@code full}
     * —— 这与 v0.2.1.4 之前"文件动作永远可用"的行为一致，避免升级后突然失能。
     */
    public static String fileModeRaw() {
        String raw;
        try {
            raw = FILE_MODE.get();
        } catch (Throwable t) {
            return "full";
        }
        if (raw == null) {
            return "full";
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "off":
            case "none":
            case "disabled":
                return "off";
            case "read":
            case "readonly":
            case "read_only":
            case "ro":
                return "readonly";
            default:
                return "full";
        }
    }

    public static boolean commandEnabled() {
        try {
            return COMMAND_ENABLED.get();
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean selfToolEnabled() {
        try {
            return SELF_TOOL_ENABLED.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 游戏内动作模式，已归一为 {@code full / safe / observe} 三者之一。
     *
     * <p>容错策略与 {@link #fileModeRaw()} 一致：配置未加载或值无法识别时一律返回
     * {@code full}（= 旧版全开），避免升级后突然失能。
     */
    public static String gameActionModeRaw() {
        String raw;
        try {
            raw = GAME_ACTION_MODE.get();
        } catch (Throwable t) {
            return "full";
        }
        if (raw == null) {
            return "full";
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "observe":
            case "observer":
            case "watch":
            case "spectate":
                return "observe";
            case "safe":
            case "no_grief":
            case "nogrief":
            case "notouch":
                return "safe";
            default:
                return "full";
        }
    }

    public static boolean enabled() {
        return ENABLED.get();
    }

    public static boolean brainEnabled() {
        return BRAIN_ENABLED.get();
    }

    public static String dataDirectory() {
        return DATA_DIRECTORY.get();
    }

    public static String serverAddress() {
        return SERVER_ADDRESS.get();
    }

    public static boolean autoLogin() {
        try {
            return AUTO_LOGIN.get();
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean autoReconnect() {
        try {
            return AUTO_RECONNECT.get();
        } catch (Throwable t) {
            return false;
        }
    }

    public static int reconnectDelaySec() {
        try {
            return RECONNECT_DELAY.get();
        } catch (Throwable t) {
            return 20;
        }
    }

    public static int autoConnectMaxAttempts() {
        try {
            return Math.max(1, AUTO_CONNECT_MAX_ATTEMPTS.get());
        } catch (Throwable t) {
            return 2;
        }
    }

    public static int pollIntervalMs() {
        return POLL_INTERVAL_MS.get();
    }

    public static int logKeepDays() {
        return LOG_KEEP_DAYS.get();
    }

    public static boolean verbose() {
        return VERBOSE.get();
    }

    public static boolean controlEnabled() {
        return CONTROL_ENABLED.get();
    }

    public static String controlHost() {
        return CONTROL_HOST.get();
    }

    public static int controlPort() {
        return CONTROL_PORT.get();
    }

    public static String controlToken() {
        return CONTROL_TOKEN.get();
    }

    public static String shutdownMessage() {
        try {
            return SHUTDOWN_MESSAGE.get();
        } catch (Throwable t) {
            return "[自动] 客户端关闭中，稍后回来";
        }
    }

    // ---- 内置 AI：通用 API ----

    public static boolean networkEnabled() {
        try {
            return NETWORK_ENABLED.get();
        } catch (Throwable t) {
            return true;   // 配置未就绪 → 按默认值，保证静态装配期不炸
        }
    }

    public static String apiProvider() {
        return API_PROVIDER.get();
    }

    public static String apiBaseUrl() {
        return API_BASE_URL.get();
    }

    public static String apiKeyEnv() {
        return API_KEY_ENV.get();
    }

    public static String apiKey() {
        return API_KEY.get();
    }

    public static String apiModel() {
        return API_MODEL.get();
    }

    public static String apiHeadersJson() {
        return API_HEADERS_JSON.get();
    }

    public static String apiExtraBodyJson() {
        return API_EXTRA_BODY_JSON.get();
    }

    public static int apiTimeoutMs() {
        return API_TIMEOUT_MS.get();
    }

    public static double apiTemperature() {
        return API_TEMPERATURE.get();
    }

    public static int apiMaxTokens() {
        return API_MAX_TOKENS.get();
    }
}
