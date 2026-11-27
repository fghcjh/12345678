package com.memochat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 人格卡：角色设定 + 世界书。存成一段 JSON，进系统提示时按块拼接。 */
public class Persona {

    public String name = "忆聊";
    public String oneLiner = "";
    public String who = "";
    public String personality = "";
    public String worldview = "";
    public String mood = "";
    public String channels = "";
    public String schedule = "";
    public String examples = "";
    public String greeting = "";
    public String perTurn = "";

    /** 每条世界书：[关键词, 内容] */
    public final List<String[]> worldbook = new ArrayList<>();

    public static Persona parse(String json) {
        Persona c = new Persona();
        if (json == null || json.trim().isEmpty()) return c;
        try {
            JSONObject o = new JSONObject(json);
            c.name = clean(o.optString("name", "忆聊"), 24, "忆聊");
            c.oneLiner = clean(o.optString("oneLiner", ""), 200, "");
            c.who = clean(o.optString("who", ""), 2000, "");
            c.personality = clean(o.optString("personality", ""), 2000, "");
            c.worldview = clean(o.optString("worldview", ""), 1500, "");
            c.mood = clean(o.optString("mood", ""), 1500, "");
            c.channels = clean(o.optString("channels", ""), 200, "");
            c.schedule = clean(o.optString("schedule", ""), 800, "");
            c.examples = clean(o.optString("examples", ""), 2000, "");
            c.greeting = clean(o.optString("greeting", ""), 500, "");
            c.perTurn = clean(o.optString("perTurn", ""), 1000, "");
            JSONArray wb = o.optJSONArray("worldbook");
            if (wb != null) {
                for (int i = 0; i < wb.length() && c.worldbook.size() < 30; i++) {
                    JSONObject e = wb.optJSONObject(i);
                    if (e == null) continue;
                    String k = clean(e.optString("k", ""), 100, "");
                    String v = clean(e.optString("v", ""), 800, "");
                    if (!k.isEmpty() && !v.isEmpty()) c.worldbook.add(new String[]{k, v});
                }
            }
        } catch (Exception ignored) {
        }
        return c;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("oneLiner", oneLiner);
            o.put("who", who);
            o.put("personality", personality);
            o.put("worldview", worldview);
            o.put("mood", mood);
            o.put("channels", channels);
            o.put("schedule", schedule);
            o.put("examples", examples);
            o.put("greeting", greeting);
            o.put("perTurn", perTurn);
            JSONArray arr = new JSONArray();
            for (String[] e : worldbook) {
                JSONObject x = new JSONObject();
                x.put("k", e[0]);
                x.put("v", e[1]);
                arr.put(x);
            }
            o.put("worldbook", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    public boolean isEmpty() {
        return who.isEmpty() && personality.isEmpty() && worldview.isEmpty()
                && mood.isEmpty() && examples.isEmpty() && perTurn.isEmpty()
                && oneLiner.isEmpty() && worldbook.isEmpty();
    }

    /** 最近几轮文本里命中关键词的世界书条目，最多 3 条。 */
    public String matchWorldbook(String text, int max) {
        if (text == null || text.isEmpty() || worldbook.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String[] e : worldbook) {
            if (used >= max) break;
            String[] keys = e[0].split("[,，、;；/|\\s]+");
            boolean hit = false;
            for (String k : keys) {
                if (k.length() >= 1 && text.contains(k)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) continue;
            sb.append("【").append(e[0]).append("】\n").append(e[1]).append('\n');
            used++;
        }
        return sb.toString();
    }

    private static String clean(String s, int max, String def) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return def;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
