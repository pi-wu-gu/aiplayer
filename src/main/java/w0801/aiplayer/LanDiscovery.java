package net.aiplayer;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 局域网世界广播监听（高级联机，v0.2.1.26）。
 *
 * <p>原理：MC 客户端「对局域网开放」时，会周期性（约 1.5s）向多播组
 * {@code 224.0.2.60:4445} 发送公告包，格式 {@code [MOTD]...[/MOTD][AD]端口[/AD]}。
 * 本类在后台 daemon 线程收包，记录最近一次公告（发送方 IP + 公告端口）。
 *
 * <p>使用方（{@link AiPlayerClient} 的主界面 tick）调用 {@link #fresh(long)} 取新鲜公告，
 * 有则自动加入该 LAN 世界（配置项 {@code lanAutoJoin} 总闸）。
 *
 * <p>安全边界：只解析端口并连接；MOTD 仅记日志不进决策。监听为被动收包，不发送任何内容。
 */
public final class LanDiscovery {

    private static final String GROUP = "224.0.2.60";
    private static final int PORT = 4445;
    private static final Pattern AD_PORT = Pattern.compile("\\[AD\\](\\d+)\\[/AD\\]");

    /** 最近一次公告的 "ip:port"；无公告为 null。 */
    private static volatile String latest = null;
    private static volatile long latestAtMs = 0L;
    private static volatile boolean started = false;

    private LanDiscovery() {
    }

    /** 启动后台监听线程（幂等；失败只记日志，绝不影响游戏）。 */
    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(LanDiscovery::loop, "aiplayer-lan-discovery");
        t.setDaemon(true);
        t.start();
        AiPlayer.LOG.info("[AI Player] LAN 世界广播监听已启动（{}:{}）", GROUP, PORT);
    }

    /**
     * 取新鲜公告。
     *
     * @param maxAgeMs 公告最大年龄（毫秒）；超期视为世界已关闭
     * @return "ip:port"，无新鲜公告返回 null
     */
    public static String fresh(long maxAgeMs) {
        String s = latest;
        if (s == null) {
            return null;
        }
        return (System.currentTimeMillis() - latestAtMs <= maxAgeMs) ? s : null;
    }

    /** 测试/诊断用：当前记录的公告与年龄。 */
    public static String describe() {
        String s = latest;
        if (s == null) {
            return "尚无 LAN 公告";
        }
        return s + "（" + (System.currentTimeMillis() - latestAtMs) + "ms 前）";
    }

    // ---------------------------------------------------------------- 监听循环

    private static void loop() {
        while (true) {
            try (MulticastSocket sock = new MulticastSocket(PORT)) {
                InetAddress group = InetAddress.getByName(GROUP);
                sock.joinGroup(group);
                sock.setSoTimeout(30_000);
                byte[] buf = new byte[1024];
                while (true) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(pkt);
                    } catch (SocketTimeoutException e) {
                        continue;   // 超时只是没包，继续收
                    }
                    String msg = new String(pkt.getData(), 0, pkt.getLength(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    Matcher m = AD_PORT.matcher(msg);
                    if (!m.find()) {
                        continue;   // 非 MC 局域网公告格式
                    }
                    String ip = pkt.getAddress().getHostAddress();
                    String addr = ip + ":" + m.group(1);
                    String motd = extractMotd(msg);
                    latest = addr;
                    latestAtMs = System.currentTimeMillis();
                    AiPlayer.LOG.debug("[AI Player] LAN 公告: {} MOTD={}", addr, motd);
                }
            } catch (Exception e) {
                AiPlayer.LOG.warn("[AI Player] LAN 监听异常（5s 后重试）: {}", e.toString());
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static String extractMotd(String msg) {
        int a = msg.indexOf("[MOTD]");
        int b = msg.indexOf("[/MOTD]");
        return (a >= 0 && b > a) ? msg.substring(a + 6, b) : "";
    }
}
