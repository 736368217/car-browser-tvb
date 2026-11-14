package com.carweb.audit;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.util.TypedValue;
/** Separate-package recipient proves that granted content URIs are actually readable. */
public final class ViewProbe extends Activity {
 @Override public void onCreate(Bundle state){super.onCreate(state);TextView text=new TextView(this);
  text.setTextSize(TypedValue.COMPLEX_UNIT_PX,getResources().getDisplayMetrics().widthPixels/72f);
  text.setPadding(120,120,120,120);text.setTextColor(0xFFFFFFFF);text.setBackgroundColor(0xFF152130);
  try(java.io.InputStream in=getContentResolver().openInputStream(getIntent().getData())){
   java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];int n;
   while((n=in.read(b))!=-1 && bytes.size()<2097152)bytes.write(b,0,n);
   text.setText("FILE_READ_OK bytes="+bytes.size()+"\n"+new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
  }catch(Throwable e){text.setText("FILE_READ_FAILED "+e.toString());}setContentView(text);
 }
}
