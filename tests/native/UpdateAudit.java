package com.carweb.audit;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Deterministic tests invoked against the installed production classes. */
public final class UpdateAudit {
    private static JSONArray checks;
    private static int failed;
    private UpdateAudit() { }

    private static void check(String name, boolean pass, String detail) throws Exception {
        JSONObject row = new JSONObject(); row.put("name", name); row.put("pass", pass);
        row.put("detail", detail == null ? "" : detail); checks.put(row); if (!pass) failed++;
    }
    private static String manifest(String apk, long size, String sha) {
        return "{\"version\":\"6.20\",\"apk\":\"" + apk + "\",\"size\":" + size
                + ",\"sha256\":\"" + sha + "\",\"notes\":\"audit\"}";
    }
    private static JSONObject parse(Method method, String json) throws Exception {
        Object result=method.invoke(null,json);Class<?> type=result.getClass();JSONObject out=new JSONObject();
        for(String field:new String[]{"tag","versionName","apkName","apkUrl","sha256","notes"})out.put(field,type.getField(field).get(result));
        out.put("size",type.getField("size").getLong(result));return out;
    }

    public static JSONObject run(Context context) throws Exception {
        checks = new JSONArray(); failed = 0;
        ClassLoader loader = context.getClassLoader();
        Class<?> updater = Class.forName("com.carweb.tvb.UpdateChecker", true, loader);
        Method parse = updater.getDeclaredMethod("parseManifest", String.class); parse.setAccessible(true);
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        JSONObject valid = parse(parse, manifest("car-browser/car-browser-6.20.apk", 12345, sha));
        String expectedUrl="https://dl.736.de5.net/car-browser/car-browser-6.20.apk";
        check("manifest valid accepted", valid.optLong("size") == 12345
                && sha.equals(valid.optString("sha256"))
                && "car-browser-6.20.apk".equals(valid.optString("apkName"))
                && expectedUrl.equals(valid.optString("apkUrl")), valid.toString());
        String[] invalid = {
                manifest("https://evil.example/update.apk",12345,sha),
                manifest("car-browser/../other.apk",12345,sha),
                manifest("car-browser/car-browser.apk",12345,"not-a-sha"),
                "{\"version\":\"6.20\",\"apk\":\"car-browser/a.apk\",\"size\":1}",
                manifest("car-browser/car-browser.apk",0,sha),
                manifest("car-browser/a.apk?x=1",12345,sha),
                manifest("carclash/car-browser.apk",12345,sha)
        };
        String[] names={"foreign host","path traversal","invalid sha","missing sha","invalid size","query suffix","wrong project"};
        for(int i=0;i<invalid.length;i++){
            boolean rejected=false;try{parse(parse,invalid[i]);}catch(Throwable expected){rejected=true;}
            check("manifest rejects "+names[i],rejected,names[i]);
        }
        Method verify=updater.getMethod("verifyDownloadedArtifact",long.class,String.class,long.class,String.class);
        check("artifact exact sha and size accepted",(Boolean)verify.invoke(null,12345L,sha,12345L,sha),"exact match");
        check("artifact wrong digest rejected",!(Boolean)verify.invoke(null,12345L,sha,12345L,sha.substring(0,63)+"0"),"wrong sha");
        check("artifact wrong byte count rejected",!(Boolean)verify.invoke(null,12345L,sha,12344L,sha),"wrong size");
        check("GitHub fallback size-only accepted",(Boolean)verify.invoke(null,12345L,"",12345L,sha),"exact size without API digest");
        Method compare=updater.getMethod("compareVersions",String.class,String.class);
        check("version numeric ordering",(Integer)compare.invoke(null,"6.20","6.19")>0
                &&(Integer)compare.invoke(null,"6.20","6.20.0")==0,"6.20 > 6.19");

        Class<?> dc=Class.forName("com.carweb.tvb.DownloadCenter",true,loader);
        Method safe=dc.getDeclaredMethod("safeName",String.class);safe.setAccessible(true);
        String[] inputs={"short.txt","x".repeat(250),"a.txt"+"b".repeat(250),
                "x".repeat(230)+".zip","界".repeat(100)+".tar.gz",
                "x".repeat(230)+".pdf","x".repeat(230)+".verylongextension"};
        int i=0;
        for(String input:inputs){
            String result=(String)safe.invoke(null,input);int bytes=result.getBytes(StandardCharsets.UTF_8).length;
            String ext=input.substring(input.lastIndexOf('.')+1);
            boolean keepExt=input.lastIndexOf('.')<0||ext.length()>32||result.endsWith(input.substring(input.lastIndexOf('.')));
            boolean validUnicode=result.length()>0&&!Character.isHighSurrogate(result.charAt(result.length()-1));
            check("safe filename case "+(++i),bytes<=220&&keepExt&&validUnicode,bytes+" UTF-8 bytes");
        }
        JSONObject out=new JSONObject();out.put("pass",failed==0);out.put("failed",failed);out.put("checks",checks);return out;
    }
}
