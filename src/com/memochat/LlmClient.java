package com.memochat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** OpenAI 兼容的 Chat Completions 客户端：流式与非流式。 */
public class LlmClient {

    public static class ApiError extends Exception {
        public final int httpCode;
        public ApiError(int httpCode, String message) {
            super(message);
            this.httpCode = httpCode;
        }
    }

    public interface StreamListener {
        void onDelta(String text);
        void onReasoning(String text);
    }

    public static class Usage {
        public int in;
        public int out;
    }

    private final Settings settings;

    public LlmClient(Settings s) {
        this.settings = s;
    }

    public boolean hasKey() {
        return !settings.apiKey().isEmpty();
    }

    /* ---------------- streaming chat ---------------- */

    public String stream(List<JSONObject> messages, double temperature, int maxTokens,
                         StreamListener listener, AtomicBoolean cancel, Usage usageOut) throws Exception {
        requireKey();
        HttpURLConnection c = null;
        try {
            c = open(settings.chatEndpoint(), settings.apiKey());
            c.setReadTimeout(180000);

            JSONObject body = baseBody(messages, temperature, maxTokens);
            body.put("stream", true);
            JSONObject so = new JSONObject();
            so.put("include_usage", true);
            body.put("stream_options", so);
            write(c, body);

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                String raw = readAll(c.getErrorStream());
                throw new ApiError(code, errorMessage(code, raw));
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line;
            StringBuilder full = new StringBuilder();
            while ((line = br.readLine()) != null) {
                if (cancel != null && cancel.get()) break;
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) continue;
                if ("[DONE]".equals(payload)) break;
                JSONObject chunk;
                try {
                    chunk = new JSONObject(payload);
                } catch (Exception e) {
                    continue;
                }
                if (chunk.has("usage") && !chunk.isNull("usage")) {
                    JSONObject u = chunk.optJSONObject("usage");
                    if (u != null) {
                        usageOut.in = u.optInt("prompt_tokens", usageOut.in);
                        usageOut.out = u.optInt("completion_tokens", usageOut.out);
                    }
                }
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                if (delta == null) continue;
                String reasoning = delta.isNull("reasoning_content") ? "" : delta.optString("reasoning_content", "");
                if (!reasoning.isEmpty() && listener != null) listener.onReasoning(reasoning);
                String content = delta.isNull("content") ? "" : delta.optString("content", "");
                if (!content.isEmpty()) {
                    full.append(content);
                    if (listener != null) listener.onDelta(content);
                }
            }
            br.close();
            return full.toString();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ---------------- one-shot completion ---------------- */

    /** 单轮 system+user 的便捷版本 */
    public String complete(String system, String user, double temperature, int maxTokens) throws Exception {
        List<JSONObject> msgs = new ArrayList<>();
        JSONObject a = new JSONObject();
        a.put("role", "system");
        a.put("content", system == null ? "" : system);
        msgs.add(a);
        JSONObject b = new JSONObject();
        b.put("role", "user");
        b.put("content", user == null ? "" : user);
        msgs.add(b);
        return complete(msgs, temperature, maxTokens);
    }

    public String complete(List<JSONObject> messages, double temperature, int maxTokens) throws Exception {
        requireKey();
        HttpURLConnection c = null;
        try {
            c = open(settings.chatEndpoint(), settings.apiKey());
            c.setReadTimeout(120000);
            JSONObject body = baseBody(messages, temperature, maxTokens);
            body.put("stream", false);
            write(c, body);

            int code = c.getResponseCode();
            String raw = code >= 200 && code < 300 ? readAll(c.getInputStream()) : readAll(c.getErrorStream());
            if (code < 200 || code >= 300) throw new ApiError(code, errorMessage(code, raw));

            JSONObject o = new JSONObject(raw);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return "";
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            return msg == null ? "" : msg.optString("content", "");
        } finally {
            if (c != null) c.disconnect();
        }
    }


    /* ---------------- 视觉识别 ---------------- */

    public String vision(String systemPrompt, String userPrompt, String base64Jpeg) throws Exception {
        if (!settings.visionEnabled()) {
            throw new ApiError(0, "还没配置视觉模型，请在设置里填写视觉接口。");
        }
        HttpURLConnection c = null;
        try {
            c = open(settings.visionEndpoint(), settings.visionKey());
            c.setReadTimeout(150000);

            JSONObject body = new JSONObject();
            body.put("model", settings.visionModel());
            body.put("temperature", 0.2);
            body.put("max_tokens", 1100);

            JSONArray content = new JSONArray();

            JSONObject t = new JSONObject();
            t.put("type", "text");
            t.put("text", systemPrompt + "\n\n" + userPrompt);
            content.put(t);

            if (base64Jpeg != null && !base64Jpeg.isEmpty()) {
                JSONObject img = new JSONObject();
                img.put("type", "image_url");
                JSONObject u = new JSONObject();
                u.put("url", "data:image/jpeg;base64," + base64Jpeg);
                img.put("image_url", u);
                content.put(img);
            }

            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", content);
            JSONArray msgs = new JSONArray();
            msgs.put(m);
            body.put("messages", msgs);

            write(c, body);

            int code = c.getResponseCode();
            String raw = code >= 200 && code < 300 ? readAll(c.getInputStream()) : readAll(c.getErrorStream());
            if (code < 200 || code >= 300) throw new ApiError(code, errorMessage(code, raw));

            JSONObject o = new JSONObject(raw);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return "";
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            return msg == null ? "" : msg.optString("content", "");
        } finally {
            if (c != null) c.disconnect();
        }
    }


    /* ---------------- 语音转文字 ---------------- */

    public String transcribe(byte[] wav) throws Exception {
        requireKey();
        if (wav == null || wav.length < 1000) throw new ApiError(0, "没录到声音，再说一次？");
        HttpURLConnection c = null;
        try {
            String boundary = "----memochat" + System.nanoTime();
            c = (HttpURLConnection) new URL(settings.asrEndpoint()).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(90000);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setRequestProperty("Authorization", "Bearer " + settings.asrKey());
            c.setRequestProperty("Accept-Encoding", "identity");

            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n";
            String tail = "\r\n--" + boundary + "--\r\n";
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (settings.customAsr()) {
                String modelPart = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                        + settings.asrModel() + "\r\n";
                body.write(modelPart.getBytes(StandardCharsets.UTF_8));
            }
            body.write(head.getBytes(StandardCharsets.UTF_8));
            body.write(wav);
            body.write(tail.getBytes(StandardCharsets.UTF_8));
            byte[] payload = body.toByteArray();

            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            String raw = code >= 200 && code < 300 ? readAll(c.getInputStream()) : readAll(c.getErrorStream());
            if (code < 200 || code >= 300) throw new ApiError(code, errorMessage(code, raw));
            JSONObject o = new JSONObject(raw);
            return o.optString("text", "").trim();
        } finally {
            if (c != null) c.disconnect();
        }
    }


    /* ---------------- 文字转语音（OpenAI 兼容） ---------------- */

    public byte[] speech(String text) throws Exception {
        if (!settings.customTts()) throw new ApiError(0, "没有配置语音合成接口");
        HttpURLConnection c = null;
        try {
            c = open(settings.ttsEndpoint(), settings.ttsKey());
            c.setReadTimeout(60000);
            c.setRequestProperty("Accept", "audio/*");
            JSONObject body = new JSONObject();
            body.put("model", settings.ttsModel());
            body.put("input", text);
            String voice = settings.ttsVoice();
            if (!voice.isEmpty()) body.put("voice", voice);
            write(c, body);

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                String raw = readAll(c.getErrorStream());
                throw new ApiError(code, errorMessage(code, raw));
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ---------------- helpers ---------------- */

    private void requireKey() throws ApiError {
        if (settings.apiKey().isEmpty()) {
            throw new ApiError(0, "还没有填写 API Key，请到设置里填一个 DeepSeek Key。");
        }
    }

    private JSONObject baseBody(List<JSONObject> messages, double temperature, int maxTokens) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", settings.model());
        body.put("temperature", Math.max(0, Math.min(2, temperature)));
        body.put("max_tokens", Math.max(64, Math.min(8192, maxTokens)));
        JSONArray arr = new JSONArray();
        for (JSONObject m : messages) arr.put(m);
        body.put("messages", arr);
        return body;
    }

    private HttpURLConnection open(String url, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "text/event-stream, application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Cache-Control", "no-cache");
        return c;
    }

    private void write(HttpURLConnection c, JSONObject body) throws Exception {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        OutputStream os = c.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    private static String errorMessage(int code, String raw) {
        String detail = "";
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject err = o.optJSONObject("error");
            if (err != null) detail = err.optString("message", "");
        } catch (Exception ignored) {
        }
        if (detail.isEmpty()) detail = raw == null ? "" : raw.trim();
        if (detail.contains("supported API model names")) {
            return "模型名不对：现在只认 deepseek-flash 和 deepseek-v4-pro，"
                    + "不要填 DeepSeek-V4.1-Flash 这种版本号。设置 → 模型 改一下即可。";
        }
        if (detail.length() > 180) detail = detail.substring(0, 180) + "…";
        switch (code) {
            case 401:
                return "API Key 无效或已失效（401）。" + (detail.isEmpty() ? "" : " " + detail);
            case 402:
                return "账户余额不足（402）。" + (detail.isEmpty() ? "" : " " + detail);
            case 403:
                return "没有访问权限（403）。" + (detail.isEmpty() ? "" : " " + detail);
            case 404:
                return "接口地址或模型名不存在（404）。检查 Base URL 和模型名。";
            case 429:
                return "请求太频繁或额度用尽（429），稍等再试。";
            default:
                if (code >= 500) return "模型服务暂时不可用（" + code + "），稍后再试。";
                return "请求失败（" + code + "）" + (detail.isEmpty() ? "" : "：" + detail);
        }
    }
}
