package net.aiplayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 通用 LLM 客户端：**以 OpenAI 兼容的 chat/completions 为基准**。
 *
 * <p>选它的理由（对应"API 采用尽量通用的方法配置"）：
 * DeepSeek / OpenAI / 各类中转 / 本地 vLLM / Ollama(兼容端点) 都能套同一套协议，
 * 差异用配置兜底（provider、base_url、headers_json、extra_body_json），而不是为每家写一套代码。
 *
 * <p>出站走 Java 的 {@link HttpClient}（不是 Python 侧）：
 * 冒烟实测 Java HttpClient 发 HTTPS 267 ms 通过，而 GraalPy 自带的 urllib+ssl 对域名发 HTTPS
 * 会因缺 idna 编解码器失败。
 *
 * <p>★推理模型适配：实测 deepseek-flash 会把输出放进 {@code reasoning_content}
 * （usage 里记作 reasoning_tokens），此时 {@code content} 为空。
 * 因此同时取两处，避免"调用成功但回复是空的"。
 *
 * <p>安全：密钥优先读环境变量，且**任何日志都不回显密钥内容**。
 */
final class LlmClient {

    record Result(int status, String content, String reasoning, String raw, long ms, String error) {
    }

    private static HttpClient client;

    private LlmClient() {
    }

