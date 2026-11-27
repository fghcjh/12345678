package com.memochat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 记忆引擎：
 * 1) recall —— 混合打分（向量余弦 + 字面重合 + 重要度 + 时近性）召回长期记忆；
 * 2) extract —— 每轮对话后让模型抽取稳定事实，向量去重后落库；
 * 3) summarize —— 会话变长后把旧消息压成摘要。
 */
public class MemoryEngine {

    public static final String[] KINDS = {"profile", "preference", "event", "relationship", "plan", "other"};

    public static class Scored {
        public Store.Mem mem;
        public double score;
        public double cos;
        public double lex;
    }

    public static class ExtractResult {
        public int added;
        public int merged;
        public List<Store.Mem> fresh = new ArrayList<>();
    }

    private final Store store;
    private final Embedder embedder;
    private final LlmClient llm;
    private final Settings settings;

    public MemoryEngine(Store store, Embedder embedder, LlmClient llm, Settings settings) {
        this.store = store;
        this.embedder = embedder;
        this.llm = llm;
        this.settings = settings;
    }

    /* ---------------- 召回 ---------------- */

    public List<Scored> recall(String query, int k) {
        List<Scored> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        List<Store.Mem> all = store.allMemories(800);
        if (all.isEmpty()) return out;

        float[] qv = embedder.embed(query);
        String q = normalize(query);
        long now = System.currentTimeMillis();

        for (Store.Mem m : all) {
            Scored s = new Scored();
            s.mem = m;
            s.cos = m.emb != null && m.emb.length == qv.length ? Embedder.cosine(qv, m.emb) : 0;
            s.lex = lexical(q, normalize(m.content));
            double imp = (Math.max(1, m.importance) - 1) / 4.0;
            double days = Math.max(0, now - Math.max(m.lastUsed, m.created)) / 86400000.0;
            double recency = Math.exp(-days / 60.0);
            s.score = 0.50 * s.cos + 0.22 * s.lex + 0.16 * imp + 0.12 * recency;

            boolean relevant = s.cos >= 0.32 || s.lex >= 0.20 || (m.importance >= 4 && s.lex > 0.05);
            if (relevant) out.add(s);
        }

        Collections.sort(out, (a, b) -> Double.compare(b.score, a.score));
        if (out.size() > k) out = new ArrayList<>(out.subList(0, k));

        if (out.size() < 2) {
            // 兜底：至少带上最重要的两条，避免"失忆感"
            List<Store.Mem> top = store.allMemories(50);
            Collections.sort(top, (a, b) -> Integer.compare(b.importance, a.importance));
            Set<Long> have = new HashSet<>();
            for (Scored s : out) have.add(s.mem.id);
            for (Store.Mem m : top) {
                if (out.size() >= 2) break;
                if (have.contains(m.id)) continue;
                Scored s = new Scored();
                s.mem = m;
                s.score = 0.1;
                out.add(s);
            }
        }
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        String t = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** 字符 bigram 包含度（查询被记忆覆盖的比例），对中文短句足够稳。 */
    private static double lexical(String q, String m) {
        if (q.isEmpty() || m.isEmpty()) return 0;
        Set<String> a = grams(q), b = grams(m);
        if (a.isEmpty() || b.isEmpty()) return 0;
        int hit = 0;
        for (String g : a) if (b.contains(g)) hit++;
        return (double) hit / a.size();
    }

    private static Set<String> grams(String s) {
        Set<String> set = new HashSet<>();
        if (s.length() == 1) set.add(s);
        for (int i = 0; i + 1 < s.length(); i++) set.add(s.substring(i, i + 2));
        return set;
    }

    /* ---------------- 抽取 ---------------- */

    public ExtractResult extract(String sessionId, List<Store.Msg> turns) throws Exception {
        ExtractResult res = new ExtractResult();
        if (turns == null || turns.isEmpty()) return res;
        if (!llm.hasKey()) return res;

        StringBuilder convo = new StringBuilder();
        for (Store.Msg m : turns) {
            String who = "user".equals(m.role) ? "用户" : "助手";
            String text = Store.withVision(m);
            if (text.length() > 600) text = text.substring(0, 600) + "…";
            convo.append(who).append("：").append(text).append('\n');
        }

        List<JSONObject> msgs = new ArrayList<>();
        msgs.add(msg("system", EXTRACT_SYSTEM));
        msgs.add(msg("user", "对话：\n" + convo));
        String raw = llm.complete(msgs, 0.2, 700);
        JSONArray arr = parseArray(raw);
        if (arr == null) return res;

        List<Store.Mem> existing = store.allMemories(800);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String content = o.optString("content", "").trim();
            if (content.length() < 2 || content.length() > 160) continue;
            String kind = o.optString("kind", "other");
            if (!isKind(kind)) kind = "other";
            int importance = Math.max(1, Math.min(5, o.optInt("importance", 3)));

            float[] emb = embedder.embed(content);
            Store.Mem best = null;
            double bestCos = 0;
            for (Store.Mem m : existing) {
                if (!m.kind.equals(kind)) continue;
                double c = Embedder.cosine(emb, m.emb);
                if (c > bestCos) {
                    bestCos = c;
                    best = m;
                }
            }

            if (best != null && bestCos >= 0.90) {
                // 合并：内容更完整才替换，重要度取大，避免同一条记忆反复堆叠
                String merged = content.length() > best.content.length() * 1.15 && content.length() <= 120
                        ? content : best.content;
                float[] mergedEmb = merged.equals(content) ? emb : embedder.embed(merged);
                store.updateMemory(best.id, merged, Math.max(best.importance, importance), mergedEmb);
                best.content = merged;
                best.importance = Math.max(best.importance, importance);
                best.emb = mergedEmb;
                res.merged++;
                continue;
            }

            Store.Mem m = new Store.Mem();
            m.kind = kind;
            m.content = content;
            m.importance = importance;
            m.source = sessionId;
            m.emb = emb;
            store.addMemory(m);
            existing.add(m);
            res.added++;
            res.fresh.add(m);
        }
        return res;
    }

