package com.memochat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 用户上传的形象图：存成 PNG 放私有目录，再由 shouldInterceptRequest 通过
 * https://memochat.local/avatar 喂给 WebView（不经过 base64，大图也不卡）。
 */
public class AvatarStore {

    public static final String HOST = "memochat.local";
    private static final String IMG = "avatar.png";
    private static final String THUMB = "avatar_thumb.png";
    private static final String MODEL = "avatar.vrm";
    private static final String ANIM = "avatar.vrma";
    private static final int MAX = 720;
    private static final long MODEL_LIMIT = 512L * 1024 * 1024;

    /** 导入进度回调（0..100）。 */
    public interface Progress {
        void onProgress(int pct);
    }

    public static File img(Context c) {
        return new File(c.getFilesDir(), IMG);
    }

    public static File thumb(Context c) {
        return new File(c.getFilesDir(), THUMB);
    }

    public static boolean has(Context c) {
        File f = img(c);
        return f.exists() && f.length() > 0;
    }

    public static File model(Context c) {
        return new File(c.getFilesDir(), MODEL);
    }

    public static boolean hasModel(Context c) {
        File f = model(c);
        return f.exists() && f.length() > 0;
    }

    public static long modelVersion(Context c) {
        return model(c).lastModified();
    }

    public static File anim(Context c) {
        return new File(c.getFilesDir(), ANIM);
    }

    public static boolean hasAnim(Context c) {
        File f = anim(c);
        return f.exists() && f.length() > 0;
    }

    public static long animVersion(Context c) {
        return anim(c).lastModified();
    }

    /** 动作文件（.vrma）一般几十 KB 到几 MB，直接拷。 */
    public static long saveAnimFromUri(Context c, Uri uri) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("这个文件读不出来");
        File tmp = new File(c.getFilesDir(), ANIM + ".part");
        FileOutputStream out = new FileOutputStream(tmp);
        long total = 0;
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 32L * 1024 * 1024) throw new Exception("动作文件超过 32MB 了");
                out.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
        if (total < 100) {
            tmp.delete();
            throw new Exception("文件太小，不像是 .vrma");
        }
        File dst = anim(c);
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new Exception("存不进应用目录");
        }
        return total;
    }

    public static long version(Context c) {
        return img(c).lastModified();
    }

    public static void clear(Context c) {
        for (File f : new File[]{img(c), thumb(c), model(c), anim(c)}) {
            try {
                f.delete();
            } catch (Exception ignored) {
            }
        }
    }

    /** 把用户选的 .vrm / .glb 原样拷进私有目录（不解析，交给 WebView 里的 three 去读）。 */
    public static long saveModelFromUri(Context c, Uri uri) throws Exception {
        return saveModelFromUri(c, uri, null);
    }

    public static long saveModelFromUri(Context c, Uri uri, Progress cb) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("这个文件读不出来");
        File tmp = new File(c.getFilesDir(), MODEL + ".part");
        FileOutputStream out = new FileOutputStream(tmp);
        long total = 0;
        long expect = -1;
        boolean ok = false;
        try {
            try {
                android.database.Cursor cur = c.getContentResolver().query(uri, null, null, null, null);
                if (cur != null) {
                    int idx = cur.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (idx >= 0 && cur.moveToFirst()) expect = cur.getLong(idx);
                    cur.close();
                }
            } catch (Exception ignored) {
            }
            int lastPct = -1;
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MODEL_LIMIT) throw new Exception("模型超过 512MB 了，手机上真的跑不动");
                out.write(buf, 0, n);
                if (cb != null && expect > 0) {
                    int pct = (int) Math.min(100, total * 100 / expect);
                    if (pct >= lastPct + 5) {
                        lastPct = pct;
                        cb.onProgress(pct);
                    }
                }
            }
            ok = true;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
            if (!ok) {
                try {
                    tmp.delete();
                } catch (Exception ignored) {
                }
            }
        }
        if (total < 1024) {
            tmp.delete();
            throw new Exception("文件太小，不像是模型");
        }
        File dst = model(c);
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new Exception("存不进应用目录");
        }
        return total;
    }

    /** 把用户选的图片读进来 → 纠正方向 → 缩到 720 → 存 PNG（保留透明）。 */
    public static boolean saveFromUri(Context c, Uri uri) throws Exception {
        byte[] bytes = readAll(c, uri);
        if (bytes == null || bytes.length == 0) throw new Exception("这张图读不出来");
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, b);
        if (b.outWidth <= 0 || b.outHeight <= 0) throw new Exception("这不是一张能被识别的图片");

        int sample = 1;
        while (Math.max(b.outWidth, b.outHeight) / (sample * 2) >= MAX) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (bmp == null) throw new Exception("这张图解码失败");

        bmp = rotate(bmp, bytes);
        int w = bmp.getWidth(), h = bmp.getHeight();
        int big = Math.max(w, h);
        if (big > MAX) {
            float s = (float) MAX / big;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        writePng(bmp, img(c));
        int tw = bmp.getWidth(), th2 = bmp.getHeight();
        int tbig = Math.max(tw, th2);
        float ts = tbig > 200 ? 200f / tbig : 1f;
        Bitmap th = Bitmap.createScaledBitmap(bmp,
                Math.max(1, Math.round(tw * ts)), Math.max(1, Math.round(th2 * ts)), true);
        writePng(th, thumb(c));
        if (th != bmp) th.recycle();
        bmp.recycle();
        return true;
    }

    private static void writePng(Bitmap bmp, File f) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.flush();
        out.close();
    }

    private static Bitmap rotate(Bitmap bmp, byte[] bytes) {
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

    private static byte[] readAll(Context c, Uri uri) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 12 * 1024 * 1024) break;   // 形象图不该有这么大
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

    /** 给 WebView 的拦截响应。path: /avatar、/avatar-thumb、/model */
    public static WebResourceResponse respond(Context c, String path) {
        boolean isModel = path != null && path.startsWith("/model");
        File f;
        String mime;
        if (path != null && path.startsWith("/anim")) {
            f = anim(c);
            mime = "model/gltf-binary";
        } else if (isModel) {
            f = model(c);
            mime = "model/gltf-binary";
        } else if (path != null && path.startsWith("/avatar-thumb")) {
            f = thumb(c);
            mime = "image/png";
        } else {
            f = img(c);
            mime = "image/png";
        }
        if (!f.exists() || f.length() == 0) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    noStore(), new ByteArrayInputStream(new byte[0]));
        }
        try {
            java.util.Map<String, String> h = noStore();
            h.put("Content-Length", String.valueOf(f.length()));
            return new WebResourceResponse(mime, null, 200, "OK",
                    h, new FileInputStream(f));
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 500, "Error",
                    noStore(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private static java.util.Map<String, String> noStore() {
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("Cache-Control", "no-store");
        // file:// 页面里用 XHR 读模型时要跨域头（配合 allowUniversalAccessFromFileURLs）
        h.put("Access-Control-Allow-Origin", "*");
        return h;
    }
}
