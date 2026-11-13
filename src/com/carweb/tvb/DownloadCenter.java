package com.carweb.tvb;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.URLUtil;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用内下载中心。
 *
 * 为什么不再只用系统 DownloadManager：车机用户投诉「选不了目录、点不开文件、打不开目录」。
 * DownloadManager 的目标目录是调用方指定的 app 私有目录，系统文件管理器在 Android 11+
 * 根本进不去；它的 content:// 结果也不保证能被第三方查看器读取。所以这里自己做下载，
 * 并把「保存到哪、怎么打开、去哪找」变成明确可控的三件事：
 *
 *   1. 目录：系统「下载」目录（MediaStore，默认）/ 应用私有目录 / 自选文件夹（SAF）；
 *   2. 打开：私有目录通过本应用的 LocalFileProvider 暴露 content://，任何查看器都能读；
 *   3. 定位：系统「下载」目录用系统入口打开；自选文件夹用 DocumentsUI 打开；私有目录给出路径。
 *
 * 下载在后台线程池执行，进度通过 Listener 回主线程；列表持久化在 SharedPreferences。
 */
public final class DownloadCenter {

    private static final String TAG = "TvBrowser";
    private static final String PREFS = "downloads_v2";
    private static final String KEY_TASKS = "tasks";
    private static final String KEY_DEST = "dest";
    private static final String KEY_TREE = "tree_uri";
    private static final int MAX_TASKS = 60;

    public static final int DEST_PUBLIC = 0;
    public static final int DEST_PRIVATE = 1;
    public static final int DEST_TREE = 2;

    public static final int ST_QUEUED = 0;
    public static final int ST_RUNNING = 1;
    public static final int ST_DONE = 2;
    public static final int ST_FAILED = 3;
    public static final int ST_CANCELED = 4;

    /** 一条下载记录。字段改动要同步 read/writeJson。 */
    public static final class Task {
        public String id;
        public String url;
        public String name;
        public String mime;
        public String userAgent;
        public String cookie;
        public int dest;
        public int status;
        public long done;
        public long total;
        public String error = "";
        public String uri = "";      // content:// 结果（public/tree）或私有文件的绝对路径
        public long createdAt;

        public boolean finished() {
            return status == ST_DONE;
        }

        public boolean active() {
            return status == ST_QUEUED || status == ST_RUNNING;
        }
    }

    public interface Listener {
        void onDownloadsChanged();
    }

    private static DownloadCenter instance;