    private static final String EXTRACT_SYSTEM =
            "你是记忆抽取器。从对话中提取值得长期记住的、关于用户的稳定信息：身份、偏好、习惯、关系、计划、重要事件、明确的好恶与禁忌。\n" +
            "不要提取：寒暄、一次性的情绪、助手自己的话、纯知识问答。\n" +
            "每条必须是独立可理解的完整陈述句，主语写「用户」。同义重复要合并成一条。\n" +
            "只输出 JSON 数组，不要解释，不要 Markdown 代码块。格式：\n" +
            "[{\"kind\":\"profile|preference|event|relationship|plan|other\",\"content\":\"用户……\",\"importance\":1-5}]\n" +
            "importance：5=身份/长期核心，4=稳定偏好或重要关系，3=较重要的事件或计划，2=一般信息，1=琐碎。\n" +
            "没有值得记住的内容时输出 []。";

    /* ---------------- 摘要 ---------------- */

    public String summarizeIfNeeded(String sessionId) throws Exception {
        if (!llm.hasKey()) return null;
        int count = store.messageCount(sessionId);
        if (count < 26) return null;

        String old = store.summary(sessionId);
        long upto = store.summaryUpto(sessionId);
        List<Store.Msg> tail = store.messagesAfter(sessionId, upto, 60);

        int keep = 12; // 最近 12 条保持原文
        if (tail.size() <= keep + 8) return null;

        List<Store.Msg> toCompress = new ArrayList<>(tail.subList(0, tail.size() - keep));
        StringBuilder convo = new StringBuilder();
        for (Store.Msg m : toCompress) {
            String who = "user".equals(m.role) ? "用户" : "助手";
            String full = Store.withVision(m);
            String text = full.length() > 400 ? full.substring(0, 400) + "…" : full;
            convo.append(who).append("：").append(text).append('\n');
        }

        String prompt = "已有摘要：\n" + (old.isEmpty() ? "（无）" : old) + "\n\n新增对话：\n" + convo +
                "\n请把两者合并成一段新的会话纪要：保留用户的关键信息、决定、未完成的事、情绪基调；" +
                "≤220 字，纯文本，不要分点标题，不要评价。";

        List<JSONObject> msgs = new ArrayList<>();
        msgs.add(msg("system", "你是精准的对话摘要器，只输出摘要正文。"));
        msgs.add(msg("user", prompt));
        String sum = llm.complete(msgs, 0.3, 500).trim();
        if (sum.isEmpty()) return null;
        if (sum.length() > 400) sum = sum.substring(0, 400);

        long lastId = toCompress.get(toCompress.size() - 1).id;
        store.setSummary(sessionId, sum, lastId);
        return sum;
    }

    /* ---------------- 维护 ---------------- */

    public int reindex() {
        List<Store.Mem> all = store.allMemories(2000);
        int n = 0;
        for (Store.Mem m : all) {
            float[] v = embedder.embed(m.content);
            store.updateMemory(m.id, null, 0, v);
            n++;
        }
        return n;
    }

    /* ---------------- 工具 ---------------- */

    public static JSONObject msg(String role, String content) {
        JSONObject o = new JSONObject();
        try {
            o.put("role", role);
            o.put("content", content);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static boolean isKind(String k) {
        for (String x : KINDS) if (x.equals(k)) return true;
        return false;
    }

    public static String kindLabel(String k) {
        switch (k) {
            case "profile": return "身份";
            case "preference": return "偏好";
            case "event": return "事件";
            case "relationship": return "关系";
            case "plan": return "计划";
            default: return "其他";
        }
    }

    /** 容忍模型偶尔带代码块或前后缀，取第一个 [ 到最后一个 ]。 */
    static JSONArray parseArray(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONArray(raw.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
