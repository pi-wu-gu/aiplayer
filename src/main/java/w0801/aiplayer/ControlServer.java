package net.aiplayer;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

/**
 * 内嵌 HTTP 控制服务（替代 Python 的 {@code bridge_server.py}）。
 *
 * <p>为什么手写而不是用 {@code com.sun.net.httpserver}：后者位于 {@code jdk.httpserver} 模块，
 * 在 FML 的模块层里能否被 mod 读到有不确定性，而每次"部署→重启客户端→看日志"的验证成本约 4 分钟，
 * 不值得为省几十行代码去赌。这里只用 {@code java.base}（ServerSocket），行为完全可控。
 *
 * <p>协议与旧桥接对齐（上层 agent 可零改动）：
 * <ul>
 *   <li>{@code GET  /health} → {@code {"ok":true,"age_sec":...}}</li>
 *   <li>{@code GET  /state} → 玩家/世界状态快照（含 {@code last_action}）</li>
 *   <li>{@code GET  /chat?since=<id>&limit=<n>} → 聊天缓冲</li>
 *   <li>{@code POST /command} body {@code {"commands":[{...}]}} → 逐条在主线程执行并返回结果</li>
 *   <li>{@code GET  /actions} → 合法动作名自省</li>
 * </ul>
 *
 * <p>安全：只监听回环地址（默认 127.0.0.1），不对外网开放。
 */
final class ControlServer {

    private static final int MAX_BODY = 256 * 1024;
    private static final int ACTION_TIMEOUT_MS = 20000;

    private static ServerSocket socket;
    private static Thread acceptThread;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong COMMANDS = new AtomicLong();
    private static final AtomicLong COMMAND_ERRORS = new AtomicLong();
    private static long startedAt;
    private static volatile String bound = "(未启动)";

    private ControlServer() {
    }

    static String endpoint() {
        return bound;
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requests", REQUESTS.get());
        m.put("commands_sent", COMMANDS.get());
        m.put("command_errors", COMMAND_ERRORS.get());
        // 权限表拒绝次数（越界路径 / 被禁用动作的调用尝试）——审计用
        m.put("permission_denied", Actions.deniedCount());
        m.put("uptime_sec", startedAt == 0 ? 0 : (System.currentTimeMillis() - startedAt) / 1000.0);
        m.put("endpoint", bound);
        return m;
    }

    static synchronized void start() {
        if (RUNNING.get()) {
            return;
        }
        String host = AiPlayerConfig.controlHost();
        int port = AiPlayerConfig.controlPort();
        try {
            InetAddress addr = InetAddress.getByName(host);
            socket = new ServerSocket(port, 8, addr);
            socket.setReuseAddress(true);
        } catch (IOException e) {
            bound = "(启动失败: " + e.getMessage() + ")";
            AiPlayer.LOG.error("[AI Player] 控制服务启动失败（{}:{}）: {}", host, port, e.toString());
            return;
        }
        startedAt = System.currentTimeMillis();
        RUNNING.set(true);
        bound = "http://" + host + ":" + socket.getLocalPort();
        acceptThread = new Thread(ControlServer::acceptLoop, "aiplayer-control");
        acceptThread.setDaemon(true);
        acceptThread.start();
        AiPlayer.LOG.info("[AI Player] 控制服务已启动: {} （GET /state /chat /health /actions，POST /command）", bound);
    }

