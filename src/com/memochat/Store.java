package com.memochat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 本地持久化：会话、消息、长期记忆、会话摘要。全部存在 App 私有目录。 */
public class Store extends SQLiteOpenHelper {

    public static final String DB_NAME = "memochat.db";

    public static class Msg {
        public long id;
        public String role;
        public String content;
        public long ts;
        public String meta;
        public String extra;
    }

    public static class Mem {
        public long id;
        public String kind = "other";
        public String content = "";
        public int importance = 3;
        public long created;
        public long updated;
        public long lastUsed;
        public int useCount;
        public float[] emb;
        public String source = "";
    }

    public static class Session {
        public String id;
        public String title;
        public long updated;
        public int count;
    }

    public Store(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions(" +
                "id TEXT PRIMARY KEY, title TEXT, created_at INTEGER, updated_at INTEGER)");
        db.execSQL("CREATE TABLE messages(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT, content TEXT, created_at INTEGER, meta TEXT)");
        db.execSQL("CREATE TABLE memories(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, content TEXT, importance INTEGER," +
                " created_at INTEGER, updated_at INTEGER, last_used_at INTEGER, use_count INTEGER DEFAULT 0," +
                " embedding BLOB, source TEXT)");
        db.execSQL("CREATE TABLE summaries(" +
                "session_id TEXT PRIMARY KEY, content TEXT, upto INTEGER, updated_at INTEGER)");
        db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id, id)");
        db.execSQL("CREATE TABLE traits(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, content TEXT, source TEXT," +
                " evidence INTEGER DEFAULT 1, active INTEGER DEFAULT 0, created_at INTEGER)");
        db.execSQL("CREATE INDEX idx_memories_kind ON memories(kind)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN meta TEXT");
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS traits(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, content TEXT, source TEXT," +
                    " evidence INTEGER DEFAULT 1, active INTEGER DEFAULT 0, created_at INTEGER)");
        }
    }

    public static class Trait {
        public long id;
        public String kind;
        public String content;
        public String source;
        public int evidence;
        public boolean active;
    }

    /* ---------------- 成长：她慢慢长出来的性格/习惯 ---------------- */

    public List<Trait> traits() {
        List<Trait> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query("traits", null, null, null, null, null,
                    "active DESC, evidence DESC, id DESC");
            while (c.moveToNext()) {
                Trait t = new Trait();
                t.id = c.getLong(c.getColumnIndexOrThrow("id"));
                t.kind = c.getString(c.getColumnIndexOrThrow("kind"));
                t.content = c.getString(c.getColumnIndexOrThrow("content"));
                t.source = c.getString(c.getColumnIndexOrThrow("source"));
                t.evidence = c.getInt(c.getColumnIndexOrThrow("evidence"));
                t.active = c.getInt(c.getColumnIndexOrThrow("active")) == 1;
                out.add(t);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public long addTrait(String kind, String content, String source, boolean active) {
        ContentValues v = new ContentValues();
        v.put("kind", kind == null || kind.isEmpty() ? "性格" : kind);
        v.put("content", content);
        v.put("source", source == null ? "auto" : source);
        v.put("evidence", 1);
        v.put("active", active ? 1 : 0);
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("traits", null, v);
    }

    public void deleteTrait(long id) {
        getWritableDatabase().delete("traits", "id=?", new String[]{String.valueOf(id)});
    }

    public void setTraitActive(long id, boolean on) {
        ContentValues v = new ContentValues();
        v.put("active", on ? 1 : 0);
        getWritableDatabase().update("traits", v, "id=?", new String[]{String.valueOf(id)});
    }

    public int bumpTrait(long id) {
        getWritableDatabase().execSQL("UPDATE traits SET evidence = evidence + 1 WHERE id = ?",
                new Object[]{id});
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT evidence FROM traits WHERE id=?", new String[]{String.valueOf(id)});
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 1;
    }

    /* ---------------- sessions ---------------- */

    public String createSession() {
        String id = "s" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4);
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("title", "新对话");
        v.put("created_at", System.currentTimeMillis());
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insert("sessions", null, v);
        return id;
    }

    public long lastMessageTs(String sid) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT MAX(created_at) FROM messages WHERE session_id=?", new String[]{sid});
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    public void touchSession(String id, String title) {
        ContentValues v = new ContentValues();
        v.put("updated_at", System.currentTimeMillis());
        if (title != null && !title.isEmpty()) v.put("title", title);
        getWritableDatabase().update("sessions", v, "id=?", new String[]{id});
    }

    public String sessionTitle(String id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT title FROM sessions WHERE id=?", new String[]{id});
        try {
            return c.moveToFirst() ? c.getString(0) : "新对话";
        } finally {
            c.close();
        }
    }

    public List<Session> sessions() {
        List<Session> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT s.id, s.title, s.updated_at," +
                        " (SELECT COUNT(*) FROM messages m WHERE m.session_id=s.id) AS cnt" +
                        " FROM sessions s ORDER BY s.updated_at DESC LIMIT 100", null);
        try {
            while (c.moveToNext()) {
                Session s = new Session();
                s.id = c.getString(0);
                s.title = c.getString(1);
                s.updated = c.getLong(2);
                s.count = c.getInt(3);
                out.add(s);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void deleteSession(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("messages", "session_id=?", new String[]{id});
        db.delete("summaries", "session_id=?", new String[]{id});
        db.delete("sessions", "id=?", new String[]{id});
    }

    /* ---------------- messages ---------------- */

    public long addMessage(String sid, String role, String content) {
        return addMessage(sid, role, content, null);
    }

    public long addMessage(String sid, String role, String content, String meta) {
        ContentValues v = new ContentValues();
        v.put("session_id", sid);
        v.put("role", role);
        v.put("content", content);
        v.put("created_at", System.currentTimeMillis());
        if (meta != null && !meta.isEmpty()) v.put("meta", meta);
        return getWritableDatabase().insert("messages", null, v);
    }

    public void updateMessageMeta(long id, String meta) {
        ContentValues v = new ContentValues();
        v.put("meta", meta);
        getWritableDatabase().update("messages", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** 最近 limit 条，按时间正序返回。 */
    public List<Msg> tailMessages(String sid, int limit) {
        List<Msg> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, role, content, created_at, meta FROM messages WHERE session_id=? ORDER BY id DESC LIMIT ?",
                new String[]{sid, String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                Msg m = new Msg();
                m.id = c.getLong(0);
                m.role = c.getString(1);
                m.content = c.getString(2);
                m.ts = c.getLong(3);
                m.meta = c.getString(4);
                m.extra = metaDesc(m.meta);
                out.add(0, m);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public List<Msg> messages(String sid, int limit) {
        List<Msg> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, role, content, created_at, meta FROM messages WHERE session_id=? ORDER BY id ASC LIMIT ?",
                new String[]{sid, String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                Msg m = new Msg();
                m.id = c.getLong(0);
                m.role = c.getString(1);
                m.content = c.getString(2);
                m.ts = c.getLong(3);
                m.meta = c.getString(4);
                m.extra = metaDesc(m.meta);
                out.add(m);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** id 大于 afterId 的消息（用于摘要增量）。 */
    public List<Msg> messagesAfter(String sid, long afterId, int limit) {
        List<Msg> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, role, content, created_at, meta FROM messages WHERE session_id=? AND id>? ORDER BY id ASC LIMIT ?",
                new String[]{sid, String.valueOf(afterId), String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                Msg m = new Msg();
                m.id = c.getLong(0);
                m.role = c.getString(1);
                m.content = c.getString(2);
                m.ts = c.getLong(3);
                m.meta = c.getString(4);
                m.extra = metaDesc(m.meta);
                out.add(m);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public int messageCount(String sid) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM messages WHERE session_id=?", new String[]{sid});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public int userMessageCount(String sid) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM messages WHERE session_id=? AND role='user'", new String[]{sid});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /* ---------------- summaries ---------------- */

    public String summary(String sid) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT content FROM summaries WHERE session_id=?", new String[]{sid});
        try {
            return c.moveToFirst() ? c.getString(0) : "";
        } finally {
            c.close();
        }
    }

    public long summaryUpto(String sid) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT upto FROM summaries WHERE session_id=?", new String[]{sid});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            c.close();
        }
    }

    public void setSummary(String sid, String content, long upto) {
        ContentValues v = new ContentValues();
        v.put("session_id", sid);
        v.put("content", content);
        v.put("upto", upto);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("summaries", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /* ---------------- memories ---------------- */

    public long addMemory(Mem m) {
        ContentValues v = new ContentValues();
        v.put("kind", m.kind);
        v.put("content", m.content);
        v.put("importance", m.importance);
        v.put("created_at", m.created > 0 ? m.created : System.currentTimeMillis());
        v.put("updated_at", System.currentTimeMillis());
        v.put("last_used_at", System.currentTimeMillis());
        v.put("use_count", 0);
        v.put("embedding", encode(m.emb));
        v.put("source", m.source);
        long id = getWritableDatabase().insert("memories", null, v);
        m.id = id;
        return id;
    }

    public void updateMemory(long id, String content, int importance, float[] emb) {
        ContentValues v = new ContentValues();
        if (content != null) v.put("content", content);
        if (importance > 0) v.put("importance", importance);
        if (emb != null) v.put("embedding", encode(emb));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("memories", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteMemory(long id) {
        getWritableDatabase().delete("memories", "id=?", new String[]{String.valueOf(id)});
    }

    public void touchMemories(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        for (Long id : ids) {
            db.execSQL("UPDATE memories SET last_used_at=?, use_count=use_count+1 WHERE id=?",
                    new Object[]{now, id});
        }
    }

    public int memoryCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memories", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public List<Mem> allMemories(int limit) {
        List<Mem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, kind, content, importance, created_at, updated_at, last_used_at, use_count, embedding, source" +
                        " FROM memories ORDER BY updated_at DESC LIMIT ?", new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) out.add(readMem(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** 关键词粗筛（用于记忆库搜索框，不做语义）。 */
    public List<Mem> searchMemories(String query, int limit) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return allMemories(limit);
        List<Mem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, kind, content, importance, created_at, updated_at, last_used_at, use_count, embedding, source" +
                        " FROM memories WHERE content LIKE ? OR kind LIKE ? ORDER BY updated_at DESC LIMIT ?",
                new String[]{"%" + q + "%", "%" + q + "%", String.valueOf(limit)});
        try {
            while (c.moveToNext()) out.add(readMem(c));
        } finally {
            c.close();
        }
        return out;
    }

    private Mem readMem(Cursor c) {
        Mem m = new Mem();
        m.id = c.getLong(0);
        m.kind = c.getString(1);
        m.content = c.getString(2);
        m.importance = c.getInt(3);
        m.created = c.getLong(4);
        m.updated = c.getLong(5);
        m.lastUsed = c.getLong(6);
        m.useCount = c.getInt(7);
        m.emb = decode(c.getBlob(8));
        m.source = c.getString(9);
        return m;
    }

    public void clearAllData() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("messages", null, null);
        db.delete("summaries", null, null);
        db.delete("sessions", null, null);
        db.delete("memories", null, null);
    }

    public static String metaDesc(String meta) {
        if (meta == null || meta.isEmpty()) return "";
        try {
            return new JSONObject(meta).optString("desc", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 送进模型时的正文：原文 + 附件画面描述。 */
    public static String withVision(Msg m) {
        if (m.extra == null || m.extra.isEmpty()) return m.content;
        return m.content + "\n\n[附件画面：" + m.extra + "]";
    }

    /* ---------------- vector blob ---------------- */

    public static byte[] encode(float[] v) {
        if (v == null) return null;
        ByteBuffer b = ByteBuffer.allocate(4 + v.length * 4);
        b.putInt(v.length);
        for (float f : v) b.putFloat(f);
        return b.array();
    }

    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return null;
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int n = b.getInt();
        if (n <= 0 || n > 4096 || bytes.length != 4 + n * 4) return null;
        float[] v = new float[n];
        for (int i = 0; i < n; i++) v[i] = b.getFloat();
        return v;
    }
}
