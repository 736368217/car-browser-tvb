package com.carweb.audit;
import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.io.*;
/** Behavior tests against the installed production DownloadCenter, with a local HTTP fixture. */
public final class DownloadAudit {
 static Object center;static Class<?> type,taskType;static Context ctx;
 static JSONArray checks=new JSONArray();static java.util.List<Object> made=new java.util.ArrayList<>();
 static Object call(String n,Class<?>[] types,Object... values)throws Exception{return type.getMethod(n,types).invoke(center,values);}
 static Object field(Object t,String n)throws Exception{return t.getClass().getField(n).get(t);}
 static void check(String n,boolean ok)throws Exception{JSONObject r=new JSONObject();r.put("name",n);r.put("pass",ok);checks.put(r);if(!ok)throw new AssertionError(n);}
 static Object enqueue(String path,String name)throws Exception{
  Object t=call("enqueue",new Class[]{String.class,String.class,String.class,String.class,String.class,long.class},"http://10.0.2.2:8099"+path,"car-ui-audit",null,"attachment; filename=\""+name+"\"","application/octet-stream",-1L);made.add(t);return t;
 }
 static int status(Object t)throws Exception{return (Integer)field(t,"status");}
 static String id(Object t)throws Exception{return (String)field(t,"id");}
 static void waitEnd(Object t)throws Exception{long end=android.os.SystemClock.uptimeMillis()+20000;while(status(t)<2&&android.os.SystemClock.uptimeMillis()<end)Thread.sleep(100);check("download terminated: "+field(t,"name"),status(t)>=2);}
 public static JSONObject run(Context context)throws Exception {
  ctx=context;type=Class.forName("com.carweb.tvb.DownloadCenter",true,ctx.getClassLoader());taskType=Class.forName("com.carweb.tvb.DownloadCenter$Task",true,ctx.getClassLoader());
  center=type.getMethod("get",Context.class).invoke(null,ctx);
  int original=(Integer)call("destination",new Class[]{});String originalTree=(String)call("treeUri",new Class[]{});
  checks=new JSONArray();made.clear();JSONObject result=new JSONObject();
  try{
   call("setDestination",new Class[]{int.class},1);
   Object one=enqueue("/small.bin","audit615.bin");waitEnd(one);check("private completed",status(one)==2);
   long size=(Long)field(one,"done");check("complete bytes",size==12288);
   Object two=enqueue("/small.bin","audit615.bin");waitEnd(two);check("duplicate name protected",!field(one,"uri").equals(field(two,"uri")));
   Object percent=enqueue("/percent","audit%20note.txt");waitEnd(percent);check("percent completed",status(percent)==2);
   Uri uri=(Uri)call("contentUri",new Class[]{taskType},percent);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
   try(InputStream in=ctx.getContentResolver().openInputStream(uri)){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);}
   check("provider preserves percent filename",new String(bytes.toByteArray(),"UTF-8").contains("car-browser audit"));
   Object cut=enqueue("/cut.bin","audit615-cut.bin");waitEnd(cut);check("truncated response fails",status(cut)==3);
   Object slow=enqueue("/slow.bin","audit615-slow.bin");long until=System.currentTimeMillis()+6000;while((Long)field(slow,"done")==0&&System.currentTimeMillis()<until)Thread.sleep(80);
   call("cancel",new Class[]{String.class},id(slow));check("cancel state",status(slow)==4);call("retry",new Class[]{String.class},id(slow));waitEnd(slow);check("immediate retry completes",status(slow)==2);
   String saved=(String)field(one,"uri");call("forget",new Class[]{String.class},id(one));check("forget preserves file",new File(saved).isFile());new File(saved).delete();
   call("setDestination",new Class[]{int.class},0);Object pub=enqueue("/small.bin","audit615-public.bin");waitEnd(pub);check("public completed",status(pub)==2);check("public readable",(Boolean)call("fileExists",new Class[]{taskType},pub));
   if(!originalTree.isEmpty()){
    call("setDestination",new Class[]{int.class},2);Object saf=enqueue("/small.bin","audit615-saf.bin");waitEnd(saf);check("SAF completed",status(saf)==2);check("SAF readable",(Boolean)call("fileExists",new Class[]{taskType},saf));
    call("setDestination",new Class[]{int.class},0);check("SAF retains original folder",originalTree.equals(field(saf,"tree")));
   }
   boolean rejected=false;try{enqueue("/ignored","unused");}catch(Exception ignored){}
   try{call("enqueue",new Class[]{String.class,String.class,String.class,String.class,String.class,long.class},"data:text/plain,hello",null,null,null,"text/plain",-1L);}catch(InvocationTargetException expected){rejected=true;}
   check("unsupported URL rejected before task",rejected);
   result.put("pass",true);
  }catch(Throwable e){result.put("pass",false);result.put("error",e.toString());}
  finally{
   for(Object t:made){try{call("remove",new Class[]{String.class},id(t));}catch(Throwable ignored){}}
   call("setDestination",new Class[]{int.class},original);
  }
  result.put("checks",checks);return result;
 }
}