    static synchronized void stop() {
        RUNNING.set(false);
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // 关闭失败无所谓
        }
        socket = null;
        bound = "(已停止)";
    }

    private static void acceptLoop() {
        while (RUNNING.get()) {
            try {
                Socket client = socket.accept();
                Thread t = new Thread(() -> handle(client), "aiplayer-http");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (RUNNING.get()) {
                    AiPlayer.LOG.warn("[AI Player] 接受连接失败: {}", e.toString());
                }
                return;
            }
        }
    }

    // ---------------------------------------------------------------- HTTP 最小实现

    private static void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(s, 400, Json.obj(Json.kv("error", Json.str("bad request line"))));
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            int contentLength = 0;
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String key = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(idx + 1).trim();
                headers.put(key, value);
                if (key.equals("content-length")) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        contentLength = 0;
                    }
                }
            }
            // 可选鉴权：配置了 controlToken 就必须带 X-AI-Token
            String wantToken = AiPlayerConfig.controlToken();
            if (wantToken != null && !wantToken.isBlank()) {
                String got = headers.get("x-ai-token");
                if (got == null || !got.equals(wantToken)) {
                    respond(s, 401, Json.obj(Json.kv("error", Json.str("缺少或错误的 X-AI-Token"))));
                    return;
                }
            }
            byte[] body = new byte[0];
            if (contentLength > 0) {
                if (contentLength > MAX_BODY) {
                    respond(s, 413, Json.obj(Json.kv("error", Json.str("body too large"))));
                    return;
                }
                body = readN(in, Math.min(contentLength, MAX_BODY));
            }
            REQUESTS.incrementAndGet();

            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            String response;
            if (path.equals("/health")) {
                response = Json.obj(Json.kv("ok", Json.bool(true)),
                        Json.kv("age_sec", "0"),
                        Json.kv("endpoint", Json.str(bound)),
                        Json.kv("stats", statsJson()));
            } else if (path.equals("/state")) {
                response = runOnClientThread("state", new JsonObject()).result();
            } else if (path.equals("/chat")) {
                response = chat(query);
            } else if (path.equals("/actions")) {
                response = runOnClientThread("actions", new JsonObject()).result();
            } else if (path.equals("/tools")) {
                response = runOnClientThread("tools", new JsonObject()).result();
            } else if (path.equals("/docs")) {
                JsonObject par = new JsonObject();
                String f = param(query, "file");
                if (f != null) {
                    par.addProperty("file", f);
                }
                response = runOnClientThread("docs", par).result();
            } else if (path.equals("/command") && method.equals("POST")) {
                response = commands(body);
            } else if (path.equals("/stats")) {
                response = statsJson();
            } else {
                respond(s, 404, Json.obj(Json.kv("error", Json.str("unknown path: " + path))));
                return;
            }
            respond(s, 200, response);
        } catch (Exception e) {
            AiPlayer.LOG.warn("[AI Player] 处理请求异常: {}", e.toString());
        }
    }

    private static String statsJson() {
        Map<String, Object> st = stats();
        List<String> parts = new ArrayList<>();
        parts.add(Json.kv("requests", Json.num(((Number) st.get("requests")).longValue())));
        parts.add(Json.kv("commands_sent", Json.num(((Number) st.get("commands_sent")).longValue())));
        parts.add(Json.kv("command_errors", Json.num(((Number) st.get("command_errors")).longValue())));
        parts.add(Json.kv("permission_denied", Json.num(((Number) st.get("permission_denied")).longValue())));
        parts.add(Json.kv("uptime_sec", Json.num(((Number) st.get("uptime_sec")).doubleValue())));
        parts.add(Json.kv("endpoint", Json.str(bound)));
        return Json.obj(parts.toArray(new String[0]));
    }

    private static String chat(String query) {
        long since = parseLong(param(query, "since"), 0);
        int limit = (int) parseLong(param(query, "limit"), 50);
        ChatBuffer.init();
        List<String> entries = new ArrayList<>();
        for (ChatBuffer.Entry e : ChatBuffer.since(since, limit)) {
            entries.add(Json.obj(Json.kv("id", Json.num(e.id())),
                    Json.kv("t", Json.num(e.t())),
                    Json.kv("ts", Json.str(ChatBuffer.tsOf(e.t()))),
                    Json.kv("text", Json.str(e.text()))));
        }
        return Json.obj(Json.kv("entries", Json.arr(entries)),
                Json.kv("total", Json.num(ChatBuffer.size())),
                Json.kv("latest_id", Json.num(ChatBuffer.latestId())));
    }

    private static String param(String query, String key) {
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                return kv.substring(i + 1);
            }
        }
        return null;
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** POST /command：{@code {"commands":[{...}]}}，逐条执行（顺序执行，便于"先放料再取件"这类序列）。 */
    private static String commands(byte[] body) {
        JsonObject req;
        try {
            String text = new String(body, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return Json.obj(Json.kv("queued", Json.num(0)), Json.kv("error", Json.str("空 body")));
            }
            req = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            return Json.obj(Json.kv("queued", Json.num(0)),
                    Json.kv("error", Json.str("JSON 解析失败: " + e.getMessage()
                            + "（body 必须是 {\"commands\":[{...}]}，裸对象会被拒）")));
        }
        JsonElement cmdsEl = req.get("commands");
        if (cmdsEl == null || !cmdsEl.isJsonArray()) {
            return Json.obj(Json.kv("queued", Json.num(0)),
                    Json.kv("error", Json.str("缺少 commands 数组")));
        }
        JsonArray cmds = cmdsEl.getAsJsonArray();
        List<String> results = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        for (JsonElement el : cmds) {
            if (!el.isJsonObject()) {
                results.add(Json.obj(Json.kv("ok", Json.bool(false)), Json.kv("error", Json.str("命令不是对象"))));
                failed++;
                continue;
            }
            JsonObject cmd = el.getAsJsonObject();
            String type = Json.optString(cmd, "type", "");
            DispatchResult r = runOnClientThread(type, cmd);
            COMMANDS.incrementAndGet();
            if (r.ok()) {
                ok++;
            } else {
                failed++;
                COMMAND_ERRORS.incrementAndGet();
            }
            results.add(Json.obj(Json.kv("type", Json.str(type)),
                    Json.kv("ok", Json.bool(r.ok())),
                    // ★必须走 maybeJson：直接嵌字符串会产出非法 JSON（v0.3 已修）
                    Json.kv("result", Json.maybeJson(r.result()))));
        }
        return Json.obj(Json.kv("queued", Json.num(cmds.size())),
                Json.kv("ok", Json.num(ok)),
                Json.kv("failed", Json.num(failed)),
                Json.kv("results", Json.arr(results)),
                Json.kv("last_action", Json.str(Actions.lastAction())));
    }

    private record DispatchResult(String result, boolean ok) {
    }

    /**
     * 在客户端主线程执行一个动作并等结果。
     *
     * <p>★这是旧 JsMacros 版"主线程写操作后必须轮询完成标志"那条经验的 Java 版本：
     * {@code Minecraft.execute} 只是把任务排进主线程队列，是异步的，因此这里用闩锁等它跑完。
     */
    private static DispatchResult runOnClientThread(String type, JsonObject params) {
        Minecraft mc = Minecraft.getInstance();
        if (type == null || type.isEmpty()) {
            return new DispatchResult(Json.obj(Json.kv("error", Json.str("缺少 type"))), false);
        }
        CountDownLatch latch = new CountDownLatch(1);
        String[] holder = new String[1];
        Runnable task = () -> {
            try {
                holder[0] = Actions.dispatch(type, params);
            } catch (Throwable t) {
                holder[0] = "error: " + t;
            } finally {
                latch.countDown();
            }
        };
        try {
            if (mc.isSameThread()) {
                task.run();
            } else {
                mc.execute(task);
                if (!latch.await(ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return new DispatchResult("timeout: 动作 " + type + " 在 " + ACTION_TIMEOUT_MS + "ms 内未完成", false);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DispatchResult("interrupted", false);
        }
        String result = holder[0] == null ? "(null)" : holder[0];
        Actions.setLastAction(result);
        boolean ok = !(result.startsWith("unknown:") || result.startsWith("error:") || result.endsWith("_err: 缺少 text"));
        return new DispatchResult(result, ok);
    }

    // ---------------------------------------------------------------- IO 小工具

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            if (buf.size() > 8192) {
                break;
            }
        }
        if (c == -1 && buf.size() == 0) {
            return null;
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(out, off, n - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        return out;
    }

    private static void respond(Socket s, int code, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (IOException ignored) {
            // 客户端提前断开
        }
    }
}
