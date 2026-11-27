package com.memochat;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Locale;

/** 全局配置：模型接口、记忆策略、人格卡。 */
public class Settings {

    private final SharedPreferences sp;

    public Settings(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("memochat_settings", Context.MODE_PRIVATE);
    }

    public String apiKey() { return sp.getString("apiKey", "").trim(); }
    public String baseUrl() {
        String b = sp.getString("baseUrl", "https://api.deepseek.com").trim();
        if (b.isEmpty()) b = "https://api.deepseek.com";
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
    public String model() {
        String m = sp.getString("model", "deepseek-chat").trim();
        return m.isEmpty() ? "deepseek-chat" : canonical(m);
    }
    public double temperature() { return sp.getFloat("temperature", 1.0f); }
    public int maxTokens() { return sp.getInt("maxTokens", 2048); }
    public boolean memoryEnabled() { return sp.getBoolean("memoryEnabled", true); }
    public int extractEvery() { return Math.max(1, sp.getInt("extractEvery", 1)); }
    public int topK() { return Math.max(2, Math.min(12, sp.getInt("topK", 6))); }

    public String embBase() {
        String b = sp.getString("embBase", "").trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
    public String embModel() { return sp.getString("embModel", "").trim(); }
    public String embKey() {
        String k = sp.getString("embKey", "").trim();
        return k.isEmpty() ? apiKey() : k;
    }
    public boolean remoteEmbedding() { return !embBase().isEmpty() && !embModel().isEmpty(); }

    /* ---------------- 视觉模型 ---------------- */

    public String visionBase() {
        String b = sp.getString("visionBase", "").trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        // 没单独配时，DeepSeek 用户直接复用聊天接口（deepseek-flash 本身就支持看图）
        if (b.isEmpty() && baseUrl().contains("deepseek.com")) return baseUrl();
        return b;
    }
    public String visionModel() {
        String m = sp.getString("visionModel", "").trim();
        if (m.isEmpty() && baseUrl().contains("deepseek.com")) return "deepseek-flash";
        String c = canonical(m);
        // deepseek-v4-pro 不支持图片输入，视觉请求必须落到 flash
        if (c.equals("deepseek-v4-pro")) return "deepseek-flash";
        return c;
    }

    /** 容错：把「DeepSeek-V4.1-Flash」这类版本名/Eh 写法折回接口真正认的模型名。 */
    public static String canonical(String raw) {
        if (raw == null) return "";
        String m = raw.trim();
        if (m.isEmpty()) return m;
        String l = m.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "-");
        if (l.startsWith("deepseek-ai/")) l = l.substring("deepseek-ai/".length());
        if (l.contains("v4") && (l.contains("flash") || l.contains("vision"))) return "deepseek-flash";
        if (l.contains("v4") && l.contains("pro")) return "deepseek-v4-pro";
        if (l.equals("deepseek-v4-flash") || l.equals("deepseek-v4-flash-vision-exp")) return "deepseek-flash";
        if (l.equals("deepseek-flash")) return "deepseek-flash";
        return m;
    }
    public String visionKey() {
        String k = sp.getString("visionKey", "").trim();
        return k.isEmpty() ? apiKey() : k;
    }
    public boolean visionEnabled() { return !visionBase().isEmpty() && !visionModel().isEmpty(); }

    /* ---------------- 语音 ---------------- */

    /* ---------------- 插件市场 ---------------- */

    public String marketUrl() { return sp.getString("marketUrl", "").trim(); }
    public void saveMarketUrl(String url) { sp.edit().putString("marketUrl", url == null ? "" : url.trim()).apply(); }
    public String marketCache() { return sp.getString("marketCache", ""); }
    public void saveMarketCache(String raw) { sp.edit().putString("marketCache", raw == null ? "" : raw).apply(); }

    public org.json.JSONArray installedPlugins() {
        try {
            String raw = sp.getString("installedPlugins", "");
            if (raw == null || raw.isEmpty()) return new org.json.JSONArray();
            return new org.json.JSONArray(raw);
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    public void saveInstalledPlugins(org.json.JSONArray arr) {
        sp.edit().putString("installedPlugins", arr == null ? "[]" : arr.toString()).apply();
    }

    public boolean hasPersonaBackup() { return !sp.getString("personaBackup", "").isEmpty(); }
    public void savePersonaBackup(String json) { sp.edit().putString("personaBackup", json == null ? "" : json).apply(); }
    public String personaBackup() { return sp.getString("personaBackup", ""); }
    public void clearPersonaBackup() { sp.edit().remove("personaBackup").apply(); }

    /* ---------------- 成长 & 主动开口 ---------------- */

    public boolean growEnabled() { return sp.getBoolean("growEnabled", true); }
    public boolean growAutoApply() { return sp.getBoolean("growAutoApply", true); }
    public void setGrow(boolean enabled, boolean autoApply) {
        sp.edit().putBoolean("growEnabled", enabled).putBoolean("growAutoApply", autoApply).apply();
    }

    public boolean proactiveOn() { return sp.getBoolean("proactiveOn", false); }
    public int proactiveMax() { return Math.max(1, Math.min(50, sp.getInt("proactiveMax", 10))); }
    public int proactiveStart() { return Math.max(0, Math.min(23, sp.getInt("proactiveStart", 8))); }
    public int proactiveEnd() { return Math.max(1, Math.min(24, sp.getInt("proactiveEnd", 24))); }
    public int proactiveIdle() { return Math.max(1, Math.min(72, sp.getInt("proactiveIdle", 6))); }

    public void setProactive(boolean on, int max, int start, int end, int idle) {
        sp.edit().putBoolean("proactiveOn", on)
                .putInt("proactiveMax", Math.max(1, Math.min(50, max)))
                .putInt("proactiveStart", Math.max(0, Math.min(23, start)))
                .putInt("proactiveEnd", Math.max(1, Math.min(24, end)))
                .putInt("proactiveIdle", Math.max(1, Math.min(72, idle)))
                .apply();
    }

    public String scheduleJson() { return sp.getString("scheduleJson", ""); }
    public void saveSchedule(String json) { sp.edit().putString("scheduleJson", json == null ? "" : json).apply(); }

    /** 今天已经主动说过几次 */
    public int proactiveCountToday() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new java.util.Date());
        if (!today.equals(sp.getString("proactiveDay", ""))) return 0;
        return sp.getInt("proactiveCount", 0);
    }

    public void bumpProactive() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new java.util.Date());
        int n = today.equals(sp.getString("proactiveDay", "")) ? sp.getInt("proactiveCount", 0) : 0;
        sp.edit().putString("proactiveDay", today).putInt("proactiveCount", n + 1).apply();
    }

    /* ---------------- 虚拟形象 ---------------- */

    public String avatarJson() { return sp.getString("avatarJson", ""); }
    public void saveAvatar(String json) {
        sp.edit().putString("avatarJson", json == null ? "" : json).apply();
    }

    public boolean autoSpeak() { return sp.getBoolean("autoSpeak", false); }

    public String asrBase() {
        String b = sp.getString("asrBase", "").trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
    public String asrKey() {
        String k = sp.getString("asrKey", "").trim();
        return k.isEmpty() ? apiKey() : k;
    }
    public String asrModel() { return sp.getString("asrModel", "").trim(); }
    public int asrLimit() { return Math.max(1, sp.getInt("asrLimit", 200)); }

    public String ttsBase() {
        String b = sp.getString("ttsBase", "").trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
    public String ttsKey() {
        String k = sp.getString("ttsKey", "").trim();
        return k.isEmpty() ? apiKey() : k;
    }
    public String ttsModel() { return sp.getString("ttsModel", "").trim(); }
    public String ttsVoice() { return sp.getString("ttsVoice", "alloy").trim(); }
    public int ttsLimit() { return Math.max(1, sp.getInt("ttsLimit", 200)); }

    /** 配了 Base + 模型就走 OpenAI 兼容的语音接口，否则用内置方案。 */
    public boolean customAsr() { return !asrBase().isEmpty() && !asrModel().isEmpty(); }
    public boolean customTts() { return !ttsBase().isEmpty() && !ttsModel().isEmpty(); }

    public String asrEndpoint() {
        String b = asrBase();
        if (b.isEmpty()) return chatEndpoint().replace("/chat/completions", "/tools/asr");
        if (b.endsWith("/audio/transcriptions")) return b;
        if (b.matches(".*/v\\d+$")) return b + "/audio/transcriptions";
        return b + "/v1/audio/transcriptions";
    }

    public String ttsEndpoint() {
        String b = ttsBase();
        if (b.endsWith("/audio/speech")) return b;
        if (b.matches(".*/v\\d+$")) return b + "/audio/speech";
        return b + "/v1/audio/speech";
    }

    private static String todayKey() {
        return new java.text.SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new java.util.Date());
    }

    public int asrUsedToday() {
        return todayKey().equals(sp.getString("asrDate", "")) ? sp.getInt("asrUsed", 0) : 0;
    }

    public int ttsUsedToday() {
        return todayKey().equals(sp.getString("ttsDate", "")) ? sp.getInt("ttsUsed", 0) : 0;
    }

    public boolean asrAllowed() { return asrUsedToday() < asrLimit(); }
    public boolean ttsAllowed() { return ttsUsedToday() < ttsLimit(); }

    public void bumpAsr() {
        sp.edit().putString("asrDate", todayKey()).putInt("asrUsed", asrUsedToday() + 1).apply();
    }

    public void bumpTts() {
        sp.edit().putString("ttsDate", todayKey()).putInt("ttsUsed", ttsUsedToday() + 1).apply();
    }

    /** 启动时自愈：纠正模型名、为 DeepSeek 用户补齐视觉配置。 */
    public void ensureDefaults() {
        String rawModel = sp.getString("model", "").trim();
        if (!rawModel.isEmpty()) {
            String c = canonical(rawModel);
            if (!c.equals(rawModel)) {
                sp.edit().putString("model", c).apply();
            }
        }
        if (baseUrl().contains("deepseek.com")) {
            boolean changed = false;
            if (sp.getString("visionModel", "").trim().isEmpty()) {
                sp.edit().putString("visionModel", "deepseek-flash").apply();
                changed = true;
            }
            if (sp.getString("visionBase", "").trim().isEmpty()) {
                sp.edit().putString("visionBase", baseUrl()).apply();
                changed = true;
            }
            if (changed) {
                // 视觉 Key 留空即可，回退到聊天 Key
            }
        }
    }

    /* ---------------- 人格卡 ---------------- */

    public String characterJson() { return sp.getString("character", ""); }

    public void saveCharacter(String json) {
        sp.edit().putString("character", json).apply();
    }

    /* ---------------- 保存 ---------------- */

    public void saveFromJson(JSONObject o) {
        sp.edit()
                .putString("apiKey", o.optString("apiKey", apiKey()).trim())
                .putString("baseUrl", o.optString("baseUrl", baseUrl()).trim())
                .putString("model", o.optString("model", model()).trim())
                .putFloat("temperature", (float) o.optDouble("temperature", temperature()))
                .putInt("maxTokens", o.optInt("maxTokens", maxTokens()))
                .putBoolean("memoryEnabled", o.optBoolean("memoryEnabled", memoryEnabled()))
                .putInt("extractEvery", Math.max(1, o.optInt("extractEvery", extractEvery())))
                .putInt("topK", Math.max(2, Math.min(12, o.optInt("topK", topK()))))
                .putString("embBase", o.optString("embBase", embBase()).trim())
                .putString("embModel", o.optString("embModel", embModel()).trim())
                .putString("embKey", o.optString("embKey", "").trim())
                .putString("visionBase", o.optString("visionBase", "").trim())
                .putString("visionModel", o.optString("visionModel", "").trim())
                .putString("visionKey", o.optString("visionKey", "").trim())
                .putBoolean("autoSpeak", o.optBoolean("autoSpeak", autoSpeak()))
                .putString("asrBase", o.optString("asrBase", "").trim())
                .putString("asrKey", o.optString("asrKey", "").trim())
                .putString("asrModel", o.optString("asrModel", "").trim())
                .putInt("asrLimit", Math.max(1, o.optInt("asrLimit", asrLimit())))
                .putString("ttsBase", o.optString("ttsBase", "").trim())
                .putString("ttsKey", o.optString("ttsKey", "").trim())
                .putString("ttsModel", o.optString("ttsModel", "").trim())
                .putString("ttsVoice", o.optString("ttsVoice", ttsVoice()).trim())
                .putInt("ttsLimit", Math.max(1, o.optInt("ttsLimit", ttsLimit())))
                .apply();
    }

    /** 聊天补全地址：兼容只填域名、带 /v1、以及完整地址三种写法。 */
    public String chatEndpoint() {
        String b = baseUrl();
        if (b.endsWith("/chat/completions")) return b;
        if (b.matches(".*/v\\d+$")) return b + "/chat/completions";
        return b + "/v1/chat/completions";
    }

    public String visionEndpoint() {
        String b = visionBase();
        if (b.isEmpty()) return "";
        if (b.endsWith("/chat/completions")) return b;
        if (b.matches(".*/v\\d+$")) return b + "/chat/completions";
        return b + "/v1/chat/completions";
    }

    public String embeddingEndpoint() {
        String b = embBase();
        if (b.isEmpty()) return "";
        if (b.endsWith("/embeddings")) return b;
        if (b.matches(".*/v\\d+$")) return b + "/embeddings";
        return b + "/v1/embeddings";
    }
}
