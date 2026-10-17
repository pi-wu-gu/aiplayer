package net.aiplayer;

/**
 * 极简 JSON 输出辅助：手写 JSON 字符串，避免依赖 Gson 的序列化行为。
 * （解析请求体仍用 MC 自带的 Gson，见 {@link ControlServer}。）
 */
final class Json {

    private Json() {
    }

    /** 转义为 JSON 字符串字面量（含引号）。 */
    static String str(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "null";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    static String num(long l) {
        return Long.toString(l);
    }

    static String bool(boolean b) {
        return b ? "true" : "false";
    }

    /** 键值对拼接工具：{@code kv("a", Json.num(1))} → {@code "a":1} */
    static String kv(String key, String rawValue) {
        return str(key) + ":" + rawValue;
    }

    static String obj(String... pairs) {
        return "{" + String.join(",", pairs) + "}";
    }

    static String arr(java.util.List<String> items) {
        return "[" + String.join(",", items) + "]";
    }

    /**
     * 把一段文本安全地作为 JSON 值嵌入：
     * 若它本身是合法的 JSON 对象/数组则原样嵌入（嵌套结构），否则当作字符串（加引号转义）。
     *
     * <p>★v0.3 的 bug：以前一律原样嵌入，结果像 {@code "result":say: hello} 这样的输出**不是合法 JSON**，
     * 任何客户端解析都会失败。
     */
    static String maybeJson(String s) {
        if (s == null) {
            return "null";
        }
        try {
            com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(s);
            if (e.isJsonObject() || e.isJsonArray()) {
                return s;
            }
        } catch (Exception ignored) {
            // 不是 JSON，按字符串处理
        }
        return str(s);
    }

    /** 最小化的 JSON 字符串读取（用于可选参数），失败返回默认值。 */
    static String optString(com.google.gson.JsonObject o, String key, String def) {
        try {
            if (o != null && o.has(key) && !o.get(key).isJsonNull()) {
                return o.get(key).getAsString();
            }
        } catch (Exception ignored) {
            // 类型不符时退回默认值
        }
        return def;
    }

    static double optDouble(com.google.gson.JsonObject o, String key, double def) {
        try {
            if (o != null && o.has(key) && !o.get(key).isJsonNull()) {
                return o.get(key).getAsDouble();
            }
        } catch (Exception ignored) {
            // 同上
        }
        return def;
    }

    static int optInt(com.google.gson.JsonObject o, String key, int def) {
        try {
            if (o != null && o.has(key) && !o.get(key).isJsonNull()) {
                return o.get(key).getAsInt();
            }
        } catch (Exception ignored) {
            // 同上
        }
        return def;
    }

    static boolean optBool(com.google.gson.JsonObject o, String key, boolean def) {
        try {
            if (o != null && o.has(key) && !o.get(key).isJsonNull()) {
                return o.get(key).getAsBoolean();
            }
        } catch (Exception ignored) {
            // 同上
        }
        return def;
    }
}
