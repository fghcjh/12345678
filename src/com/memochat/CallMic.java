package com.memochat;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

/**
 * 通话用的连续麦克风：自带简单端点检测（能量门限 + 自适应噪声底），
 * 检测到一句话结束后吐出一段 WAV，交给 ASR。
 */
public class CallMic {

    public interface Listener {
        void onSpeechStart();
        void onSpeechEnd(byte[] wav, long durationMs);
        void onError(String msg);
    }

    private static final int RATE = 16000;
    private static final int FRAME_BYTES = 3200;   // 100ms
    private static final int PRE_ROLL = RATE * 2 / 5; // 400ms 预录
    private static final int MAX_SEGMENT_MS = 15000;

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private Listener listener;

    public boolean start(Listener l) {
        listener = l;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = 4096;
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 8192));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                release();
                return false;
            }
            running = true;
            record.startRecording();
            thread = new Thread(this::loop, "memochat-callmic");
            thread.start();
            return true;
        } catch (Exception e) {
            release();
            return false;
        }
    }

    public void stop() {
        running = false;
        try {
            if (record != null) record.stop();
        } catch (Exception ignored) {
        }
        if (thread != null) {
            try {
                thread.join(500);
            } catch (Exception ignored) {
            }
            thread = null;
        }
        release();
    }

    private void release() {
        try {
            if (record != null) record.release();
        } catch (Exception ignored) {
        }
        record = null;
    }

    private void loop() {
        byte[] buf = new byte[FRAME_BYTES];
        ByteArrayOutputStream pre = new ByteArrayOutputStream();
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        boolean speaking = false;
        int voiceFrames = 0;
        int silenceFrames = 0;
        int noiseFloor = 500;
        long segStart = 0;

        while (running) {
            int n;
            try {
                n = record.read(buf, 0, buf.length);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) continue;

            int rms = rms(buf, n);
            boolean voice = rms > Math.max(900, noiseFloor * 3);

            if (!speaking) {
                pre.write(buf, 0, n);
                if (pre.size() > PRE_ROLL) {
                    byte[] all = pre.toByteArray();
                    pre.reset();
                    pre.write(all, all.length - PRE_ROLL, PRE_ROLL);
                }
                if (voice) {
                    voiceFrames++;
                } else {
                    voiceFrames = 0;
                    noiseFloor = (noiseFloor * 15 + Math.min(rms, 2500)) / 16;
                }
                if (voiceFrames >= 2) {   // 连续 200ms 有声音
                    speaking = true;
                    silenceFrames = 0;
                    seg.reset();
                    byte[] p = pre.toByteArray();
                    seg.write(p, 0, p.length);
                    pre.reset();
                    segStart = System.currentTimeMillis();
                    if (listener != null) listener.onSpeechStart();
                }
            } else {
                seg.write(buf, 0, n);
                if (voice) silenceFrames = 0; else silenceFrames++;
                long dur = System.currentTimeMillis() - segStart;
                if (silenceFrames >= 7 || dur >= MAX_SEGMENT_MS) {  // 静音 700ms 或 15 秒封顶
                    speaking = false;
                    voiceFrames = 0;
                    silenceFrames = 0;
                    byte[] raw = seg.toByteArray();
                    seg.reset();
                    if (raw.length > RATE * 2 / 2 && listener != null) {
                        listener.onSpeechEnd(AudioKit.wav(raw), dur);
                    }
                }
            }
        }
    }

    private static int rms(byte[] pcm, int len) {
        long sum = 0;
        int samples = len / 2;
        if (samples == 0) return 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (long) s * s;
        }
        return (int) Math.sqrt(sum / samples);
    }
}
