package com.lumen.files;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** 供系统“打开方式/分享”读取本地文件的最小 ContentProvider（带权限校验）。 */
public final class FileContentProvider extends ContentProvider {

    public static final String AUTHORITY = "com.lumen.files.files";

    public static Uri uriFor(String path) {
        return Uri.parse("content://" + AUTHORITY + Uri.encode(path, "/"));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String path = uri.getPath();
        if (path == null) {
            return "application/octet-stream";
        }
        return Format.mime(new Entry(nameOf(path), path, false, 0, 0));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        File file = new File(uri.getPath() == null ? "" : uri.getPath());
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.length();
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null || !allowed(path)) {
            throw new FileNotFoundException("不允许访问该路径");
        }
        return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private boolean allowed(String path) {
        String canonical;
        try {
            canonical = new File(path).getCanonicalPath();
        } catch (IOException error) {
            return false;
        }
        if (canonical.startsWith("/storage/") || canonical.startsWith("/mnt/")
                || canonical.startsWith("/sdcard/")) {
            return true;
        }
        if (getContext() == null) {
            return false;
        }
        return canonical.startsWith(getContext().getCacheDir().getAbsolutePath())
                || canonical.startsWith(getContext().getFilesDir().getAbsolutePath())
                || canonical.startsWith(getContext().getExternalCacheDir() == null
                ? "/nonexistent" : getContext().getExternalCacheDir().getAbsolutePath());
    }

    private static String nameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
