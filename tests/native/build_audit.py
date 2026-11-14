"""Build the standalone UI audit instrumentation; outputs belong in session scratch."""
import os, pathlib, subprocess, sys, zipfile
src = pathlib.Path(__file__).resolve().parent
sdk = pathlib.Path(os.environ.get('ANDROID_HOME', r'D:\codex\projects\2026-08-21\a\work\android-toolchain\sdk'))
bt = sdk/'build-tools'/'35.0.0'
android = sdk/'platforms'/'android-35'/'android.jar'
out = pathlib.Path(sys.argv[1]).resolve(); out.mkdir(parents=True, exist_ok=True)
classes = out/'classes'; classes.mkdir(exist_ok=True)
def run(argv):
    p = subprocess.run([str(v) for v in argv], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = p.stdout.decode('utf-8', 'replace')
    if p.returncode:
        print(text.encode('ascii','backslashreplace').decode()); raise SystemExit(p.returncode)
manifest=out/'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.carweb.audit" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="33"/><application android:label="验收文件查看器"><activity android:name=".ViewProbe" android:exported="true"><intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><data android:scheme="content" android:mimeType="text/plain"/></intent-filter></activity></application><instrumentation android:name=".UiAudit" android:targetPackage="com.carweb.tvb" android:functionalTest="true"/></manifest>''',encoding='utf-8')
run([bt/'aapt2.exe','link','-o',out/'base.apk','-I',android,'--manifest',manifest])
run(['javac','--release','17','-encoding','UTF-8','-classpath',android,'-d',classes,*src.glob('*.java')])
run(['jar','--create','--file',out/'classes.jar','-C',classes,'.'])
run([bt/'d8.bat','--lib',android,'--min-api','26','--output',out,out/'classes.jar'])
with zipfile.ZipFile(out/'base.apk','a') as z: z.write(out/'classes.dex','classes.dex')
run([bt/'zipalign.exe','-f','4',out/'base.apk',out/'aligned.apk'])
ks=src.parent.parent/'keystore'/'carweb.jks'
run([bt/'apksigner.bat','sign','--ks',ks,'--ks-pass','pass:carweb2026','--key-pass','pass:carweb2026','--ks-key-alias','carweb','--out',out/'ui-audit.apk',out/'aligned.apk'])
print('AUDIT_BUILD_PASS '+str(out/'ui-audit.apk'))
