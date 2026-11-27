package com.memochat;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

/** 16kHz 单声道 16-bit PCM 录音，停止时封成 WAV（DeepSeek ASR 要求）。 */
public class AudioKit {

    public static final int RATE = 16000;
    private static final int MAX_BYTES = RATE * 2 * 60; // 60 秒

    private AudioRecord record;
    private Thread thread;
    private ByteArrayOutputStream pcm;
    private volatile boolean recording;

    public boolean start() {
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = 4096;
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 8192));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                release();
                return false;
            }
            pcm = new ByteArrayOutputStream();
            recording = true;
            record.startRecording();
            thread = new Thread(() -> {
                byte[] buf = new byte[3200];
                while (recording) {
                    int n;
                    try {
                        n = record.read(buf, 0, buf.length);
                    } catch (Exception e) {
                        break;
                    }
                    if (n > 0) {
                        pcm.write(buf, 0, n);
                        if (pcm.size() >= MAX_BYTES) break;
                    }
                }
            }, "memochat-audio");
            thread.start();
            return true;
        } catch (Exception e) {
            release();
            return false;
        }
    }

    /** 停止并返回 WAV 字节；没有有效音频时返回 null。 */
    public byte[] stop() {
        recording = false;
        try {
            if (record != null) record.stop();
        } catch (Exception ignored) {
        }
        if (thread != null) {
            try {
                thread.join(600);
            } catch (Exception ignored) {
            }
            thread = null;
        }
        byte[] raw = pcm == null ? null : pcm.toByteArray();
        release();
        if (raw == null || raw.length < RATE / 4) return null;
        return wav(raw);
    }

    private void release() {
        try {
            if (record != null) record.release();
        } catch (Exception ignored) {
        }
        record = null;
    }

    public static byte[] wav(byte[] pcmData) {
        int dataLen = pcmData.length;
        int channels = 1;
        int bits = 16;
        int byteRate = RATE * channels * bits / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLen);
        writeAscii(out, "RIFF");
        writeInt(out, 36 + dataLen);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeInt(out, 16);
        writeShort(out, 1);
        writeShort(out, channels);
        writeInt(out, RATE);
        writeInt(out, byteRate);
        writeShort(out, channels * bits / 8);
        writeShort(out, bits);
        writeAscii(out, "data");
        writeInt(out, dataLen);
        out.write(pcmData, 0, dataLen);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < s.length(); i++) o.write(s.charAt(i));
    }

    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff);
        o.write((v >> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }
}
