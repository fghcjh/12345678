package com.memochat;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 媒体预处理：图片直接缩放；GIF / 视频抽关键帧拼成一张带时间标注的联动图，
 * 这样任何只支持单图输入的视觉模型都能看懂「发生的顺序」。
 */
public class MediaKit {

    public static class Prepared {
        public String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        public String kind = "image";   // image | gif | video
        public int frames = 1;
        public long durationMs = 0;
        public String name = "";
        public String thumb = "";       // data:image/jpeg;base64,...
        public String image = "";       // 纯 base64，JPEG
    }

    private static final int CELL = 480;
    private static final int GAP = 6;

    public static Prepared prepare(Context ctx, Uri uri) throws Exception {
        String mime = null;
        try {
            mime = ctx.getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        String name = queryName(ctx, uri);
        String lower = name.toLowerCase(Locale.ROOT);
        boolean gif = (mime != null && mime.contains("gif")) || lower.endsWith(".gif");
        boolean video = (mime != null && mime.startsWith("video/"))
                || lower.matches(".*\\.(mp4|mov|mkv|webm|avi|3gp|m4v|ts)$");

        Prepared p = new Prepared();
        p.name = name;

        if (video) {
            video(ctx, uri, p);
        } else {
            byte[] bytes = readAll(ctx, uri, 40 * 1024 * 1024);
            if (gif) gif(bytes, p);
            else image(bytes, p);
        }
        return p;
    }

    /* ---------------- 静态图片 ---------------- */

    private static void image(byte[] bytes, Prepared p) throws Exception {
        Bitmap bmp = decode(bytes, 2048);
        if (bmp == null) throw new Exception("这张图打不开");
        bmp = applyExif(bmp, bytes);
        p.kind = "image";
        p.frames = 1;
        p.durationMs = 0;
        p.thumb = dataUrl(jpeg(scaleTo(bmp, 220), 60));
        p.image = base64(jpeg(scaleTo(bmp, 1024), 72));
        bmp.recycle();
    }

    /* ---------------- GIF ---------------- */

    @SuppressWarnings("deprecation")
    private static void gif(byte[] bytes, Prepared p) throws Exception {
        Movie movie;
        try {
            movie = Movie.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            movie = null;
        }
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
            image(bytes, p);
            return;
        }
        int dur = movie.duration();
        if (dur <= 0) dur = 1200;

        int n = 4;
        List<Bitmap> frames = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        float s = Math.min(1f, 640f / Math.max(movie.width(), movie.height()));
        int fw = Math.max(1, Math.round(movie.width() * s));
        int fh = Math.max(1, Math.round(movie.height() * s));

        for (int i = 0; i < n; i++) {
            long t = (long) (dur * (i / (double) n));
            Bitmap b = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(Color.BLACK);
            c.scale(s, s);
            movie.setTime((int) Math.min(t, Math.max(0, dur - 1)));
            movie.draw(c, 0, 0);
            frames.add(b);
            times.add(t);
        }

        p.kind = "gif";
        p.frames = frames.size();
        p.durationMs = dur;
        p.image = base64(jpeg(sheet(frames, times, "动图"), 72));
        p.thumb = dataUrl(jpeg(scaleTo(frames.get(0), 220), 60));
        for (Bitmap b : frames) b.recycle();
    }

    /* ---------------- 视频 ---------------- */

