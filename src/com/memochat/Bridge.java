package com.memochat;

import android.app.Activity;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import android.speech.tts.TextToSpeech;
import android.media.MediaPlayer;

/** JS 与原生之间的桥：所有存储、模型调用、记忆逻辑都在这里收口。 */
public class Bridge {

    private final Activity activity;
    private final WebView web;
    private final Store store;
    private final Settings settings;
    private final LlmClient llm;
    private final Embedder embedder;
    private final MemoryEngine engine;
    private final GrowEngine grow;
    private final PluginMarket market;

    private final ExecutorService chatExec = Executors.newSingleThreadExecutor();
    private final ExecutorService memExec = Executors.newSingleThreadExecutor();
    private final ExecutorService mediaExec = Executors.newSingleThreadExecutor();
    private final ExecutorService talkExec = Executors.newSingleThreadExecutor();
    private final ExecutorService voiceExec = Executors.newSingleThreadExecutor();
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private AudioKit audioRecorder;
    private CallMic callMic;
    private volatile boolean pendingCallVoice;
    private Runnable voiceAutoStop;

    private volatile AtomicBoolean cancel = null;
    private volatile long reqSeq = 0;
    private volatile boolean busy = false;
    private volatile MediaKit.Prepared pending;
    private static final int REQ_MEDIA = 7788;
    private static final int REQ_CAMERA = 7789;
    private static final int REQ_AVATAR = 7790;
    private static final int REQ_AVATAR_MODEL = 7791;
    private static final int REQ_AVATAR_ANIM = 7792;
    private volatile Uri cameraUri;
    private TextToSpeech tts;
    private MediaPlayer player;
    private volatile String pendingSpeak;

