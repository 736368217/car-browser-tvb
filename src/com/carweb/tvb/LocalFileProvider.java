package com.carweb.tvb;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只服务本应用私有目录的最小 ContentProvider。
 *
 * 为什么不直接用 FileProvider：那需要 AndroidX/Support 库，本项目是零依赖直编（aapt2 + javac + d8）。
 * 这里 80 行就能做到同样的事，而且能顺手把可访问范围锁死在应用自己的目录里：
 *   · 下载到「应用内私有目录」的文件，也能被第三方查看器 / 分享目标读取；
 *   · 不导出（exported=false），只有拿到 Intent 授权的外部应用才能临时读。
 */
public final class LocalFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.carweb.tvb.files";
    private static final String PREFIX = "content://" + AUTHORITY + "/download/";

    public static Uri uriFor(File file) {
        if (file == null) return null;
        return Uri.parse(PREFIX + Uri.encode(file.getAbsolutePath()));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** 只允许应用私有根目录下的文件，防止被当成任意文件读取器。 */
    private File resolve(Uri uri) throws FileNotFoundException {
        if (uri == null || getContext() == null) throw new FileNotFoundException("空地址");
        String path = uri.getPath();
        if (path == null || !path.startsWith("/download/")) throw new FileNotFoundException("不支持该地址");
        String raw = path.substring("/download/".length());
        File f = new File(raw); // Uri.getPath() has already decoded once.
        try {
            String canonical = f.getCanonicalPath();
            String[] roots = new String[]{
                    getContext().getFilesDir().getCanonicalPath(),
                    getContext().getCacheDir().getCanonicalPath()
            };
            boolean ok = false;
            for (String root : roots) {
                if (canonical.equals(root) || canonical.startsWith(root + File.separator)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                File ext = getContext().getExternalFilesDir(null);
                if (ext != null) {
                    String er = ext.getCanonicalPath();
                    if (canonical.equals(er) || canonical.startsWith(er + File.separator)) ok = true;
                }
            }
            if (!ok) throw new FileNotFoundException("越界路径");
            if (!f.exists()) throw new FileNotFoundException("文件不存在");
            return f;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            throw new FileNotFoundException("无法解析路径");
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri == null ? "" : uri.getLastPathSegment();
        if (name == null) return "application/octet-stream";
        String decoded = Uri.decode(name);
        int dot = decoded.lastIndexOf('.');
        if (dot > 0) {
            String ext = decoded.substring(dot + 1).toLowerCase(java.util.Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    /** 查看器/分享目标常读 DISPLAY_NAME、SIZE；给一行就够。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            String[] cols = projection != null ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor c = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
                else row[i] = null;
            }
            c.addRow(row);
            return c;
        } catch (Throwable t) {
            return null;
        }
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
