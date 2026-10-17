package net.aiplayer;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 客户端入口。
 *
 * <p>AI Player 是<strong>纯客户端 mod</strong>：它驱动的"AI 玩家"就是一个普通玩家账号，
 * 逻辑全部跑在客户端侧，因此这里以 {@code dist = Dist.CLIENT} 注册。
 *
 * <p>v0.2 起承担三件事：① 采集聊天进缓冲 ② 启动内嵌控制服务 ③ 退出时清理资源。
 */
@Mod(value = AiPlayer.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = AiPlayer.MODID, value = Dist.CLIENT)
public class AiPlayerClient {

    private static volatile boolean bootstrapped = false;

    public AiPlayerClient(ModContainer container) {
        // 让 NeoForge 为本 mod 生成配置界面：游戏内「模组 → AI Player → 配置」
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        try {
            if (!AiPlayerConfig.enabled()) {
                AiPlayer.LOG.info("[AI Player] enabled=false，跳过控制服务与聊天缓冲初始化");
                return;
            }
            ChatBuffer.init();
            LanDiscovery.start();   // LAN 世界广播监听（被动收包；lanAutoJoin 总闸在 tick 侧）

            if (AiPlayerConfig.controlEnabled()) {
                // ★不要在主线程里起服务：绑定端口是 IO，放到独立线程，失败也不影响游戏启动
                Thread boot = new Thread(ControlServer::start, "aiplayer-control-boot");
                boot.setDaemon(true);
                boot.start();
            } else {
                AiPlayer.LOG.info("[AI Player] controlEnabled=false，内嵌控制服务未启动");
            }

            // 退出时释放端口与线程（渲染/HTTP 线程都是 daemon，这里只是做得干净些）
            // ★先向服务器播报关闭原因（2026-10-16 用户约定：重启/关闭客户端要让服务器知道），
            //  再停控制服务；硬杀进程不走本 hook，那类重启按 SOP 预先 say 原因
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Actions.announceShutdown();
                ControlServer.stop();
            }, "aiplayer-shutdown"));

            if (AiPlayerConfig.verbose()) {
                AiPlayer.LOG.info("[AI Player] 客户端就绪；登录名 = {}，数据目录 = {}",
                        Minecraft.getInstance().getUser().getName(),
                        AiPlayerPaths.dataDir());
            }
        } catch (Exception e) {
            // 任何初始化失败都只记日志：绝不能让 AI 模块把游戏拦在启动画面
            AiPlayer.LOG.warn("[AI Player] 客户端初始化失败（不影响游戏）: {}", e.toString());
        }
    }

    // ---------------- 自动登录 / 自动重连 / LAN 自动加入（0.2.1.20→0.2.1.26） ----------------
    // 场景：无人值守的 AI 客户端，启动后应自己进服；服务器崩了也要能自己回去；
    //       另一客户端「对局域网开放」时能自动发现并加入（高级联机，0.2.1.26）。
    // 规则：①LAN 优先——lanAutoJoin 开且收到新鲜广播 → 直接加入；②serverAddress——
    //       主菜单→按 autoLogin 连；断线界面→按 autoReconnect 连。
    // 两次连接之间至少间隔 reconnectDelaySec 秒；次数上限 autoConnectMaxAttempts
    // （默认 2，成功进世界后重置；用尽断开界面→回主界面不再连）。
    // ★0.2.1.26：serverAddress 热改 → 自动清零尝试次数（改完地址立刻可重连，无需重启）。
    private static volatile long nextConnectAtMs = 0L;
    private static volatile int autoConnectAttempts = 0;
    private static volatile String lastSeenAddr = null;   // serverAddress 热改检测基线

    @SubscribeEvent
    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        try {
            if (!AiPlayerConfig.enabled()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }
            if (mc.player != null) {
                // 成功进过世界 → 重置自动连接预算（之后每次断线都重新有 maxAttempts 次机会）
                if (autoConnectAttempts != 0) {
                    autoConnectAttempts = 0;
                }
                return;                  // 已在世界里（或正在游戏中）→ 不干预
            }
            net.minecraft.client.gui.screens.Screen scr = mc.screen;
            boolean title = scr instanceof net.minecraft.client.gui.screens.TitleScreen;
            boolean disconnected = scr instanceof net.minecraft.client.gui.screens.DisconnectedScreen;
            if (!title && !disconnected) {
                return;                  // 其它界面（设置/服务器列表…）交给人工或 agent
            }
            int maxAttempts = AiPlayerConfig.autoConnectMaxAttempts();
            long now = System.currentTimeMillis();
            boolean rateOk = now >= nextConnectAtMs;

            // ---- ①LAN 自动加入（优先于 serverAddress；title/断开界面都允许——被踢后 LAN 世界
            //      还开着就重进；公告超过 30s 视为世界已关闭）----
            if (AiPlayerConfig.lanAutoJoin()) {
                String lan = LanDiscovery.fresh(30_000);
                if (lan != null && autoConnectAttempts < maxAttempts && rateOk) {
                    nextConnectAtMs = now + AiPlayerConfig.reconnectDelaySec() * 1000L;
                    autoConnectAttempts++;
                    if (startConnect(mc, scr, lan)) {
                        AiPlayer.LOG.info("[AI Player] 发现 LAN 世界，自动加入({}, 第 {}/{} 次): {}",
                                disconnected ? "重连" : "加入", autoConnectAttempts, maxAttempts, lan);
                        return;
                    }
                }
            }

            // ---- ②serverAddress 自动登录/重连 ----
            String addr = AiPlayerConfig.serverAddress();
            if (addr == null || addr.isBlank()) {
                return;
            }
            String target = addr.trim();
            if (!target.equals(lastSeenAddr)) {
                // 地址热改 → 重置预算（改完地址立刻可重连，无需重启客户端）
                if (lastSeenAddr != null && autoConnectAttempts != 0) {
                    AiPlayer.LOG.info("[AI Player] serverAddress 变更（{} → {}）：自动连接次数已重置（原 {} 次）",
                            lastSeenAddr, target, autoConnectAttempts);
                    autoConnectAttempts = 0;
                }
                lastSeenAddr = target;
            }
            if (autoConnectAttempts >= maxAttempts) {
                // 次数用尽：断开界面→自动回主界面并停止自动连接；主界面→静默不再连
                if (disconnected) {
                    mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
                    AiPlayer.LOG.info("[AI Player] 自动连接已满 {} 次均失败，返回主界面，不再自动连接"
                                    + "（重启客户端重置计数；改 serverAddress 或调大 autoConnectMaxAttempts 亦可）",
                            maxAttempts);
                }
                return;
            }
            if (title && !AiPlayerConfig.autoLogin()) {
                return;
            }
            if (disconnected && !AiPlayerConfig.autoReconnect()) {
                return;
            }
            if (!rateOk) {
                return;
            }
            nextConnectAtMs = now + AiPlayerConfig.reconnectDelaySec() * 1000L;
            autoConnectAttempts++;
            if (startConnect(mc, scr, target)) {
                AiPlayer.LOG.info("[AI Player] 自动连接服务器({}, 第 {}/{} 次): {}",
                        disconnected ? "重连" : "登录", autoConnectAttempts, maxAttempts, target);
            }
        } catch (Throwable t) {
            // 自动连接失败绝不能影响游戏
            AiPlayer.LOG.warn("[AI Player] 自动连接失败（不影响游戏）: {}", t.toString());
        }
    }

    /** 解析并启动连接（★主线程调用）。返回 false=未启动（已在世界/地址非法）。 */
    private static boolean startConnect(Minecraft mc, net.minecraft.client.gui.screens.Screen scr, String target) {
        if (mc.player != null || target == null || target.isBlank()) {
            return false;
        }
        try {
            net.minecraft.client.multiplayer.resolver.ServerAddress sa =
                    net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(target);
            net.minecraft.client.multiplayer.ServerData sd =
                    new net.minecraft.client.multiplayer.ServerData("AI", target,
                            net.minecraft.client.multiplayer.ServerData.Type.OTHER);
            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                    scr, mc, sa, sd, false, null);
            return true;
        } catch (Throwable t) {
            AiPlayer.LOG.warn("[AI Player] 连接启动失败: {}", t.toString());
            return false;
        }
    }

    /**
     * connect 动作入口（★由 ControlServer 派发，已在主线程）。
     *
     * @param addr 目标地址 "ip:port"；null/空 = 使用配置的 serverAddress
     * @return JSON 结果
     */
    public static String requestConnect(String addr) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return Json.obj(Json.kv("ok", Json.bool(false)), Json.kv("error", Json.str("客户端未就绪")));
        }
        if (mc.player != null) {
            return Json.obj(Json.kv("ok", Json.bool(false)),
                    Json.kv("error", Json.str("已在世界中（先退出再连接）")));
        }
        String target = (addr == null || addr.isBlank())
                ? (AiPlayerConfig.serverAddress() == null ? "" : AiPlayerConfig.serverAddress().trim())
                : addr.trim();
        if (target.isBlank()) {
            return Json.obj(Json.kv("ok", Json.bool(false)),
                    Json.kv("error", Json.str("未提供 address 且 serverAddress 为空")));
        }
        autoConnectAttempts = 0;    // 手动连接不占自动预算，且给后续自动连接重开预算
        nextConnectAtMs = System.currentTimeMillis() + AiPlayerConfig.reconnectDelaySec() * 1000L;
        net.minecraft.client.gui.screens.Screen scr = mc.screen != null
                ? mc.screen : new net.minecraft.client.gui.screens.TitleScreen();
        boolean ok = startConnect(mc, scr, target);
        AiPlayer.LOG.info("[AI Player] connect 动作: {} → {}", target, ok ? "已发起" : "失败");
        return Json.obj(
                Json.kv("ok", Json.bool(ok)),
                Json.kv("connecting", Json.str(target)),
                Json.kv("attempts_reset", Json.bool(true)));
    }

    /** 聊天栏采集：进缓冲并落盘，供 {@code GET /chat?since=} 与大脑做"只注入新消息"。 */
    @SubscribeEvent
    static void onChatReceived(ClientChatReceivedEvent event) {
        try {
            if (!AiPlayerConfig.enabled()) {
                return;
            }
            ChatBuffer.add(event.getMessage().getString());
        } catch (Exception e) {
            // 聊天采集失败绝不能影响游戏
        }
    }
}