    public Bridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
        this.store = new Store(activity);
        this.settings = new Settings(activity);
        this.settings.ensureDefaults();
        this.llm = new LlmClient(settings);
        this.embedder = new Embedder(settings);
        this.engine = new MemoryEngine(store, embedder, llm, settings);
        this.grow = new GrowEngine(store, settings, llm);
        this.market = new PluginMarket(activity, settings);
    }

    /* ==================== 初始化 / 会话 ==================== */

    @JavascriptInterface
    public String bootstrap() {
        try {
            JSONObject o = new JSONObject();
            JSONObject st;
            try {
                st = new JSONObject(settingsJson());
            } catch (Exception e) {
                st = new JSONObject();
            }
            o.put("settings", st);
            o.put("sessions", sessionsJson());
            o.put("memCount", store.memoryCount());
            o.put("hasKey", llm.hasKey());
            o.put("version", "4.8");
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":" + JSONObject.quote(e.toString()) + "}";
        }
    }

    @JavascriptInterface
    public String sessionsJson() {
        try {
            List<Store.Session> list = store.sessions();
            JSONArray arr = new JSONArray();
            for (Store.Session s : list) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("title", s.title);
                o.put("updated", s.updated);
                o.put("count", s.count);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @JavascriptInterface
    public String newSession() {
        String id = store.createSession();
        Persona c = persona();
        if (!c.greeting.isEmpty()) {
            store.addMessage(id, "assistant", c.greeting);
        }
        return id;
    }

    @JavascriptInterface
    public String openSession(String sid) {
        try {
            List<Store.Msg> msgs = store.messages(sid, 500);
            JSONArray arr = new JSONArray();
            for (Store.Msg m : msgs) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("role", m.role);
                o.put("content", m.content);
                o.put("ts", m.ts);
                o.put("meta", m.meta == null ? "" : m.meta);
                arr.put(o);
            }
            JSONObject o = new JSONObject();
            o.put("id", sid);
            o.put("messages", arr);
            o.put("summary", store.summary(sid));
            o.put("memCount", store.memoryCount());
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":" + JSONObject.quote(e.toString()) + "}";
        }
    }

    @JavascriptInterface
    public void deleteSession(String sid) {
        store.deleteSession(sid);
        emit("sessions");
    }

    /* ==================== 聊天 ==================== */

    @JavascriptInterface
    public String send(String sid, String text) {
        return sendWithMedia(sid, text, "");
    }

    @JavascriptInterface
    public String sendWithMedia(String sid, String text, String mediaId) {
        final String content = text == null ? "" : text.trim();
        final MediaKit.Prepared media = takeMedia(mediaId);
        if (content.isEmpty() && media == null) return "";
        if (media != null && !settings.visionEnabled()) {
            emitError("", "还没配置视觉模型：设置 → 视觉识别，填一个支持看图的接口（比如智谱 glm-4v-flash，免费）。");
            return "";
        }

        final String reqId = "r" + (++reqSeq);
        final boolean first = store.userMessageCount(sid) == 0;
        final long msgId = store.addMessage(sid, "user", content, media == null ? null : mediaMeta(media, ""));

        String titleSrc = content;
        if (titleSrc.isEmpty() && media != null) titleSrc = kindLabel(media.kind);
        if (first) {
            String title = titleSrc.replace('\n', ' ').trim();
            if (title.length() > 16) title = title.substring(0, 16) + "…";
            store.touchSession(sid, title);
        } else {
            store.touchSession(sid, null);
        }

        final AtomicBoolean flag = new AtomicBoolean(false);
        cancel = flag;
        busy = true;

        chatExec.execute(() -> {
            try {
                String desc = "";
                if (media != null) {
                    emitStage(reqId, "正在看画面…");
                    try {
                        desc = llm.vision(MediaKit.imagePrompt(media), MediaKit.userPrompt(content), media.image);
                    } catch (Exception ve) {
                        desc = "（画面识别失败：" + friendly(ve) + "）";
                        emitNotice("画面识别失败：" + friendly(ve));
                    }
                    desc = desc == null ? "" : desc.trim();
                    store.updateMessageMeta(msgId, mediaMeta(media, desc));
                }

                List<Store.Msg> tail = store.tailMessages(sid, 24);
                String query = content;
                if (query.isEmpty()) query = desc;
                else if (!desc.isEmpty()) query = content + " " + desc;

                List<MemoryEngine.Scored> mems = settings.memoryEnabled()
                        ? engine.recall(query, settings.topK())
                        : new ArrayList<>();
                String summary = store.summary(sid);

                StringBuilder wbText = new StringBuilder(query);
                for (int i = Math.max(0, tail.size() - 3); i < tail.size(); i++) {
                    wbText.append('\n').append(Store.withVision(tail.get(i)));
                }
                String worldbook = persona().matchWorldbook(wbText.toString(), 3);

                List<JSONObject> msgs = new ArrayList<>();
                msgs.add(MemoryEngine.msg("system", systemPrompt(mems, summary, worldbook)));
                int start = Math.max(0, tail.size() - 18);
                for (int i = start; i < tail.size(); i++) {
                    Store.Msg m = tail.get(i);
                    msgs.add(MemoryEngine.msg(m.role, Store.withVision(m)));
                }

                LlmClient.Usage usage = new LlmClient.Usage();
                long t0 = System.currentTimeMillis();
                String answer = llm.stream(msgs, settings.temperature(), settings.maxTokens(),
                        new LlmClient.StreamListener() {
                            @Override
                            public void onDelta(String t) {
                                JSONObject ev = new JSONObject();
                                safePut(ev, "t", "token");
                                safePut(ev, "id", reqId);
                                safePut(ev, "d", t);
                                emit(ev);
                            }

                            @Override
                            public void onReasoning(String t) {
                                JSONObject ev = new JSONObject();
                                safePut(ev, "t", "reason");
                                safePut(ev, "id", reqId);
                                safePut(ev, "d", t);
                                emit(ev);
                            }
                        }, flag, usage);

                boolean stopped = flag.get();
                if (answer == null) answer = "";
                if (!answer.isEmpty()) store.addMessage(sid, "assistant", answer);
                if (!stopped && settings.growEnabled()) {
                    final String gsid = sid;
                    memExec.execute(() -> {
                        try {
                            if (store.userMessageCount(gsid) % 6 != 0) return;
                            JSONObject gr = grow.extract();
                            if (gr.optInt("added", 0) + gr.optInt("bumped", 0) > 0) {
                                JSONObject ev = new JSONObject();
                                safePut(ev, "t", "grow");
                                safePut(ev, "added", gr.optInt("added", 0));
                                safePut(ev, "bumped", gr.optInt("bumped", 0));
                                safePut(ev, "activated", gr.optInt("activated", 0));
                                emit(ev);
                            }
                        } catch (Exception ignored) {
                        }
                    });
                }
                store.touchSession(sid, null);

                List<Long> used = new ArrayList<>();
                for (MemoryEngine.Scored s : mems) used.add(s.mem.id);
                store.touchMemories(used);

                JSONObject ev = new JSONObject();
                safePut(ev, "t", "done");
                safePut(ev, "id", reqId);
                safePut(ev, "sid", sid);
                safePut(ev, "stopped", stopped);
                safePut(ev, "in", usage.in);
                safePut(ev, "out", usage.out);
                safePut(ev, "ms", System.currentTimeMillis() - t0);
                safePut(ev, "recall", recallJson(mems));
                emit(ev);

                if (settings.memoryEnabled()) {
                    memExec.execute(() -> {
                        try {
                            int turn = store.messageCount(sid) / 2;
                            if (turn % settings.extractEvery() != 0) return;
                            List<Store.Msg> recent = store.tailMessages(sid, 8);
                            MemoryEngine.ExtractResult r = engine.extract(sid, recent);
                            if (r.added + r.merged > 0) {
                                JSONObject e2 = new JSONObject();
                                safePut(e2, "t", "mem");
                                safePut(e2, "added", r.added);
                                safePut(e2, "merged", r.merged);
                                safePut(e2, "count", store.memoryCount());
                                JSONArray items = new JSONArray();
                                for (Store.Mem m : r.fresh) {
                                    JSONObject o = new JSONObject();
                                    safePut(o, "content", m.content);
                                    safePut(o, "kind", m.kind);
                                    safePut(o, "label", MemoryEngine.kindLabel(m.kind));
                                    items.put(o);
                                }
                                safePut(e2, "items", items);
                                emit(e2);
                            }
                        } catch (Exception ignored) {
                        }
                        try {
                            engine.summarizeIfNeeded(sid);
                        } catch (Exception ignored) {
                        }
                    });
                }
            } catch (Exception e) {
                emitError(reqId, friendly(e));
            } finally {
                busy = false;
            }
        });
        return reqId;
    }



    /* ==================== 语音 ==================== */

    @JavascriptInterface
    public void startVoice() {
        activity.runOnUiThread(() -> {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 9002);
                return;
            }
            beginRecording();
        });
    }

    public void onVoicePermissionResult(boolean granted) {
        if (pendingCallVoice) {
            pendingCallVoice = false;
            if (granted) startCallMic();
            else emitCall("error", null, "没有麦克风权限，听不见你说话");
            return;
        }
        if (granted) {
            activity.runOnUiThread(this::beginRecording);
        } else {
            emitVoice("error", null, "没有麦克风权限，说不了话");
        }
    }

    private void beginRecording() {
        if (audioRecorder != null) return;
        audioRecorder = new AudioKit();
        if (!audioRecorder.start()) {
            audioRecorder = null;
            emitVoice("error", null, "麦克风打不开，可能被别的应用占着");
            return;
        }
        emitVoice("recording", null, null);
        voiceAutoStop = this::stopVoice;
        uiHandler.postDelayed(voiceAutoStop, 60000);
    }

    @JavascriptInterface
    public void stopVoice() {
        if (audioRecorder == null) return;
        if (voiceAutoStop != null) uiHandler.removeCallbacks(voiceAutoStop);
        byte[] wav = audioRecorder.stop();
        audioRecorder = null;
        emitVoice("thinking", null, null);
        voiceExec.execute(() -> {
            try {
                if (!settings.asrAllowed()) {
                    emitVoice("error", null, "今天的语音转文字条数用完了（上限 " + settings.asrLimit() + "）");
                    return;
                }
                String text = llm.transcribe(wav);
                settings.bumpAsr();
                if (text == null || text.trim().isEmpty()) {
                    emitVoice("error", null, "没听清，再说一次？");
                } else {
                    emitVoice("done", text, null);
                }
            } catch (Exception e) {
                emitVoice("error", null, friendly(e));
            }
        });
    }

    @JavascriptInterface
    public void cancelVoice() {
        if (audioRecorder == null) return;
        if (voiceAutoStop != null) uiHandler.removeCallbacks(voiceAutoStop);
        audioRecorder.stop();
        audioRecorder = null;
        emitVoice("cancel", null, null);
    }

    private void emitVoice(String state, String text, String msg) {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "voice");
        safePut(ev, "state", state);
        if (text != null) safePut(ev, "text", text);
        if (msg != null) safePut(ev, "msg", msg);
        emit(ev);
    }


    /* ==================== 通话语音 ==================== */

    @JavascriptInterface
    public void startCallVoice() {
        activity.runOnUiThread(() -> {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingCallVoice = true;
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 9002);
                return;
            }
            startCallMic();
        });
    }

    private void startCallMic() {
        if (callMic != null) return;
        callMic = new CallMic();
        boolean ok = callMic.start(new CallMic.Listener() {
            @Override
            public void onSpeechStart() {
                emitCall("speech-start", null, null);
            }

            @Override
            public void onSpeechEnd(byte[] wav, long durationMs) {
                emitCall("speech-end", null, null);
                voiceExec.execute(() -> {
                    try {
                        if (!settings.asrAllowed()) {
                            emitCall("error", null, "今天的语音转文字条数用完了");
                            return;
                        }
                        String text = llm.transcribe(wav);
                        settings.bumpAsr();
                        if (text != null && !text.trim().isEmpty()) {
                            emitCall("heard", text.trim(), null);
                        } else {
                            emitCall("heard", "", null);
                        }
                    } catch (Exception e) {
                        emitCall("error", null, friendly(e));
                    }
                });
            }

            @Override
            public void onError(String msg) {
                emitCall("error", null, msg);
            }
        });
        if (!ok) {
            callMic = null;
            emitCall("error", null, "麦克风打不开，可能被别的应用占着");
        } else {
            emitCall("listening", null, null);
        }
    }

    @JavascriptInterface
    public void stopCallVoice() {
        if (callMic != null) {
            callMic.stop();
            callMic = null;
        }
        emitCall("stopped", null, null);
    }

    private void emitCall(String type, String text, String msg) {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "call");
        safePut(ev, "type", type);
        if (text != null) safePut(ev, "text", text);
        if (msg != null) safePut(ev, "msg", msg);
        emit(ev);
    }

    /** 听到用户说话后，带着当前画面回应。 */
    @JavascriptInterface
    public void talkAsk(int id, String base64Jpeg, String question) {
        final String q = question == null ? "" : question.trim();
        talkExec.execute(() -> {
            JSONObject ev = new JSONObject();
            try {
                if (!settings.visionEnabled()) throw new LlmClient.ApiError(0, "还没配置视觉模型");
                String sys = "你正在和用户视频通话，能看到摄像头实时画面，也听得见对方说话。"
                        + "用 1-2 句口语化中文回应，像面对面聊天，别用书面语，别提「画面中」。"
                        + "如果画面太暗或看不清，就顺口提一句（比如让对方开个灯、这边看不清），"
                        + "然后自然地把话接下去；别说摄像头被挡住/没开，也别反复追问同一件事。";
                String user = q.isEmpty() ? "对方没说话，你看着画面说一句。" : "对方说：" + q + "\n看着画面回应这句话。";
                String text = llm.vision(sys, user, base64Jpeg);
                safePut(ev, "t", "talk");
                safePut(ev, "id", id);
                safePut(ev, "text", text);
            } catch (Exception e) {
                safePut(ev, "t", "talk");
                safePut(ev, "id", id);
                safePut(ev, "error", friendly(e));
            }
            emit(ev);
        });
    }

    /* ==================== 视频通话 ==================== */

    @JavascriptInterface
    public void requestCameraPermission() {
        activity.runOnUiThread(() -> {
            if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                emitCamera(true);
            } else {
                activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, 9001);
            }
        });
    }

    public void onCameraPermissionResult(boolean granted) {
        emitCamera(granted);
    }

    private void emitCamera(boolean granted) {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "camera");
        safePut(ev, "granted", granted);
        emit(ev);
    }

    /** 通话中的一帧：id 原样回传，识别结果通过 talk 事件返回。 */
    @JavascriptInterface
    public void talkFrame(int id, String base64Jpeg, String question) {
        final String q = question == null ? "" : question.trim();
        talkExec.execute(() -> {
            JSONObject ev = new JSONObject();
            try {
                if (!settings.visionEnabled()) {
                    throw new LlmClient.ApiError(0, "还没配置视觉模型");
                }
                String sys = "你正在和用户视频通话，看到的是摄像头实时画面。"
                        + "用 1-2 句口语化的中文回应，像朋友在旁边一起看着说话。"
                        + "不要用「画面中」「图像显示」这种书面表达；看不清就直说看不清。";
                StringBuilder user = new StringBuilder();
                if (!q.isEmpty()) {
                    user.append("你前两句刚说过：").append(q).append('\n');
                    user.append("请接着当前画面说一句新的，别重复刚才的话。");
                } else {
                    user.append("你现在看到了什么？");
                }
                String text = llm.vision(sys, user.toString(), base64Jpeg);
                safePut(ev, "t", "talk");
                safePut(ev, "id", id);
                safePut(ev, "text", text);
            } catch (Exception e) {
                safePut(ev, "t", "talk");
                safePut(ev, "id", id);
                safePut(ev, "error", friendly(e));
            }
            emit(ev);
        });
    }

    @JavascriptInterface
    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (settings.customTts()) {
            if (!settings.ttsAllowed()) {
                emitNotice("今天的朗读条数用完了（上限 " + settings.ttsLimit() + "）");
                emitTtsDone();
                return;
            }
            final String t = text;
            talkExec.execute(() -> {
                try {
                    byte[] audio = llm.speech(t);
                    settings.bumpTts();
                    playAudio(audio);
                } catch (Exception e) {
                    emitNotice("朗读失败：" + friendly(e));
                    emitTtsDone();
                }
            });
            return;
        }
        activity.runOnUiThread(() -> {
            if (tts == null) {
                pendingSpeak = text;
                tts = new TextToSpeech(activity, status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        tts.setLanguage(Locale.CHINA);
                        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                            @Override
                            public void onStart(String utteranceId) {
                            }

                            @Override
                            public void onDone(String utteranceId) {
                                emitTtsDone();
                            }

                            @Override
                            public void onError(String utteranceId) {
                                emitTtsDone();
                            }
                        });
                        if (pendingSpeak != null) {
                            tts.speak(pendingSpeak, TextToSpeech.QUEUE_FLUSH, null, "memochat");
                            pendingSpeak = null;
                        }
                    }
                });
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "memochat");
            }
        });
    }

    private void emitTtsDone() {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "tts");
        safePut(ev, "done", true);
        emit(ev);
    }

    @JavascriptInterface
    public void stopSpeak() {
        activity.runOnUiThread(() -> {
            if (tts != null) tts.stop();
            stopPlayer();
        });
    }

    private void playAudio(byte[] data) {
        activity.runOnUiThread(() -> {
            try {
                File f = new File(activity.getCacheDir(), "tts_" + System.currentTimeMillis() + ".mp3");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(data);
                fos.close();
                stopPlayer();
                player = new MediaPlayer();
                player.setDataSource(f.getAbsolutePath());
                player.setOnPreparedListener(MediaPlayer::start);
                player.setOnCompletionListener(mp -> {
                    emitTtsDone();
                    stopPlayer();
                });
                player.setOnErrorListener((mp, what, extra) -> {
                    emitTtsDone();
                    stopPlayer();
                    return true;
                });
                player.prepareAsync();
            } catch (Exception e) {
                emitNotice("播放失败：" + friendly(e));
                emitTtsDone();
            }
        });
    }

    private void stopPlayer() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    /* ---------- 虚拟形象 ---------- */

    private JSONObject avatarConfig() {
        try {
            String raw = settings.avatarJson();
            if (raw == null || raw.trim().isEmpty()) return new JSONObject();
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @JavascriptInterface
    public void pickAvatar() {
        activity.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                activity.startActivityForResult(Intent.createChooser(i, "选择形象图片"), REQ_AVATAR);
            } catch (Exception e) {
                emitAvatarError("打不开系统选择器：" + friendly(e));
            }
        });
    }

    @JavascriptInterface
    public void pickAvatarModel() {
        activity.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"*/*"});
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                activity.startActivityForResult(Intent.createChooser(i, "选择 .vrm 或 .glb 模型"), REQ_AVATAR_MODEL);
            } catch (Exception e) {
                emitModelError("打不开系统选择器：" + friendly(e));
            }
        });
    }

    @JavascriptInterface
    public void pickAvatarAnim() {
        activity.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"*/*"});
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                activity.startActivityForResult(Intent.createChooser(i, "选择 .vrma 动作文件"), REQ_AVATAR_ANIM);
            } catch (Exception e) {
                emitAnimError("打不开系统选择器：" + friendly(e));
            }
        });
    }

    private void emitAnimError(String msg) {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "anim");
        safePut(ev, "status", "error");
        safePut(ev, "msg", msg);
        emit(ev);
    }

    private void emitModelError(String msg) {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "model");
        safePut(ev, "status", "error");
        safePut(ev, "msg", msg);
        emit(ev);
    }

    @JavascriptInterface
    public String saveAvatar(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            settings.saveAvatar(o.toString());
            return settingsJson();
        } catch (Exception e) {
            return settingsJson();
        }
    }

    /* ==================== 成长 / 主动开口 ==================== */

    /* ==================== 插件市场 ==================== */

    @JavascriptInterface
    public String marketJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("plugins", market.catalog());
            o.put("installed", settings.installedPlugins());
            o.put("url", settings.marketUrl());
            return o.toString();
        } catch (Exception e) {
            return "{\"plugins\":[],\"installed\":[]}";
        }
    }

    @JavascriptInterface
    public String installPlugin(String id) {
        JSONObject r = market.install(id);
        try {
            if (r.optBoolean("ok", false)) {
                JSONObject c = r.optJSONObject("character");
                if (c != null) r.put("character", c);
            }
        } catch (Exception ignored) {
        }
        return r.toString();
    }

    @JavascriptInterface
    public String uninstallPlugin(String id) {
        return market.uninstall(id).toString();
    }

    @JavascriptInterface
    public String setMarketUrl(String url) {
        settings.saveMarketUrl(url);
        return marketJson();
    }

    @JavascriptInterface
    public String traitsJson() {
        return grow.traitsJson().toString();
    }

    @JavascriptInterface
    public String addTrait(String kind, String text) {
        String t = text == null ? "" : text.trim();
        if (!t.isEmpty()) store.addTrait(kind, t.length() > 40 ? t.substring(0, 40) : t, "manual", true);
        return grow.traitsJson().toString();
    }

    @JavascriptInterface
    public void deleteTrait(long id) {
        store.deleteTrait(id);
    }

    @JavascriptInterface
    public String toggleTrait(long id) {
        for (Store.Trait t : store.traits()) {
            if (t.id == id) {
                store.setTraitActive(id, !t.active);
                break;
            }
        }
        return grow.traitsJson().toString();
    }

    @JavascriptInterface
    public void saveGrowSettings(String json) {
        try {
            JSONObject o = new JSONObject(json);
            settings.setGrow(o.optBoolean("enabled", true), o.optBoolean("autoApply", true));
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void saveProactive(String json) {
        try {
            JSONObject o = new JSONObject(json);
            settings.setProactive(o.optBoolean("on", false),
                    o.optInt("max", 10), o.optInt("start", 8), o.optInt("end", 24), o.optInt("idle", 6));
            if (settings.proactiveOn()) {
                requestNotifyPermission();
                ProactiveScheduler.schedule(activity);
            } else {
                ProactiveScheduler.cancel(activity);
            }
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void requestNotifyPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9101);
            }
        } catch (Exception ignored) {
        }
    }

    /** 后台生成、还没发给界面的消息 */
    @JavascriptInterface
    public String pendingEvents() {
        try {
            JSONArray arr = new JSONArray();
            for (String line : PendingEvents.takeAll(activity)) {
                try {
                    arr.put(new JSONObject(line));
                } catch (Exception ignored) {
                }
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @JavascriptInterface
    public String scheduleJson() {
        return grow.schedule().toString();
    }

    @JavascriptInterface
    public void genSchedule() {
        memExec.execute(() -> {
            try {
                JSONArray arr = grow.genSchedule();
                JSONObject ev = new JSONObject();
                safePut(ev, "t", "schedule");
                safePut(ev, "schedule", arr);
                emit(ev);
            } catch (Exception e) {
                emitNotice("作息表生成失败：" + friendly(e));
            }
        });
    }

    @JavascriptInterface
    public void growNow() {
        memExec.execute(() -> {
            try {
                JSONObject gr = grow.extract();
                JSONObject ev = new JSONObject();
                safePut(ev, "t", "grow");
                safePut(ev, "added", gr.optInt("added", 0));
                safePut(ev, "bumped", gr.optInt("bumped", 0));
                safePut(ev, "activated", gr.optInt("activated", 0));
                safePut(ev, "traits", gr.optJSONArray("traits"));
                safePut(ev, "manual", true);
                emit(ev);
            } catch (Exception e) {
                emitNotice("成长抽取失败：" + friendly(e));
            }
        });
    }

    @JavascriptInterface
    public void proactiveTick() {
        memExec.execute(() -> {
            try {
                JSONObject r = grow.proactiveTick();
                if (r.optBoolean("due", false)) {
                    JSONObject ev = new JSONObject();
                    safePut(ev, "t", "proactive");
                    safePut(ev, "sid", r.optString("sid", ""));
                    safePut(ev, "text", r.optString("text", ""));
                    safePut(ev, "activity", r.optString("activity", ""));
                    emit(ev);
                } else {
                    logQuiet("proactive: " + r.optString("why", ""));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void logQuiet(String s) {
        android.util.Log.d("memochat", s);
    }

    @JavascriptInterface
    public String clearAvatarAnim() {
        try {
            AvatarStore.anim(activity).delete();
        } catch (Exception ignored) {
        }
        return settingsJson();
    }

    @JavascriptInterface
    public String clearAvatar() {
        settings.saveAvatar("");
        AvatarStore.clear(activity);
        return settingsJson();
    }

    private void emitAvatarError(String msg) {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "avatar");
        safePut(ev, "status", "error");
        safePut(ev, "msg", msg);
        emit(ev);
    }

    /* ---------- 媒体 ---------- */

    @JavascriptInterface
    public void pickMedia() {
        activity.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                activity.startActivityForResult(Intent.createChooser(i, "选择图片或视频"), REQ_MEDIA);
            } catch (Exception e) {
                emitMediaError("打不开系统选择器：" + friendly(e));
            }
        });
    }

    @JavascriptInterface
    public void pickCamera(String mode) {
        activity.runOnUiThread(() -> {
            boolean video = "video".equals(mode);
            try {
                File dir = new File(activity.getCacheDir(), "camera");
                if (!dir.exists()) dir.mkdirs();
                String name = "cap_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg");
                Uri uri = Uri.parse("content://" + MediaProvider.AUTHORITY + "/camera/" + name);
                cameraUri = uri;

                Intent i = new Intent(video ? MediaStore.ACTION_VIDEO_CAPTURE : MediaStore.ACTION_IMAGE_CAPTURE);
                i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (video) {
                    i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
                    i.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30);
                }
                activity.startActivityForResult(i, REQ_CAMERA);
            } catch (Exception e) {
                emitMediaError("打不开相机：" + friendly(e));
            }
        });
    }

    @JavascriptInterface
    public void clearMedia(String id) {
        MediaKit.Prepared p = pending;
        if (p != null && (id == null || id.isEmpty() || p.id.equals(id))) pending = null;
    }

    /** 由 MainActivity 转发系统选择器结果。 */
    public void onPickResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_AVATAR) {
            onAvatarPicked(resultCode, data);
            return;
        }
        if (requestCode == REQ_AVATAR_MODEL) {
            onAvatarModelPicked(resultCode, data);
            return;
        }
        if (requestCode == REQ_AVATAR_ANIM) {
            onAvatarAnimPicked(resultCode, data);
            return;
        }
        if (requestCode != REQ_MEDIA && requestCode != REQ_CAMERA) return;
        if (resultCode != Activity.RESULT_OK) {
            JSONObject ev = new JSONObject();
            safePut(ev, "t", "media");
            safePut(ev, "status", "cancel");
            emit(ev);
            return;
        }
        final Uri uri;
        if (requestCode == REQ_CAMERA) {
            uri = cameraUri;
        } else {
            uri = data == null ? null : data.getData();
        }
        if (uri == null) {
            emitMediaError("没有拿到文件");
            return;
        }
        mediaExec.execute(() -> {
            try {
                MediaKit.Prepared p = MediaKit.prepare(activity, uri);
                pending = p;
                JSONObject ev = new JSONObject();
                safePut(ev, "t", "media");
                safePut(ev, "status", "ready");
                safePut(ev, "id", p.id);
                safePut(ev, "kind", p.kind);
                safePut(ev, "frames", p.frames);
                safePut(ev, "dur", p.durationMs);
                safePut(ev, "thumb", p.thumb);
                emit(ev);
            } catch (Exception e) {
                emitMediaError(friendly(e));
            }
        });
    }

    private void onAvatarPicked(int resultCode, Intent data) {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "avatar");
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            safePut(ev, "status", "cancel");
            emit(ev);
            return;
        }
        final Uri uri = data.getData();
        mediaExec.execute(() -> {
            try {
                AvatarStore.saveFromUri(activity, uri);
                JSONObject ok = new JSONObject();
                safePut(ok, "t", "avatar");
                safePut(ok, "status", "ready");
                safePut(ok, "url", "https://" + AvatarStore.HOST + "/avatar?v=" + AvatarStore.version(activity));
                emit(ok);
            } catch (Exception e) {
                emitAvatarError(friendly(e));
            }
        });
    }

    private void onAvatarModelPicked(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            JSONObject ev = new JSONObject();
            safePut(ev, "t", "model");
            safePut(ev, "status", "cancel");
            emit(ev);
            return;
        }
        final Uri uri = data.getData();
        mediaExec.execute(() -> {
            try {
                long size = AvatarStore.saveModelFromUri(activity, uri, pct -> {
                    JSONObject pe = new JSONObject();
                    safePut(pe, "t", "model");
                    safePut(pe, "status", "progress");
                    safePut(pe, "pct", pct);
                    emit(pe);
                });
                JSONObject ok = new JSONObject();
                safePut(ok, "t", "model");
                safePut(ok, "status", "ready");
                safePut(ok, "size", size);
                safePut(ok, "url", "https://" + AvatarStore.HOST + "/model?v=" + AvatarStore.modelVersion(activity));
                emit(ok);
            } catch (Exception e) {
                emitModelError(friendly(e));
            }
        });
    }

    private void onAvatarAnimPicked(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            JSONObject ev = new JSONObject();
            safePut(ev, "t", "anim");
            safePut(ev, "status", "cancel");
            emit(ev);
            return;
        }
        final Uri uri = data.getData();
        mediaExec.execute(() -> {
            try {
                long size = AvatarStore.saveAnimFromUri(activity, uri);
                JSONObject ok = new JSONObject();
                safePut(ok, "t", "anim");
                safePut(ok, "status", "ready");
                safePut(ok, "size", size);
                safePut(ok, "url", "https://" + AvatarStore.HOST + "/anim?v=" + AvatarStore.animVersion(activity));
                emit(ok);
            } catch (Exception e) {
                emitAnimError(friendly(e));
            }
        });
    }

    private MediaKit.Prepared takeMedia(String id) {
        if (id == null || id.isEmpty()) return null;
        MediaKit.Prepared p = pending;
        if (p != null && p.id.equals(id)) {
            pending = null;
            return p;
        }
        return null;
    }

    private String mediaMeta(MediaKit.Prepared p, String desc) {
        JSONObject o = new JSONObject();
        safePut(o, "kind", p.kind);
        safePut(o, "frames", p.frames);
        safePut(o, "dur", p.durationMs);
        safePut(o, "thumb", p.thumb);
        if (desc != null && !desc.isEmpty()) safePut(o, "desc", desc);
        return o.toString();
    }

    private static String kindLabel(String kind) {
        if ("video".equals(kind)) return "视频";
        if ("gif".equals(kind)) return "动图";
        return "图片";
    }

    private void emitMediaError(String msg) {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "media");
        safePut(ev, "status", "error");
        safePut(ev, "msg", msg);
        emit(ev);
    }

    private void emitStage(String id, String text) {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "stage");
        safePut(ev, "id", id);
        safePut(ev, "text", text);
        emit(ev);
    }

    private void emitNotice(String msg) {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "notice");
        safePut(ev, "msg", msg);
        emit(ev);
    }

    private void emitError(String id, String msg) {
        JSONObject ev = new JSONObject();
        safePut(ev, "t", "error");
        safePut(ev, "id", id);
        safePut(ev, "msg", msg);
        emit(ev);
    }

    @JavascriptInterface
    public void stop() {
        AtomicBoolean f = cancel;
        if (f != null) f.set(true);
    }

    @JavascriptInterface
    public boolean isBusy() {
        return busy;
    }

    private String systemPrompt(List<MemoryEngine.Scored> mems, String summary, String worldbook) {
        Persona c = persona();
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(c.name).append("」。");
        if (!c.oneLiner.isEmpty()) sb.append(c.oneLiner);
        sb.append('\n');
        block(sb, "你是谁", c.who);
        block(sb, "性格", c.personality);
        block(sb, "你看事情的方式", c.worldview);
        block(sb, "不同心情下的说话方式", c.mood);
        try {
            StringBuilder tr = new StringBuilder();
            for (Store.Trait t : store.traits()) {
                if (t.active) tr.append("- (").append(t.kind).append(") ").append(t.content).append('\n');
            }
            if (tr.length() > 0) block(sb, "你慢慢长出来的样子（这些是相处中形成的新倾向，自然带着就行，不要刻意声明）", tr.toString().trim());
        } catch (Exception ignored) {
        }
        block(sb, "平时在哪聊", c.channels);
        block(sb, "作息", c.schedule);
        if (!c.examples.isEmpty()) {
            sb.append("\n【说话示例】\n").append(c.examples).append('\n');
        }
        if (worldbook != null && !worldbook.isEmpty()) {
            sb.append("\n【世界书（与当前话题相关）】\n").append(worldbook);
        }
        if (!c.perTurn.isEmpty()) {
            sb.append("\n【每轮必须遵守】\n").append(c.perTurn).append('\n');
        }

        if (mems != null && !mems.isEmpty()) {
            sb.append("\n【关于对方的长期记忆】\n");
            for (MemoryEngine.Scored s : mems) {
                sb.append("- (").append(MemoryEngine.kindLabel(s.mem.kind)).append(") ")
                        .append(s.mem.content).append('\n');
            }
        }
        if (summary != null && !summary.isEmpty()) {
            sb.append("\n【本次会话早前摘要】\n").append(summary).append('\n');
        }

        sb.append("\n规则：\n")
                .append("1. 始终保持「").append(c.name).append("」的角色口吻，绝不跳出设定自称 AI。\n")
                .append("2. 长期记忆是背景，不是台词。合适时自然用上，不逐条复述，也不提「记忆」「数据库」这类字眼。\n")
                .append("3. 记忆和对方当前说法冲突时，以当前为准，并顺手更新认知。\n")
                .append("4. 简洁、具体；能一句话说清就别说三段。\n")
                .append("5. 不知道就直说，不要编造细节。\n")
                .append("6. 需要时可以反问，但别把每句都变成提问。\n");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA);
        sb.append("当前时间：").append(f.format(new Date())).append('\n');
        return sb.toString();
    }

    private static void block(StringBuilder sb, String title, String text) {
        if (text == null || text.isEmpty()) return;
        sb.append("\n【").append(title).append("】\n").append(text).append('\n');
    }

    private Persona persona() {
        return Persona.parse(settings.characterJson());
    }

    /* ==================== 记忆 ==================== */

    @JavascriptInterface
    public String memories(String query) {
        try {
            List<Store.Mem> list = store.searchMemories(query, 300);
            JSONArray arr = new JSONArray();
            for (Store.Mem m : list) arr.put(memJson(m));
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @JavascriptInterface
    public void deleteMemory(long id) {
        store.deleteMemory(id);
    }

    @JavascriptInterface
    public String memoryCount() {
        return String.valueOf(store.memoryCount());
    }

    @JavascriptInterface
    public String recallPreview(String q) {
        try {
            if (q == null || q.trim().isEmpty()) return "[]";
            List<MemoryEngine.Scored> list = engine.recall(q.trim(), settings.topK());
            JSONArray arr = new JSONArray();
            for (MemoryEngine.Scored s : list) {
                JSONObject o = memJson(s.mem);
                o.put("score", Math.round(s.score * 1000) / 1000.0);
                o.put("cos", Math.round(s.cos * 1000) / 1000.0);
                o.put("lex", Math.round(s.lex * 1000) / 1000.0);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private JSONObject memJson(Store.Mem m) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", m.id);
        o.put("kind", m.kind);
        o.put("label", MemoryEngine.kindLabel(m.kind));
        o.put("content", m.content);
        o.put("importance", m.importance);
        o.put("ts", m.updated > 0 ? m.updated : m.created);
        o.put("uses", m.useCount);
        return o;
    }

    private JSONArray recallJson(List<MemoryEngine.Scored> list) throws Exception {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (MemoryEngine.Scored s : list) {
            JSONObject o = new JSONObject();
            o.put("content", s.mem.content);
            o.put("kind", s.mem.kind);
            o.put("score", Math.round(s.score * 100) / 100.0);
            arr.put(o);
        }
        return arr;
    }

    /* ==================== 设置 ==================== */

    @JavascriptInterface
    public String settingsJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("apiKey", settings.apiKey());
            o.put("baseUrl", settings.baseUrl());
            o.put("model", settings.model());
            o.put("temperature", settings.temperature());
            o.put("maxTokens", settings.maxTokens());
            o.put("character", persona().toJson());
            o.put("memoryEnabled", settings.memoryEnabled());
            o.put("extractEvery", settings.extractEvery());
            o.put("topK", settings.topK());
            o.put("embBase", settings.embBase());
            o.put("embModel", settings.embModel());
            o.put("embKey", settings.embKey().equals(settings.apiKey()) ? "" : settings.embKey());
            o.put("remoteEmbedding", settings.remoteEmbedding());
            o.put("visionBase", settings.visionBase());
            o.put("visionModel", settings.visionModel());
            o.put("visionKey", settings.visionKey().equals(settings.apiKey()) ? "" : settings.visionKey());
            o.put("visionEnabled", settings.visionEnabled());
            o.put("autoSpeak", settings.autoSpeak());
            o.put("asrBase", settings.asrBase());
            o.put("asrKey", settings.asrKey().equals(settings.apiKey()) ? "" : settings.asrKey());
            o.put("asrModel", settings.asrModel());
            o.put("asrLimit", settings.asrLimit());
            o.put("asrLeft", Math.max(0, settings.asrLimit() - settings.asrUsedToday()));
            o.put("ttsBase", settings.ttsBase());
            o.put("ttsKey", settings.ttsKey().equals(settings.apiKey()) ? "" : settings.ttsKey());
            o.put("ttsModel", settings.ttsModel());
            o.put("ttsVoice", settings.ttsVoice());
            o.put("ttsLimit", settings.ttsLimit());
            o.put("ttsLeft", Math.max(0, settings.ttsLimit() - settings.ttsUsedToday()));
            JSONObject g = new JSONObject();
            g.put("enabled", settings.growEnabled());
            g.put("autoApply", settings.growAutoApply());
            o.put("grow", g);
            JSONObject pa = new JSONObject();
            pa.put("on", settings.proactiveOn());
            pa.put("max", settings.proactiveMax());
            pa.put("start", settings.proactiveStart());
            pa.put("end", settings.proactiveEnd());
            pa.put("idle", settings.proactiveIdle());
            pa.put("count", settings.proactiveCountToday());
            o.put("proactive", pa);
            o.put("schedule", grow.schedule());
            o.put("avatar", avatarConfig());
            o.put("avatarUrl", AvatarStore.has(activity)
                    ? "https://" + AvatarStore.HOST + "/avatar?v=" + AvatarStore.version(activity) : "");
            o.put("modelUrl", AvatarStore.hasModel(activity)
                    ? "https://" + AvatarStore.HOST + "/model?v=" + AvatarStore.modelVersion(activity) : "");
            o.put("modelSize", AvatarStore.hasModel(activity) ? AvatarStore.model(activity).length() : 0);
            o.put("animUrl", AvatarStore.hasAnim(activity)
                    ? "https://" + AvatarStore.HOST + "/anim?v=" + AvatarStore.animVersion(activity) : "");
            o.put("animSize", AvatarStore.hasAnim(activity) ? AvatarStore.anim(activity).length() : 0);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public String saveSettings(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String oldEmb = settings.embBase() + "|" + settings.embModel();
            settings.saveFromJson(o);
            String newEmb = settings.embBase() + "|" + settings.embModel();
            final boolean reindex = !oldEmb.equals(newEmb);
            if (reindex) {
                memExec.execute(() -> {
                    try {
                        int n = engine.reindex();
                        JSONObject ev = new JSONObject();
                        safePut(ev, "t", "notice");
                        safePut(ev, "msg", "向量空间已切换，重建了 " + n + " 条记忆的向量");
                        emit(ev);
                    } catch (Exception ignored) {
                    }
                });
            }
            return settingsJson();
        } catch (Exception e) {
            return settingsJson();
        }
    }

    @JavascriptInterface
    public String saveCharacter(String json) {
        try {
            Persona c = Persona.parse(json);
            JSONObject out = c.toJson();
            settings.saveCharacter(out.toString());
            return out.toString();
        } catch (Exception e) {
            return settings.characterJson();
        }
    }

    @JavascriptInterface
    public void testConnection() {
        memExec.execute(() -> {
            JSONObject ev = new JSONObject();
            try {
                List<JSONObject> msgs = new ArrayList<>();
                msgs.add(MemoryEngine.msg("system", "回复 ok 两个字母即可。"));
                msgs.add(MemoryEngine.msg("user", "ping"));
                long t0 = System.currentTimeMillis();
                String r = llm.complete(msgs, 0, 8);
                safePut(ev, "t", "test");
                safePut(ev, "ok", true);
                safePut(ev, "msg", "连接正常 · " + settings.model() + " · " + (System.currentTimeMillis() - t0) + "ms");
            } catch (Exception e) {
                try {
                    safePut(ev, "t", "test");
                    safePut(ev, "ok", false);
                    safePut(ev, "msg", friendly(e));
                } catch (Exception ignored) {
                }
            }
            emit(ev);
        });
    }

    @JavascriptInterface
    public void reindexMemories() {
        memExec.execute(() -> {
            try {
                int n = engine.reindex();
                JSONObject ev = new JSONObject();
                safePut(ev, "t", "notice");
                safePut(ev, "msg", "已重建 " + n + " 条记忆向量");
                emit(ev);
            } catch (Exception ignored) {
            }
        });
    }

    @JavascriptInterface
    public void clearAll() {
        store.clearAllData();
        emit("sessions");
    }

    @JavascriptInterface
    public String exportJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("app", "忆聊");
            root.put("exportedAt", System.currentTimeMillis());
            root.put("settings", settingsJson());
            JSONArray sess = new JSONArray();
            for (Store.Session s : store.sessions()) {
                JSONObject so = new JSONObject();
                so.put("id", s.id);
                so.put("title", s.title);
                JSONArray ms = new JSONArray();
                for (Store.Msg m : store.messages(s.id, 2000)) {
                    JSONObject mo = new JSONObject();
                    mo.put("role", m.role);
                    mo.put("content", m.content);
                    mo.put("ts", m.ts);
                    ms.put(mo);
                }
                so.put("messages", ms);
                so.put("summary", store.summary(s.id));
                sess.put(so);
            }
            root.put("sessions", sess);
            JSONArray mems = new JSONArray();
            for (Store.Mem m : store.allMemories(2000)) mems.put(memJson(m));
            root.put("memories", mems);
            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void finishApp() {
        activity.finish();
    }

    /* ==================== 杂项 ==================== */

    private String friendly(Exception e) {
        if (e instanceof LlmClient.ApiError) {
            return e.getMessage();
        }
        if (e instanceof java.net.UnknownHostException) {
            return "网络不通：解析不到服务器地址。检查网络或 Base URL。";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "请求超时了，检查网络后再试。";
        }
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        return m;
    }

    private static void safePut(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
    }

    private void emit(String type) {
        JSONObject ev = new JSONObject();
        try {
            safePut(ev, "t", type);
        } catch (Exception ignored) {
        }
        emit(ev);
    }

    private void emit(JSONObject ev) {
        String js = "window.__ev(" + ev.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }
}