    private static synchronized HttpClient http() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return client;
    }

    /** 组装最终端点：base_url 末尾按需补 `chat/completions`（provider=custom 时视作完整 URL）。 */
    static String endpoint() {
        String provider = AiPlayerConfig.apiProvider();
        String base = AiPlayerConfig.apiBaseUrl();
        if (base == null || base.isBlank()) {
            base = "https://api.deepseek.com";
        }
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if ("custom".equalsIgnoreCase(provider)) {
            return base;
        }
        if (base.endsWith("chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    static String resolveKey() {
        String env = AiPlayerConfig.apiKeyEnv();
        if (env != null && !env.isBlank()) {
            String v = System.getenv(env.trim());
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return AiPlayerConfig.apiKey();
    }

    /** 一条 user 消息的快捷调用。 */
    static Result chat(String prompt) {
        return chat(null, prompt, null, null);
    }

    /**
     * 通用对话调用。
     *
     * @param system    系统提示（可空）
     * @param prompt    user 消息（可空，若 messages 非空则以 messages 为准）
     * @param messages  显式消息数组（[{role,content}]，可空）
     * @param maxTokens 覆盖默认 max_tokens（null = 用配置）
     */
    static Result chat(String system, String prompt, JsonArray messages, Integer maxTokens) {
        String key = resolveKey();
        if (key == null || key.isBlank()) {
            return new Result(0, null, null, null, 0,
                    "api_key 为空：请填 config/aiplayer-common.toml 的 apiKey，或设置环境变量 apiKeyEnv 指向的变量");
        }

        JsonArray msgs = new JsonArray();
        if (messages != null && !messages.isEmpty()) {
            msgs = messages;
        } else {
            if (system != null && !system.isBlank()) {
                msgs.add(msg("system", system));
            }
            if (prompt != null && !prompt.isBlank()) {
                msgs.add(msg("user", prompt));
            }
        }
        if (msgs.isEmpty()) {
            return new Result(0, null, null, null, 0, "没有消息内容（prompt / messages 都为空）");
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", AiPlayerConfig.apiModel());
        body.add("messages", msgs);
        body.addProperty("stream", false);
        double temp = AiPlayerConfig.apiTemperature();
        if (temp >= 0) {
            body.addProperty("temperature", temp);
        }
        int mt = maxTokens != null && maxTokens > 0 ? maxTokens : AiPlayerConfig.apiMaxTokens();
        if (mt > 0) {
            body.addProperty("max_tokens", mt);
        }
        // 通用兜底：把用户配置的额外字段合并进请求体（各家中转站的奇葩参数都靠它；重名以 extra 为准）
        String extra = AiPlayerConfig.apiExtraBodyJson();
        if (extra != null && !extra.isBlank()) {
            try {
                JsonObject ex = JsonParser.parseString(extra).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : ex.entrySet()) {
                    body.add(e.getKey(), e.getValue());
                }
            } catch (Exception e) {
                AiPlayer.LOG.warn("[AI Player] apiExtraBodyJson 解析失败，已忽略: {}", e.getMessage());
            }
        }

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(Duration.ofMillis(Math.max(1000, AiPlayerConfig.apiTimeoutMs())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));

        String extraHeaders = AiPlayerConfig.apiHeadersJson();
        if (extraHeaders != null && !extraHeaders.isBlank()) {
            try {
                JsonObject hdrs = JsonParser.parseString(extraHeaders).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : hdrs.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        req.header(e.getKey(), e.getValue().getAsString());
                    }
                }
            } catch (Exception e) {
                AiPlayer.LOG.warn("[AI Player] apiHeadersJson 解析失败，已忽略: {}", e.getMessage());
            }
        }

        long t0 = System.currentTimeMillis();
        try {
            HttpResponse<String> resp = http().send(req.build(), HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - t0;
            String raw = resp.body();
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return new Result(resp.statusCode(), null, null, clip(raw), ms,
                        "HTTP " + resp.statusCode() + ": " + clip(raw));
            }
            String content = null;
            String reasoning = null;
            String usage = null;
            try {
                JsonObject jo = JsonParser.parseString(raw).getAsJsonObject();
                JsonArray choices = jo.getAsJsonArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JsonObject first = choices.get(0).getAsJsonObject();
                    JsonObject message = first.has("message") ? first.getAsJsonObject("message") : null;
                    if (message != null) {
                        content = textOrNull(message, "content");
                        // ★推理模型：正文在 reasoning_content，content 可能为空
                        reasoning = textOrNull(message, "reasoning_content");
                    }
                }
                if (jo.has("usage")) {
                    usage = jo.getAsJsonObject("usage").toString();
                }
            } catch (Exception e) {
                return new Result(resp.statusCode(), null, null, clip(raw), ms, "响应解析失败: " + e.getMessage());
            }
            return new Result(resp.statusCode(), content == null ? "" : content,
                    reasoning, usage == null ? "" : usage, ms, null);
        } catch (HttpConnectTimeoutException e) {
            return new Result(0, null, null, null, System.currentTimeMillis() - t0, "连接超时：" + endpoint());
        } catch (java.io.IOException e) {
            return new Result(0, null, null, null, System.currentTimeMillis() - t0, "网络错误: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(0, null, null, null, 0, "调用被中断");
        } catch (Throwable e) {
            return new Result(0, null, null, null, System.currentTimeMillis() - t0, "未知错误: " + e);
        }
    }

    private static String textOrNull(JsonObject o, String key) {
        if (o.has(key)) {
            JsonElement e = o.get(key);
            if (e != null && e.isJsonPrimitive()) {
                return e.getAsString();
            }
        }
        return null;
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    /** 给动作层用的结果序列化（不回显密钥）。 */
    static String asJson(Result r) {
        List<String> parts = new ArrayList<>();
        parts.add(Json.kv("action", Json.str("llm_chat")));
        parts.add(Json.kv("ok", Json.bool(r.error() == null)));
        parts.add(Json.kv("status", Json.num(r.status())));
        parts.add(Json.kv("ms", Json.num(r.ms())));
        parts.add(Json.kv("model", Json.str(AiPlayerConfig.apiModel())));
        parts.add(Json.kv("endpoint", Json.str(endpoint())));
        parts.add(Json.kv("content", r.content() == null ? "null" : Json.str(r.content())));
        parts.add(Json.kv("reasoning", r.reasoning() == null ? "null" : Json.str(clip(r.reasoning()))));
        parts.add(Json.kv("usage", Json.str(r.raw())));
        parts.add(Json.kv("error", r.error() == null ? "null" : Json.str(r.error())));
        return Json.obj(parts.toArray(new String[0]));
    }
}
