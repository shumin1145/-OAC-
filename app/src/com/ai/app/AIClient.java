package com.oac.nazhiyazi.op;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;

/**
 * AI HTTP 客户端。兼容 Android 2.3 (API 9+)
 *
 * - 使用 HttpURLConnection（API 9+）
 * - 对 HTTPS 信任所有证书（Android 2.3 对现代 TLS 兼容性差，作为兜底方案）
 * - 支持流式 SSE 响应和一次性 JSON 响应
 * - 支持 reasoning_content（DeepSeek 思考链）
 *
 * 重要修复：
 * - JSONObject.optString 在字段值为 JSON null 时会返回字符串 "null"，
 *   必须用 isNull + 显式检查避免把 "null" 字符串拼接到回复里
 */
public class AIClient {

    /**
     * 构造 OpenAI 兼容协议请求体 JSON
     */
    public static String buildRequestBody(ModelConfig model, java.util.List<ChatMessage> history,
                                          String userMessage, boolean stream) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":");
        sb.append(jsonStr(model.modelId));
        sb.append(",\"messages\":[");

        boolean first = true;
        // 系统提示词
        if (model.systemPrompt != null && model.systemPrompt.length() > 0) {
            sb.append("{\"role\":").append(jsonStr("system"))
              .append(",\"content\":").append(jsonStr(model.systemPrompt)).append("}");
            first = false;
        }
        // 历史消息
        if (history != null) {
            for (ChatMessage m : history) {
                if (m == null) continue;
                if (m.isSystem()) continue; // 系统提示词已在上方处理
                if (!first) sb.append(",");
                sb.append("{\"role\":").append(jsonStr(m.role))
                  .append(",\"content\":").append(jsonStr(m.content)).append("}");
                first = false;
            }
        }
        // 当前用户消息
        if (userMessage != null && userMessage.length() > 0) {
            if (!first) sb.append(",");
            sb.append("{\"role\":").append(jsonStr("user"))
              .append(",\"content\":").append(jsonStr(userMessage)).append("}");
        }
        sb.append("]");

        // 采样参数
        sb.append(",\"temperature\":").append(formatDouble(model.temperature));
        if (model.maxTokens > 0) {
            sb.append(",\"max_tokens\":").append(model.maxTokens);
        }
        if (stream) {
            sb.append(",\"stream\":true");
        }
        // 部分 API（如 DeepSeek）支持 include_reasoning 来显式请求思考内容
        if (model.forceRequestReasoning()) {
            sb.append(",\"include_reasoning\":true");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 建立到 API 的连接（流式 / 非流式通用）。
     */
    public static HttpURLConnection connect(ModelConfig model, String body) throws Exception {
        URL url = new URL(model.apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        if (conn instanceof HttpsURLConnection) {
            // 兼容老安卓：信任所有证书 + 所有主机名
            // 优先尝试 TLSv1.2（Android 4.0+ 可用），失败再回退到默认 TLS
            try {
                SSLContext ctx = createTrustAllSSLContext();
                if (ctx != null) {
                    ((HttpsURLConnection) conn).setSSLSocketFactory(ctx.getSocketFactory());
                    ((HttpsURLConnection) conn).setHostnameVerifier(new TrustAllHostVerifier());
                }
            } catch (Exception e) {
                // ignore
            }
        }

        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setUseCaches(false);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "text/event-stream, application/json");
        conn.setRequestProperty("Authorization", "Bearer " + model.apiKey);

        OutputStream os = conn.getOutputStream();
        byte[] data = body.getBytes("UTF-8");
        os.write(data);
        os.flush();
        os.close();

        return conn;
    }

    /**
     * 读取非流式响应，返回 assistant 文本。
     */
    public static String readFullResponse(HttpURLConnection conn) throws Exception {
        StreamDelta d = readFullResponseDelta(conn);
        return d.content;
    }

    /**
     * 读取非流式响应，返回包含 content 和 reasoning 的 StreamDelta。
     */
    public static StreamDelta readFullResponseDelta(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream is;
        if (code >= 200 && code < 300) {
            is = conn.getInputStream();
        } else {
            is = conn.getErrorStream();
            if (is == null) {
                throw new Exception("HTTP " + code);
            }
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        String body = sb.toString();

        if (code >= 200 && code < 300) {
            return parseFullResponseDelta(body);
        } else {
            String msg = parseErrorMessage(body);
            throw new Exception("HTTP " + code + (msg != null ? ": " + msg : ""));
        }
    }

    /**
     * 解析非流式响应中的 assistant 消息内容
     */
    private static String parseFullResponseContent(String body) {
        return parseFullResponseDelta(body).content;
    }

    /**
     * 解析非流式响应中的 assistant 消息（content + reasoning）
     */
    private static StreamDelta parseFullResponseDelta(String body) {
        StreamDelta d = new StreamDelta();
        if (body == null || body.length() == 0) return d;
        try {
            org.json.JSONObject json = new org.json.JSONObject(body);
            org.json.JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                org.json.JSONObject first = choices.getJSONObject(0);
                org.json.JSONObject message = first.optJSONObject("message");
                if (message != null) {
                    d.content = safeOptString(message, "content");
                    d.reasoning = safeOptString(message, "reasoning_content");
                    if (d.content.length() == 0) {
                        d.content = safeOptString(message, "text");
                    }
                    return d;
                }
                // 兼容 text 字段
                d.content = safeOptString(first, "text");
            }
        } catch (Exception e) {
            // ignore
        }
        return d;
    }

    /**
     * 解析错误响应
     */
    private static String parseErrorMessage(String body) {
        if (body == null || body.length() == 0) return null;
        try {
            org.json.JSONObject json = new org.json.JSONObject(body);
            org.json.JSONObject err = json.optJSONObject("error");
            if (err != null) {
                String msg = safeOptString(err, "message");
                if (msg.length() > 0) return msg;
            }
            String msg = safeOptString(json, "message");
            if (msg.length() > 0) return msg;
        } catch (Exception e) {
            // 返回原始截断
            if (body.length() > 200) body = body.substring(0, 200);
            return body;
        }
        return body;
    }

    /**
     * 解析流式响应中的一行。
     *
     * @return null 表示 [DONE] 结束；非 null 的 StreamDelta 表示增量（content/reasoning 可能为空）
     */
    public static StreamDelta parseStreamLine(String line) {
        StreamDelta d = new StreamDelta();
        if (line == null) return d;
        line = line.trim();
        if (line.length() == 0) return d;
        if (!line.startsWith("data:")) return d;
        String data = line.substring(5).trim();
        if ("[DONE]".equals(data)) return null;  // 结束标记
        if (data.length() == 0) return d;
        try {
            org.json.JSONObject json = new org.json.JSONObject(data);
            org.json.JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                org.json.JSONObject first = choices.getJSONObject(0);
                org.json.JSONObject delta = first.optJSONObject("delta");
                if (delta == null) {
                    // 非流式格式回退
                    delta = first.optJSONObject("message");
                }
                if (delta != null) {
                    d.content = safeOptString(delta, "content");
                    d.reasoning = safeOptString(delta, "reasoning_content");
                    if (d.content.length() == 0) {
                        // 兼容 text 字段
                        d.content = safeOptString(delta, "text");
                    }
                }
                // 兼容旧格式：finish_reason 等
            }
        } catch (Exception e) {
            // ignore
        }
        return d;
    }

    /**
     * 安全读取 JSON 字符串字段。
     *
     * 关键：Android 的 JSONObject.optString(key, "") 在字段值是 JSON null 时
     * 会返回字符串 "null" 而非 ""，这会导致回复中出现大量 "null" 字符串。
     * 必须用 isNull + 显式 "null" 检查。
     */
    private static String safeOptString(org.json.JSONObject obj, String key) {
        if (obj == null) return "";
        if (obj.isNull(key)) return "";  // JSON null
        try {
            String s = obj.optString(key, "");
            if (s == null || "null".equals(s)) return "";
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    // ============ 工具方法 ============

    private static String jsonStr(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        // 手动拼接十六进制，避免 String.format 的 Locale 问题
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        while (hex.length() < 4) hex = "0" + hex;
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) d = 0.7;
        if (d == (long) d) {
            return String.valueOf((long) d);
        }
        // 保留 Java 默认的完整精度，避免截断导致温度等参数不生效
        String s = String.valueOf(d);
        // 去除尾部多余的 0（如 1.900000 变成 1.9），但保留有效数字
        if (s.indexOf('.') > 0) {
            while (s.length() > 1 && s.charAt(s.length() - 1) == '0') {
                s = s.substring(0, s.length() - 1);
            }
            // 如果最后剩下小数点也去掉（整数情况已经在上面处理）
            if (s.length() > 1 && s.charAt(s.length() - 1) == '.') {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    // ============ SSL 信任所有 ============

    private static SSLContext createTrustAllSSLContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, new TrustManager[]{new TrustAllManager()}, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            // TLSv1.2 不可用（Android 2.3 基本都会走到这里）
        }
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new TrustAllManager()}, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private static class TrustAllManager implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static class TrustAllHostVerifier implements HostnameVerifier {
        public boolean verify(String hostname, SSLSession session) { return true; }
    }
}
