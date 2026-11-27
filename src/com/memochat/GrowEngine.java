package com.memochat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 成长（她慢慢长出来的性格/习惯）和主动开口（按作息主动说一句）。
 * 都用主模型跑，包在后台线程里调用。
 */
public class GrowEngine {

    private final Store store;
    private final Settings settings;
    private final LlmClient llm;

    public GrowEngine(Store store, Settings settings, LlmClient llm) {
        this.store = store;
        this.settings = settings;
        this.llm = llm;
    }

    /* ==================== 成长 ==================== */

    public JSONObject extract() throws Exception {
        Persona p = Persona.parse(settings.characterJson());
        List<Store.Session> ss = store.sessions();
        if (ss.isEmpty()) throw new Exception("还没有对话");
        String sid = ss.get(0).id;
        List<Store.Msg> msgs = store.messages(sid, 200);
        if (msgs.size() < 4) throw new Exception("聊得还太少");

        int from = Math.max(0, msgs.size() - 10);
        StringBuilder talk = new StringBuilder();
        for (int i = from; i < msgs.size(); i++) {
            Store.Msg m = msgs.get(i);
            String who = "user".equals(m.role) ? "对方" : p.name;
            String text = m.content == null ? "" : m.content.trim();
            if (text.length() > 120) text = text.substring(0, 120);
            if (text.isEmpty()) continue;
            talk.append(who).append("：").append(text).append('\n');
        }

        List<Store.Trait> exist = store.traits();
        StringBuilder have = new StringBuilder();
        for (Store.Trait t : exist) {
            have.append("- ").append(t.content).append('\n');
        }

        String sys = "你是角色成长观察者，只输出 JSON，不解释。";
        String user = "角色：「" + p.name + "」。" +
                (p.personality.isEmpty() ? "" : "她的性格是：" + p.personality + "\n") +
                "下面是她和对方的最近对话。判断她有没有**因为这段相处**新长出来一点稳定的东西——" +
                "比如新的习惯、新的偏好、对某件事态度的变化、和对方关系上的小变化。\n" +
                "要求：\n" +
                "1) 只输出 JSON 数组，最多 2 条，没有就输出 []；\n" +
                "2) 每条格式 {\"kind\":\"性格|习惯|偏好|关系\",\"text\":\"以她第一人称写，不超过 20 字\"}；\n" +
                "3) 宁缺勿滥，别写空话套话，别复述对话内容；\n" +
                "4) 已经有的（下面列出）不要重复或换个说法再写一遍。\n" +
                (have.length() > 0 ? "已经有了：\n" + have : "现在已经有的：无\n") +
                "对话：\n" + talk;

        String raw = llm.complete(sys, user, 0.6, 400);
        JSONArray cands = parseArray(raw);
        int added = 0, bumped = 0, activated = 0;

        for (int i = 0; i < cands.length() && i < 2; i++) {
            JSONObject o = cands.optJSONObject(i);
            if (o == null) continue;
            String text = o.optString("text", "").trim();
            if (text.isEmpty() || text.length() > 40) continue;
            String kind = o.optString("kind", "性格").trim();
            Store.Trait same = findSimilar(exist, text);
            if (same != null) {
                int ev = store.bumpTrait(same.id);
                bumped++;
                if (!same.active && ev >= 2 && settings.growAutoApply()) {
                    store.setTraitActive(same.id, true);
                    activated++;
                }
            } else {
                store.addTrait(kind, text, "auto", false);
                added++;
            }
        }

        JSONObject out = new JSONObject();
        out.put("added", added);
        out.put("bumped", bumped);
        out.put("activated", activated);
        out.put("traits", traitsJson());
        return out;
    }

