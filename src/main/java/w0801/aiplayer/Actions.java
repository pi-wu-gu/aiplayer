package net.aiplayer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

/**
 * 动作层：本 mod 对外的"客户端能力"。
 *
 * <p>设计约束（从 JsMacros 版 index.js 的 22 条踩坑经验继承）：
 * <ul>
 *   <li><b>所有动作都在客户端主线程执行</b>（由 {@link ControlServer} 通过 {@code Minecraft.execute} 派发并等完成）</li>
 *   <li>动作返回 JSON 字符串（与旧桥接的 {@code last_action} 语义一致），失败用 {@code *_err:} 前缀</li>
 *   <li>内部一律 try/catch：单个动作失败不能影响其它动作或游戏本体</li>
 * </ul>
 *
 * <p>v0.2.1 已覆盖：感知类 + 基础交互类 + UI 感知/交互 + 自建区 + 通用 LLM 接口 + 容器操作；
 * v0.2.1.3 增加 GUI 文本输入（screen_type，搜索框/命名框通吃）；
 * 合成配方辅助 / 寻路 / 网络终端等留到后续版本。
 */
final class Actions {

    /** 动作执行器：入参为命令 JSON，返回结果（JSON 字符串或纯文本）。 */
    interface Action {
        String run(JsonObject params) throws Exception;
    }

    private static final Map<String, Action> REGISTRY = new HashMap<>();

    /** 最近一次动作结果（与旧桥接 last_action 对齐，供上层轮询）。 */
    private static volatile String lastAction = "(尚无动作)";
    private static volatile long lastActionAt = 0;

    private static ScheduledExecutorService scheduler;

    private Actions() {
    }

    static String lastAction() {
        return lastAction;
    }

    static long lastActionAt() {
        return lastActionAt;
    }

    static void setLastAction(String text) {
        lastAction = text;
        lastActionAt = System.currentTimeMillis();
    }

    /** 已知动作名（供 /actions 自省）。 */
    static List<String> names() {
        List<String> n = new ArrayList<>(REGISTRY.keySet());
        n.sort(String::compareTo);
        return n;
    }

    static boolean known(String type) {
        return REGISTRY.containsKey(type);
    }

    static String dispatch(String type, JsonObject params) {
        ensurePermissions();
        Action a = REGISTRY.get(type);
        if (a == null) {
            if (GATED.contains(type)) {
                // 受权限表管辖却缺席 → 是被禁用，不是拼错；单独审计，便于发现探测行为
                logDenied("fileMode=" + AiPlayerConfig.fileModeRaw() + "/gameAction="
                        + AiPlayerConfig.gameActionModeRaw() + "/network="
                        + AiPlayerConfig.networkEnabled(), "动作被权限表禁用: " + type);
                return "denied: " + type + " 被权限表禁用（config/aiplayer-common.toml 的 [permissions]；"
                        + "当前实际模式见 {\"type\":\"actions\"} 的 permissions 字段）";
            }
            return "unknown: " + type + "（用 {\"type\":\"actions\"} 查合法动作名）";
        }
        try {
            return a.run(params == null ? new JsonObject() : params);
        } catch (Throwable e) {
            return "error: " + e;
        }
    }

    private static void register(String name, Action a, String... aliases) {
        REGISTRY.put(name, a);
        for (String al : aliases) {
            REGISTRY.put(al, a);
        }
    }

    /** 被权限表拒绝 / 越界的次数（审计计数，{@code /stats} 可见）。 */
    private static final java.util.concurrent.atomic.AtomicLong DENIED =
            new java.util.concurrent.atomic.AtomicLong();

    /** 受权限表管辖的动作名——它们缺席时报"被禁用"，与拼错动作名区分开（便于审计探测行为）。 */
    private static final java.util.Set<String> GATED = java.util.Set.of(
            // 文件三模式（fileMode）
            "file_read", "file_write", "file_append", "file_list", "tools", "docs",
            // 命令 / 出站网络
            "command", "cmd", "llm_chat", "llm", "llm_ping",
            // v0.2.1.10 sidecar：agent 进程启停与状态（受 [sidecar].enabled 管辖）
            "agent_status", "agent_start", "agent_run", "agent_stop", "agent_shutdown",
            // v0.2.1.5 游戏内动作五轴：safe 摘输入/改物类，observe 再摘移动类
            "press", "key", "keys", "hold_keys",
            "click_slot", "slot_click", "cont_click", "cont_button", "menu_button",
            "screen_click", "screen_click_widget", "click_widget",
            "move", "jump", "sneak",
            // v0.2.1.6 自建工具执行（selfToolEnabled）
            "tool_run", "run_tool");

    /** 上次装配注册表时的权限快照；与当前不一致就重建（不订阅配置事件也能自愈）。 */
    private static volatile String permSnapshot = null;

    static long deniedCount() {
        return DENIED.get();
    }

    /** 权限快照。配置未就绪时按"全开"取值 —— 与 v0.2.1.4 之前的行为一致。 */
    private static String permissionSnapshot() {
        return AiPlayerConfig.fileModeRaw() + "|" + AiPlayerConfig.commandEnabled()
                + "|" + AiPlayerConfig.networkEnabled() + "|" + AiPlayerConfig.gameActionModeRaw()
                + "|" + AiPlayerConfig.selfToolEnabled();
    }

    /** 权限变化时重建注册表（配置加载/热重载后由 dispatch 自动触发）。 */
    private static void ensurePermissions() {
        String now = permissionSnapshot();
        if (!now.equals(permSnapshot)) {
            buildRegistry();
        }
    }

    /** 记录一次拒绝：计数 + 主日志 + 落盘 {@code <datadir>/logs/denied_access.log}。 */
    private static void logDenied(String reason, String detail) {
        DENIED.incrementAndGet();
        AiPlayer.LOG.warn("[AI Player][权限表] 拒绝: {} | {}", reason, detail);
        try {
            Path f = AiPlayerPaths.dataDir().resolve("logs").resolve("denied_access.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f, java.time.Instant.now() + " | " + reason + " | " + detail + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // 审计失败不得影响主流程
        }
    }

    /**
     * 按当前权限表装配注册表。
     *
     * <p>★闸门放在<strong>装配期</strong>而不是调用期：被禁的能力**根本不进注册表**，
     * 模型看不到它，也就不会浪费轮次去试。提示词只是建议，装配期才是硬边界。
     *
     * <p>可重复调用；配置加载/热重载后由 {@link #ensurePermissions()} 自动重建。
     */
    private static void buildRegistry() {
        REGISTRY.clear();
        String fm = AiPlayerConfig.fileModeRaw();
        boolean netOk = AiPlayerConfig.networkEnabled();
        // v0.2.1.5 第五轴：游戏内动作模式。safe/observe 摘掉"改世界·改物品"的输入类；
        //   observe 再摘掉移动类。（模组没有 place/dig 动作——放/挖/攻击全部走 press/keys）
        String gm = AiPlayerConfig.gameActionModeRaw();
        boolean gmSafe = !"full".equals(gm);
        boolean gmObserve = "observe".equals(gm);

        register("ping", p -> "pong");
        register("actions", p -> actionsList(), "help", "list_actions");
        register("state", p -> state());
        register("say", Actions::say, "chat");
        if (AiPlayerConfig.commandEnabled()) {
            register("command", Actions::command, "cmd");
        }
        register("connect", Actions::connectAction, "join");
        register("time", Actions::time);
        register("block_at", Actions::blockAt);
        register("blockstate", Actions::blockState, "block_state");
        register("scan_region", Actions::scanRegion);
        register("find_block", Actions::findBlock, "search_block", "nearest_block");
        register("get_inventory", Actions::inventory, "inv_query");
        register("look", Actions::look);
        register("turn", Actions::turn);
        // 输入/按键：safe 及以上摘掉——放方块(key.use)/挖方块(key.attack)/攻击/开界面都经此路
        if (!gmSafe) {
            register("press", Actions::press, "key");
            register("keys", Actions::keys, "hold_keys");
        }
        // 移动：仅 observe 摘掉（safe 保留移动，否则没法走近目标）
        if (!gmObserve) {
            register("move", Actions::move);
            register("jump", Actions::jump);
            register("sneak", Actions::sneak);
        }
        register("select", Actions::select, "slot");
        register("scroll", Actions::scroll, "wheel");
        register("mouse_move", Actions::mouseMove);
        register("close_screen", p -> closeScreen(), "close");
        register("reload_chunks", p -> reloadChunks());
        // v0.2.1：UI 感知（石碑传送 / 容器交互的前提）
        register("get_screen", Actions::getScreen, "screen");
        // 界面"点"类：safe 及以上摘掉（真鼠标点击可改动界面里的物品，如维度网络终端）
        if (!gmSafe) {
            register("screen_click", Actions::screenClick);
            register("screen_click_widget", Actions::screenClickWidget, "click_widget");
        }
        register("screen_type", Actions::screenType, "type_text", "ui_type");
        register("overlay", Actions::overlay, "actionbar");
        register("players", Actions::players);
        register("online_players", Actions::onlinePlayers, "online", "player_list", "tab_list");
        register("entities", Actions::entities);
        // v0.2.1.2：容器操作（容器交互 / 合成取放的前提）
        register("get_container", Actions::containerInfo, "container", "cont_info");
        // 容器"改"类：safe 及以上摘掉（读仍可用，写不给）
        if (!gmSafe) {
            register("click_slot", Actions::clickSlot, "slot_click", "cont_click");
            register("cont_button", Actions::contButton, "menu_button");
        }
        // v0.2.1.4：截图——F2 真实按键注入（走 KeyboardHandler）或 Screenshot.grab 直调
        register("screenshot", Actions::screenshot, "shot", "f2");
        // v0.3：自建区（工具/文档）与通用 LLM 接口——"尽可能多地把内容暴露给 agent"
        //   ★v0.2.1.4 起由权限表裁剪：off 整组不注册；readonly 只保留读类
        if (!"off".equals(fm)) {
            register("file_read", Actions::fileRead);
            register("file_list", Actions::fileList);
            register("tools", Actions::toolsList);
            register("docs", Actions::docsList);
            if (!"readonly".equals(fm)) {
                register("file_write", Actions::fileWrite);
                register("file_append", Actions::fileAppend);
            }
        }
        // 出站网络闸（networkEnabled）同样收在这张权限表里：关掉则 llm_* 不注册
        if (netOk) {
            register("llm_chat", Actions::llmChat, "llm");
            register("llm_ping", Actions::llmPing);
        }
        // v0.2.1.6：自建工具执行闸（selfToolEnabled，默认关）——关掉则 tool_run 根本不进注册表
        if (AiPlayerConfig.selfToolEnabled()) {
            register("tool_run", Actions::toolRun, "run_tool");
        }
        // v0.2.1.10：sidecar 闸（[sidecar].enabled，默认关）——agent 进程的启停与状态
        if (AiPlayerConfig.sidecarEnabled()) {
            register("agent_status", Actions::agentStatus);
            register("agent_start", Actions::agentStart, "agent_run");
            register("agent_stop", Actions::agentStop, "agent_shutdown");
        }

        permSnapshot = permissionSnapshot();
        AiPlayer.LOG.info("[AI Player][权限表] 装配完成：{} 个动作名 | fileMode={} gameAction={} command={} network={} selfTool={}",
                REGISTRY.size(), fm, gm, AiPlayerConfig.commandEnabled(), netOk, AiPlayerConfig.selfToolEnabled());
    }

    static {
        buildRegistry();
    }

    // ---------------------------------------------------------------- 状态快照

    /** 玩家/世界状态快照（{@code GET /state} 与 {@code state} 动作共用）。 */
    static String state() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return Json.obj(Json.kv("loaded", Json.bool(false)),
                    Json.kv("note", Json.str("未进入世界（主菜单/重连中）")),
                    Json.kv("last_action", Json.str(lastAction)));
        }
        Level level = p.level();
        List<String> parts = new ArrayList<>();
        parts.add(Json.kv("loaded", Json.bool(true)));
        parts.add(Json.kv("pos", Json.arr(List.of(Json.num(p.getX()), Json.num(p.getY()), Json.num(p.getZ())))));
        parts.add(Json.kv("yaw", Json.num(p.getYRot())));
        parts.add(Json.kv("pitch", Json.num(p.getXRot())));
        parts.add(Json.kv("health", Json.num(p.getHealth())));
        parts.add(Json.kv("food", Json.num(p.getFoodData().getFoodLevel())));
        parts.add(Json.kv("dead", Json.bool(p.isDeadOrDying())));
        parts.add(Json.kv("dim", Json.str(level.dimension().location().toString())));
        parts.add(Json.kv("gamemode", Json.str(mc.gameMode == null ? "?" : mc.gameMode.getPlayerMode().getName())));
        parts.add(Json.kv("time", Json.obj(
                Json.kv("dayTime", Json.num(level.getDayTime())),
                Json.kv("timeOfDay", Json.num(level.getDayTime() % 24000L)),
                Json.kv("isDay", Json.bool(level.isDay())))));

        ItemStack held = p.getMainHandItem();
        parts.add(Json.kv("held", Json.obj(
                Json.kv("id", Json.str(held.isEmpty() ? "air" : itemId(held))),
                Json.kv("count", Json.num(held.getCount())),
                Json.kv("selected", Json.num(p.getInventory().selected)))));

