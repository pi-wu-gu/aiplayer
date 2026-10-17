package net.aiplayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 游戏聊天栏缓冲。
 *
 * <p>与 Python 侧 {@code bridge_server.py} 的聊天缓冲语义一致：单调递增 id + 落盘，
 * 便于"每次只注入新消息、已注入不重复"（游标机制）。
 * 这里只负责采集与查询，游标由上层（大脑）自己维护。
 */
final class ChatBuffer {

    private static final int CAPACITY = 800;

    private static final Deque<Entry> BUFFER = new ArrayDeque<>();

    private static long nextId = 1;
    private static Path logPath;
    private static boolean loaded = false;

    record Entry(long id, double t, String text) {
    }

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 人类可读时间戳（落盘与 /chat 输出都用它；t 无效时返回空串）。 */
    static String tsOf(double t) {
        if (t <= 0) {
            return "";
        }
        return TS_FMT.format(Instant.ofEpochMilli((long) (t * 1000.0)));
    }

    private ChatBuffer() {
    }

    /** 初始化：确定落盘路径并加载历史（保证 id 单调，重启不丢）。 */
    static synchronized void init() {
        if (loaded) {
            return;
        }
        logPath = AiPlayerPaths.of("chat", "chat_log.jsonl");
        try {
            if (Files.exists(logPath)) {
                List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String s = line.trim();
                    if (s.isEmpty()) {
                        continue;
                    }
                    // 极简解析：{"id":N,"t":...,"text":"..."}——取 id/t/text，重启后时间戳不丢
                    long id = extractLong(s, "\"id\":");
                    double t = extractDouble(s, "\"t\":");
                    String text = extractString(s, "\"text\":");
                    if (id > 0 && text != null) {
                        BUFFER.addLast(new Entry(id, t, text));
                        nextId = Math.max(nextId, id + 1);
                    }
                }
                while (BUFFER.size() > CAPACITY) {
                    BUFFER.removeFirst();
                }
            }
        } catch (IOException e) {
            AiPlayer.LOG.warn("[AI Player] 聊天缓冲加载失败: {}", e.toString());
        }
        loaded = true;
        AiPlayer.LOG.info("[AI Player] 聊天缓冲就绪：历史 {} 条，下一 id = {}", BUFFER.size(), nextId);
    }

    /** 收到一条聊天（在客户端主线程调用）。 */
    static synchronized void add(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Entry e = new Entry(nextId++, System.currentTimeMillis() / 1000.0, text);
        BUFFER.addLast(e);
        while (BUFFER.size() > CAPACITY) {
            BUFFER.removeFirst();
        }
        if (logPath != null) {
            try {
                Files.createDirectories(logPath.getParent());
                Files.writeString(logPath,
                        Json.obj(Json.kv("id", Json.num(e.id())),
                                 Json.kv("t", Json.num(e.t())),
                                 Json.kv("ts", Json.str(tsOf(e.t()))),
                                 Json.kv("text", Json.str(e.text()))) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                // 落盘失败不影响内存缓冲
            }
        }
    }

    /** 查询：id > since 的条目，最多 limit 条（limit<=0 表示不限）。 */
    static synchronized List<Entry> since(long since, int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : BUFFER) {
            if (e.id() > since) {
                out.add(e);
            }
        }
        if (limit > 0 && out.size() > limit) {
            out = out.subList(out.size() - limit, out.size());
        }
        return out;
    }

    static synchronized long latestId() {
        return nextId - 1;
    }

    static synchronized int size() {
        return BUFFER.size();
    }

    static synchronized List<String> tail(int n) {
        List<String> out = new ArrayList<>();
        int skip = Math.max(0, BUFFER.size() - n);
        int i = 0;
        for (Entry e : BUFFER) {
            if (i++ >= skip) {
                out.add(e.text());
            }
        }
        return out;
    }

    // ---------- 极简字段提取（避免为一行历史引入完整 JSON 解析） ----------

    private static long extractLong(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) {
            return -1;
        }
        int j = i + key.length();
        int k = j;
        while (k < s.length() && (Character.isDigit(s.charAt(k)) || s.charAt(k) == '-')) {
            k++;
        }
        try {
            return Long.parseLong(s.substring(j, k));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double extractDouble(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) {
            return 0;
        }
        int j = i + key.length();
        int k = j;
        while (k < s.length() && (Character.isDigit(s.charAt(k)) || s.charAt(k) == '-'
                || s.charAt(k) == '.' || s.charAt(k) == 'e' || s.charAt(k) == 'E')) {
            k++;
        }
        try {
            return Double.parseDouble(s.substring(j, k));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String extractString(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) {
            return null;
        }
        int j = s.indexOf('"', i + key.length());
        if (j < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = j + 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\') {
                char n = k + 1 < s.length() ? s.charAt(k + 1) : 0;
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(n);
                }
                k++;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }
}
