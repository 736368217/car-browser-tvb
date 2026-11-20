package com.carweb.audit;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;

/** Real DownloadManager files, then the installed Activity's completion and install path. */
public final class UpdateFlowAudit {
    private static void check(JSONArray checks,String name,boolean pass)throws Exception {
        JSONObject row=new JSONObject();row.put("name",name);row.put("pass",pass);checks.put(row);
        if(!pass)throw new AssertionError(name);
    }
    private static Field field(Activity a,String name)throws Exception {Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private static void main(Instrumentation i,Activity a,String name,Class<?>[] types,Object...params)throws Exception {
        Method method=a.getClass().getDeclaredMethod(name,types);method.setAccessible(true);final Throwable[] error=new Throwable[1];
        i.runOnMainSync(()->{try{method.invoke(a,params);}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new Exception(error[0]);
    }
    private static String hash(File file)throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}
        StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format(java.util.Locale.US,"%02x",b));return s.toString();
    }
    public static JSONObject run(Instrumentation i,Activity a,File out)throws Exception {
        JSONArray checks=new JSONArray();JSONObject result=new JSONObject();DownloadManager dm=(DownloadManager)a.getSystemService(Context.DOWNLOAD_SERVICE);
        File installed=new File(a.getApplicationInfo().sourceDir);String expected=hash(installed);long size=installed.length();
        java.net.ServerSocket server=new java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));
        Thread http=new Thread(()->{
            while(!server.isClosed())try(java.net.Socket socket=server.accept()){
                socket.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII));
                String line=reader.readLine();if(line==null)continue;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                OutputStream response=socket.getOutputStream();String headers="HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: "+size+"\r\nConnection: close\r\n\r\n";
                response.write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));try(InputStream data=new FileInputStream(installed)){byte[] b=new byte[32768];int n;while((n=data.read(b))!=-1)response.write(b,0,n);}response.flush();
            }catch(IOException ignored){}
        },"update-audit-loopback");http.setDaemon(true);http.start();
        long id=-1;File dest=null;
        try {
            check(checks,"installer permission available",a.getPackageManager().canRequestPackageInstalls());
            for(String mode:new String[]{"digest","size","valid"}){
                String name="audit620-update-"+mode+"-"+System.currentTimeMillis()+".apk";
                dest=new File(a.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),name);
                DownloadManager.Request req=new DownloadManager.Request(Uri.parse("http://127.0.0.1:"+server.getLocalPort()+"/update.apk"));
                req.setDestinationInExternalFilesDir(a,android.os.Environment.DIRECTORY_DOWNLOADS,name);req.setNotificationVisibility(0);
                id=dm.enqueue(req);int status=0;long until=SystemClock.uptimeMillis()+25000;
                while(SystemClock.uptimeMillis()<until){try(android.database.Cursor c=dm.query(new DownloadManager.Query().setFilterById(id))){if(c!=null&&c.moveToFirst())status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));}if(status==DownloadManager.STATUS_SUCCESSFUL||status==DownloadManager.STATUS_FAILED)break;SystemClock.sleep(100);}
                JSONObject diagnostic=new JSONObject();diagnostic.put("status",status);diagnostic.put("expectedFile",dest.getAbsolutePath());diagnostic.put("fileExists",dest.isFile());
                try(android.database.Cursor c=dm.query(new DownloadManager.Query().setFilterById(id))){if(c!=null&&c.moveToFirst()){diagnostic.put("reason",c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));diagnostic.put("uri",c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)));diagnostic.put("bytes",c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));}}
                result.put(mode+"Download",diagnostic);
                check(checks,mode+" real download completed",status==DownloadManager.STATUS_SUCCESSFUL&&dest.isFile());
                check(checks,mode+" downloaded APK byte identity",dest.length()==size&&expected.equals(hash(dest)));
                field(a,"pendingDownloadId").setLong(a,id);field(a,"pendingSha").set(a,"digest".equals(mode)?"0".repeat(64):expected);
                field(a,"pendingSize").setLong(a,"size".equals(mode)?size+1:size);field(a,"pendingVersion").set(a,"6.20");
                main(i,a,"finishUpdateDownload",new Class[]{long.class},id);
                String state=(String)field(a,"updateStatusText").get(a);
                if(!"valid".equals(mode)){
                    check(checks,mode+" rejected by product",state.equals("校验失败"));
                    check(checks,mode+" pending state cleared",field(a,"pendingDownloadId").getLong(a)==-1L);
                    until=SystemClock.uptimeMillis()+4000;while(dest.exists()&&SystemClock.uptimeMillis()<until)SystemClock.sleep(100);
                    check(checks,mode+" rejected file removed",!dest.exists());
                }else{
                    SystemClock.sleep(1600);android.view.accessibility.AccessibilityNodeInfo root=i.getUiAutomation().getRootInActiveWindow();
                    String pkg=root==null?"":String.valueOf(root.getPackageName());
                    android.content.Intent view=new android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(dm.getUriForDownloadedFile(id),"application/vnd.android.package-archive");
                    android.content.pm.ResolveInfo resolved=a.getPackageManager().resolveActivity(view,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                    String handler=resolved==null||resolved.activityInfo==null?"":resolved.activityInfo.packageName;
                    result.put("installerPackage",pkg);result.put("resolvedApkHandler",handler);
                    check(checks,"valid APK handed to system installer",state.equals("已交给系统安装器")&&!handler.isEmpty());
                    Bitmap shot=i.getUiAutomation().takeScreenshot();if(shot!=null){try(OutputStream stream=new FileOutputStream(new File(out,"installer.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,stream);}shot.recycle();}
                    try(android.os.ParcelFileDescriptor.AutoCloseInputStream stream=new android.os.ParcelFileDescriptor.AutoCloseInputStream(i.getUiAutomation().executeShellCommand("input keyevent 4"))){byte[] b=new byte[512];while(stream.read(b)!=-1){}}
                }
                dm.remove(id);id=-1;dest=null;
            }
            result.put("pass",true);
        }catch(Throwable e){result.put("pass",false);result.put("error",e.toString());}
        finally{server.close();if(id>0)dm.remove(id);if(dest!=null&&dest.exists())dest.delete();main(i,a,"clearPendingDownload",new Class[]{});}
        result.put("apkSha256",expected);result.put("checks",checks);return result;
    }
}
