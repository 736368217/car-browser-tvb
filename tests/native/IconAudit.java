package com.carweb.audit;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

/** Render actual packaged native vectors, including tint/alpha and non-square bounds. */
public final class IconAudit {
    private static final int COLOR = 0xFF67B8E8;
    private IconAudit() {}
    public static JSONObject run(Context context, File out) throws Exception {
        Class<?> type=Class.forName("com.carweb.tvb.GlyphIcon",true,context.getClassLoader());
        Constructor<?> constructor=type.getConstructor(Resources.class,int.class,int.class);
        TreeMap<Integer,String> names=new TreeMap<>();
        for(Field field:type.getFields()) if(field.getType()==int.class&&Modifier.isStatic(field.getModifiers()))
            names.put(field.getInt(null),field.getName());
        Bitmap atlas=Bitmap.createBitmap(1600,650,Bitmap.Config.ARGB_8888);
        Canvas gallery=new Canvas(atlas);gallery.drawColor(0xFF0D1117);
        Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setColor(0xFFB6C0CC);text.setTextSize(14);
        JSONArray checks=new JSONArray(); int failures=0;
        for(Map.Entry<Integer,String> entry:names.entrySet()) {
            String error="";
            try {
                Drawable drawable=(Drawable)constructor.newInstance(context.getResources(),entry.getKey(),COLOR);
                for(int size:new int[]{24,64,78}) {
                    Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
                    drawable.setBounds(0,0,size,size);drawable.draw(new Canvas(bitmap));
                    int[] pixels=new int[size*size];bitmap.getPixels(pixels,0,size,0,0,size,size);
                    int visible=0,border=0;long alpha=0;
                    for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
                        int pixel=pixels[y*size+x];int a=Color.alpha(pixel);alpha+=a;
                        if(a>0){visible++;if(x==0||y==0||x==size-1||y==size-1)border++;}
                        // RGB at antialiased edges is unpremultiplied with rounding; compare opaque pixels exactly.
                        if(a==255&&(pixel&0x00FFFFFF)!=(COLOR&0x00FFFFFF))throw new AssertionError("Tint mismatch: "+Integer.toHexString(pixel));
                    }
                    if(visible<10||border!=0)throw new AssertionError("Empty/clipped at "+size+": "+visible+"/"+border);
                    drawable.setAlpha(96);bitmap.eraseColor(Color.TRANSPARENT);drawable.draw(new Canvas(bitmap));
                    bitmap.getPixels(pixels,0,size,0,0,size,size);long faded=0;for(int pixel:pixels)faded+=Color.alpha(pixel);
                    if(faded>=alpha*.5||faded<=0)throw new AssertionError("Alpha ignored");
                    drawable.setAlpha(255);bitmap.recycle();
                }
                Bitmap wide=Bitmap.createBitmap(100,64,Bitmap.Config.ARGB_8888);
                drawable.setBounds(0,0,100,64);drawable.draw(new Canvas(wide));
                for(int y=0;y<64;y++)for(int x=0;x<18;x++)if(Color.alpha(wide.getPixel(x,y))>0)throw new AssertionError("Non-square bounds stretched");
                wide.recycle();
                int x=(entry.getKey()%8)*200,y=(entry.getKey()/8)*130;
                drawable.setBounds(x+68,y+12,x+132,y+76);drawable.draw(gallery);
                gallery.drawText(entry.getValue(),x+12,y+106,text);
            } catch(Throwable failure){error=failure.toString();failures++;}
            JSONObject check=new JSONObject();check.put("icon",entry.getValue());check.put("pass",error.isEmpty());check.put("error",error);checks.put(check);
        }
        try(FileOutputStream stream=new FileOutputStream(new File(out,"lucide-atlas.png"))){atlas.compress(Bitmap.CompressFormat.PNG,100,stream);}atlas.recycle();
        JSONObject result=new JSONObject();result.put("checks",checks);result.put("failed",failures);result.put("iconCount",names.size());result.put("sizes",new JSONArray(new int[]{24,64,78}));result.put("pass",failures==0&&names.size()==40);return result;
    }
}
