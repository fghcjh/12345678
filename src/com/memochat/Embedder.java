package com.memochat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 向量化。默认走完全离线的字符 n-gram 有符号哈希嵌入（中文友好、零成本）；
 * 在设置里填了 OpenAI 兼容的 /embeddings 接口时优先用远程语义向量，失败自动回退本地。
 */
public class Embedder {

    public static final int LOCAL_DIM = 512;

    private final Settings settings;

    public Embedder(Settings s) {
        this.settings = s;
    }

    public float[] embed(String text) {
        if (settings.remoteEmbedding()) {
            try {
                float[] v = remote(text);
                if (v != null && v.length > 0) return v;
            } catch (Exception ignored) {
                // 远程不可用时静默回退，保证记忆功能不中断
            }
        }
        return local(text);
    }

    /** 离线嵌入：字符 unigram + 字符 bigram + ASCII 词，带符号哈希映射到固定维度。 */
    public static float[] local(String text) {
        float[] v = new float[LOCAL_DIM];
        if (text == null) return v;
        String t = text.toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return v;

        Map<String, Integer> tf = new HashMap<>();
        StringBuilder seg = new StringBuilder();
        List<String> segs = new ArrayList<>();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                seg.append(ch);
            } else {
                if (seg.length() > 0) {
                    segs.add(seg.toString());
                    seg = new StringBuilder();
                }
            }
        }
        if (seg.length() > 0) segs.add(seg.toString());

        for (String s : segs) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                bump(tf, "u:" + c);
                if (i + 1 < s.length()) bump(tf, "b:" + c + s.charAt(i + 1));
            }
            if (s.length() > 1 && isAscii(s)) bump(tf, "w:" + s);
        }
        if (tf.isEmpty()) return v;

        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            int h = mix(e.getKey().hashCode());
            int idx = (h & 0x7fffffff) % LOCAL_DIM;
            float sign = ((h >>> 16) & 1) == 0 ? 1f : -1f;
            String key = e.getKey();
            float typeWeight = key.startsWith("b:") ? 1.30f : key.startsWith("w:") ? 1.50f : 0.85f;
            float weight = (1f + (float) Math.log(e.getValue())) * typeWeight * sign;
            v[idx] += weight;
        }
        l2(v);
        return v;
    }

    private static void bump(Map<String, Integer> tf, String key) {
        Integer old = tf.get(key);
        tf.put(key, old == null ? 1 : old + 1);
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 127) return false;
        return true;
    }

    private static int mix(int h) {
        h ^= (h >>> 16);
        h *= 0x7feb352d;
        h ^= (h >>> 15);
        h *= 0x846ca68b;
        h ^= (h >>> 16);
        return h;
    }

    private static void l2(float[] v) {
        double n = 0;
        for (float f : v) n += (double) f * f;
        if (n <= 0) return;
        float inv = (float) (1.0 / Math.sqrt(n));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) return 0;
        return dot / Math.sqrt(na * nb);
    }

    private float[] remote(String text) throws Exception {
        String endpoint = settings.embeddingEndpoint();
        if (endpoint.isEmpty()) return null;

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(12000);
            c.setReadTimeout(25000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Bearer " + settings.embKey());

            JSONObject body = new JSONObject();
            body.put("model", settings.embModel());
            body.put("input", text);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String raw = readAll(is);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("embedding HTTP " + code + ": " + raw);
            }
            JSONObject o = new JSONObject(raw);
            JSONArray data = o.getJSONArray("data");
            JSONArray arr = data.getJSONObject(0).getJSONArray("embedding");
            float[] v = new float[arr.length()];
            for (int i = 0; i < v.length; i++) v[i] = (float) arr.getDouble(i);
            l2(v);
            return v;
        } finally {
            if (c != null) c.disconnect();
        }
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
}