    private static void video(Context ctx, Uri uri, Prepared p) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d == null) throw new Exception("读不到这个视频的时长");
            long dur = Long.parseLong(d);
            if (dur <= 0) throw new Exception("视频时长为 0，可能文件损坏");

            int rotation = 0;
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rot != null) {
                try {
                    rotation = Integer.parseInt(rot);
                } catch (Exception ignored) {
                }
            }

            int n = 4;
            List<Bitmap> frames = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                long us = (long) (dur * 1000L * (i + 0.5) / n);
                Bitmap f = frame(r, us, 640, rotation);
                if (f != null) {
                    frames.add(f);
                    times.add(dur * i / n);
                }
            }
            if (frames.isEmpty()) throw new Exception("这个视频抽不出画面（可能是编码格式不支持）");

            p.kind = "video";
            p.frames = frames.size();
            p.durationMs = dur;
            p.image = base64(jpeg(sheet(frames, times, "视频"), 72));
            p.thumb = dataUrl(jpeg(scaleTo(frames.get(0), 220), 60));
            for (Bitmap b : frames) b.recycle();
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static Bitmap frame(MediaMetadataRetriever r, long us, int max, int rotation) {
        Bitmap b = null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                b = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, max, max);
            }
        } catch (Exception ignored) {
        }
        if (b == null) {
            try {
                b = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception ignored) {
            }
        }
        if (b == null) return null;
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (rotated != b) b.recycle();
            b = rotated;
        }
        return scaleTo(b, max);
    }

    /* ---------------- 拼接 ---------------- */

    /** 2 列网格，每格裁剪填充，右下角标注时间。 */
    private static Bitmap sheet(List<Bitmap> frames, List<Long> times, String label) {
        int cols = Math.min(2, frames.size());
        int rows = (frames.size() + cols - 1) / cols;
        int w = cols * CELL + (cols - 1) * GAP;
        int h = rows * CELL + (rows - 1) * GAP;

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextSize(26f);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xB3000000);

        for (int i = 0; i < frames.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            float left = col * (CELL + GAP);
            float top = row * (CELL + GAP);

            Bitmap f = frames.get(i);
            float scale = Math.max((float) CELL / f.getWidth(), (float) CELL / f.getHeight());
            float dw = f.getWidth() * scale;
            float dh = f.getHeight() * scale;
            Rect src = new Rect(0, 0, f.getWidth(), f.getHeight());
            RectF dst = new RectF(
                    left + (CELL - dw) / 2f,
                    top + (CELL - dh) / 2f,
                    left + (CELL + dw) / 2f,
                    top + (CELL + dh) / 2f);
            c.drawBitmap(f, src, dst, null);

            String t = String.format(Locale.CHINA, "%.1fs", times.get(i) / 1000.0);
            float tw = text.measureText(t);
            c.drawRoundRect(new RectF(left + 10, top + CELL - 42, left + 22 + tw, top + CELL - 10), 8, 8, bg);
            c.drawText(t, left + 16, top + CELL - 16, text);
        }

        // 左上角类型角标
        String tag = label + " · " + frames.size() + " 帧";
        float tw = text.measureText(tag);
        c.drawRoundRect(new RectF(10, 10, 22 + tw, 50), 8, 8, bg);
        c.drawText(tag, 16, 40, text);
        return out;
    }

    /* ---------------- 工具 ---------------- */

    public static String imagePrompt(Prepared p) {
        String kind;
        if ("video".equals(p.kind)) kind = "用户发来一段视频";
        else if ("gif".equals(p.kind)) kind = "用户发来一张动图（GIF）";
        else kind = "用户发来一张图片";
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个视觉识别器，只做客观描述，不闲聊、不评价。\n");
        sb.append("请仔细看提供的画面");
        if (!"image".equals(p.kind)) {
            sb.append("（多帧按时间顺序拼成一张：左上→右下，每帧标了时间）");
        }
        sb.append("，输出：\n");
        sb.append("1) 画面里有什么：人物/物体/场景、外观细节、相对位置；\n");
        sb.append("2) 画面中的文字：原样转录，看不清的标注[不清]；\n");
        sb.append("3) 动作或变化：谁在做什么，如果是多帧，按时间说明发生了什么；\n");
        sb.append("4) 氛围与风格：光线、色调、构图（一句话即可）。\n");
        sb.append("用中文，平铺直叙，不要分点编号，总长 200-400 字。");
        return sb.toString();
    }

    public static String userPrompt(String userText) {
        if (userText == null || userText.trim().isEmpty()) return "请描述这些画面。";
        return "结合用户的问题来描述画面，重点回答用户关心的部分。用户说：" + userText.trim();
    }

    private static Bitmap decode(byte[] bytes, int max) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o2);
    }

    private static Bitmap applyExif(Bitmap bmp, byte[] bytes) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(bytes));
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int deg = 0;
            if (o == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            if (deg == 0) return bmp;
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            return r;
        } catch (Exception e) {
            return bmp;
        }
    }

    private static Bitmap scaleTo(Bitmap src, int max) {
        int w = src.getWidth();
        int h = src.getHeight();
        int big = Math.max(w, h);
        if (big <= max) return src;
        float s = (float) max / big;
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    private static byte[] jpeg(Bitmap bmp, int quality) {
        Bitmap flat = bmp;
        boolean needFlat = bmp.hasAlpha();
        if (needFlat) {
            flat = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(flat);
            c.drawColor(Color.BLACK);
            c.drawBitmap(bmp, 0, 0, null);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        flat.compress(Bitmap.CompressFormat.JPEG, quality, out);
        if (needFlat) flat.recycle();
        return out.toByteArray();
    }

    private static String base64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static String dataUrl(byte[] bytes) {
        return "data:image/jpeg;base64," + base64(bytes);
    }

    private static byte[] readAll(Context ctx, Uri uri, int limit) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("读不到这个文件");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) throw new Exception("图片超过 40MB，换一张小点的吧");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String queryName(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String p = uri.getLastPathSegment();
        return p == null ? "" : p;
    }
}
