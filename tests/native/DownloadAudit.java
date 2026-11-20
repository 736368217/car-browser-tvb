package com.carweb.audit;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/** Behavior tests against installed production DownloadCenter using isolated fixture names. */
public final class DownloadAudit {
 static Object center; static Class<?> type,taskType; static Context ctx;
 static JSONArray checks; static List<Object> made; static String prefix;
 private DownloadAudit(){}
 static Object call(String n,Class<?>[] types,Object...values)throws Exception{return type.getMethod(n,types).invoke(center,values);}
 static Object field(Object t,String n)throws Exception{return t.getClass().getField(n).get(t);}
 static void setField(Object t,String n,Object value)throws Exception{t.getClass().getField(n).set(t,value);}
 static void check(String n,boolean ok)throws Exception{check(n,ok,"");}
 static void check(String n,boolean ok,String detail)throws Exception{JSONObject r=new JSONObject();r.put("name",n);r.put("pass",ok);r.put("detail",detail==null?"":detail);checks.put(r);}
 static Object enqueue(String path,String name)throws Exception{
  Object t=call("enqueue",new Class[]{String.class,String.class,String.class,String.class,String.class,long.class},"http://10.0.2.2:8099"+path,"car-ui-audit",null,"attachment; filename=\""+name+"\"","application/octet-stream",-1L);made.add(t);return t;
 }
 static int status(Object t)throws Exception{return (Integer)field(t,"status");}
 static String id(Object t)throws Exception{return (String)field(t,"id");}
 static void waitEnd(Object t)throws Exception{long end=android.os.SystemClock.uptimeMillis()+20000;while(status(t)<2&&android.os.SystemClock.uptimeMillis()<end)Thread.sleep(100);check("download terminated: "+field(t,"name"),status(t)>=2);check("download state "+field(t,"name"),status(t)==2||status(t)==3||status(t)==4||status(t)==5);}
 static byte[] contents(Object task)throws Exception{
  Uri uri=(Uri)call("contentUri",new Class[]{taskType},task);if(uri==null)return new byte[0];
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  try(InputStream in=ctx.getContentResolver().openInputStream(uri)){if(in==null)return new byte[0];byte[] b= new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
  return out.toByteArray();
 }
 static void persist()throws Exception{java.lang.reflect.Method m=type.getDeclaredMethod("save");m.setAccessible(true);m.invoke(center);}
 static Object reloadCenter()throws Exception{java.lang.reflect.Field f=type.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);return type.getMethod("get",Context.class).invoke(null,ctx);}
 public static JSONObject run(Context context)throws Exception{
  ctx=context;type=Class.forName("com.carweb.tvb.DownloadCenter",true,ctx.getClassLoader());
  taskType=Class.forName("com.carweb.tvb.DownloadCenter$Task",true,ctx.getClassLoader());
  center=type.getMethod("get",Context.class).invoke(null,ctx);int original=(Integer)call("destination",new Class[]{});
  String originalTree=(String)call("treeUri",new Class[]{});checks=new JSONArray();made=new ArrayList<>();
  prefix="audit620-"+Long.toString(System.currentTimeMillis(),36);JSONObject result=new JSONObject();
  try{
   call("setDestination",new Class[]{int.class},1);
   Object one=enqueue("/small.bin",prefix+"-private.bin");waitEnd(one);
   check("private completed",status(one)==2&&((Long)field(one,"done"))==12288L,"status="+status(one)+" done="+field(one,"done")+" total="+field(one,"total")+" uri="+field(one,"uri"));
   byte[] privateData=contents(one);check("private bytes readable",privateData.length==12288&&new String(privateData,0,18,"UTF-8").equals("car-browser audit\n"));
   persist();center=reloadCenter();Object reloaded=call("byId",new Class[]{String.class},id(one));
   boolean reopened=reloaded!=null&&(Boolean)call("fileExists",new Class[]{taskType},reloaded);
   check("private task readable after center reload",reopened);
   byte[] reloadedData=reloaded==null?new byte[0]:contents(reloaded);
   check("private bytes readable after center reload",reloadedData.length==12288&&new String(reloadedData,0,18,"UTF-8").equals("car-browser audit\n"));
   one=reloaded;
   // Internal app-data migration is covered by the target-process UI upgrade audit; this instrumentation UID cannot write target files.
   Object two=enqueue("/small.bin",prefix+"-private.bin");waitEnd(two);
   check("duplicate name protected",!field(one,"uri").equals(field(two,"uri")));
   Object percent=enqueue("/percent",prefix+"%20note.txt");waitEnd(percent);
   byte[] percentData=contents(percent);check("provider percent filename bytes",new String(percentData,"UTF-8").equals("car-browser audit\n"));
   Object cut=enqueue("/cut.bin",prefix+"-cut.bin");waitEnd(cut);check("truncated response fails",status(cut)==3);
   Object slow=enqueue("/slow.bin",prefix+"-slow.bin");long until=android.os.SystemClock.uptimeMillis()+6000;
   while((Long)field(slow,"done")==0&&android.os.SystemClock.uptimeMillis()<until)Thread.sleep(80);
   call("cancel",new Class[]{String.class},id(slow));check("cancel state",status(slow)==4);
   call("retry",new Class[]{String.class},id(slow));waitEnd(slow);check("immediate retry completes",status(slow)==2);
   Object missing=enqueue("/small.bin",prefix+"-missing.bin");waitEnd(missing);
   new java.io.File((String)field(missing,"uri")).delete();
   boolean missingExists=(Boolean)call("fileExists",new Class[]{taskType},missing);
   check("missing private file marked retryable",!missingExists&&status(missing)==3&&String.valueOf(field(missing,"error")).contains("可重试"));
   call("retry",new Class[]{String.class},id(missing));waitEnd(missing);
   check("missing private file retry succeeds",status(missing)==2);
   String saved=(String)field(one,"uri");call("forget",new Class[]{String.class},id(one));check("forget preserves file",new java.io.File(saved).isFile());new java.io.File(saved).delete();
   call("setDestination",new Class[]{int.class},0);Object pub=enqueue("/small.bin",prefix+"-public.bin");waitEnd(pub);
   byte[] publicData=contents(pub);check("public bytes readable",publicData.length==12288&&new String(publicData,0,18,"UTF-8").equals("car-browser audit\n"));
   setField(pub,"uri","content://media/external/downloads/0");persist();center=reloadCenter();Object recoveredPublic=call("byId",new Class[]{String.class},id(pub));
   boolean publicOpen=recoveredPublic!=null&&(Boolean)call("fileExists",new Class[]{taskType},recoveredPublic);
   check("public MediaStore URI recovered after app restart",publicOpen&&!"content://media/external/downloads/0".equals(field(recoveredPublic,"uri")));
   byte[] recoveredPublicData=recoveredPublic==null?new byte[0]:contents(recoveredPublic);
   check("recovered public bytes readable",recoveredPublicData.length==12288&&new String(recoveredPublicData,0,18,"UTF-8").equals("car-browser audit\n"));
   pub=recoveredPublic;
   if(!originalTree.isEmpty()){
    call("setDestination",new Class[]{int.class},2);Object saf=enqueue("/small.bin",prefix+"-saf.bin");waitEnd(saf);
    byte[] safData=contents(saf);check("SAF bytes readable",status(saf)==2&&safData.length==12288&&new String(safData,0,18,"UTF-8").equals("car-browser audit\n"));
    call("setDestination",new Class[]{int.class},0);check("SAF retains selected folder",originalTree.equals(field(saf,"tree")));
   }
   boolean rejected=false;
   try{call("enqueue",new Class[]{String.class,String.class,String.class,String.class,String.class,long.class},"data:text/plain,hello",null,null,null,"text/plain",-1L);}
   catch(InvocationTargetException expected){rejected=true;}
   check("unsupported URL rejected before task",rejected);result.put("pass",true);result.put("fixturePrefix",prefix);
  }catch(Throwable e){result.put("pass",false);result.put("error",e.toString());result.put("fixturePrefix",prefix);}
  finally{
   for(Object t:made){try{call("remove",new Class[]{String.class},id(t));}catch(Throwable ignored){}}
   long end=android.os.SystemClock.uptimeMillis()+10000;int remaining;
   do{remaining=0;for(Object t:made)try{if(call("byId",new Class[]{String.class},id(t))!=null)remaining++;}catch(Throwable ignored){}if(remaining>0)Thread.sleep(100);}while(remaining>0&&android.os.SystemClock.uptimeMillis()<end);
   result.put("cleanupMatched",made.size());result.put("cleanupRemaining",remaining);check("owned task cleanup",remaining==0);
   call("setDestination",new Class[]{int.class},original);
  }
  result.put("checks",checks);result.put("failed",0);for(int i=0;i<checks.length();i++)if(!checks.getJSONObject(i).optBoolean("pass"))result.put("failed",result.getInt("failed")+1);
  result.put("pass",result.optBoolean("pass")&&result.optInt("failed")==0);return result;
 }
}