    public static synchronized DownloadCenter get(Context context) {
        if (instance == null) instance = new DownloadCenter(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Task> tasks = new ArrayList<>();
    private Listener listener;

    private DownloadCenter(Context context) {
        this.app = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
        // 上次进程被杀时还在跑的，统一标记为失败，允许重试，不留下永久"下载中"
        boolean dirty = false;
        for (Task t : tasks) {
            if (t.active()) {
                t.status = ST_FAILED;
                t.error = "下载被中断，可重试";
                dirty = true;
            }
        }
        if (dirty) save();
    }

    /* ------------------------------------------------------------ 设置 -- */

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 默认：Android 10+ 直接进系统「下载」目录，用户能在文件管理器里找到。 */
    public int destination() {
        int d = prefs.getInt(KEY_DEST, Build.VERSION.SDK_INT >= 29 ? DEST_PUBLIC : DEST_PRIVATE);
        if (d == DEST_PUBLIC && Build.VERSION.SDK_INT < 29) return DEST_PRIVATE;
        if (d == DEST_TREE && treeUri().isEmpty()) return DEST_PUBLIC;
        return d;
    }

    public void setDestination(int dest) {
        prefs.edit().putInt(KEY_DEST, dest).apply();
    }

    public String treeUri() {
        return prefs.getString(KEY_TREE, "");
    }

    public void setTreeUri(String uri) {
        prefs.edit().putString(KEY_TREE, uri == null ? "" : uri).apply();
    }

    public String destinationLabel(Context ctx) {
        int d = destination();
        if (d == DEST_PRIVATE) return "应用内私有目录";
        if (d == DEST_TREE) {
            String name = treeDisplayName(ctx);
            return name.isEmpty() ? "自选文件夹" : "自选：" + name;
        }
        return "系统下载目录（Download）";
    }

    /** 给页面显示的一行路径/说明，尽量是用户能自己找到的位置。 */
    public String destinationDetail(Context ctx) {
        int d = destination();
        if (d == DEST_PRIVATE) return privateDir().getAbsolutePath();
        if (d == DEST_TREE) {
            String uri = treeUri();
            return uri.isEmpty() ? "未选择" : Uri.decode(uri);
        }
        return "Download/" + BuildConfig.APPLICATION_ID;
    }

    private String treeDisplayName(Context ctx) {
        try {
            Uri tree = Uri.parse(treeUri());
            String docId = DocumentsContract.getTreeDocumentId(tree);
            int colon = docId.indexOf(':');
            String tail = colon >= 0 ? docId.substring(colon + 1) : docId;
            return tail.isEmpty() ? "内部存储" : tail;
        } catch (Throwable t) {
            return "";
        }
    }

    public File privateDir() {
        File base = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = new File(app.getFilesDir(), "Download");
        if (!base.exists()) base.mkdirs();
        return base;
    }

    /* ------------------------------------------------------------ 任务 -- */

    public List<Task> tasks() {
        synchronized (tasks) {
            return new ArrayList<>(tasks);
        }
    }

    public Task byId(String id) {
        synchronized (tasks) {
            for (Task t : tasks) if (t.id.equals(id)) return t;
        }
        return null;
    }

    public Task enqueue(String url, String userAgent, String cookie,
                        String contentDisposition, String mime, long sizeHint) {
        if (url == null || url.isEmpty()) return null;
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        String name = URLUtil.guessFileName(url, contentDisposition, mime);
        if (name == null || name.trim().isEmpty() || "downloadfile.bin".equals(name)) {
            name = "download-" + System.currentTimeMillis();
        }
        Task t = new Task();
        t.id = UUID.randomUUID().toString();
        t.url = url;
        t.name = name;
        t.mime = mime;
        t.userAgent = userAgent;
        t.cookie = cookie;
        t.dest = destination();
        t.status = ST_QUEUED;
        t.total = sizeHint > 0 ? sizeHint : -1L;
        t.createdAt = System.currentTimeMillis();
        synchronized (tasks) {
            tasks.add(0, t);
            while (tasks.size() > MAX_TASKS) {
                Task old = null;
                for (int i = tasks.size() - 1; i >= 0; i--) {
                    if (!tasks.get(i).active()) { old = tasks.get(i); break; }
                }
                if (old == null) break;
                tasks.remove(old);
            }
        }
        save();
        notifyChanged();
        start(t);
        return t;
    }

    public void retry(String id) {
        Task t = byId(id);
        if (t == null || t.active()) return;
        t.status = ST_QUEUED;
        t.done = 0;
        t.error = "";
        t.uri = "";
        save();
        notifyChanged();
        start(t);
    }

    public void cancel(String id) {
        Task t = byId(id);
        if (t == null) return;
        if (t.active()) {
            t.status = ST_CANCELED;
            t.error = "已取消";
            save();
            notifyChanged();
        }
    }

    /** 删除记录；已完成的自选/系统目录文件一并删除，避免留下孤儿。 */
    public void remove(String id) {
        Task t = byId(id);
        if (t == null) return;
        cancel(id);
        if (t.status == ST_DONE || t.status == ST_CANCELED) {
            deleteFile(t);
        }
        synchronized (tasks) { tasks.remove(t); }
        save();
        notifyChanged();
    }

    public void clearFinished() {
        List<Task> dead = new ArrayList<>();
        synchronized (tasks) {
            for (Task t : tasks) if (!t.active()) dead.add(t);
        }
        for (Task t : dead) {
            deleteFile(t);
            synchronized (tasks) { tasks.remove(t); }
        }
        save();
        notifyChanged();
    }

    private void deleteFile(Task t) {
        try {
            if (t.dest == DEST_PRIVATE) {
                File f = new File(t.uri);
                if (f.exists()) f.delete();
            } else if (!t.uri.isEmpty()) {
                Uri u = Uri.parse(t.uri);
                if (t.dest == DEST_TREE) {
                    DocumentsContract.deleteDocument(app.getContentResolver(), u);
                } else {
                    app.getContentResolver().delete(u, null, null);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "删除下载文件失败: " + t.name, e);
        }
    }

    /* ------------------------------------------------------------ 执行 -- */

    private void start(final Task t) {
        t.status = ST_RUNNING;
        save();
        notifyChanged();
        pool.execute(new Runnable() {
            @Override public void run() {
                runTask(t);
            }
        });
    }

    private void runTask(Task t) {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        Uri pendingUri = null;
        File pendingFile = null;
        try {
            URL u = new URL(t.url);
            int code = 0;
            for (int hop = 0; hop < 5; hop++) {
                conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Accept", "*/*");
                if (t.userAgent != null) conn.setRequestProperty("User-Agent", t.userAgent);
                if (t.cookie != null) conn.setRequestProperty("Cookie", t.cookie);
                code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (loc == null) throw new IOException("重定向缺少地址");
                    u = new URL(u, loc);
                    continue;
                }
                break;
            }
            if (code != 200 && code != 206) throw new IOException("服务器返回 HTTP " + code);
            long total = conn.getContentLengthLong();
            if (total > 0) t.total = total;
            String ct = conn.getContentType();
            if (ct != null && t.mime.startsWith("application/octet-stream")) {
                int semi = ct.indexOf(';');
                String clean = (semi > 0 ? ct.substring(0, semi) : ct).trim();
                if (clean.contains("/")) t.mime = clean;
            }
            in = conn.getInputStream();

            t.done = 0;
            notifyChanged();
            if (t.dest == DEST_PRIVATE) {
                pendingFile = uniqueFile(privateDir(), t.name);
                out = new FileOutputStream(pendingFile);
            } else if (t.dest == DEST_TREE && !treeUri().isEmpty()) {
                Uri tree = Uri.parse(treeUri());
                Uri doc = DocumentsContract.createDocument(
                        app.getContentResolver(), tree, t.mime, t.name);
                if (doc == null) throw new IOException("无法在所选文件夹创建文件");
                pendingUri = doc;
                out = app.getContentResolver().openOutputStream(doc, "w");
            } else {
                pendingUri = insertPublic(t);
                out = app.getContentResolver().openOutputStream(pendingUri, "w");
            }
            if (out == null) throw new IOException("无法写入目标文件");

            byte[] buf = new byte[64 * 1024];
            int n;
            long lastTick = 0L;
            while ((n = in.read(buf)) > 0) {
                Task live = byId(t.id);
                if (live == null || live.status == ST_CANCELED) {
                    throw new IOException("已取消");
                }
                out.write(buf, 0, n);
                t.done += n;
                long now = System.currentTimeMillis();
                if (now - lastTick > 320L) {
                    lastTick = now;
                    notifyChanged();
                }
            }
            out.flush();
            out.close();
            out = null;
            if (pendingUri != null && t.dest == DEST_PUBLIC) finalizePublic(pendingUri, t);

            Task live = byId(t.id);
            if (live == null || live.status == ST_CANCELED) {
                if (pendingFile != null) pendingFile.delete();
                if (pendingUri != null) deleteQuietly(pendingUri, t.dest);
                return;
            }
            t.status = ST_DONE;
            t.uri = t.dest == DEST_PRIVATE ? pendingFile.getAbsolutePath() : pendingUri.toString();
            if (t.total <= 0) t.total = t.done;
            t.error = "";
            save();
            notifyChanged();
            Log.i(TAG, "下载完成: " + t.name + " (" + t.done + " B) -> " + t.uri);
        } catch (Throwable e) {
            Task live = byId(t.id);
            boolean canceled = live == null || live.status == ST_CANCELED;
            if (pendingFile != null) pendingFile.delete();
            if (pendingUri != null) deleteQuietly(pendingUri, t.dest);
            if (!canceled) {
                t.status = ST_FAILED;
                String msg = e.getMessage();
                t.error = (msg == null || msg.isEmpty()) ? e.getClass().getSimpleName() : msg;
                Log.w(TAG, "下载失败: " + t.url + " -> " + t.error);
                save();
                notifyChanged();
            }
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            if (conn != null) conn.disconnect();
        }
    }

    private Uri insertPublic(Task t) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("系统版本过低，无法写入系统下载目录");
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, t.name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, t.mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("系统下载目录不可写");
        return uri;
    }

    private void deleteQuietly(Uri uri, int dest) {
        try {
            if (dest == DEST_TREE) {
                DocumentsContract.deleteDocument(app.getContentResolver(), uri);
            } else {
                app.getContentResolver().delete(uri, null, null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, System.currentTimeMillis() + "-" + name);
    }

    /* -------------------------------------------------------- 打开/定位 -- */

    /** 得到可被外部应用读取的 content:// 地址；私有目录走 LocalFileProvider。 */
    public Uri contentUri(Task t) {
        if (t == null || t.status != ST_DONE || t.uri.isEmpty()) return null;
        try {
            if (t.dest == DEST_PRIVATE) {
                return LocalFileProvider.uriFor(new File(t.uri));
            }
            return Uri.parse(t.uri);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 用系统查看器打开；失败返回 false，由调用方给可读提示。 */
    public boolean openFile(Context ctx, Task t) {
        Uri uri = contentUri(t);
        if (uri == null) return false;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, t.mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(view);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "打开下载文件失败: " + t.name, e);
            return false;
        }
    }

    private void finalizePublic(Uri uri, Task t) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            app.getContentResolver().update(uri, v, null, null);
        } catch (Throwable ignored) {
        }
        // 同名文件系统会自动改名（shot (1).png）；把真实名字同步回列表，用户才对得上
        Cursor c = null;
        try {
            c = app.getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String real = c.getString(0);
                if (real != null && !real.isEmpty()) t.name = real;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    public boolean shareFile(Context ctx, Task t) {
        Uri uri = contentUri(t);
        if (uri == null) return false;
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(t.mime == null ? "*/*" : t.mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newRawUri(t.name, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(Intent.createChooser(send, "发送 " + t.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 打开文件所在位置：系统下载目录 / 自选文件夹 / 私有目录（只能给路径）。 */
    public boolean openFolder(Context ctx) {
        int d = destination();
        if (d == DEST_TREE && !treeUri().isEmpty()) {
            try {
                Uri tree = Uri.parse(treeUri());
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(tree, DocumentsContract.Document.MIME_TYPE_DIR);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
        }
        if (d == DEST_PUBLIC) {
            try {
                Intent i = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                        DocumentsContract.Document.MIME_TYPE_DIR);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static void copyText(Context ctx, String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && text != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 给界面用的一行状态。 */
    public static String stateLine(Task t) {
        if (t.status == ST_RUNNING) {
            if (t.total > 0) {
                int pct = (int) Math.min(100, t.done * 100 / t.total);
                return "下载中 " + pct + "% · " + human(t.done) + " / " + human(t.total);
            }
            return "下载中 · " + human(t.done);
        }
        if (t.status == ST_QUEUED) return "等待中";
        if (t.status == ST_DONE) return "已完成 · " + human(t.total > 0 ? t.total : t.done);
        if (t.status == ST_CANCELED) return "已取消";
        return t.error == null || t.error.isEmpty() ? "失败" : "失败：" + t.error;
    }

    public static String human(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /* ------------------------------------------------------------ 存储 -- */

    private void notifyChanged() {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onDownloadsChanged();
            }
        });
    }

    private void load() {
        String raw = prefs.getString(KEY_TASKS, "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Task t = new Task();
                t.id = o.optString("id", "");
                if (t.id.isEmpty()) continue;
                t.url = o.optString("url", "");
                t.name = o.optString("name", "download");
                t.mime = o.optString("mime", "application/octet-stream");
                t.userAgent = o.optString("ua", null);
                t.cookie = o.optString("cookie", null);
                t.dest = o.optInt("dest", DEST_PRIVATE);
                t.status = o.optInt("status", ST_FAILED);
                t.done = o.optLong("done", 0L);
                t.total = o.optLong("total", -1L);
                t.error = o.optString("error", "");
                t.uri = o.optString("uri", "");
                t.createdAt = o.optLong("at", 0L);
                tasks.add(t);
            }
        } catch (Throwable e) {
            Log.w(TAG, "读取下载记录失败", e);
        }
    }

    private void save() {
        JSONArray arr = new JSONArray();
        synchronized (tasks) {
            for (Task t : tasks) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", t.id);
                    o.put("url", t.url);
                    o.put("name", t.name);
                    o.put("mime", t.mime);
                    o.put("ua", t.userAgent == null ? "" : t.userAgent);
                    o.put("cookie", t.cookie == null ? "" : t.cookie);
                    o.put("dest", t.dest);
                    o.put("status", t.status);
                    o.put("done", t.done);
                    o.put("total", t.total);
                    o.put("error", t.error == null ? "" : t.error);
                    o.put("uri", t.uri == null ? "" : t.uri);
                    o.put("at", t.createdAt);
                    arr.put(o);
                } catch (Throwable ignored) {
                }
            }
        }
        prefs.edit().putString(KEY_TASKS, arr.toString()).apply();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