        parts.add(Json.kv("looking_at", lookingAt(p)));
        parts.add(Json.kv("players", nearbyPlayers(p)));
        parts.add(Json.kv("chat", Json.arr(chatTail(6))));
        // v0.2.1.13 诊断字段（2026-10-16 事故修复：bot 开着物品栏 30 分钟、移动全废，
        // 旧版 state 无 screen 字段，agent 拿不到任何失败信号白烧 87 万 token）
        parts.add(Json.kv("screen", screenBrief()));
        parts.add(Json.kv("onGround", Json.bool(p.onGround())));
        parts.add(Json.kv("sprinting", Json.bool(p.isSprinting())));
        parts.add(Json.kv("flying", Json.bool(p.getAbilities().flying)));
        parts.add(Json.kv("vehicle", vehicleBrief(p)));
        parts.add(Json.kv("effects", effectsBrief(p)));
        parts.add(Json.kv("last_action", Json.str(lastAction)));
        parts.add(Json.kv("last_action_age_ms", lastActionAt == 0 ? "null" : Json.num(System.currentTimeMillis() - lastActionAt)));
        return Json.obj(parts.toArray(new String[0]));
    }

    private static String lookingAt(LocalPlayer p) {
        try {
            HitResult hit = p.pick(6.0, 0.0f, false);
            if (hit instanceof BlockHitResult bhr) {
                BlockPos pos = bhr.getBlockPos();
                BlockState st = p.level().getBlockState(pos);
                return Json.obj(
                        Json.kv("block", Json.str(blockId(st))),
                        Json.kv("pos", Json.arr(List.of(Json.num(pos.getX()), Json.num(pos.getY()), Json.num(pos.getZ())))),
                        Json.kv("face", Json.str(bhr.getDirection().getName())));
            }
        } catch (Exception ignored) {
            // 视线判定失败不影响整体状态
        }
        return "null";
    }

    private static String nearbyPlayers(LocalPlayer p) {
        List<String> out = new ArrayList<>();
        try {
            for (var other : p.level().players()) {
                if (other == p) {
                    continue;
                }
                double d = Math.sqrt(other.distanceToSqr(p));
                out.add(Json.obj(Json.kv("name", Json.str(other.getGameProfile().getName())),
                        Json.kv("dist", Json.num(Math.round(d * 10) / 10.0)),
                        Json.kv("pos", Json.arr(List.of(Json.num(other.getX()), Json.num(other.getY()), Json.num(other.getZ()))))));
                if (out.size() >= 10) {
                    break;
                }
            }
        } catch (Exception ignored) {
            // 同上
        }
        return Json.arr(out);
    }

    private static List<String> chatTail(int n) {
        try {
            ChatBuffer.init();
            List<String> out = new ArrayList<>();
            for (String s : ChatBuffer.tail(n)) {
                out.add(Json.str(s));
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 当前界面简况：无界面 = "null"，有界面 = {class,title,pause}。
     *  ★界面开着会完全屏蔽移动输入（原版 MC 行为），state 必须能直接看到——
     *  这是 2026-10-16"开着物品栏 30 分钟查不出根因"事故的核心修复。 */
    private static String screenBrief() {
        try {
            Screen s = Minecraft.getInstance().screen;
            if (s == null) {
                return "null";
            }
            return Json.obj(
                    Json.kv("class", Json.str(s.getClass().getSimpleName())),
                    Json.kv("title", Json.str(textOf(s.getTitle()))),
                    Json.kv("pause", Json.bool(s.isPauseScreen())));
        } catch (Exception e) {
            return "null";
        }
    }

    /** 骑乘的实体类型（未骑乘 = "null"）。卡在坐骑/船上也会表现为"移动异常"。 */
    private static String vehicleBrief(LocalPlayer p) {
        try {
            if (p.getVehicle() == null) {
                return "null";
            }
            return Json.str(BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType()).toString());
        } catch (Exception e) {
            return "null";
        }
    }

    /** 状态效果简表：{id,amp,left秒}；无效果 = 空数组。slow/levitation 等会直接改变移动表现。 */
    private static String effectsBrief(LocalPlayer p) {
        List<String> out = new ArrayList<>();
        try {
            for (var e : p.getActiveEffects()) {
                String id = e.getEffect().unwrapKey()
                        .map(k -> k.location().toString()).orElse("?");
                out.add(Json.obj(
                        Json.kv("id", Json.str(id)),
                        Json.kv("amp", Json.num(e.getAmplifier())),
                        Json.kv("left", Json.num(e.getDuration() / 20))));
            }
        } catch (Exception ignored) {
            // 单字段失败不影响整体快照
        }
        return Json.arr(out);
    }

    private static String itemId(ItemStack st) {
        try {
            return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        } catch (Exception e) {
            return "?";
        }
    }

    private static String blockId(BlockState st) {
        try {
            return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
        } catch (Exception e) {
            return "?";
        }
    }

    // ---------------------------------------------------------------- 动作实现

    private static LocalPlayer requirePlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            throw new IllegalStateException("offline: 未进入世界，动作被拒绝");
        }
        return p;
    }

    /** 聊天净化：去掉换行与控制字符并限长（对应 TrChat 的 100 字上限与非法字符踢人问题）。 */
    private static String sanitizeChat(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        s = s.replaceAll("[\\p{Cntrl}]", "");
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static String say(JsonObject par) {
        requirePlayer();
        String text = Json.optString(par, "text", null);
        if (text == null || text.isBlank()) {
            return "say_err: 缺少 text";
        }
        text = sanitizeChat(text);
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return "say_err: 未连接服务器";
        }
        mc.getConnection().sendChat(text);
        return "say: " + text;
    }

    // ---------------------------------------------------------------- 关闭播报

    /**
     * 客户端关闭时向服务器自动播报（2026-10-16 用户约定的服务器礼仪：
     * "每次重启或关闭客户端时向服务器发出聊天简短说明原因"）。
     *
     * <p>由 {@link AiPlayerClient} 的 JVM shutdown hook 调用（窗口关闭等正常退出路径）。
     * ★硬杀进程（部署换 jar 的 taskkill）不走 shutdown hook——那类重启按 SOP 先用
     * {@code say} 说明原因再杀。
     *
     * <p>全程防御式：播报失败只吞掉，绝不影响退出流程。
     */
    static void announceShutdown() {
        try {
            if (!AiPlayerConfig.enabled()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null || mc.player == null || mc.level == null) {
                return;   // 不在服务器中（主菜单/单人局）无需播报
            }
            String text = sanitizeChat(AiPlayerConfig.shutdownMessage());
            if (text.isBlank()) {
                return;
            }
            mc.getConnection().sendChat(text);
            // 给 netty 写线程一点时间把包发出去（shutdown hook 里进程即将退出）
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (Throwable ignored) {
            // 退出路径上的任何异常都必须吞掉
        }
    }

    private static String command(JsonObject par) {
        requirePlayer();
        String text = Json.optString(par, "text", null);
        if (text == null || text.isBlank()) {
            return "command_err: 缺少 text";
        }
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return "command_err: 未连接服务器";
        }
        mc.getConnection().sendCommand(text);
        return "command: " + text;
    }

    /** 高级联机（0.2.1.26）：connect {address:"ip:port"} 或 {} 用配置地址——立即发起连接并清零自动连接预算。 */
    private static String connectAction(JsonObject par) {
        String addr = Json.optString(par, "address", null);
        AiPlayer.LOG.info("[AI Player] connect 动作触发: address={}", addr == null ? "(用配置地址)" : addr);
        return AiPlayerClient.requestConnect(addr);
    }

    private static String time(JsonObject par) {
        LocalPlayer p = requirePlayer();
        Level level = p.level();
        long dayTime = level.getDayTime();
        return Json.obj(
                Json.kv("action", Json.str("time")),
                Json.kv("dayTime", Json.num(dayTime)),
                Json.kv("timeOfDay", Json.num(dayTime % 24000L)),
                Json.kv("isDay", Json.bool(level.isDay())),
                Json.kv("isRaining", Json.bool(level.isRaining())),
                Json.kv("isThundering", Json.bool(level.isThundering())),
                Json.kv("canSleep", Json.bool(!level.isDay())));
    }

    /** 注意：1.21 里 isDay() 在深夜也可能返回 true（旧手册记录），上层如需判定夜晚请用 timeOfDay。 */
    private static String blockAt(JsonObject par) {
        LocalPlayer p = requirePlayer();
        int x = (int) Math.floor(Json.optDouble(par, "x", Double.NaN));
        int y = (int) Math.floor(Json.optDouble(par, "y", Double.NaN));
        int z = (int) Math.floor(Json.optDouble(par, "z", Double.NaN));
        if (Double.isNaN(x + y + z)) {
            return "block_err: 缺少坐标";
        }
        BlockState st = p.level().getBlockState(new BlockPos(x, y, z));
        return Json.obj(
                Json.kv("action", Json.str("block_at")),
                Json.kv("pos", Json.arr(List.of(Json.num(x), Json.num(y), Json.num(z)))),
                Json.kv("id", Json.str(blockId(st))));
    }

    private static String blockState(JsonObject par) {
        LocalPlayer p = requirePlayer();
        int x = (int) Math.floor(Json.optDouble(par, "x", Double.NaN));
        int y = (int) Math.floor(Json.optDouble(par, "y", Double.NaN));
        int z = (int) Math.floor(Json.optDouble(par, "z", Double.NaN));
        if (Double.isNaN(x + y + z)) {
            return "blockstate_err: 缺少坐标";
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState st = p.level().getBlockState(pos);
        Map<String, String> props = new LinkedHashMap<>();
        for (Property<?> prop : st.getProperties()) {
            props.put(prop.getName(), String.valueOf(st.getValue(prop)));
        }
        List<String> ps = new ArrayList<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            ps.add(Json.kv(e.getKey(), Json.str(e.getValue())));
        }
        return Json.obj(
                Json.kv("action", Json.str("blockstate")),
                Json.kv("pos", Json.arr(List.of(Json.num(x), Json.num(y), Json.num(z)))),
                Json.kv("id", Json.str(blockId(st))),
                Json.kv("properties", Json.obj(ps.toArray(new String[0]))));
    }

    /** 区域扫描（上限 400 格，空气跳过）——诊断/摸清周边方块 id 用。 */
    private static String scanRegion(JsonObject par) {
        LocalPlayer p = requirePlayer();
        int x1 = (int) Math.floor(Json.optDouble(par, "x1", Double.NaN));
        int y1 = (int) Math.floor(Json.optDouble(par, "y1", Double.NaN));
        int z1 = (int) Math.floor(Json.optDouble(par, "z1", Double.NaN));
        int x2 = (int) Math.floor(Json.optDouble(par, "x2", Double.NaN));
        int y2 = (int) Math.floor(Json.optDouble(par, "y2", Double.NaN));
        int z2 = (int) Math.floor(Json.optDouble(par, "z2", Double.NaN));
        if (Double.isNaN(x1 + y1 + z1 + x2 + y2 + z2)) {
            return "scan_err: 需要 x1,y1,z1,x2,y2,z2";
        }
        int lx = Math.min(x1, x2), hx = Math.max(x1, x2);
        int ly = Math.min(y1, y2), hy = Math.max(y1, y2);
        int lz = Math.min(z1, z2), hz = Math.max(z1, z2);
        List<String> out = new ArrayList<>();
        int n = 0;
        for (int yy = ly; yy <= hy && n < 400; yy++) {
            for (int zz = lz; zz <= hz && n < 400; zz++) {
                for (int xx = lx; xx <= hx && n < 400; xx++) {
                    n++;
                    BlockState st = p.level().getBlockState(new BlockPos(xx, yy, zz));
                    if (st.isAir()) {
                        continue;
                    }
                    out.add(Json.str(xx + "," + yy + "," + zz + "=" + blockId(st)));
                }
            }
        }
        return Json.obj(Json.kv("action", Json.str("scan_region")),
                Json.kv("n", Json.num(n)),
                Json.kv("blocks", Json.arr(out)));
    }

    /**
     * 搜索最近的目标方块：按切比雪夫半径由近及远逐层外扩，凑够 max 个即停。
     * 对应 JsMacros 版的 find_block（2026-10-15 新增的那个"地形工具"）。
     */
    private static String findBlock(JsonObject par) {
        LocalPlayer p = requirePlayer();
        List<String> pats = new ArrayList<>();
        JsonElement idEl = par.has("ids") ? par.get("ids") : (par.has("id") ? par.get("id") : par.get("block"));
        if (idEl == null || idEl.isJsonNull()) {
            return "find_err: 需要 id（方块 id 子串，多个用 | 分隔）";
        }
        if (idEl.isJsonArray()) {
            JsonArray arr = idEl.getAsJsonArray();
            for (JsonElement e : arr) {
                pats.add(e.getAsString().toLowerCase(Locale.ROOT).trim());
            }
        } else {
            for (String s : idEl.getAsString().split("\\|")) {
                if (!s.isBlank()) {
                    pats.add(s.toLowerCase(Locale.ROOT).trim());
                }
            }
        }
        pats.removeIf(String::isEmpty);
        if (pats.isEmpty()) {
            return "find_err: id 为空";
        }

        int radius = Math.max(1, Math.min(Json.optInt(par, "radius", 24), 64));
        int max = Math.max(1, Math.min(Json.optInt(par, "max", 3), 20));
        int px = (int) Math.floor(p.getX()), py = (int) Math.floor(p.getY()), pz = (int) Math.floor(p.getZ());
        int ylo = par.has("y_min") ? (int) Math.floor(Json.optDouble(par, "y_min", py - 12)) : Math.max(-64, py - 12);
        int yhi = par.has("y_max") ? (int) Math.floor(Json.optDouble(par, "y_max", py + 12)) : Math.min(320, py + 12);

        long t0 = System.currentTimeMillis();
        record Hit(int x, int y, int z, String id, double dist) {
        }
        List<Hit> hits = new ArrayList<>();
        int reads = 0;
        outer:
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;   // 只扫最外层壳
                    }
                    for (int y = ylo; y <= yhi; y++) {
                        reads++;
                        BlockState st = p.level().getBlockState(new BlockPos(px + dx, y, pz + dz));
                        if (st.isAir()) {
                            continue;
                        }
                        String id = blockId(st).toLowerCase(Locale.ROOT);
                        boolean match = false;
                        for (String pat : pats) {
                            if (id.contains(pat)) {
                                match = true;
                                break;
                            }
                        }
                        if (!match) {
                            continue;
                        }
                        double d = Math.sqrt((double) dx * dx + (double) (y - py) * (y - py) + (double) dz * dz);
                        hits.add(new Hit(px + dx, y, pz + dz, blockId(st), Math.round(d * 10) / 10.0));
                        break;    // 同一竖列只记最近的一个
                    }
                    if (hits.size() >= max) {
                        break outer;
                    }
                }
            }
        }
        hits.sort((a, b) -> Double.compare(a.dist(), b.dist()));
        List<String> near = new ArrayList<>();
        for (Hit h : hits.subList(0, Math.min(max, hits.size()))) {
            near.add(Json.obj(Json.kv("x", Json.num(h.x())), Json.kv("y", Json.num(h.y())), Json.kv("z", Json.num(h.z())),
                    Json.kv("id", Json.str(h.id())), Json.kv("dist", Json.num(h.dist()))));
        }
        return Json.obj(
                Json.kv("action", Json.str("find_block")),
                Json.kv("query", Json.arr(pats.stream().map(Json::str).toList())),
                Json.kv("radius", Json.num(radius)),
                Json.kv("origin", Json.arr(List.of(Json.num(px), Json.num(py), Json.num(pz)))),
                Json.kv("y_range", Json.arr(List.of(Json.num(ylo), Json.num(yhi)))),
                Json.kv("reads", Json.num(reads)),
                Json.kv("ms", Json.num(System.currentTimeMillis() - t0)),
                Json.kv("found", Json.num(near.size())),
                Json.kv("nearest", Json.arr(near)),
                Json.kv("hint", near.isEmpty()
                        ? Json.str("没找到：加大 radius（上限 64）或换关键词；也可用 scan_region 看周边真实方块 id")
                        : "null"));
    }

    /** 光标所持物品：容器界面打开时取 menu.getCarried()，否则视为空。 */
    private static ItemStack cursorItem() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?> cs) {
            try {
                return cs.getMenu().getCarried();
            } catch (Throwable t) {
                return ItemStack.EMPTY;
            }
        }
        return ItemStack.EMPTY;
    }

    private static String inventory(JsonObject par) {
        LocalPlayer p = requirePlayer();
        Inventory inv = p.getInventory();
        List<String> items = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) {
                continue;
            }
            items.add(Json.obj(Json.kv("slot", Json.num(i)),
                    Json.kv("id", Json.str(itemId(st))),
                    Json.kv("count", Json.num(st.getCount()))));
        }
        // v0.2.1.7：每次查物品栏都带回**光标所持物品**——GUI 多步操作（取放/网络存取）时
        // 模型不用再单独 get_screen 就能确认手上拿着什么，避免"带物关界面"类失误
        ItemStack cur = cursorItem();
        return Json.obj(Json.kv("action", Json.str("get_inventory")),
                Json.kv("selected", Json.num(inv.selected)),
                Json.kv("totalSlots", Json.num(inv.getContainerSize())),
                Json.kv("cursor", Json.obj(
                        Json.kv("id", Json.str(cur.isEmpty() ? "air" : itemId(cur))),
                        Json.kv("count", Json.num(cur.getCount())))),
                Json.kv("items", Json.arr(items)));
    }

    private static String look(JsonObject par) {
        LocalPlayer p = requirePlayer();
        float yaw;
        float pitch;
        if (par.has("yaw") || par.has("pitch")) {
            yaw = (float) Json.optDouble(par, "yaw", p.getYRot());
            pitch = (float) Json.optDouble(par, "pitch", p.getXRot());
        } else {
            double tx = Json.optDouble(par, "x", Double.NaN);
            double ty = Json.optDouble(par, "y", Double.NaN);
            double tz = Json.optDouble(par, "z", Double.NaN);
            if (Double.isNaN(tx + ty + tz)) {
                return "look_err: 需要 x,y,z 或 yaw,pitch";
            }
            double dx = tx - p.getX();
            double dy = ty - p.getEyeY();
            double dz = tz - p.getZ();
            double flat = Math.sqrt(dx * dx + dz * dz);
            yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
            pitch = (float) (-(Math.atan2(dy, flat) * 180.0 / Math.PI));
        }
        p.setYRot(yaw);
        p.setXRot(pitch);
        return Json.obj(Json.kv("action", Json.str("look")),
                Json.kv("yaw", Json.num(yaw)), Json.kv("pitch", Json.num(pitch)));
    }

    // ---------------------------------------------------------------- 输入：按键 / 移动 / 视角 / 鼠标

    /**
     * 按键名 → {@link KeyMapping}。支持**绑定名**（key.forward / key.use / key.jump …）
     * 也支持物理名（key.keyboard.w / key.mouse.right …）。
     *
     * <p>按住走 {@code KeyMapping.setDown(boolean)} —— 与旧 JsMacros 版 `kbSet()` 同一个路子：
     * 那是当年修好"移动类按键无效"的关键（持续型按键必须置 KeyMapping 的 down 状态，
     * 单纯发一次 click 只会被 consumeClick 的事件闩锁处理掉）。
     */
    private static KeyMapping resolveMapping(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            for (KeyMapping km : mc.options.keyMappings) {
                if (km.getName().equalsIgnoreCase(name) || km.saveString().equalsIgnoreCase(name)) {
                    return km;
                }
            }
        } catch (Exception ignored) {
            // options 还没就绪
        }
        return null;
    }

    private static InputConstants.Key resolvePhysical(String name) {
        try {
            InputConstants.Key k = InputConstants.getKey(name);
            return k == null || k == InputConstants.UNKNOWN ? null : k;
        } catch (Exception e) {
            return null;
        }
    }

    private static KeyMapping requireMapping(String name) {
        KeyMapping km = resolveMapping(name);
        if (km == null) {
            throw new IllegalArgumentException("未知按键: " + name
                    + "（用绑定名 key.forward / key.use / key.jump …，或物理名 key.keyboard.w / key.mouse.right）");
        }
        return km;
    }

    private static void hold(boolean down, List<KeyMapping> maps) {
        for (KeyMapping km : maps) {
            km.setDown(down);
        }
    }

    /** 到时间自动松开（避免按键永久卡住 —— 卡住比没按下更糟）。 */
    private static void releaseAfter(long ms, List<KeyMapping> maps) {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aiplayer-key-release");
                t.setDaemon(true);
                return t;
            });
        }
        Minecraft mc = Minecraft.getInstance();
        scheduler.schedule(() -> mc.execute(() -> hold(false, maps)), ms, TimeUnit.MILLISECONDS);
    }

    private static void holdAndRelease(int durationMs, List<KeyMapping> maps) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> hold(true, maps));
        releaseAfter(durationMs, maps);
    }

    /**
     * 单击一次按键。
     *
     * <p>★必须走 {@code KeyMapping.click(key)}（累加 clickCount → 被 {@code consumeClick()} 消费）：
     * 开背包 / 丢弃 / 切换手这类是**事件闩锁型**，只 {@code setDown} 不会触发
     * ——v0.2.1 实测过：setDown 版本的 press 开不了背包，改成 click 才正常。
     *
     * <p>持续型按键（移动 / 攻击 / 使用 / 跳跃）请用 {@code keys} 或 {@code move}（走 setDown 按住）。
     */
    private static String press(JsonObject par) {
        requirePlayer();
        String name = Json.optString(par, "key", null);
        if (name == null) {
            return "press_err: 缺少 key";
        }
        // 绑定名（key.inventory / key.use …）→ 取它绑定的物理键；物理名 → 直接用
        InputConstants.Key k = null;
        KeyMapping km = resolveMapping(name);
        if (km != null) {
            k = resolvePhysical(km.saveString());
        }
        if (k == null) {
            k = resolvePhysical(name);
        }
        if (k == null) {
            return "press_err: 未知按键 " + name
                    + "（用绑定名 key.inventory / key.use …，或物理名 key.keyboard.e / key.mouse.right）";
        }
        KeyMapping.click(k);
        return "press " + name;
    }

    /** 长按一组按键：持续型交互（挖矿、右键食用、蓄力）必须用它，不是 press。 */
    private static String keys(JsonObject par) {
        requirePlayer();
        JsonArray arr = par.has("keys") && par.get("keys").isJsonArray() ? par.getAsJsonArray("keys") : null;
        if (arr == null || arr.isEmpty()) {
            return "keys_err: 需要 keys 数组";
        }
        int duration = Math.max(1, Math.min(Json.optInt(par, "duration", 600), 30000));
        List<KeyMapping> maps = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (JsonElement e : arr) {
            KeyMapping km = requireMapping(e.getAsString());
            maps.add(km);
            names.add(e.getAsString());
        }
        holdAndRelease(duration, maps);
        return "keys 按住 " + duration + "ms: " + names;
    }

    /** 移动：forward/back/left/right/jump/sneak/sprint 任意组合，按 duration 毫秒。 */
    private static String move(JsonObject par) {
        requirePlayer();
        int duration = Math.max(1, Math.min(Json.optInt(par, "duration", 1000), 60000));
        List<KeyMapping> maps = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String dir = Json.optString(par, "dir", null);
        boolean forward = Json.optBool(par, "forward", false) || "forward".equalsIgnoreCase(String.valueOf(dir));
        boolean back = Json.optBool(par, "back", false) || "back".equalsIgnoreCase(String.valueOf(dir));
        boolean left = Json.optBool(par, "left", false) || "left".equalsIgnoreCase(String.valueOf(dir));
        boolean right = Json.optBool(par, "right", false) || "right".equalsIgnoreCase(String.valueOf(dir));
        boolean jump = Json.optBool(par, "jump", false);
        boolean sneak = Json.optBool(par, "sneak", false);
        boolean sprint = Json.optBool(par, "sprint", false);
        if (forward) {
            maps.add(requireMapping("key.forward"));
            names.add("forward");
        }
        if (back) {
            maps.add(requireMapping("key.back"));
            names.add("back");
        }
        if (left) {
            maps.add(requireMapping("key.left"));
            names.add("left");
        }
        if (right) {
            maps.add(requireMapping("key.right"));
            names.add("right");
        }
        if (jump) {
            maps.add(requireMapping("key.jump"));
            names.add("jump");
        }
        if (sneak) {
            maps.add(requireMapping("key.sneak"));
            names.add("sneak");
        }
        if (sprint) {
            // 1.21 的疾跑绑定名
            KeyMapping sp = resolveMapping("key.sprint");
            if (sp != null) {
                maps.add(sp);
                names.add("sprint");
            }
        }
        if (maps.isEmpty()) {
            return "move_err: 需要 forward/back/left/right/jump/sneak/sprint 之一（或 dir=forward|back|left|right）";
        }
        holdAndRelease(duration, maps);
        return "move " + duration + "ms: " + names;
    }

    /** 跳一下（按住跳跃键；长按时会连续跳）。 */
    private static String jump(JsonObject par) {
        requirePlayer();
        int duration = Math.max(80, Math.min(Json.optInt(par, "duration", 260), 5000));
        holdAndRelease(duration, List.of(requireMapping("key.jump")));
        return "jump " + duration + "ms";
    }

    /** 潜行（按住 sneak）。 */
    private static String sneak(JsonObject par) {
        requirePlayer();
        int duration = Math.max(1, Math.min(Json.optInt(par, "duration", 1000), 60000));
        holdAndRelease(duration, List.of(requireMapping("key.sneak")));
        return "sneak " + duration + "ms";
    }

    /** 快捷栏切换：改 selected 并同步服务端（否则只改了客户端，服务端仍是旧槽）。 */
    private static String select(JsonObject par) {
        LocalPlayer p = requirePlayer();
        int slot = Json.optInt(par, "slot", -1);
        if (slot < 0 || slot > 8) {
            return "select_err: slot 需 0-8（快捷栏 9 格）";
        }
        Minecraft mc = Minecraft.getInstance();
        p.getInventory().selected = slot;
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
        ItemStack st = p.getInventory().getSelected();
        return Json.obj(Json.kv("action", Json.str("select")),
                Json.kv("slot", Json.num(slot)),
                Json.kv("item", Json.obj(Json.kv("id", Json.str(st.isEmpty() ? "air" : itemId(st))),
                        Json.kv("count", Json.num(st.getCount())))));
    }

    /** 相对转向（默认）或绝对转向。 */
    private static String turn(JsonObject par) {
        LocalPlayer p = requirePlayer();
        boolean absolute = Json.optBool(par, "absolute", false);
        float yaw;
        float pitch;
        if (absolute) {
            yaw = (float) Json.optDouble(par, "yaw", p.getYRot());
            pitch = (float) Json.optDouble(par, "pitch", p.getXRot());
        } else {
            yaw = p.getYRot() + (float) Json.optDouble(par, "dyaw", 0);
            pitch = p.getXRot() + (float) Json.optDouble(par, "dpitch", 0);
        }
        pitch = Math.max(-90.0f, Math.min(90.0f, pitch));
        yaw = yaw % 360.0f;
        if (yaw >= 180.0f) {
            yaw -= 360.0f;
        } else if (yaw < -180.0f) {
            yaw += 360.0f;
        }
        p.setYRot(yaw);
        p.setXRot(pitch);
        return Json.obj(Json.kv("action", Json.str("turn")),
                Json.kv("yaw", Json.num(yaw)),
                Json.kv("pitch", Json.num(pitch)));
    }

    /**
     * 鼠标滚轮（AccessTransformer 放通后的直调）。
     * 旧 JsMacros 版靠反射调私有 onScroll —— 映射一变就静默失效，现在是编译期可见。
     */
    private static String scroll(JsonObject par) {
        requirePlayer();
        String dir = Json.optString(par, "dir", "up");
        boolean down = "down".equalsIgnoreCase(dir);
        if (!down && !"up".equalsIgnoreCase(dir)) {
            return "scroll_err: dir 只能是 up / down（" + dir + "）";
        }
        int amount = Math.max(1, Math.min(Json.optInt(par, "amount", 1), 20));
        Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler == null || mc.getWindow() == null) {
            return "scroll_err: 鼠标或窗口不可用";
        }
        double yOffset = (down ? -1.0 : 1.0) * amount;
        mc.mouseHandler.onScroll(mc.getWindow().getWindow(), 0.0, yOffset);
        return Json.obj(Json.kv("action", Json.str("scroll")),
                Json.kv("dir", Json.str(down ? "down" : "up")),
                Json.kv("amount", Json.num(amount)));
    }

    /**
     * 移动鼠标准星（窗口像素坐标）。
     *
     * <p>★必须同时写私有坐标字段再走 onMove：旧脚本"坑15"记过 ——
     * MouseHandler 的私有 xpos/ypos 不随 glfwSetCursorPos 回调更新，
     * 不同步的话之后 onPress 会拿旧坐标点空。
     */
    private static String mouseMove(JsonObject par) {
        Minecraft mc = Minecraft.getInstance();
        double x = Json.optDouble(par, "x", Double.NaN);
        double y = Json.optDouble(par, "y", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return "mouse_err: 需要 x,y（窗口像素坐标）";
        }
        if (mc.mouseHandler == null || mc.getWindow() == null) {
            return "mouse_err: 鼠标或窗口不可用";
        }
        mc.mouseHandler.xpos = x;
        mc.mouseHandler.ypos = y;
        mc.mouseHandler.onMove(mc.getWindow().getWindow(), x, y);
        return Json.obj(Json.kv("action", Json.str("mouse_move")),
                Json.kv("x", Json.num(x)), Json.kv("y", Json.num(y)));
    }

    private static String closeScreen() {
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(null));
        return "close_screen";
    }

    private static String reloadChunks() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.levelRenderer != null) {
                mc.levelRenderer.allChanged();
            }
        });
        return "reload_chunks";
    }

    private static String players(JsonObject par) {
        LocalPlayer p = requirePlayer();
        return nearbyPlayers(p);
    }

    /**
     * 全服在线玩家（v0.2.1.14 新增）。
     *
     * <p>数据源 = 客户端 Tab 列表（{@code connection.getOnlinePlayers()}）——服务端下发的全服在线
     * 名单，含其它维度与远距离玩家；与 {@link #players}（附近已加载玩家）互补：想知道"谁在线、
     * 要不要打招呼"用本动作，想知道"身边有谁、多远"用 players。state.players 同 nearbyPlayers，别混。
     *
     * <p>输出 {"count":N,"players":[{"name":..,"ping":ms},..]}，按延迟升序；未连接服务器返回 err。
     */
    private static String onlinePlayers(JsonObject par) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return "err: 未连接服务器";
        }
        List<PlayerInfo> infos = new ArrayList<>();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            if (info == null || info.getProfile() == null) {
                continue;
            }
            String name = info.getProfile().getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            infos.add(info);
        }
        infos.sort(Comparator.comparingInt(PlayerInfo::getLatency));
        List<String> out = new ArrayList<>();
        for (PlayerInfo info : infos) {
            out.add(Json.obj(Json.kv("name", Json.str(info.getProfile().getName())),
                    Json.kv("ping", Json.num(info.getLatency()))));
        }
        return Json.obj(Json.kv("count", Json.num(out.size())),
                Json.kv("players", Json.arr(out)));
    }

    private static String entities(JsonObject par) {
        LocalPlayer p = requirePlayer();
        List<String> out = new ArrayList<>();
        try {
            int limit = Math.max(1, Math.min(Json.optInt(par, "limit", 20), 100));
            double radius = Json.optDouble(par, "radius", 32);
            var box = p.getBoundingBox().inflate(radius);
            for (var e : p.level().getEntities(p, box)) {
                double d = Math.sqrt(e.distanceToSqr(p));
                out.add(Json.obj(Json.kv("type", Json.str(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())))),
                        Json.kv("dist", Json.num(Math.round(d * 10) / 10.0)),
                        Json.kv("pos", Json.arr(List.of(Json.num(e.getX()), Json.num(e.getY()), Json.num(e.getZ()))))));
                if (out.size() >= limit) {
                    break;
                }
            }
        } catch (Exception e) {
            return "entities_err: " + e;
        }
        return Json.arr(out);
    }

    // ---------------------------------------------------------------- UI 感知 / UI 交互

    /**
     * 读当前界面的结构：类名、标题、控件树（含中心坐标与文本）、容器槽位（含 screen 坐标与物品）。
     *
     * <p>这是**石碑传送 / 容器交互 / 任何模组 GUI** 的前提：没有它，agent 只能盲点。
     * 输出设计成"能直接喂给 screen_click"的形式（cx/cy 就是控件中心）。
     */
    private static String getScreen(JsonObject par) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) {
            return Json.obj(Json.kv("action", Json.str("get_screen")),
                    Json.kv("screen", "null"),
                    Json.kv("note", Json.str("当前无界面（人在游戏中）")));
        }
        boolean slots = Json.optBool(par, "slots", true);
        List<String> parts = new ArrayList<>();
        parts.add(Json.kv("action", Json.str("get_screen")));
        parts.add(Json.kv("class", Json.str(screen.getClass().getName())));
        parts.add(Json.kv("simple", Json.str(screen.getClass().getSimpleName())));
        parts.add(Json.kv("title", Json.str(textOf(screen.getTitle()))));
        parts.add(Json.kv("is_pause", Json.bool(screen.isPauseScreen())));
        try {
            parts.add(Json.kv("gui_size", Json.obj(
                    Json.kv("w", Json.num(mc.getWindow().getGuiScaledWidth())),
                    Json.kv("h", Json.num(mc.getWindow().getGuiScaledHeight())),
                    Json.kv("scale", Json.num(mc.getWindow().getGuiScale())))));
        } catch (Exception ignored) {
            // 窗口尺寸拿不到就算了
        }

        // 控件树（一层）：按钮/标签/输入框等 AbstractWidget
        List<String> widgets = new ArrayList<>();
        List<AbstractWidget> found = new ArrayList<>();
        int idx = 0;
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w) {
                found.add(w);
                widgets.add(Json.obj(
                        Json.kv("i", Json.num(idx)),
                        Json.kv("class", Json.str(w.getClass().getSimpleName())),
                        Json.kv("x", Json.num(w.getX())), Json.kv("y", Json.num(w.getY())),
                        Json.kv("w", Json.num(w.getWidth())), Json.kv("h", Json.num(w.getHeight())),
                        Json.kv("cx", Json.num(w.getX() + w.getWidth() / 2.0)),
                        Json.kv("cy", Json.num(w.getY() + w.getHeight() / 2.0)),
                        Json.kv("text", Json.str(textOf(w.getMessage()))),
                        Json.kv("active", Json.bool(w.isActive())),
                        Json.kv("visible", Json.bool(w.visible))));
                idx++;
            }
        }
        parts.add(Json.kv("widget_count", Json.num(found.size())));
        parts.add(Json.kv("widgets", Json.arr(widgets)));

        // 0.2.1.16：列表条目（石碑选择/服务器列表等 AbstractSelectionList）——
        // 选项行不是 AbstractWidget，上面的控件树整个看不见它们（石碑"几条选项"事故根因）。
        // 这里枚举条目：总条数 + 每条文本 + 可见条目的行坐标（可直接 screen_click）。
        List<String> lists = new ArrayList<>();
        idx = 0;
        for (var child : screen.children()) {
            if (child instanceof AbstractSelectionList<?> sel) {
                List<String> rows = new ArrayList<>();
                var entries = sel.children();
                int rowLeft, rowWidth, listTop, listH;
                try {
                    rowLeft = sel.getRowLeft();
                    rowWidth = sel.getRowWidth();
                    listTop = sel.getY();
                    listH = sel.getHeight();
                } catch (Throwable t) {
                    rowLeft = rowWidth = listTop = listH = -1;
                }
                // getRowTop 是 protected —— 反射取（一次性解析）
                java.lang.reflect.Method rowTopM = null;
                try {
                    rowTopM = AbstractSelectionList.class.getDeclaredMethod("getRowTop", int.class);
                    rowTopM.setAccessible(true);
                } catch (Throwable ignored) {
                }
                for (int e = 0; e < entries.size(); e++) {
                    Object entry = entries.get(e);
                    String txt = entryTextOf(entry);
                    boolean offscreen = false;
                    Integer cy = null;
                    if (listTop >= 0 && rowTopM != null) {
                        try {
                            int rowTop = (int) rowTopM.invoke(sel, e);
                            cy = rowTop + 10;   // 行高近似中点（行高≈20，取文本行中心）
                            offscreen = rowTop < listTop - 20 || rowTop > listTop + listH;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (offscreen) {
                        continue;   // 屏幕外条目不占摘要空间（总数已含它们）
                    }
                    List<String> row = new ArrayList<>();
                    row.add(Json.kv("i", Json.num(e)));
                    if (cy != null) {
                        row.add(Json.kv("cx", Json.num(rowLeft + rowWidth / 2.0)));
                        row.add(Json.kv("cy", Json.num(cy)));
                    }
                    row.add(Json.kv("text", Json.str(txt)));
                    rows.add(Json.obj(row.toArray(new String[0])));
                }
                List<String> li = new ArrayList<>();
                li.add(Json.kv("widget_i", Json.num(idx)));
                li.add(Json.kv("class", Json.str(sel.getClass().getSimpleName())));
                li.add(Json.kv("entry_count", Json.num(entries.size())));
                li.add(Json.kv("visible", Json.arr(rows)));
                lists.add(Json.obj(li.toArray(new String[0])));
            }
            idx++;
        }
        if (!lists.isEmpty()) {
            parts.add(Json.kv("lists", Json.arr(lists)));
            parts.add(Json.kv("hint2", Json.str("列表条目用 screen_click{cx,cy} 点击；entry_count=总条数")));
        }

        if (screen instanceof AbstractContainerScreen<?> cs && slots) {
            int left = cs.getGuiLeft();
            int top = cs.getGuiTop();
            parts.add(Json.kv("container", Json.obj(
                    Json.kv("left", Json.num(left)), Json.kv("top", Json.num(top)),
                    Json.kv("image_w", Json.num(cs.getXSize())), Json.kv("image_h", Json.num(cs.getYSize())))));
            List<String> slotList = new ArrayList<>();
            for (net.minecraft.world.inventory.Slot s : cs.getMenu().slots) {
                ItemStack st = s.getItem();
                slotList.add(Json.obj(
                        Json.kv("index", Json.num(s.index)),
                        Json.kv("cx", Json.num(left + s.x + 8)),
                        Json.kv("cy", Json.num(top + s.y + 8)),
                        Json.kv("id", Json.str(st.isEmpty() ? "air" : itemId(st))),
                        Json.kv("count", Json.num(st.getCount()))));
            }
            parts.add(Json.kv("slots", Json.arr(slotList)));
            ItemStack cur = cs.getMenu().getCarried();
            parts.add(Json.kv("cursor", Json.obj(
                    Json.kv("id", Json.str(cur.isEmpty() ? "air" : itemId(cur))),
                    Json.kv("count", Json.num(cur.getCount())))));
            net.minecraft.world.inventory.Slot hs = cs.hoveredSlot;
            parts.add(Json.kv("hovered", hs == null ? "null" : Json.num(hs.index)));
        }
        parts.add(Json.kv("hint", Json.str("点控件用 screen_click_widget{text|index}；点槽位用 screen_click{x=cx,y=cy}")));
        return Json.obj(parts.toArray(new String[0]));
    }

    private static String textOf(Component c) {
        try {
            return c == null ? "" : c.getString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 0.2.1.18：在列表条目里按文本找可见条目。
     * 返回 {cx, cy, 条目序号}；cx&lt;0 表示命中但在可见区域之外；没找到返回 null。
     * 坐标与 getScreen 输出保持一致（rowTop + 10 ≈ 行文本中心）。
     */
    private static double[] findListEntry(Screen screen, String lower) {
        for (var child : screen.children()) {
            if (!(child instanceof AbstractSelectionList<?> sel)) {
                continue;
            }
            int listTop, listH, rowLeft, rowWidth;
            try {
                listTop = sel.getY();
                listH = sel.getHeight();
                rowLeft = sel.getRowLeft();
                rowWidth = sel.getRowWidth();
            } catch (Throwable t) {
                continue;
            }
            java.lang.reflect.Method rowTopM = null;
            try {
                rowTopM = AbstractSelectionList.class.getDeclaredMethod("getRowTop", int.class);
                rowTopM.setAccessible(true);
            } catch (Throwable ignored) {
            }
            if (rowTopM == null) {
                continue;
            }
            var entries = sel.children();
            for (int e = 0; e < entries.size(); e++) {
                int rowTop;
                try {
                    rowTop = (int) rowTopM.invoke(sel, e);
                } catch (Throwable ignored) {
                    continue;
                }
                boolean off = rowTop < listTop - 20 || rowTop > listTop + listH;
                if (off) {
                    if (entryTextOf(entries.get(e)).toLowerCase(Locale.ROOT).contains(lower)) {
                        return new double[]{-1, -1, e};
                    }
                    continue;
                }
                if (entryTextOf(entries.get(e)).toLowerCase(Locale.ROOT).contains(lower)) {
                    return new double[]{rowLeft + rowWidth / 2.0, rowTop + 10.0, e};
                }
            }
        }
        return null;
    }

    /**
     * 0.2.1.16：列表条目文本提取。列表条目（AbstractSelectionList.Entry）不是 widget、
     * 没有 getMessage()——各模组自定义字段五花八门，这里用反射兜底：
     * 先扫 0 参方法（getMessage/getName/getTitle 类），再扫字段（优先 message/text/name/title/label，
     * 其余次之）；字段值既非 Component/String 时下钻一层（实测 Waystone 条目文本藏在
     * entry 内层对象里，第一版只扫一层拿到的是空文本）。
     */
    private static String entryTextOf(Object entry) {
        return entry == null ? "" : scanText(entry, 0);
    }

    private static String scanText(Object obj, int depth) {
        if (obj == null || depth > 1) {
            return "";
        }
        Class<?> top = obj.getClass();
        if (top.getName().startsWith("java.") || top.getName().startsWith("com.mojang.datafixers")) {
            return "";
        }
        // ① 0 参方法：方法名含 message/text/name/title/label/narration 且返回 Component/String
        for (Class<?> c = top; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) {
                    continue;
                }
                Class<?> rt = m.getReturnType();
                if (!Component.class.isAssignableFrom(rt) && rt != String.class) {
                    continue;
                }
                String n = m.getName().toLowerCase();
                if (!(n.contains("message") || n.contains("text") || n.contains("name")
                        || n.contains("title") || n.contains("label") || n.contains("narration"))) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    String s = componentText(m.invoke(obj));
                    if (!s.isBlank()) {
                        return s;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // ② 字段：优先 message/text/name/title/label，其次其余；非文本值下钻一层
        List<Field> preferred = new ArrayList<>();
        List<Field> others = new ArrayList<>();
        for (Class<?> c = top; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    String n = f.getName().toLowerCase();
                    if (n.contains("message") || n.contains("text") || n.contains("name")
                            || n.contains("title") || n.contains("label")) {
                        preferred.add(f);
                    } else {
                        others.add(f);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        for (Field f : preferred) {
            try {
                Object v = f.get(obj);
                String s = componentText(v);
                if (!s.isBlank()) {
                    return s;
                }
                if (v != null && depth < 1) {
                    s = scanText(v, depth + 1);
                    if (!s.isBlank()) {
                        return s;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        for (Field f : others) {
            try {
                Object v = f.get(obj);
                String s = componentText(v);
                if (!s.isBlank()) {
                    return s;
                }
                if (v != null && depth < 1) {
                    s = scanText(v, depth + 1);
                    if (!s.isBlank()) {
                        return s;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    /** 条目字段 → 文本：Component / String / List&lt;Component&gt;（siblings）三种形态。 */
    private static String componentText(Object v) {
        if (v instanceof Component comp) {
            return textOf(comp);
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof List<?> l && !l.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object o : l) {
                String s = componentText(o);
                if (s != null) {
                    sb.append(s);
                }
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * 点击界面：x/y 为 **GUI 坐标**（get_screen 给的 cx/cy 可直接用）。
     *
     * <p>★两个必须照做的细节（旧 index.js 的"坑15"）：
     * ① 私有 xpos/ypos 不随鼠标回调更新 → 点之前必须先同步；
     * ② 1.21.1 没有 onRelease → 按下/释放都走 onPress，action 1=按下 0=释放。
     */
    private static String screenClick(JsonObject par) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && !Json.optBool(par, "force", false)) {
            return "screen_click_err: 当前没有界面（force=true 可强制点击）";
        }
        double x = Json.optDouble(par, "x", Double.NaN);
        double y = Json.optDouble(par, "y", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return "screen_click_err: 需要 x,y（GUI 坐标；先用 get_screen 拿 cx/cy）";
        }
        int button = Math.max(0, Math.min(Json.optInt(par, "button", 0), 2));   // 0左 1右 2中
        if (mc.mouseHandler == null || mc.getWindow() == null) {
            return "screen_click_err: 鼠标或窗口不可用";
        }
        double scale = mc.getWindow().getGuiScale();
        double px = x * scale;
        double py = y * scale;
        long win = mc.getWindow().getWindow();
        mc.mouseHandler.xpos = px;
        mc.mouseHandler.ypos = py;
        mc.mouseHandler.onMove(win, px, py);
        mc.mouseHandler.onPress(win, button, 1, 0);   // 按下
        mc.mouseHandler.onPress(win, button, 0, 0);   // 释放
        return Json.obj(Json.kv("action", Json.str("screen_click")),
                Json.kv("x", Json.num(x)), Json.kv("y", Json.num(y)),
                Json.kv("px", Json.num(px)), Json.kv("py", Json.num(py)),
                Json.kv("button", Json.num(button)));
    }

    /** 按文本（子串，忽略大小写）或序号点控件 —— 石碑列表、按钮都靠它。 */
    private static String screenClickWidget(JsonObject par) {        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) {
            return "screen_click_err: 当前没有界面";
        }
        String text = Json.optString(par, "text", null);
        int index = Json.optInt(par, "index", -1);
        List<AbstractWidget> widgets = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w) {
                widgets.add(w);
            }
        }
        AbstractWidget target = null;
        int matched = -1;
        if (text != null && !text.isBlank()) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (int i = 0; i < widgets.size(); i++) {
                String t = textOf(widgets.get(i).getMessage()).toLowerCase(Locale.ROOT);
                if (t.contains(lower)) {
                    target = widgets.get(i);
                    matched = i;
                    break;
                }
            }
            if (target == null) {
                // 0.2.1.18：列表条目（石碑选择/服务器列表）不是 widget——按文本找条目行
                double[] hit = findListEntry(screen, lower);
                if (hit != null) {
                    if (hit[0] < 0) {
                        return "screen_click_err: 文本「" + text
                                + "」在第 " + (int) hit[2] + " 条，但该条目当前不在可见区域（先滚动列表再点）";
                    }
                    JsonObject inner2 = new JsonObject();
                    inner2.addProperty("x", hit[0]);
                    inner2.addProperty("y", hit[1]);
                    inner2.addProperty("button", Json.optInt(par, "button", 0));
                    String r2 = screenClick(inner2);
                    return Json.obj(Json.kv("action", Json.str("screen_click_widget")),
                            Json.kv("matched", Json.str("列表条目#" + (int) hit[2])),
                            Json.kv("x", Json.num(hit[0])), Json.kv("y", Json.num(hit[1])),
                            Json.kv("result", Json.str(r2)));
                }
                return "screen_click_err: 没有文本的控件包含 " + text
                        + "（先用 get_screen 看看有哪些 text；列表类界面的条目文本在 列表.条目 里）";
            }
        } else if (index >= 0 && index < widgets.size()) {
            target = widgets.get(index);
            matched = index;
        } else {
            return "screen_click_err: 需要 text 或 index（index 范围 0-" + (widgets.size() - 1) + "）";
        }
        JsonObject inner = new JsonObject();
        inner.addProperty("x", target.getX() + target.getWidth() / 2.0);
        inner.addProperty("y", target.getY() + target.getHeight() / 2.0);
        inner.addProperty("button", Json.optInt(par, "button", 0));
        String r = screenClick(inner);
        return Json.obj(Json.kv("action", Json.str("screen_click_widget")),
                Json.kv("matched_index", Json.num(matched)),
                Json.kv("matched_text", Json.str(textOf(target.getMessage()))),
                Json.kv("click", Json.maybeJson(r)));
    }

    /**
     * 往当前界面的输入框（EditBox）写文本 —— 维度网络搜索框 /  JEI 搜索 / 命名框通吃。
     *
     * <p>★为什么不逐字符合成键盘事件：反字节码核实过 {@code EditBox.setValue} 末尾会调用
     * {@code onValueChange}（responder），模组的搜索/过滤 responder **立即生效**；
     * DimensionsNetGUI 的 containerTick 还会在下一 tick 比对 lastSearchText 兜底。
     * 而 {@code press} 的 {@code KeyMapping.click} 只累加 clickCount，到不了 EditBox 的
     * keyTyped —— 合成键盘那条路是死的，直接写值才是正解。
     *
     * <p>目标框选取优先级：{@code index}（get_screen 的控件序号 i）> {@code x,y}（GUI 坐标命中）
     * > 当前焦点 > 界面里第一个 EditBox。传 {@code text=""} 即清空（setValue 替换全文）。
     */
    private static String screenType(JsonObject par) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) {
            return "screen_type_err: 当前没有界面";
        }
        String text = Json.optString(par, "text", null);
        if (text == null) {
            return "screen_type_err: 需要 text（传 \"\" 清空输入框）";
        }
        // 按 get_screen 同样的顺序收集控件（保证 index 语义一致）
        List<AbstractWidget> all = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w) {
                all.add(w);
            }
        }
        if (all.isEmpty()) {
            return "screen_type_err: 当前界面没有控件";
        }
        EditBox target = null;
        int matched = -1;
        int index = Json.optInt(par, "index", -1);
        double x = Json.optDouble(par, "x", Double.NaN);
        double y = Json.optDouble(par, "y", Double.NaN);
        if (index >= 0 && index < all.size() && all.get(index) instanceof EditBox eb) {
            target = eb;
            matched = index;
        }
        if (target == null && !Double.isNaN(x) && !Double.isNaN(y)) {
            for (int i = 0; i < all.size(); i++) {
                AbstractWidget w = all.get(i);
                if (w instanceof EditBox eb
                        && x >= eb.getX() && x <= eb.getX() + eb.getWidth()
                        && y >= eb.getY() && y <= eb.getY() + eb.getHeight()) {
                    target = eb;
                    matched = i;
                    break;
                }
            }
        }
        if (target == null && screen.getFocused() instanceof EditBox eb) {
            target = eb;
            matched = all.indexOf(eb);
        }
        if (target == null) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i) instanceof EditBox eb) {
                    target = eb;
                    matched = i;
                    break;
                }
            }
        }
        if (target == null) {
            return "screen_type_err: 当前界面没有输入框（EditBox）";
        }
        try {
            screen.setFocused(target);
            target.setFocused(true);
            target.setValue(text);   // 内部走 responder，搜索/过滤立即生效
        } catch (Exception e) {
            return "screen_type_err: 写入失败 " + e;
        }
        return Json.obj(
                Json.kv("action", Json.str("screen_type")),
                Json.kv("widget_index", Json.num(matched)),
                Json.kv("box", Json.str(target.getClass().getSimpleName())),
                Json.kv("value", Json.str(target.getValue())),
                Json.kv("note", Json.str("已写入并触发 responder；搜索类界面等 1 tick 后 get_screen 看过滤结果")));
    }

    /** 动作栏（overlay）文本：很多模组提示不走聊天栏而走这里。 */
    private static String overlay(JsonObject par) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) {
            return "overlay_err: gui 未就绪";
        }
        String text = mc.gui.overlayMessageString == null ? null : mc.gui.overlayMessageString.getString();
        return Json.obj(Json.kv("action", Json.str("overlay")),
                Json.kv("text", text == null ? "null" : Json.str(text)),
                Json.kv("time", Json.num(mc.gui.overlayMessageTime)));
    }

    // ---------------------------------------------------------------- 容器操作（v0.2.1.2）

    /** 最近一次 click_slot / cont_button 的"同步后"快照（服务端回包到达后由主线程异步写入）。 */
    private static volatile String postClickInfo = null;
    private static volatile long postClickAt = 0;

    /**
     * 排两段式容器状态捕获（350ms + 900ms）。
     *
     * <p>★为什么必须异步：容器点击的真实效果要等服务端回包
     * （{@code ClientboundContainerSetSlotPacket} / {@code ContainerSetContentPacket}），
     * 而**回包是在客户端主线程处理的**——动作本身就运行在主线程上，
     * 在这里 sleep 等回包会把自己锁死（旧 JsMacros"主线程写操作后轮询完成标志"经验的 Java 版：
     * 只能异步排任务，绝不在主线程阻塞）。
     *
     * <p>两段式的原因：本地/近端服务器一两个 tick 就同步完（350ms 那帧），
     * 但 bot 连的是**远端服务器**（实测 RTT 可达数百 ms），900ms 二帧兜底；
     * 后写的覆盖先写的，{@code get_container} 的 post_click 收敛到最终态。
     */
    private static void schedulePostClick() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aiplayer-post-click");
                t.setDaemon(true);
                return t;
            });
        }
        Minecraft mc = Minecraft.getInstance();
        for (long delayMs : new long[]{350, 900}) {
            scheduler.schedule(() -> mc.execute(() -> {
                postClickInfo = postClickSnapshot();
                postClickAt = System.currentTimeMillis();
            }), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 点击后的容器快照：光标物品 / 悬停槽 / 非空槽数（判断点击是否生效的最小集合）。 */
    private static String postClickSnapshot() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AbstractContainerScreen<?> cs)) {
            return Json.obj(Json.kv("screen", "null"), Json.kv("note", Json.str("界面已关闭")));
        }
        AbstractContainerMenu menu = cs.getMenu();
        int nonEmpty = 0;
        for (net.minecraft.world.inventory.Slot s : menu.slots) {
            if (s.hasItem()) {
                nonEmpty++;
            }
        }
        ItemStack cur = menu.getCarried();
        return Json.obj(
                Json.kv("screen", Json.str(cs.getClass().getSimpleName())),
                Json.kv("cursor", Json.obj(
                        Json.kv("id", Json.str(cur.isEmpty() ? "air" : itemId(cur))),
                        Json.kv("count", Json.num(cur.getCount())))),
                Json.kv("hovered", cs.hoveredSlot == null ? "null" : Json.num(cs.hoveredSlot.index)),
                Json.kv("non_empty_slots", Json.num(nonEmpty)));
    }

    /**
     * 容器结构化视图（get_container / container / cont_info）。
     *
     * <p>比 {@code get_screen} 更进一步：
     * <ul>
     *   <li><b>槽位分区</b>：用 {@code Slot.container}（公开字段，无需 AT）判断每槽属于"容器区"还是"背包区"，
     *       并压出连续区间 {@code ranges} —— 快速移动/合成取放按区操作不会错</li>
     *   <li><b>每槽语义</b>：{@code cls}（槽类名：ResultSlot=成品槽/FurnaceResultSlot/ArmorSlot=装备栏…）、
     *       {@code pick}（mayPickup：能否取出）、{@code place}（mayPlace 探测：能否放入）——
     *       找合成成品槽、燃料槽、装备栏全靠它</li>
     *   <li>{@code find}：按物品 id 子串查槽，直接给出可点的 cx/cy</li>
     *   <li>{@code post_click}：上一次点击 300ms 后的同步后状态（异步捕获，见 {@link #schedulePostClick()}）</li>
     * </ul>
     */
    private static String containerInfo(JsonObject par) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = requirePlayer();
        if (!(mc.screen instanceof AbstractContainerScreen<?> cs)) {
            return "get_container_err: 当前没有容器界面。开背包 press(key.inventory)；"
                    + "开箱子/熔炉：靠近后 look 对着它再 keys [key.use] 600ms";
        }
        AbstractContainerMenu menu = cs.getMenu();
        Inventory pinv = p.getInventory();
        int left = cs.getGuiLeft();
        int top = cs.getGuiTop();

        List<String> parts = new ArrayList<>();
        parts.add(Json.kv("action", Json.str("get_container")));
        parts.add(Json.kv("class", Json.str(cs.getClass().getName())));
        parts.add(Json.kv("simple", Json.str(cs.getClass().getSimpleName())));
        parts.add(Json.kv("title", Json.str(textOf(cs.getTitle()))));
        parts.add(Json.kv("menu", Json.str(menu.getClass().getSimpleName())));
        parts.add(Json.kv("container_id", Json.num(menu.containerId)));
        parts.add(Json.kv("slot_count", Json.num(menu.slots.size())));
        parts.add(Json.kv("container", Json.obj(
                Json.kv("left", Json.num(left)), Json.kv("top", Json.num(top)),
                Json.kv("image_w", Json.num(cs.getXSize())), Json.kv("image_h", Json.num(cs.getYSize())))));

        // 分区：Slot.container 是不是玩家背包 → 容器区 / 背包区（压成连续区间）
        List<String> runs = new ArrayList<>();
        String prevSec = null;
        int runStart = 0;

        String find = Json.optString(par, "find", null);
        String findLower = (find == null || find.isBlank()) ? null : find.toLowerCase(Locale.ROOT);
        List<String> slots = new ArrayList<>();
        List<String> matches = new ArrayList<>();
        ItemStack carried = menu.getCarried();

        for (int i = 0; i < menu.slots.size(); i++) {
            net.minecraft.world.inventory.Slot s = menu.slots.get(i);
            String sec = s.container == pinv ? "inventory" : "container";
            ItemStack st = s.getItem();
            String id = st.isEmpty() ? "air" : itemId(st);
            int cx = left + s.x + 8;
            int cy = top + s.y + 8;
            // mayPlace 探测：有物品用自身，空槽用光标（EMPTY 也能识别 ResultSlot 这类永不放行的槽）
            boolean place;
            try {
                place = s.mayPlace(!st.isEmpty() ? st : carried);
            } catch (Throwable t) {
                place = true; // 模组槽异常不拖垮整个视图
            }
            if (findLower != null) {
                if (id.toLowerCase(Locale.ROOT).contains(findLower)) {
                    matches.add(Json.obj(
                            Json.kv("index", Json.num(i)),
                            Json.kv("sec", Json.str(sec)),
                            Json.kv("cx", Json.num(cx)), Json.kv("cy", Json.num(cy)),
                            Json.kv("id", Json.str(id)),
                            Json.kv("count", Json.num(st.getCount()))));
                }
            } else if (slots.size() < 200) {
                boolean pick;
                try {
                    pick = s.mayPickup(p);
                } catch (Throwable t) {
                    pick = true;
                }
                slots.add(Json.obj(
                        Json.kv("index", Json.num(i)),
                        Json.kv("sec", Json.str(sec)),
                        Json.kv("cx", Json.num(cx)), Json.kv("cy", Json.num(cy)),
                        Json.kv("id", Json.str(id)),
                        Json.kv("count", Json.num(st.getCount())),
                        Json.kv("cls", Json.str(s.getClass().getSimpleName())),
                        Json.kv("pick", Json.bool(pick)),
                        Json.kv("place", Json.bool(place)),
                        Json.kv("active", Json.bool(s.isActive()))));
            }
            if (prevSec == null) {
                prevSec = sec;
                runStart = i;
            } else if (!prevSec.equals(sec)) {
                runs.add(Json.obj(Json.kv("sec", Json.str(prevSec)),
                        Json.kv("from", Json.num(runStart)), Json.kv("to", Json.num(i - 1))));
                prevSec = sec;
                runStart = i;
            }
        }
        if (prevSec != null) {
            runs.add(Json.obj(Json.kv("sec", Json.str(prevSec)),
                    Json.kv("from", Json.num(runStart)), Json.kv("to", Json.num(menu.slots.size() - 1))));
        }
        parts.add(Json.kv("ranges", Json.arr(runs)));
        if (findLower != null) {
            parts.add(Json.kv("find", Json.str(find)));
            parts.add(Json.kv("matched", Json.num(matches.size())));
            parts.add(Json.kv("matches", Json.arr(matches)));
            parts.add(Json.kv("hint", Json.str("点匹配槽用 click_slot{slot=index}；shift=true 为快速移动")));
        } else {
            parts.add(Json.kv("slots", Json.arr(slots)));
        }
        parts.add(Json.kv("cursor", Json.obj(
                Json.kv("id", Json.str(carried.isEmpty() ? "air" : itemId(carried))),
                Json.kv("count", Json.num(carried.getCount())))));
        parts.add(Json.kv("hovered", cs.hoveredSlot == null ? "null" : Json.num(cs.hoveredSlot.index)));
        if (postClickInfo != null && System.currentTimeMillis() - postClickAt < 15000) {
            parts.add(Json.kv("post_click_age_ms", Json.num(System.currentTimeMillis() - postClickAt)));
            parts.add(Json.kv("post_click", Json.maybeJson(postClickInfo)));
        }
        return Json.obj(parts.toArray(new String[0]));
    }

    /**
     * 按索引点槽位（click_slot / slot_click / cont_click）—— 容器交互的主力动作。
     *
     * <p>默认走 {@code MultiPlayerGameMode.handleInventoryMouseClick}（**发包路径**，与真人鼠标点击
     * 同一条网络路径）：不依赖坐标/缩放换算，槽位滚出视野也能点，shift 快速移动原生支持。
     * 创造模式菜单索引语义不同 → 自动回退坐标点击（{@code screen_click}）。
     *
 * <p>语义：{@code shift=true} → QUICK_MOVE（背包↔容器快速移动）；
 * {@code slot=-1} → 点到界面外（丢光标：button 0=整堆 1=一个）；
 * {@code drop=true + slot=N} → 丢该槽位里的物品（Q 键语义，需光标为空，button 0=一个 1=整堆）；
 * {@code button=2 + hotbar=0..8} → SWAP（与热键栏交换）。
     *
     * <p>★效果要等服务端回包，主线程不能阻塞等 → 立即返回"当前态"，
     * 300ms 后的同步态由 {@link #schedulePostClick()} 异步写进 {@code get_container} 的 post_click。
     */
    private static String clickSlot(JsonObject par) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = requirePlayer();
        if (!(mc.screen instanceof AbstractContainerScreen<?> cs)) {
            return "click_slot_err: 当前没有容器界面（先用 get_screen 确认；开背包 press(key.inventory)）";
        }
        AbstractContainerMenu menu = cs.getMenu();
        if (!par.has("slot")) {
            return "click_slot_err: 缺少 slot（槽位索引见 get_container；-1 = 界面外 = 丢弃光标）";
        }
        int slot = Json.optInt(par, "slot", Integer.MIN_VALUE);
        if (slot >= menu.slots.size()) {
            return "click_slot_err: slot 越界（合法 0-" + (menu.slots.size() - 1)
                    + "，共 " + menu.slots.size() + " 槽；用 get_container 查索引）";
        }
        int button = Math.max(0, Math.min(Json.optInt(par, "button", 0), 2));   // 0左 1右 2中
        boolean shift = Json.optBool(par, "shift", false);
        int hotbar = Json.optInt(par, "hotbar", -1);
        String mode = Json.optString(par, "mode", "auto").toLowerCase(Locale.ROOT);

        boolean menuPath;
        if ("mouse".equals(mode)) {
            menuPath = false;
        } else if ("menu".equals(mode)) {
            if (p.containerMenu != menu) {
                return "click_slot_err: menu 路径要求 player.containerMenu 与当前界面一致"
                        + "（创造模式/异常状态改用 mode=mouse）";
            }
            menuPath = true;
        } else {  // auto
            menuPath = p.containerMenu == menu && !p.isCreative();
        }

        String clickType;
        int effSlot = slot;
        int effButton = button;
        ItemStack carried0 = menu.getCarried();
        if (shift) {
            clickType = "QUICK_MOVE";
            effButton = 0;
        } else if (Json.optBool(par, "drop", false) && slot >= 0) {
            // Q 键语义：丢指定槽位里的物品（button 0=一个 1=整堆），不用先拿起。
            // ★vanilla 要求光标为空（doClick 字节码 1462：carried 非空直接跳过），否则静默无效——
            // 这里显式报错，比让 agent 猜"为什么没掉"好。
            if (!carried0.isEmpty()) {
                return "click_slot_err: drop=true 需要光标为空（先把光标物品放回槽位或用 slot=-1 丢掉）";
            }
            clickType = "THROW";
        } else if (button == 2 && hotbar >= 0 && hotbar <= 8) {
            clickType = "SWAP";
            effButton = hotbar;
        } else if (button == 2 && p.isCreative()) {
            clickType = "CLONE";
        } else if (slot < 0) {
            // 界面外点击：vanilla = PICKUP + slotId=-999（丢光标物品）。
            // ★按钮语义反字节码核实（doClick 594-655）：button 0(PRIMARY)=丢【整堆】，button 1(SECONDARY)=丢【一个】
            // （和"左键整堆/右键一个"的鼠标直觉一致；v0.2.1.2 第一版注释写反过）。
            // ★THROW 不是这个用途——THROW 是"丢槽位里的物品"（Q 键语义，见上面 drop 参数）。
            if (carried0.isEmpty()) {
                return "click_slot_err: 光标为空，没有可丢弃的物品（slot=-1 是丢光标物品）";
            }
            clickType = "PICKUP";
            effSlot = -999;
        } else {
            clickType = "PICKUP";
        }

        if (menuPath) {
            mc.gameMode.handleInventoryMouseClick(menu.containerId, effSlot, effButton,
                    ClickType.valueOf(clickType), p);
        } else {
            if (slot < 0) {
                return "click_slot_err: 鼠标路径不支持界面外点击（丢弃改用 mode=menu，或无界面时按 Q）";
            }
            net.minecraft.world.inventory.Slot s = menu.slots.get(slot);
            JsonObject inner = new JsonObject();
            inner.addProperty("x", left2(cs, s));
            inner.addProperty("y", top2(cs, s));
            inner.addProperty("button", Math.min(button, 1));   // 中键回退成左键
            String r = screenClick(inner);
            clickType = clickType + "（鼠标路径，shift 不生效）";
            // 鼠标路径也把悬停槽同步过去，保持 get_screen/get_container 的 hovered 一致
            cs.hoveredSlot = s;
            schedulePostClick();
            return Json.obj(Json.kv("action", Json.str("click_slot")),
                    Json.kv("slot", Json.num(slot)),
                    Json.kv("path", Json.str("mouse")),
                    Json.kv("click", Json.maybeJson(r)),
                    Json.kv("cursor_now", cursorJson(menu)),
                    Json.kv("note", Json.str("300ms 后 get_container 的 post_click 为同步后状态")));
        }

        if (slot >= 0 && slot < menu.slots.size()) {
            cs.hoveredSlot = menu.slots.get(slot);   // AT 已公开；保持后续 get_* 的 hovered 一致
        }
        schedulePostClick();
        return Json.obj(Json.kv("action", Json.str("click_slot")),
                Json.kv("slot", Json.num(slot)),
                Json.kv("path", Json.str("menu")),
                Json.kv("click_type", Json.str(clickType)),
                Json.kv("container_id", Json.num(menu.containerId)),
                Json.kv("cursor_now", cursorJson(menu)),
                Json.kv("note", Json.str("效果等服务端回包；★连发点击前先用 get_container 的 post_click"
                        + "（或重新读 slots）确认光标状态，否则下一发会带旧光标发包；"
                        + "900ms 后 post_click 收敛到最终态")));
    }

    private static double left2(AbstractContainerScreen<?> cs, net.minecraft.world.inventory.Slot s) {
        return cs.getGuiLeft() + s.x + 8;
    }

    private static double top2(AbstractContainerScreen<?> cs, net.minecraft.world.inventory.Slot s) {
        return cs.getGuiTop() + s.y + 8;
    }

    private static String cursorJson(AbstractContainerMenu menu) {
        ItemStack cur = menu.getCarried();
        return Json.obj(Json.kv("id", Json.str(cur.isEmpty() ? "air" : itemId(cur))),
                Json.kv("count", Json.num(cur.getCount())));
    }

    /**
     * 菜单按钮（cont_button / menu_button）：附魔台 / 切石机 / 讲台 / 按钮行等
     * {@code handleInventoryButtonClick} 通道。id 见容器协议（get_screen 的 widgets 或页面按钮）。
     */
    private static String contButton(JsonObject par) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = requirePlayer();
        if (!(mc.screen instanceof AbstractContainerScreen<?> cs)) {
            return "cont_button_err: 当前没有容器界面";
        }
        AbstractContainerMenu menu = cs.getMenu();
        if (!par.has("id")) {
            return "cont_button_err: 缺少 id（按钮 id，整数）";
        }
        int id = Json.optInt(par, "id", Integer.MIN_VALUE);
        if (p.containerMenu != menu) {
            return "cont_button_err: 菜单不匹配（player.containerMenu 与当前界面不一致；创造模式改用鼠标）";
        }
        mc.gameMode.handleInventoryButtonClick(menu.containerId, id);
        schedulePostClick();
        return Json.obj(Json.kv("action", Json.str("cont_button")),
                Json.kv("id", Json.num(id)),
                Json.kv("container_id", Json.num(menu.containerId)),
                Json.kv("note", Json.str("300ms 后 get_container 的 post_click 为同步后状态")));
    }

    // ---------------------------------------------------------------- 截图（F2）

    /** 最近一次截图结果（PNG 异步落盘后由调度器补写）：绝对路径 / 提示消息。 */
    private static volatile String lastShotFile = null;
    private static volatile String lastShotMessage = null;
    private static volatile long lastShotAt = 0L;

    /**
     * 截图：{@code screenshot [mode=f2|grab] [query=true]}。
     *
     * <p>★为什么不能复用 {@code press}：1.21.1 的 F2 截图在 {@code KeyboardHandler#keyPress}
     * 的**原始按键事件**分支里触发，而 {@code press} 只做 {@code KeyMapping.click()}（累加 clickCount）、
     * {@code keys} 只做 {@code setDown()} —— 两者都到不了那个分支（实测三条路径均无输出文件）。
     *
     * <p>两条路：
     * <ul>
     *   <li>{@code mode=f2}（默认）：反射调用 {@code mc.keyboardHandler.keyPress(win, GLFW_KEY_F2, 0, 1, 0)}
     *       再补一个 release —— 等价于真人按 F2，顺便验证"按键注入链"是否够真实</li>
     *   <li>{@code mode=grab}：直接 {@code Screenshot.grab(...)}，绕开按键层（f2 失败时也会自动兜底）</li>
     * </ul>
     *
     * <p>★PNG 落盘是异步的（MC 丢给 ioPool 写盘），主线程不能阻塞等 → 立即返回，
     * 600/1600ms 后由调度器回查 {@code <gamedir>/screenshots/} 最新 png 写入 {@code lastShotFile}；
     * 用 {@code {"type":"screenshot","query":true}} 取结果。
     *
     * <p>★设计边界（用户明确要求 2026-10-16）：这是**纯按需可调用能力** ——
     * <b>不进 {@code state()}、不随轮次/定时自动采集、不挂任何自动流程；是否调用由 agent 自行决定。</b>
     * 请勿在别处"顺手"把它接进状态快照或回合逻辑。
     */
    private static String screenshot(JsonObject par) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return "screenshot_err: 未进入世界";
        }
        Path dir = FMLPaths.GAMEDIR.get().resolve("screenshots");
        if (Json.optBool(par, "query", false)) {
            return Json.obj(Json.kv("action", Json.str("screenshot")),
                    Json.kv("query", Json.bool(true)),
                    Json.kv("file", Json.str(lastShotFile == null ? "(尚无)" : lastShotFile)),
                    Json.kv("message", Json.str(lastShotMessage == null ? "(尚无)" : lastShotMessage)),
                    Json.kv("age_ms", Json.num(lastShotAt == 0L ? -1L : System.currentTimeMillis() - lastShotAt)),
                    Json.kv("dir", Json.str(dir.toString())));
        }
        String mode = Json.optString(par, "mode", "f2").toLowerCase(Locale.ROOT);
        String before = newestShot(dir);
        String used;
        String err = null;
        if (!"grab".equals(mode)) {
            err = injectF2(mc);
            used = err == null ? "keyboardHandler.keyPress(GLFW_KEY_F2)" : "f2_err";
        } else {
            used = "Screenshot.grab";
        }
        if (err != null || "grab".equals(mode)) {
            try {
                Screenshot.grab(FMLPaths.GAMEDIR.get().toFile(), mc.getMainRenderTarget(),
                        c -> lastShotMessage = c.getString());
                if (err != null) {
                    used = used + " -> 回退 Screenshot.grab";
                    err = null;
                }
            } catch (Throwable t) {
                err = (err == null ? "" : err + " | ") + "Screenshot.grab 失败: " + t;
            }
        }
        scheduleShotCheck(dir, before);
        return Json.obj(Json.kv("action", Json.str("screenshot")),
                Json.kv("mode", Json.str(used)),
                Json.kv("dir", Json.str(dir.toString())),
                Json.kv("before", Json.str(before == null ? "(none)" : before)),
                Json.kv("error", Json.str(err == null ? "(none)" : err)),
                Json.kv("note", Json.str("PNG 异步落盘；稍后 {\"type\":\"screenshot\",\"query\":true} 取文件名")));
    }

    /** 反射注入一次真实 F2 按下 + 抬起；返回 null 表示成功。 */
    private static String injectF2(Minecraft mc) {
        try {
            Object kh = mc.keyboardHandler;
            if (kh == null) {
                return "keyboardHandler 为空";
            }
            long win = mc.getWindow().getWindow();
            java.lang.reflect.Method m = kh.getClass().getMethod(
                    "keyPress", long.class, int.class, int.class, int.class, int.class);
            m.setAccessible(true);
            m.invoke(kh, win, GLFW.GLFW_KEY_F2, 0, 1, 0);   // GLFW_PRESS
            m.invoke(kh, win, GLFW.GLFW_KEY_F2, 0, 0, 0);   // GLFW_RELEASE
            return null;
        } catch (Throwable t) {
            return "F2 按键注入失败: " + t;
        }
    }

    /** screenshots 目录里最新的 png 文件名；无目录/无文件 → null。 */
    private static String newestShot(Path dir) {
        try {
            if (!Files.isDirectory(dir)) {
                return null;
            }
            try (Stream<Path> s = Files.list(dir)) {
                return s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                        .map(p -> p.getFileName().toString())
                        .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 600/1600ms 后回查截图目录，把新出现的 png 记进 lastShotFile。 */
    private static void scheduleShotCheck(Path dir, String before) {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aiplayer-shot-check");
                t.setDaemon(true);
                return t;
            });
        }
        for (long delayMs : new long[]{600L, 1600L}) {
            scheduler.schedule(() -> {
                String now = newestShot(dir);
                if (now != null && !now.equals(before)) {
                    lastShotFile = dir.resolve(now).toString();
                    lastShotAt = System.currentTimeMillis();
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    // ---------------------------------------------------------------- 自建区：文件 / 工具 / 文档

    /**
     * 路径解析（带穿越防护）：所有文件操作都只能在 {@code <gamedir>/aiplayer/} 之内。
     *
     * @throws IllegalStateException 越界时抛出，动作层会转成 {@code *_err}
     */
    private static Path safePath(String rel) {
        Path p = AiPlayerPaths.resolveInside(rel);
        if (p == null) {
            logDenied("path_escape", "越界路径: " + rel);
            throw new IllegalStateException("路径越界（必须在工作区 " + AiPlayerPaths.dataDir() + " 之内）: " + rel);
        }
        return p;
    }

    private static String fileRead(JsonObject par) {
        String rel = Json.optString(par, "path", null);
        if (rel == null) {
            return "file_err: 缺少 path";
        }
        int max = Math.max(1, Math.min(Json.optInt(par, "max", 8000), 200000));
        Path p = safePath(rel);
        try {
            if (!Files.exists(p)) {
                return "file_err: 文件不存在: " + rel;
            }
            if (Files.isDirectory(p)) {
                return "file_err: 这是目录（用 file_list 列目录）: " + rel;
            }
            String content = Files.readString(p, StandardCharsets.UTF_8);
            boolean truncated = content.length() > max;
            return Json.obj(
                    Json.kv("action", Json.str("file_read")),
                    Json.kv("path", Json.str(rel)),
                    Json.kv("length", Json.num(content.length())),
                    Json.kv("truncated", Json.bool(truncated)),
                    Json.kv("content", Json.str(truncated ? content.substring(0, max) : content)));
        } catch (IOException e) {
            return "file_err: " + e.getMessage();
        }
    }

    private static String fileWrite(JsonObject par) {
        String rel = Json.optString(par, "path", null);
        String content = Json.optString(par, "content", null);
        if (rel == null) {
            return "file_err: 缺少 path";
        }
        if (content == null) {
            return "file_err: 缺少 content";
        }
        Path p = safePath(rel);
        try {
            if (Files.exists(p) && Files.isDirectory(p)) {
                return "file_err: 目标是目录: " + rel;
            }
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            boolean ok = AiPlayerPaths.writeAtomic(p, content);
            return Json.obj(Json.kv("action", Json.str("file_write")),
                    Json.kv("path", Json.str(rel)),
                    Json.kv("ok", Json.bool(ok)),
                    Json.kv("bytes", Json.num(content.getBytes(StandardCharsets.UTF_8).length)));
        } catch (IOException e) {
            return "file_err: " + e.getMessage();
        }
    }

    private static String fileAppend(JsonObject par) {
        String rel = Json.optString(par, "path", null);
        String content = Json.optString(par, "content", null);
        if (rel == null || content == null) {
            return "file_err: 需要 path 与 content";
        }
        Path p = safePath(rel);
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return Json.obj(Json.kv("action", Json.str("file_append")),
                    Json.kv("path", Json.str(rel)),
                    Json.kv("ok", Json.bool(true)));
        } catch (IOException e) {
            return "file_err: " + e.getMessage();
        }
    }

    /** 列目录（默认一层；recursive=true 时递归，上限 500 项）。 */
    private static String fileList(JsonObject par) {
        String rel = Json.optString(par, "path", "");
        boolean recursive = Json.optBool(par, "recursive", false);
        Path dir = safePath(rel);
        if (!Files.exists(dir)) {
            return "file_err: 目录不存在: " + rel;
        }
        if (!Files.isDirectory(dir)) {
            return "file_err: 不是目录: " + rel;
        }
        List<String> entries = new ArrayList<>();
        try (Stream<Path> stream = recursive ? Files.walk(dir) : Files.list(dir)) {
            for (Path p : stream.toList()) {
                String rel2 = AiPlayerPaths.dataDir().relativize(p).toString().replace('\\', '/');
                String suffix = Files.isDirectory(p) ? "/" : " (" + sizeOf(p) + " 字节)";
                entries.add(Json.str(rel2 + suffix));
                if (entries.size() >= 500) {
                    break;
                }
            }
        } catch (IOException e) {
            return "file_err: " + e.getMessage();
        }
        entries.sort(String::compareTo);
        return Json.obj(Json.kv("action", Json.str("file_list")),
                Json.kv("path", Json.str(rel)),
                Json.kv("count", Json.num(entries.size())),
                Json.kv("entries", Json.arr(entries)));
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * 自建工具注册表：<gamedir>/aiplayer/tools/index.json。
     * agent 可以自己维护这个清单（用 file_write），模组负责读出来给它看。
     */
    private static String toolsList(JsonObject par) {
        Path idx = AiPlayerPaths.of("tools", "index.json");
        String content = null;
        try {
            if (Files.exists(idx)) {
                content = Files.readString(idx, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return "tools_err: " + e.getMessage();
        }
        List<String> dirs = new ArrayList<>();
        Path toolsDir = AiPlayerPaths.of("tools");
        try (Stream<Path> s = Files.list(toolsDir)) {
            for (Path p : s.toList()) {
                if (Files.isDirectory(p)) {
                    dirs.add(Json.str(p.getFileName().toString()));
                }
            }
        } catch (IOException ignored) {
            // 目录还没建好就算了
        }
        dirs.sort(String::compareTo);
        // v0.2.1.6：把执行开关 + 每个条目的可执行性一并回显，agent 好自查
        List<String> entries = new ArrayList<>();
        for (JsonObject e : toolEntries()) {
            List<String> issues = toolIssues(e);
            List<JsonObject> steps = loadSteps(e, new ArrayList<>());
            List<String> pairs = new ArrayList<>(List.of(
                    Json.kv("name", Json.str(Json.optString(e, "name", "?"))),
                    Json.kv("entry", Json.str(Json.optString(e, "entry", "main.json"))),
                    Json.kv("desc", Json.str(Json.optString(e, "desc", ""))),
                    Json.kv("enabled", Json.bool(Json.optBool(e, "enabled", true))),
                    Json.kv("risk", Json.str(Json.optString(e, "risk", "未标注"))),
                    Json.kv("steps", Json.num(steps == null ? 0 : steps.size())),
                    Json.kv("ok", Json.bool(issues.isEmpty()))));
            if (!issues.isEmpty()) {
                pairs.add(Json.kv("issues", Json.arr(issues.stream().map(Json::str).toList())));
            }
            entries.add(Json.obj(pairs.toArray(new String[0])));
        }
        return Json.obj(Json.kv("action", Json.str("tools")),
                Json.kv("registry_path", Json.str("aiplayer/tools/index.json")),
                Json.kv("execution_enabled", Json.bool(AiPlayerConfig.selfToolEnabled())),
                Json.kv("index_raw", content == null ? "null" : Json.str(content)),
                Json.kv("tool_dirs", Json.arr(dirs)),
                Json.kv("entries", Json.arr(entries.stream().filter(s -> !s.isEmpty()).toList())),
                Json.kv("hint", Json.str("条目字段：name（调用名）| entry（相对 tools/<name>/ 的入口文件，默认 main.json）| "
                        + "args（必填参数名数组）| desc | enabled（缺省 true）| risk（只读|写世界|破坏）。"
                        + "入口文件形如 steps 数组，元素如 look / say；可带 repeat（≤20）；步骤只能引用当前可用动作"
                        + "（权限表自动级联），禁止嵌套 tool_run。用 file_write 写 index.json 与入口文件。")));
    }

    // ---------------------------------------------------------------- 自建工具执行（v0.2.1.6）

    /** 自建工具单次执行的最大步数（防超长宏拖死客户端）。 */
    private static final int TOOL_MAX_STEPS = 64;
    /** 单步最大重复次数。 */
    private static final int TOOL_MAX_REPEAT = 20;

    /** 读一个 JSON 文件为对象；不存在/非法返回 null。 */
    private static JsonObject readJson(Path p) {
        try {
            if (p == null || Files.notExists(p)) {
                return null;
            }
            JsonElement el = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** index.json 里的全部条目（按声明顺序）。 */
    private static List<JsonObject> toolEntries() {
        List<JsonObject> out = new ArrayList<>();
        JsonObject idx = readJson(AiPlayerPaths.of("tools", "index.json"));
        if (idx == null || !idx.has("tools") || !idx.get("tools").isJsonArray()) {
            return out;
        }
        for (JsonElement el : idx.getAsJsonArray("tools")) {
            if (el.isJsonObject()) {
                out.add(el.getAsJsonObject());
            }
        }
        return out;
    }

    /** 按 name 找条目；找不到返回 null。 */
    private static JsonObject toolEntry(String name) {
        for (JsonObject o : toolEntries()) {
            if (name.equals(Json.optString(o, "name", null))) {
                return o;
            }
        }
        return null;
    }

    /** 载入工具步骤。返回 null = 载入失败（原因追加到 problems）。 */
    private static List<JsonObject> loadSteps(JsonObject entry, List<String> problems) {
        String name = Json.optString(entry, "name", "");
        String file = Json.optString(entry, "entry", "main.json");
        if (file.contains("/") || file.contains("\\") || file.contains("..")) {
            problems.add("entry 只能是 tools/<name>/ 下的文件名（不含路径分隔符）: " + file);
            return null;
        }
        // 路径硬化：入口必须落在 <datadir>/aiplayer/tools/<name>/ 之内
        Path p = AiPlayerPaths.resolveInside("tools/" + name + "/" + file);
        if (p == null) {
            problems.add("入口路径越界或非法: tools/" + name + "/" + file);
            return null;
        }
        JsonObject doc = readJson(p);
        if (doc == null) {
            problems.add("入口文件不存在或不是合法 JSON 对象: tools/" + name + "/" + file);
            return null;
        }
        if (!doc.has("steps") || !doc.get("steps").isJsonArray()) {
            problems.add("入口缺少 steps 数组");
            return null;
        }
        List<JsonObject> steps = new ArrayList<>();
        for (JsonElement el : doc.getAsJsonArray("steps")) {
            if (el.isJsonObject()) {
                steps.add(el.getAsJsonObject());
            }
        }
        if (steps.isEmpty()) {
            problems.add("steps 为空");
        }
        if (steps.size() > TOOL_MAX_STEPS) {
            problems.add("步骤数 " + steps.size() + " 超过上限 " + TOOL_MAX_STEPS);
        }
        return steps;
    }

    /** 校验步骤：动作必须在当前注册表内（→ 权限表自动级联）、禁嵌套 tool_run、repeat 合法。 */
    private static void validateSteps(List<JsonObject> steps, List<String> problems) {
        for (int i = 0; i < steps.size(); i++) {
            JsonObject st = steps.get(i);
            String t = Json.optString(st, "type", null);
            if (t == null || t.isBlank()) {
                problems.add("第" + (i + 1) + "步缺少 type");
                continue;
            }
            if ("tool_run".equals(t) || "run_tool".equals(t)) {
                problems.add("第" + (i + 1) + "步禁止嵌套 tool_run（防递归死循环）");
                continue;
            }
            if (!known(t)) {
                problems.add("第" + (i + 1) + "步动作不可用: " + t + "（不在当前注册表内——被权限表禁用或名字写错）");
            }
            int rep = Json.optInt(st, "repeat", 1);
            if (rep < 1 || rep > TOOL_MAX_REPEAT) {
                problems.add("第" + (i + 1) + "步 repeat 需在 1-" + TOOL_MAX_REPEAT + "（现为 " + rep + "）");
            }
        }
    }

    /** 条目整体可执行性检查（供 tools 动作回显）。 */
    private static List<String> toolIssues(JsonObject entry) {
        List<String> problems = new ArrayList<>();
        if (!Json.optBool(entry, "enabled", true)) {
            problems.add("enabled=false（已关闭）");
        }
        List<JsonObject> steps = loadSteps(entry, problems);
        if (steps != null) {
            validateSteps(steps, problems);
        }
        return problems;
    }

    /** 把步骤里的 ${arg} 占位替换为调用参数；只处理字符串值，去掉工具级指令 repeat。 */
    private static JsonObject interpolate(JsonObject step, Map<String, String> args) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : step.entrySet()) {
            String k = e.getKey();
            if ("repeat".equals(k)) {
                continue;
            }
            JsonElement v = e.getValue();
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                out.addProperty(k, subst(v.getAsString(), args));
            } else {
                out.add(k, v);
            }
        }
        return out;
    }

    private static String subst(String s, Map<String, String> args) {
        if (s == null || s.indexOf("${") < 0) {
            return s;
        }
        for (Map.Entry<String, String> e : args.entrySet()) {
            s = s.replace("${" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    /** 结果截断，避免宏回包过大（get_screen 之类单步就能几十 KB）。 */
    private static String abbrev(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(截断)";
    }

    /**
     * 执行自建工具：tools/index.json 条目 → 宏步骤序列，逐步走 {@link #dispatch} 执行。
     *
     * <p>★走 dispatch 而不是直接调用实现，是为了让权限表与审计天然一致：
     * 被禁的动作在步骤里同样返回 denied，宏整体失败。
     */
    private static String toolRun(JsonObject par) {
        String name = Json.optString(par, "name", null);
        if (name == null || name.isBlank()) {
            return "tool_err: 缺少 name（用法 tool_run name=\"<工具名>\" 参数=值…）";
        }
        name = name.trim();
        if (!name.matches("[A-Za-z0-9_.\\-]{1,64}")) {
            return "tool_err: 工具名只允许字母/数字/_/-/.（≤64 字符）";
        }
        JsonObject entry = toolEntry(name);
        if (entry == null) {
            return "tool_err: tools/index.json 里没有名为 " + name + " 的条目（用 {\"type\":\"tools\"} 看清单）";
        }
        if (!Json.optBool(entry, "enabled", true)) {
            return "tool_err: 工具 " + name + " 被 enabled=false 关闭";
        }
        List<String> problems = new ArrayList<>();
        List<JsonObject> steps = loadSteps(entry, problems);
        if (steps != null) {
            validateSteps(steps, problems);
        }
        if (steps == null || !problems.isEmpty()) {
            return Json.obj(Json.kv("action", Json.str("tool_run")), Json.kv("tool", Json.str(name)),
                    Json.kv("ok", Json.bool(false)),
                    Json.kv("problems", Json.arr(problems.stream().map(Json::str).toList())));
        }

        // 调用参数：name/action/type 之外都是参数（值转字符串）
        Map<String, String> args = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : par.entrySet()) {
            String k = e.getKey();
            if ("action".equals(k) || "type".equals(k) || "name".equals(k)) {
                continue;
            }
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) {
                continue;
            }
            args.put(k, v.isJsonPrimitive() ? v.getAsString() : v.toString());
        }
        // 必填参数校验（entry.args 数组）
        List<String> missing = new ArrayList<>();
        if (entry.has("args") && entry.get("args").isJsonArray()) {
            for (JsonElement el : entry.getAsJsonArray("args")) {
                String a = el.isJsonPrimitive() ? el.getAsString() : null;
                if (a != null && !a.isBlank() && !args.containsKey(a)) {
                    missing.add(a);
                }
            }
        }
        if (!missing.isEmpty()) {
            return Json.obj(Json.kv("action", Json.str("tool_run")), Json.kv("tool", Json.str(name)),
                    Json.kv("ok", Json.bool(false)),
                    Json.kv("problems", Json.arr(missing.stream()
                            .map(a -> Json.str("缺少必填参数: " + a)).toList())));
        }

        List<String> trace = new ArrayList<>();
        int seq = 0;
        boolean ok = true;
        String failedAt = null;
        outer:
        for (JsonObject step : steps) {
            String type = Json.optString(step, "type", "");
            int rep = Math.max(1, Math.min(TOOL_MAX_REPEAT, Json.optInt(step, "repeat", 1)));
            JsonObject ppar = interpolate(step, args);
            for (int r = 0; r < rep; r++) {
                seq++;
                String res;
                try {
                    res = dispatch(type, ppar);
                } catch (Throwable t) {
                    res = "error: " + t;
                }
                boolean stepOk = !(res.startsWith("denied") || res.startsWith("unknown") || res.startsWith("error"));
                trace.add(Json.obj(Json.kv("step", Json.num(seq)), Json.kv("type", Json.str(type)),
                        Json.kv("ok", Json.bool(stepOk)), Json.kv("result", Json.str(abbrev(res, 200)))));
                if (!stepOk) {
                    ok = false;
                    failedAt = type;
                    break outer;
                }
            }
        }
        List<String> outPairs = new ArrayList<>(List.of(
                Json.kv("action", Json.str("tool_run")),
                Json.kv("tool", Json.str(name)),
                Json.kv("ok", Json.bool(ok)),
                Json.kv("steps_total", Json.num(steps.size())),
                Json.kv("steps_run", Json.num(seq))));
        if (failedAt != null) {
            outPairs.add(Json.kv("failed_at", Json.str(failedAt)));
        }
        outPairs.add(Json.kv("trace", Json.arr(trace)));
        return Json.obj(outPairs.toArray(new String[0]));
    }

    /** 自建文档区：列文件；传 file= 时读指定文档，默认读 INDEX.md。 */
    private static String docsList(JsonObject par) {
        String file = Json.optString(par, "file", "INDEX.md");
        Path docsDir = AiPlayerPaths.of("docs");
        List<String> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(docsDir)) {
            for (Path p : s.toList()) {
                if (!Files.isDirectory(p)) {
                    files.add(Json.str(p.getFileName().toString() + " (" + sizeOf(p) + " 字节)"));
                }
            }
        } catch (IOException ignored) {
            // 同上
        }
        files.sort(String::compareTo);
        Path target = AiPlayerPaths.resolveInside("docs/" + file);
        String body = null;
        if (target != null && Files.exists(target) && !Files.isDirectory(target)) {
            try {
                body = Files.readString(target, StandardCharsets.UTF_8);
                if (body.length() > 8000) {
                    body = body.substring(0, 8000);
                }
            } catch (IOException ignored) {
                // 读不到就留空
            }
        }
        return Json.obj(Json.kv("action", Json.str("docs")),
                Json.kv("docs_dir", Json.str("aiplayer/docs")),
                Json.kv("files", Json.arr(files)),
                Json.kv("file", Json.str(file)),
                Json.kv("content", body == null ? "null" : Json.str(body)));
    }

    // ---------------------------------------------------------------- 内置 AI：通用 LLM 接口

    private static String llmChat(JsonObject par) {
        if (!AiPlayerConfig.networkEnabled()) {
            return "llm_err: 出站网络访问已关闭（networkEnabled=false）";
        }
        String prompt = Json.optString(par, "prompt", null);
        String system = Json.optString(par, "system", null);
        JsonArray messages = par.has("messages") && par.get("messages").isJsonArray()
                ? par.getAsJsonArray("messages") : null;
        Integer maxTokens = par.has("max_tokens") ? Json.optInt(par, "max_tokens", 0) : null;
        if (maxTokens != null && maxTokens <= 0) {
            maxTokens = null;
        }
        long t0 = System.currentTimeMillis();
        LlmClient.Result r = LlmClient.chat(system, prompt, messages, maxTokens);
        AiPlayer.LOG.info("[AI Player] llm_chat: status={} ms={} 耗时总计{}ms",
                r.status(), r.ms(), System.currentTimeMillis() - t0);
        return LlmClient.asJson(r);
    }

    /** 连通性自检：极小代价验证"通用 API 配置 + 出站网络"可用。 */
    private static String llmPing(JsonObject par) {
        if (!AiPlayerConfig.networkEnabled()) {
            return "llm_err: 出站网络访问已关闭（networkEnabled=false）";
        }
        // 注意：推理模型会把正文放进 reasoning_content 而不是 content，所以两个都要看
        LlmClient.Result r = LlmClient.chat("你只回复一个词：pong", "ping", null, 32);
        String shown = (r.content() != null && !r.content().isBlank()) ? r.content() : r.reasoning();
        return Json.obj(Json.kv("action", Json.str("llm_ping")),
                Json.kv("endpoint", Json.str(LlmClient.endpoint())),
                Json.kv("model", Json.str(AiPlayerConfig.apiModel())),
                Json.kv("key_source", Json.str(LlmClient.resolveKey() == null || LlmClient.resolveKey().isBlank()
                        ? "(空)" : (System.getenv(AiPlayerConfig.apiKeyEnv()) != null ? "环境变量" : "配置文件"))),
                Json.kv("status", Json.num(r.status())),
                Json.kv("ms", Json.num(r.ms())),
                Json.kv("ok", Json.bool(r.error() == null)),
                Json.kv("reply", shown == null ? "null" : Json.str(shown)));
    }

    // ---------------- sidecar：agent 进程启停与状态（v0.2.1.10，受 [sidecar].enabled 管辖） ----------------

    /** agent 状态一览（8770 探活 + 归属 + 关键配置；探活短超时，主线程安全）。 */
    private static String agentStatus(JsonObject par) {
        return Json.obj(Json.kv("action", Json.str("agent_status")),
                Json.kv("status", Json.str(AgentSidecar.statusLine())));
    }

    /** 拉起 agent（ensure_running：已在跑则复用；★异步拉起不阻塞主线程，看门狗负责盯就绪）。 */
    private static String agentStart(JsonObject par) {
        return Json.obj(Json.kv("action", Json.str("agent_start")),
                Json.kv("result", Json.str(AgentSidecar.ensureRunning(false))));
    }

    /** 停止 agent（8770/stop 优雅收轮→超时强杀自己拉起的实例；★异步，立即返回）。 */
    private static String agentStop(JsonObject par) {
        return Json.obj(Json.kv("action", Json.str("agent_stop")),
                Json.kv("result", Json.str(AgentSidecar.stopAsync())));
    }

    /** 自省：列出全部合法动作名（旧桥接踩过"猜动作名全落空"的坑）。 */
    private static String actionsList() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        // v0.2.1.5：分组显示跟着第五轴走——被摘的动作不在 groups 里（与装配期裁剪一致）
        String gmNow = AiPlayerConfig.gameActionModeRaw();
        boolean gmSafe = !"full".equals(gmNow);
        boolean gmObserve = "observe".equals(gmNow);

        groups.put("自省/状态", List.of("actions(help/list_actions)", "state", "ping", "time"));
        groups.put("世界/地形查询", List.of("block_at", "blockstate", "scan_region", "find_block(search_block/nearest_block)"));
        groups.put("通信", List.of("say(chat)", "command(cmd)"));
        groups.put("感知", List.of("get_inventory(inv_query)", "players", "entities", "overlay(actionbar)",
                "online_players(online/player_list/tab_list)"));
        List<String> uiGroup = new ArrayList<>(List.of("get_screen(screen)"));
        if (!gmSafe) {
            uiGroup.add("screen_click");
            uiGroup.add("screen_click_widget(click_widget)");
        }
        uiGroup.add("screen_type(type_text/ui_type)");
        groups.put("UI 感知/交互", uiGroup);
        List<String> contGroup = new ArrayList<>(List.of("get_container(container/cont_info)"));
        if (!gmSafe) {
            contGroup.add("click_slot(slot_click/cont_click)");
            contGroup.add("cont_button(menu_button)");
        }
        groups.put("容器操作", contGroup);
        groups.put("视角", List.of("look（看向坐标/角度）", "turn（相对或绝对转向）"));
        List<String> inputGroup = new ArrayList<>();
        if (!gmSafe) {
            inputGroup.add("press(key)");
            inputGroup.add("keys(hold_keys)");
        }
        if (!gmObserve) {
            inputGroup.add("move");
            inputGroup.add("jump");
            inputGroup.add("sneak");
        }
        inputGroup.add("select(slot)");
        inputGroup.add("scroll(wheel)");
        inputGroup.add("mouse_move");
        if (!inputGroup.isEmpty()) {
            groups.put("输入/按键", inputGroup);
        }
        String fmNow = AiPlayerConfig.fileModeRaw();
        if (!"off".equals(fmNow)) {
            List<String> fileGroup = "readonly".equals(fmNow)
                    ? List.of("file_read", "file_list", "tools", "docs")
                    : List.of("file_read", "file_write", "file_append", "file_list", "tools", "docs");
            groups.put("自建区(工具/文档)", fileGroup);
        }
        if (AiPlayerConfig.networkEnabled()) {
            groups.put("内置 AI(LLM)", List.of("llm_chat(llm)", "llm_ping"));
        }
        // v0.2.1.6：自建工具执行开关打开时才列出（关掉则 tool_run 不在注册表）
        if (AiPlayerConfig.selfToolEnabled()) {
            groups.put("自建工具", List.of("tool_run(run_tool：执行 tools/index.json 里的宏，"
                    + "name=工具名 + 参数；步骤只能引用当前可用动作，禁嵌套和递归）"));
        }
        // v0.2.1.10：sidecar 开启时列出 agent 进程管理（启停/状态）
        if (AiPlayerConfig.sidecarEnabled()) {
            groups.put("Agent进程(sidecar)", List.of(
                    "agent_status（8770 在线/归属/配置一览）",
                    "agent_start(agent_run：ensure_running 拉起，已在跑则复用；异步)",
                    "agent_stop(agent_shutdown：8770/stop 优雅收轮→超时强杀自己拉起的实例；异步)"));
        }
        groups.put("截图", List.of("screenshot(shot/f2：F2 真实按键注入；mode=grab 直调；query=true 取结果)"));
        groups.put("其它", List.of("close_screen(close)", "reload_chunks"));
        List<String> gs = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            gs.add(Json.kv(e.getKey(), Json.arr(e.getValue().stream().map(Json::str).toList())));
        }
        return Json.obj(Json.kv("action", Json.str("actions")),
                Json.kv("count", Json.num(REGISTRY.size())),
                Json.kv("groups", Json.obj(gs.toArray(new String[0]))),
                Json.kv("permissions", Json.obj(
                        Json.kv("fileMode", Json.str(AiPlayerConfig.fileModeRaw())),
                        Json.kv("gameActionMode", Json.str(AiPlayerConfig.gameActionModeRaw())),
                        Json.kv("commandEnabled", Json.bool(AiPlayerConfig.commandEnabled())),
                        Json.kv("networkEnabled", Json.bool(AiPlayerConfig.networkEnabled())),
                        Json.kv("selfToolEnabled", Json.bool(AiPlayerConfig.selfToolEnabled())),
                        Json.kv("deniedCount", Json.num(deniedCount())),
                        Json.kv("note", Json.str("权限在装配期生效：被禁的动作不在 groups 里；"
                                + "gameActionMode=safe 摘输入/改物类（press/keys/click_slot/cont_button/screen_click*）、"
                                + "=observe 再摘 move/jump/sneak；selfToolEnabled（默认 false）开=注册 tool_run，"
                                + "关=自建工具不可执行且不列出")))),
                Json.kv("note", Json.str("v0.2.1.6：感知/基础交互/UI 输入/容器操作/截图/自建工具；"
                        + "权限表五轴（fileMode / commandEnabled / selfToolEnabled / gameActionMode / networkEnabled）全部生效；"
                        + "合成配方/寻路/网络终端写入属后续版本")));
    }
}
