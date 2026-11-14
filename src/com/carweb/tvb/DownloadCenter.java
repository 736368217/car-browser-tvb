package com.carweb.tvb;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads to MediaStore, app storage or a persistently granted SAF folder. */
public final class DownloadCenter {
    private static final String PREFS="downloads_v2", KEY_TASKS="tasks", KEY_DEST="dest", KEY_TREE="tree_uri";
    public static final int DEST_PUBLIC=0, DEST_PRIVATE=1, DEST_TREE=2;
    public static final int ST_QUEUED=0, ST_RUNNING=1, ST_DONE=2, ST_FAILED=3, ST_CANCELED=4, ST_DELETING=5;
    public static final class Task {
        public String id,url,userAgent;
        public volatile String name,mime,error="",uri="",tree="";
        public int dest;
        public volatile int status;
        public volatile long done,total;
        public long createdAt;
        private int generation;
        public boolean finished(){return status==ST_DONE;}
        public boolean active(){return status==ST_QUEUED||status==ST_RUNNING;}
    }
    private static final class Run {
        final Task task; final int generation;
        volatile HttpURLConnection connection;
        volatile InputStream input;
        Run(Task task,int generation){this.task=task;this.generation=generation;}
        void disconnect(){try{if(connection!=null)connection.disconnect();}catch(Throwable ignored){} close(input);}
    }
    public interface Listener { void onDownloadsChanged(); }
    private static DownloadCenter instance;
    public static synchronized DownloadCenter get(Context context){if(instance==null)instance=new DownloadCenter(context.getApplicationContext());return instance;}
    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService pool=Executors.newFixedThreadPool(2), storage=Executors.newSingleThreadExecutor();
    private final List<Task> tasks=new ArrayList<>();
    private final Map<String,Run> runs=new HashMap<>();
    private final Object outputLock=new Object();
    private volatile Listener listener;
    private DownloadCenter(Context app){
        this.app=app;prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);load();
        for(Task t:tasks){
            if(t.active()||t.status==ST_DELETING){
                String orphan=t.uri;int dest=t.dest;
                t.status=ST_FAILED;t.error="下载已中断，点重试继续";t.uri="";
                if(!orphan.isEmpty())storage.execute(()->deleteUri(dest,orphan));
            }
        }
        save();
    }
    public void setListener(Listener l){listener=l;}
    public int destination(){
        int d=prefs.getInt(KEY_DEST,Build.VERSION.SDK_INT>=29?DEST_PUBLIC:DEST_PRIVATE);
        if(d==DEST_TREE && treeUri().isEmpty())d=DEST_PUBLIC;
        return d==DEST_PUBLIC && Build.VERSION.SDK_INT<29?DEST_PRIVATE:d;
    }
    public void setDestination(int d){prefs.edit().putInt(KEY_DEST,d).apply();}
    public String treeUri(){return prefs.getString(KEY_TREE,"");}
    public void setTreeUri(String uri){prefs.edit().putString(KEY_TREE,uri==null?"":uri).apply();}
    public String destinationLabel(Context ctx){return folderLabel(destination(),treeUri());}
    public String destinationDetail(Context ctx){return folderPath(destination(),treeUri());}
    public String folderLabel(Task t){return folderLabel(t.dest,t.tree);}
    private String folderLabel(int dest,String tree){
        if(dest==DEST_PRIVATE)return "应用内目录";
        if(dest==DEST_PUBLIC)return "系统下载目录";
        return "自选："+treePath(tree);
    }
    private String folderPath(int dest,String tree){
        if(dest==DEST_PRIVATE)return privateDir().getAbsolutePath();
        if(dest==DEST_PUBLIC)return "内部存储 / Download";
        return treePath(tree);
    }
    private String treePath(String tree){
        try{String id=DocumentsContract.getTreeDocumentId(Uri.parse(tree));return id.replace("primary:","内部存储 / ");}
        catch(Throwable e){return "所选文件夹";}
    }
    public String fileLocation(Task t){
        if(t.dest==DEST_PRIVATE)return t.uri.isEmpty()?privateDir()+"/"+t.name:t.uri;
        if(t.dest==DEST_PUBLIC)return "内部存储 / Download / "+t.name;
        return treePath(t.tree)+" / "+t.name;
    }
    public File privateDir(){
        File d=app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(d==null)d=new File(app.getFilesDir(),"Download");
        if(!d.exists())d.mkdirs();return d;
    }
    public List<Task> tasks(){synchronized(tasks){return new ArrayList<>(tasks);}}
    public Task byId(String id){synchronized(tasks){for(Task t:tasks)if(t.id.equals(id))return t;return null;}}
    public Task enqueue(String url,String ua,String ignoredCookie,String disposition,String mime,long hint){
        if(url==null||!(url.startsWith("http://")||url.startsWith("https://")))throw new IllegalArgumentException("此链接暂不支持下载，请使用网页提供的文件链接");
        Task t=new Task();t.id=UUID.randomUUID().toString();t.url=url;t.userAgent=ua;
        t.name=safeName(URLUtil.guessFileName(url,disposition,mime));t.mime=mimeFor(t.name,mime);
        t.dest=destination();t.tree=t.dest==DEST_TREE?treeUri():"";t.status=ST_QUEUED;t.total=hint>0?hint:-1;t.createdAt=System.currentTimeMillis();
        synchronized(tasks){tasks.add(0,t);save();}start(t);return t;
    }
    public void retry(String id){
        synchronized(tasks){Task t=byId(id);if(t==null||t.active()||t.status==ST_DONE||t.status==ST_DELETING)return;
            t.done=0;t.total=-1;t.error="";t.uri="";start(t);}
    }
    public void cancel(String id){
        Run run;
        synchronized(tasks){Task t=byId(id);if(t==null||!t.active())return;t.generation++;t.status=ST_CANCELED;t.error="";run=runs.remove(id);save();}
        if(run!=null)storage.execute(run::disconnect);notifyChanged();
    }
    /** Clear history without deleting the user's completed files. */
    public void clearFinished(){
        synchronized(tasks){tasks.removeIf(t->!t.active()&&t.status!=ST_DELETING);save();}notifyChanged();
    }
    public void forget(String id){
        synchronized(tasks){Task t=byId(id);if(t==null||t.active()||t.status==ST_DELETING)return;tasks.remove(t);save();}notifyChanged();
    }
    /** Explicit file deletion. Failure keeps the record and explains why. */
    public void remove(String id){
        Task t=byId(id);if(t==null)return;cancel(id);
        final String uri; final int previous;
        synchronized(tasks){previous=t.status;t.status=ST_DELETING;uri=t.uri;save();}notifyChanged();
        storage.execute(()->{
            boolean ok=uri.isEmpty()||deleteUri(t.dest,uri);
            synchronized(tasks){if(ok)tasks.remove(t);else {t.status=previous;t.error="无法删除文件，请检查文件夹授权";}save();}notifyChanged();
        });
    }
    private boolean deleteUri(int dest,String value){
        try{
            if(value.isEmpty())return true;
            if(dest==DEST_PRIVATE){File f=new File(value);return !f.exists()||f.delete();}
            Uri uri=Uri.parse(value);
            if(dest==DEST_TREE)return DocumentsContract.deleteDocument(app.getContentResolver(),uri);
            app.getContentResolver().delete(uri,null,null);return true;
        }catch(Throwable ignored){return false;}
    }
    private void start(Task t){
        final Run run;
        synchronized(tasks){t.generation++;t.status=ST_QUEUED;run=new Run(t,t.generation);runs.put(t.id,run);save();}
        notifyChanged();pool.execute(()->runTask(run));
    }
    private boolean current(Run r){synchronized(tasks){return byId(r.task.id)==r.task && r.task.generation==r.generation && r.task.active();}}
    private void check(Run r)throws InterruptedIOException{if(!current(r))throw new InterruptedIOException("已取消");}
    private void runTask(Run r){
        Task t=r.task;OutputStream out=null;String pending="";boolean committed=false;
        try{
            synchronized(tasks){check(r);t.status=ST_RUNNING;save();}notifyChanged();
            URL u=new URL(t.url);HttpURLConnection c=null;
            for(int hop=0;hop<6;hop++){
                check(r);
                if(!"http".equals(u.getProtocol())&&!"https".equals(u.getProtocol()))throw new IOException("不支持该下载链接");
                c=(HttpURLConnection)u.openConnection();r.connection=c;
                c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(20000);
                c.setRequestProperty("Accept-Encoding","identity");
                if(t.userAgent!=null&&!t.userAgent.isEmpty())c.setRequestProperty("User-Agent",t.userAgent);
                // Resolve cookies for this hop, never forward a source-domain Cookie header.
                String cookie=CookieManager.getInstance().getCookie(u.toString());
                if(cookie!=null&&!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);
                int status=c.getResponseCode();
                if(status>=300&&status<400){
                    String loc=c.getHeaderField("Location");c.disconnect();if(loc==null)throw new IOException("下载地址已失效");
                    URL next=new URL(u,loc);if("https".equals(u.getProtocol())&&!"https".equals(next.getProtocol()))throw new IOException("下载重定向不安全");
                    u=next;continue;
                }
                if(status!=200)throw new IOException("服务器返回 HTTP "+status);
                break;
            }
            if(c==null||c.getResponseCode()!=200)throw new IOException("下载地址重定向过多");
            check(r);long length=c.getContentLengthLong();
            String disposition=c.getHeaderField("Content-Disposition");
            String mime=mimeFor(t.name,c.getContentType());
            String name=disposition==null?t.name:safeName(URLUtil.guessFileName(u.toString(),disposition,mime));
            synchronized(tasks){check(r);t.total=length>0?length:-1;t.mime=mimeFor(name,mime);t.name=name;t.done=0;}
            r.input=c.getInputStream();
            synchronized(outputLock){
                check(r);
                if(t.dest==DEST_PRIVATE){File f=reserve(privateDir(),t.name);pending=f.getAbsolutePath();t.name=f.getName();out=new FileOutputStream(f);}
                else if(t.dest==DEST_TREE){
                    Uri tree=Uri.parse(t.tree);
                    Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                    Uri doc=DocumentsContract.createDocument(app.getContentResolver(),parent,t.mime,t.name);
                    if(doc==null)throw new IOException("所选文件夹不可写，请重新选择");pending=doc.toString();out=app.getContentResolver().openOutputStream(doc,"w");
                }else{pending=insertPublic(t).toString();out=app.getContentResolver().openOutputStream(Uri.parse(pending),"w");}
                if(out==null)throw new IOException("无法写入所选位置");
                synchronized(tasks){check(r);t.uri=pending;save();}
            }
            notifyChanged();byte[] buf=new byte[32768];int n;long tick=0;
            while((n=r.input.read(buf))!=-1){check(r);out.write(buf,0,n);synchronized(tasks){check(r);t.done+=n;}
                long now=System.currentTimeMillis();if(now-tick>=400){tick=now;notifyChanged();}}
            if(length>=0&&t.done!=length)throw new IOException("下载不完整（"+human(t.done)+" / "+human(length)+"），请重试");
            out.flush();out.close();out=null;check(r);
            if(t.dest!=DEST_PRIVATE){
                Uri uri=Uri.parse(pending);
                if(t.dest==DEST_PUBLIC){ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.IS_PENDING,0);app.getContentResolver().update(uri,v,null,null);}
                try(Cursor names=app.getContentResolver().query(uri,new String[]{"_display_name"},null,null,null)){
                    if(names!=null&&names.moveToFirst())t.name=names.getString(0);
                }
            }
            synchronized(tasks){check(r);t.status=ST_DONE;t.total=t.done;t.error="";runs.remove(t.id);save();committed=true;}
            notifyChanged();
        }catch(Throwable e){
            synchronized(tasks){if(current(r)){t.status=ST_FAILED;t.error=friendly(e);t.uri="";runs.remove(t.id);save();}}notifyChanged();
        }finally{
            close(out);r.disconnect();
            if(!committed&&!pending.isEmpty())deleteUri(t.dest,pending);
        }
    }
    private Uri insertPublic(Task t)throws Exception{
        if(Build.VERSION.SDK_INT<29)throw new IOException("请改用应用内目录或自选文件夹");
        String candidate=t.name;int i=1;
        while(publicNameExists(candidate))candidate=numbered(t.name,i++);
        ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,candidate);v.put(MediaStore.MediaColumns.MIME_TYPE,t.mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/");v.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
        if(uri==null)throw new IOException("系统下载目录不可写");t.name=candidate;return uri;
    }
    private boolean publicNameExists(String name){
        if(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),name).exists())return true;
        try(Cursor c=app.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,new String[]{"_id"},"_display_name=?",new String[]{name},null)){return c!=null&&c.moveToFirst();}
        catch(Throwable ignored){return false;}
    }
    private static File reserve(File dir,String name)throws IOException{
        for(int i=0;i<10000;i++){File f=new File(dir,i==0?name:numbered(name,i));if(f.createNewFile())return f;}
        throw new IOException("同名文件过多，请更改名称");
    }
    private static String numbered(String name,int i){int dot=name.lastIndexOf('.');return dot>0?name.substring(0,dot)+" ("+i+")"+name.substring(dot):name+" ("+i+")";}
    private static String safeName(String name){
        if(name==null)name="download";
        name=name.replaceAll("[\\\\/\\p{Cntrl}]","_").trim();if(name.isEmpty()||name.equals(".")||name.equals(".."))name="download";
        return name.length()>180?name.substring(0,160)+name.substring(Math.max(160,name.lastIndexOf('.'))):name;
    }
    public static String mimeFor(String name,String response){
        String clean=response==null?"":response.split(";",2)[0].trim().toLowerCase(Locale.US);
        String ext=MimeTypeMap.getFileExtensionFromUrl(name==null?"":name.replace(" ","_"));
        if(name!=null&&name.toLowerCase(Locale.US).endsWith(".apk"))return "application/vnd.android.package-archive";
        if(clean.isEmpty()||clean.equals("application/octet-stream")||clean.equals("binary/octet-stream")){
            String guess=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.US));if(guess!=null)return guess;
        }
        return clean.contains("/")?clean:"application/octet-stream";
    }
    private static String friendly(Throwable e){
        if(e instanceof SecurityException)return "文件夹授权已失效，请重新选择保存位置";
        String s=e.getMessage();return s==null||s.isEmpty()?"网络或存储异常，请重试":s;
    }
    public Uri contentUri(Task t){
        if(t==null||t.status!=ST_DONE||t.uri.isEmpty())return null;
        return t.dest==DEST_PRIVATE?LocalFileProvider.uriFor(new File(t.uri)):Uri.parse(t.uri);
    }
    public boolean fileExists(Task t){
        Uri uri=contentUri(t);if(uri==null)return false;
        try(android.os.ParcelFileDescriptor fd=app.getContentResolver().openFileDescriptor(uri,"r")){return fd!=null;}catch(Throwable e){return false;}
    }
    public boolean openFile(Context ctx,Task t){
        Uri uri=contentUri(t);if(uri==null)return false;
        try{Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(uri,mimeFor(t.name,t.mime));i.setClipData(ClipData.newRawUri(t.name,uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);ctx.startActivity(i);return true;}
        catch(Throwable e){return false;}
    }
    public boolean shareFile(Context ctx,Task t){
        Uri uri=contentUri(t);if(uri==null)return false;
        try{Intent i=new Intent(Intent.ACTION_SEND);i.setType(mimeFor(t.name,t.mime));i.putExtra(Intent.EXTRA_STREAM,uri);i.setClipData(ClipData.newRawUri(t.name,uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);ctx.startActivity(Intent.createChooser(i,"分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;}
        catch(Throwable e){return false;}
    }
    public boolean openFolder(Context ctx){return openFolder(ctx,destination(),treeUri());}
    public boolean openFolder(Context ctx,Task t){return openFolder(ctx,t.dest,t.tree);}
    private boolean openFolder(Context ctx,int dest,String tree){
        if(dest==DEST_PRIVATE)return false;
        try{
            Uri uri=dest==DEST_TREE?DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree),DocumentsContract.getTreeDocumentId(Uri.parse(tree))):DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download");
            Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(uri,DocumentsContract.Document.MIME_TYPE_DIR);
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,uri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);ctx.startActivity(i);return true;
        }catch(Throwable e){return false;}
    }
    public static void copyText(Context ctx,String label,String text){
        ClipboardManager cm=(ClipboardManager)ctx.getSystemService(Context.CLIPBOARD_SERVICE);if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText(label,text==null?"":text));
    }
    public static String stateLine(Task t){
        if(t.status==ST_RUNNING)return t.total>0?"下载中 "+Math.min(100,t.done*100/t.total)+"% · "+human(t.done)+" / "+human(t.total):"下载中 · "+human(t.done);
        if(t.status==ST_QUEUED)return "等待下载";
        if(t.status==ST_DONE)return t.error.isEmpty()?"已完成 · "+human(t.done):t.error;
        if(t.status==ST_CANCELED)return "已取消";
        if(t.status==ST_DELETING)return "正在删除文件…";
        return "失败："+t.error;
    }
    public static String human(long b){if(b<=0)return "0 B";if(b<1024)return b+" B";if(b<1048576)return String.format(Locale.US,"%.0f KB",b/1024.0);return String.format(Locale.US,"%.1f MB",b/1048576.0);}
    private void notifyChanged(){main.post(()->{Listener l=listener;if(l!=null)l.onDownloadsChanged();});}
    private void load(){
        try{JSONArray arr=new JSONArray(prefs.getString(KEY_TASKS,"[]"));for(int i=0;i<arr.length();i++){
            JSONObject o=arr.getJSONObject(i);Task t=new Task();t.id=o.optString("id");if(t.id.isEmpty())continue;
            t.url=o.optString("url");t.name=o.optString("name","download");t.mime=mimeFor(t.name,o.optString("mime"));t.userAgent=o.optString("ua","");
            t.dest=o.optInt("dest",DEST_PRIVATE);t.tree=o.optString("tree",t.dest==DEST_TREE?treeUri():"");t.status=o.optInt("status",ST_FAILED);
            t.done=o.optLong("done");t.total=o.optLong("total",-1);t.error=o.optString("error");t.uri=o.optString("uri");t.createdAt=o.optLong("at");tasks.add(t);
        }}catch(Throwable ignored){}
    }
    private void save(){
        synchronized(tasks){JSONArray arr=new JSONArray();for(Task t:tasks){try{JSONObject o=new JSONObject();
            o.put("id",t.id);o.put("url",t.url);o.put("name",t.name);o.put("mime",t.mime);o.put("ua",t.userAgent);o.put("dest",t.dest);o.put("tree",t.tree);
            o.put("status",t.status);o.put("done",t.done);o.put("total",t.total);o.put("error",t.error);o.put("uri",t.uri);o.put("at",t.createdAt);arr.put(o);
        }catch(Throwable ignored){}}prefs.edit().putString(KEY_TASKS,arr.toString()).apply();}
    }
    private static void close(java.io.Closeable c){if(c!=null)try{c.close();}catch(Throwable ignored){}}
}