    /** 只是用字面重合判断“是不是同一条”，够用了，省一次 embedding */
    private Store.Trait findSimilar(List<Store.Trait> list, String text) {
        String a = norm(text);
        for (Store.Trait t : list) {
            String b = norm(t.content);
            if (b.isEmpty()) continue;
            if (a.equals(b) || a.contains(b) || b.contains(a)) return t;
            int hit = 0;
            for (int i = 0; i < a.length(); i++) {
                if (b.indexOf(a.charAt(i)) >= 0) hit++;
            }
            if (a.length() >= 4 && hit >= a.length() * 2 / 3) return t;
        }
        return null;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s，。！？、,.!?~…：:；;“”\"'（）()]", "");
    }

    private JSONArray parseArray(String raw) {
        if (raw == null) return new JSONArray();
        int a = raw.indexOf('[');
        int b = raw.lastIndexOf(']');
        if (a < 0 || b <= a) return new JSONArray();
        try {
            return new JSONArray(raw.substring(a, b + 1));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public JSONArray traitsJson() {
        JSONArray arr = new JSONArray();
        for (Store.Trait t : store.traits()) {
            JSONObject o = new JSONObject();
            safe(o, "id", t.id);
            safe(o, "kind", t.kind);
            safe(o, "content", t.content);
            safe(o, "source", t.source);
            safe(o, "evidence", t.evidence);
            safe(o, "active", t.active);
            arr.put(o);
        }
        return arr;
    }

    /* ==================== 作息表 & 主动开口 ==================== */

    public JSONArray schedule() {
        try {
            String raw = settings.scheduleJson();
            if (raw != null && !raw.isEmpty()) return new JSONArray(raw);
        } catch (Exception ignored) {
        }
        return defaultSchedule();
    }

    private JSONArray defaultSchedule() {
        Persona p = Persona.parse(settings.characterJson());
        JSONArray arr = new JSONArray();
        arr.put(block(0, 7, "安睡休息"));
        arr.put(block(7, 12, p.schedule.isEmpty() ? "醒着，做自己的事" : "清醒着，处理日常"));
        arr.put(block(12, 18, "白天，忙自己的事"));
        arr.put(block(18, 24, "晚上，安静下来"));
        return arr;
    }

    private JSONObject block(int from, int to, String text) {
        JSONObject o = new JSONObject();
        safe(o, "from", from);
        safe(o, "to", to);
        safe(o, "text", text);
        return o;
    }

    /** 当前时段她应该在做什么 */
    public String currentActivity(int hour) {
        JSONArray arr = schedule();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int from = o.optInt("from", 0);
            int to = o.optInt("to", 24);
            if (hour >= from && hour < to) return o.optString("text", "");
        }
        return "";
    }

    /** 让模型把作息表写成 4 个时段（按人格卡的作息和性格） */
    public JSONArray genSchedule() throws Exception {
        Persona p = Persona.parse(settings.characterJson());
        String sys = "你只输出 JSON 数组，不解释。";
        String user = "角色「" + p.name + "」" +
                (p.who.isEmpty() ? "" : "：" + p.who) +
                (p.schedule.isEmpty() ? "" : "\n她的作息：" + p.schedule) +
                "\n请把她一天分成 4 个时段（0-24 点，连续不重叠），每个时段一句话说明她在做什么（不超过 12 字，第一人称或陈述都行）。\n" +
                "输出格式：[{\"from\":0,\"to\":6,\"text\":\"安睡休息\"},...] 只输出 JSON。";
        String raw = llm.complete(sys, user, 0.5, 300);
        JSONArray arr = parseArray(raw);
        if (arr.length() < 2) throw new Exception("模型没给出可用的作息表");
        settings.saveSchedule(arr.toString());
        return arr;
    }

    /** 现在该不该主动说话；该说的话顺便把那句生成出来 */
    public JSONObject proactiveTick() throws Exception {
        JSONObject out = new JSONObject();
        out.put("due", false);
        if (!settings.proactiveOn()) {
            out.put("why", "没开主动开口");
            return out;
        }
        int hour = Integer.parseInt(new SimpleDateFormat("H", Locale.CHINA).format(new Date()));
        if (hour < settings.proactiveStart() || hour >= settings.proactiveEnd()) {
            out.put("why", "不在时段内");
            return out;
        }
        if (settings.proactiveCountToday() >= settings.proactiveMax()) {
            out.put("why", "今天说得够多了");
            return out;
        }
        List<Store.Session> ss = store.sessions();
        if (ss.isEmpty()) {
            out.put("why", "还没有对话");
            return out;
        }
        String sid = ss.get(0).id;
        List<Store.Msg> msgs = store.messages(sid, 200);
        long last = 0;
        String lastName = "";
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Store.Msg m = msgs.get(i);
            last = m.ts;
            lastName = m.content == null ? "" : m.content;
            if (last > 0) break;
        }
        long idleMs = System.currentTimeMillis() - last;
        if (last > 0 && idleMs < settings.proactiveIdle() * 3600_000L) {
            out.put("why", "还没到空闲时间");
            return out;
        }
        if (!llm.hasKey()) {
            out.put("why", "还没填 Key");
            return out;
        }

        String act = currentActivity(hour);
        Persona p = Persona.parse(settings.characterJson());
        String sys = "你是「" + p.name + "」。"
                + (p.oneLiner.isEmpty() ? "" : p.oneLiner)
                + (p.personality.isEmpty() ? "" : "\n性格：" + p.personality)
                + (p.mood.isEmpty() ? "" : "\n情绪表达：" + p.mood)
                + (p.perTurn.isEmpty() ? "" : "\n必须遵守：" + p.perTurn);
        String user = "现在是你自己想找对方说一句话，不是对方来问你。"
                + (act.isEmpty() ? "" : "你这个时间（" + hour + " 点）正在：" + act + "。")
                + (lastName.isEmpty() ? "" : "你们上次聊到：「" + clip(lastName, 60) + "」。")
                + "\n要求：只写你要发出去的那一句，20 字以内，符合你的性格和当下在干嘛；"
                + "不要问「在吗」，不要解释你为什么突然说话，不要用括号加动作描写。";
        String text = llm.complete(sys, user, 0.9, 120);
        if (text == null) text = "";
        text = text.trim().replaceAll("^[\"「]|[\"」]$", "");
        if (text.isEmpty()) {
            out.put("why", "模型没说话");
            return out;
        }
        store.addMessage(sid, "assistant", text);
        store.touchSession(sid, null);
        settings.bumpProactive();

        out.put("due", true);
        out.put("sid", sid);
        out.put("text", text);
        out.put("activity", act);
        out.put("left", Math.max(0, settings.proactiveMax() - settings.proactiveCountToday()));
        return out;
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        s = s.trim().replaceAll("\\s+", " ");
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static void safe(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
    }
}
