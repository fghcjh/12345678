package com.memochat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 插件市场：格式就是一份 JSON 目录，内置在 assets/market.json，
 * 也可以填一个远程地址（同样 JSON 格式）来换成自己的市场。
 */
public class PluginMarket {

    private final Context ctx;
    private final Settings settings;

    public PluginMarket(Context ctx, Settings settings) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
    }

    /** 内置目录 + （可选）远程目录合并，远程同 id 覆盖内置 */
    public JSONArray catalog() {
        JSONArray out = new JSONArray();
        try {
            JSONObject local = new JSONObject(localRaw());
            JSONArray arr = local.optJSONArray("plugins");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) out.put(arr.getJSONObject(i));
            }
        } catch (Exception ignored) {
        }

        String remote = remoteRaw();
        if (!remote.isEmpty()) {
            try {
                JSONArray arr = new JSONObject(remote).optJSONArray("plugins");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String id = o.optString("id", "");
                        boolean replaced = false;
                        for (int j = 0; j < out.length(); j++) {
                            if (id.equals(out.getJSONObject(j).optString("id", ""))) {
                                out.put(j, o);
                                replaced = true;
                                break;
                            }
                        }
                        if (!replaced) out.put(o);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private String localRaw() {
        try {
            InputStream in = ctx.getAssets().open("market.json");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "{\"plugins\":[]}";
        }
    }

    /** 远程目录：有缓存先给缓存，取不到就空着 */
    private String remoteRaw() {
        String url = settings.marketUrl();
        if (url == null || url.trim().isEmpty()) return "";
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return settings.marketCache();
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String raw = out.toString("UTF-8");
            if (raw.length() > 400_000) return settings.marketCache();
            settings.saveMarketCache(raw);
            return raw;
        } catch (Exception e) {
            return settings.marketCache();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public JSONObject find(String id) {
        JSONArray arr = catalog();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id", ""))) return o;
        }
        return null;
    }

    /* ---------------- 安装 / 卸载 ---------------- */

    /** 返回 {ok:true, message:"...", character:{...}} */
    public JSONObject install(String id) {
        JSONObject out = new JSONObject();
        try {
            JSONObject p = find(id);
            if (p == null) {
                out.put("ok", false);
                out.put("message", "目录里没有这个插件");
                return out;
            }
            String type = p.optString("type", "");
            JSONArray installed = settings.installedPlugins();

            if ("persona".equals(type)) {
                JSONObject src = p.optJSONObject("persona");
                if (src == null) throw new Exception("这个插件没有角色内容");
                // 覆盖前先备份一次原人格卡，卸载时能还原
                if (!settings.hasPersonaBackup()) settings.savePersonaBackup(settings.characterJson());
                Persona cur = Persona.parse(settings.characterJson());
                JSONObject merged = cur.toJson();
                for (String k : new String[]{"name", "oneLiner", "who", "personality", "worldview",
                        "mood", "channels", "schedule", "examples", "greeting", "perTurn"}) {
                    if (src.has(k)) merged.put(k, src.optString(k, ""));
                }
                if (src.has("worldbook")) merged.put("worldbook", src.getJSONArray("worldbook"));
                settings.saveCharacter(merged.toString());
                addInstalled(installed, id, type);
                out.put("ok", true);
                out.put("message", "已换成「" + p.optString("name", "") + "」的人格卡");
            } else if ("worldbook".equals(type)) {
                JSONArray entries = p.optJSONArray("entries");
                if (entries == null) throw new Exception("这个插件没有世界书内容");
                Persona cur = Persona.parse(settings.characterJson());
                JSONObject merged = cur.toJson();
                JSONArray wb = merged.optJSONArray("worldbook");
                if (wb == null) wb = new JSONArray();
                int added = 0;
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.getJSONObject(i);
                    String k = e.optString("k", "");
                    boolean dup = false;
                    for (int j = 0; j < wb.length(); j++) {
                        if (k.equals(wb.getJSONObject(j).optString("k", ""))) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        wb.put(e);
                        added++;
                    }
                }
                if (added == 0) {
                    out.put("ok", false);
                    out.put("message", "这些设定已经在了");
                    return out;
                }
                merged.put("worldbook", wb);
                settings.saveCharacter(merged.toString());
                addInstalled(installed, id, type);
                out.put("ok", true);
                out.put("message", "加了 " + added + " 条世界书设定");
            } else {
                out.put("ok", false);
                out.put("message", "这个版本还装不了类型为 " + type + " 的插件");
                return out;
            }

            settings.saveInstalledPlugins(installed);
            out.put("character", Persona.parse(settings.characterJson()).toJson());
            return out;
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("message", e.getMessage() == null ? "安装失败" : e.getMessage());
            } catch (Exception ignored) {
            }
            return out;
        }
    }

    public JSONObject uninstall(String id) {
        JSONObject out = new JSONObject();
        try {
            JSONObject p = find(id);
            String type = p == null ? "" : p.optString("type", "");
            JSONArray installed = settings.installedPlugins();
            JSONArray next = new JSONArray();
            for (int i = 0; i < installed.length(); i++) {
                String cur = installed.optString(i, "");
                if (!id.equals(cur)) next.put(cur);
            }
            settings.saveInstalledPlugins(next);

            if ("worldbook".equals(type) && p != null) {
                JSONArray entries = p.optJSONArray("entries");
                Persona cur = Persona.parse(settings.characterJson());
                JSONObject merged = cur.toJson();
                JSONArray wb = merged.optJSONArray("worldbook");
                if (wb != null && entries != null) {
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < wb.length(); i++) {
                        JSONObject e = wb.getJSONObject(i);
                        String k = e.optString("k", "");
                        boolean drop = false;
                        for (int j = 0; j < entries.length(); j++) {
                            if (k.equals(entries.getJSONObject(j).optString("k", ""))) {
                                drop = true;
                                break;
                            }
                        }
                        if (!drop) kept.put(e);
                    }
                    merged.put("worldbook", kept);
                    settings.saveCharacter(merged.toString());
                }
                out.put("message", "世界书设定已移除");
            } else if ("persona".equals(type)) {
                if (settings.hasPersonaBackup()) {
                    settings.saveCharacter(settings.personaBackup());
                    settings.clearPersonaBackup();
                    out.put("message", "已恢复安装前的角色卡");
                } else {
                    out.put("message", "没有备份可还原，角色卡保持现在这样");
                }
            }
            out.put("ok", true);
            out.put("character", Persona.parse(settings.characterJson()).toJson());
            return out;
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("message", "卸载失败");
            } catch (Exception ignored) {
            }
            return out;
        }
    }

    private void addInstalled(JSONArray arr, String id, String type) {
        boolean has = false;
        for (int i = 0; i < arr.length(); i++) {
            if (id.equals(arr.optString(i, ""))) {
                has = true;
                break;
            }
        }
        if (!has) arr.put(id);
    }
}
