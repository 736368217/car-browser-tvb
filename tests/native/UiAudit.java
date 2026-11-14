package com.carweb.audit;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Standalone UI acceptance driver. Does not patch production code or use uiautomator CLI. */
public final class UiAudit extends Instrumentation {
    private Bundle args;
    private Activity activity;
    private File out;
    private final JSONArray results = new JSONArray();
    private int failures;

    @Override public void onCreate(Bundle arguments) { args = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            out = new File(getTargetContext().getExternalFilesDir(null), args.getString("out", "audit"));
            if (!out.exists() && !out.mkdirs()) throw new Exception("Cannot create output");
            getUiAutomation();
            Intent intent = getTargetContext().getPackageManager().getLaunchIntentForPackage("com.carweb.tvb");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(intent);
            SystemClock.sleep(2500);
            String planPath = args.getString("plan");
            JSONArray plan = new JSONArray(new String(Files.readAllBytes(new File(planPath).toPath()), StandardCharsets.UTF_8));
            for (int i=0; i<plan.length(); i++) {
                JSONObject step=plan.getJSONObject(i);
                String name=String.format(java.util.Locale.US,"%02d-%s",i,step.optString("name","step"));
                JSONObject row=new JSONObject(); row.put("name",name); row.put("action",step);
                try {
                    act(step);
                    SystemClock.sleep(step.optInt("settle",450));
                    JSONArray waitTexts=step.optJSONArray("expect");
                    long until=SystemClock.uptimeMillis()+10000;
                    while(waitTexts!=null && SystemClock.uptimeMillis()<until) {
                        JSONArray probe=new JSONArray();walk(getUiAutomation().getRootInActiveWindow(),probe,0);
                        String raw=probe.toString();boolean ready=true;
                        for(int k=0;k<waitTexts.length();k++) if(!raw.contains(waitTexts.getString(k))) ready=false;
                        if(ready)break;SystemClock.sleep(250);
                    }
                    JSONObject state=capture(name);
                    String texts=state.getJSONArray("nodes").toString();
                    JSONArray expected=step.optJSONArray("expect");
                    if(expected!=null) for(int k=0;k<expected.length();k++) {
                        if(!texts.contains(expected.getString(k))) throw new AssertionError("Missing: "+expected.getString(k));
                    }
                    JSONArray absent=step.optJSONArray("absent");
                    if(absent!=null) for(int k=0;k<absent.length();k++) {
                        if(texts.contains(absent.getString(k))) throw new AssertionError("Still present: "+absent.getString(k));
                    }
                    row.put("pass",true);
                } catch(Throwable e) { failures++; row.put("pass",false); row.put("error",e.toString()); }
                results.put(row); write(new File(out,"result.json"),summary());
                Bundle progress=new Bundle(); progress.putString("step",name); progress.putBoolean("pass",row.optBoolean("pass")); sendStatus(0,progress);
                if(!row.optBoolean("pass") && step.optBoolean("stopOnFailure",true)) break;
            }
            result.putInt("failed",failures); result.putString("output",out.getAbsolutePath());
        } catch(Throwable e) { failures++; result.putString("error",e.toString()); }
        try { if(out!=null) write(new File(out,"result.json"),summary()); } catch(Throwable ignored) {}
        result.putInt("failed",failures); finish(failures==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private JSONObject summary() throws Exception {
        JSONObject o=new JSONObject(); o.put("failed",failures); o.put("steps",results);
        o.put("pid",android.os.Process.myPid()); o.put("package",getTargetContext().getPackageName());
        String source=getTargetContext().getApplicationInfo().sourceDir;
        o.put("apkPath",source);
        java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256");
        try(java.io.InputStream in=new java.io.FileInputStream(source)) { byte[] b=new byte[32768]; int n; while((n=in.read(b))!=-1)md.update(b,0,n); }
        StringBuilder sha=new StringBuilder();for(byte b:md.digest())sha.append(String.format("%02x",b));o.put("sha256",sha.toString());
        return o;
    }
    private void act(JSONObject s) throws Exception {
        String op=s.optString("op","capture");
        if("capture".equals(op)) return;
        if("backend".equals(op)) { JSONObject result=DownloadAudit.run(getTargetContext());write(new File(out,"download-behavior.json"),result);if(!result.optBoolean("pass"))throw new AssertionError(result.toString());return; }
        if("tap".equals(op)) { tap((float)s.getDouble("x"),(float)s.getDouble("y"),s.optInt("hold",70)); return; }
        if("back".equals(op)) { key(KeyEvent.KEYCODE_BACK); return; }
        if("key".equals(op)) { key(s.getInt("key")); return; }
        if("swipe".equals(op)) {
            float x=(float)s.getDouble("x"),y=(float)s.getDouble("y"),x2=(float)s.getDouble("toX"),y2=(float)s.getDouble("toY");
            long t=SystemClock.uptimeMillis(); motion(t,t,MotionEvent.ACTION_DOWN,x,y);
            for(int i=1;i<=20;i++){SystemClock.sleep(s.optInt("duration",400)/20);motion(t,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x+(x2-x)*i/20,y+(y2-y)*i/20);}
            motion(t,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x2,y2);return;
        }
        if("click".equals(op)||"text".equals(op)||"longClick".equals(op)) {
            AccessibilityNodeInfo node=null;
            long deadline=SystemClock.uptimeMillis()+s.optInt("timeout",5000);
            while(node==null && SystemClock.uptimeMillis()<deadline) {
                node=find(getUiAutomation().getRootInActiveWindow(),s.getString("target"));
                if(node==null)SystemClock.sleep(200);
            }
            if(node==null) { if(s.optBoolean("optional",false))return;throw new Exception("Control missing: "+s.getString("target")); }
            if("text".equals(op)) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                Bundle a=new Bundle();a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,s.getString("value"));
                if(!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,a))throw new Exception("setText refused");return;
            }
            if("longClick".equals(op)) { Rect b=new Rect();node.getBoundsInScreen(b);tap(b.centerX(),b.centerY(),800);return; }
            AccessibilityNodeInfo cursor=node;
            while(cursor!=null && !cursor.isClickable())cursor=cursor.getParent();
            if(cursor!=null) { if(!cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new Exception("click refused"); }
            else { Rect b=new Rect();node.getBoundsInScreen(b);tap(b.centerX(),b.centerY(),70); }
            return;
        }
        throw new Exception("Unknown op: "+op);
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo n,String target) {
        if(n==null) return null;
        if(n.isVisibleToUser() && (target.contentEquals(n.getText()==null?"":n.getText()) || target.contentEquals(n.getContentDescription()==null?"":n.getContentDescription())))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo r=find(n.getChild(i),target);if(r!=null)return r;}
        return null;
    }
    private void key(int key){sendKeyDownUpSync(key);}
    private void tap(float x,float y,int hold){long t=SystemClock.uptimeMillis();motion(t,t,MotionEvent.ACTION_DOWN,x,y);SystemClock.sleep(hold);motion(t,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y);}
    private void motion(long down,long now,int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,now,action,x,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);getUiAutomation().injectInputEvent(e,true);e.recycle();}
    private JSONObject capture(String name) throws Exception {
        JSONObject state=new JSONObject();JSONArray nodes=new JSONArray();walk(getUiAutomation().getRootInActiveWindow(),nodes,0);state.put("nodes",nodes);
        final JSONArray nativeViews=new JSONArray();runOnMainSync(()-> {try{walkViews(activity.getWindow().getDecorView(),nativeViews);}catch(Throwable ignored){}});state.put("nativeViews",nativeViews);
        Bitmap bitmap=getUiAutomation().takeScreenshot();
        if(bitmap==null)throw new Exception("No pixels");state.put("width",bitmap.getWidth());state.put("height",bitmap.getHeight());
        try(FileOutputStream stream=new FileOutputStream(new File(out,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}bitmap.recycle();
        write(new File(out,name+".json"),state);return state;
    }
    private void walk(AccessibilityNodeInfo n,JSONArray rows,int depth) throws Exception {
        if(n==null||depth>30||rows.length()>600)return;
        if(n.isVisibleToUser()) {JSONObject r=new JSONObject();r.put("text",n.getText());r.put("desc",n.getContentDescription());r.put("class",n.getClassName());r.put("package",n.getPackageName());r.put("clickable",n.isClickable());r.put("checked",n.isChecked());r.put("enabled",n.isEnabled());Rect b=new Rect();n.getBoundsInScreen(b);r.put("bounds",new JSONArray(new int[]{b.left,b.top,b.right,b.bottom}));rows.put(r);}
        for(int i=0;i<n.getChildCount();i++)walk(n.getChild(i),rows,depth+1);
    }
    private void walkViews(View v,JSONArray rows)throws Exception {
        if(v.getVisibility()!=View.VISIBLE)return;
        if(v instanceof TextView) {TextView t=(TextView)v;JSONObject r=new JSONObject();r.put("text",t.getText());r.put("textPx",t.getTextSize());r.put("color",String.format("#%08X",t.getCurrentTextColor()));int[] p=new int[2];v.getLocationOnScreen(p);r.put("bounds",new JSONArray(new int[]{p[0],p[1],p[0]+v.getWidth(),p[1]+v.getHeight()}));rows.put(r);}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)walkViews(g.getChildAt(i),rows);}
    }
    private void write(File f,JSONObject data)throws Exception{try(FileOutputStream stream=new FileOutputStream(f)){stream.write(data.toString(2).getBytes(StandardCharsets.UTF_8));}}
}
