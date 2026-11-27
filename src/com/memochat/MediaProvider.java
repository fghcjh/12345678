package com.memochat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 最小的 FileProvider：只服务 cache/camera 下的文件，
 * 让系统相机能把照片/视频写进我们的私有目录。
 */
public class MediaProvider extends ContentProvider {

    public static final String AUTHORITY = "com.memochat.files";

    private File dir() {
        File d = new File(getContext().getCacheDir(), "camera");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File fileFor(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) name = "tmp";
        name = name.replace("/", "_").replace("..", "_");
        return new File(dir(), name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        int flags = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
        return ParcelFileDescriptor.open(f, flags);
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null) return "application/octet-stream";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".3gp")) return "video/3gpp";
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = fileFor(uri);
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File f = fileFor(uri);
        return f.delete() ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
