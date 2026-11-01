# -*- coding: utf-8 -*-
from pathlib import Path
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
import hashlib,io,json,os,subprocess,sys,threading,zipfile
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
import build_apk as b
WORK=ROOT/'work/instrumentation';WORK.mkdir(parents=True,exist_ok=True)
for folder in ['classes','dex']:(WORK/folder).mkdir(exist_ok=True)
b.run([b.BT/'aapt.exe','package','-f','-M',ROOT/'tests/android/AndroidManifest.xml','-I',b.JAR,'-F',WORK/'unsigned.apk'])
b.run([b.JAVA/'bin/javac.exe','-encoding','UTF-8','--release','8','-classpath',b.JAR,'-d',WORK/'classes']+list((ROOT/'tests/android').glob('*.java')))
b.run([b.BT/'d8.bat','--lib',b.JAR,'--min-api','23','--output',WORK/'dex']+list((WORK/'classes').rglob('*.class')))
with zipfile.ZipFile(WORK/'unsigned.apk','a',zipfile.ZIP_DEFLATED)as z:z.write(WORK/'dex/classes.dex','classes.dex')
b.run([b.BT/'zipalign.exe','-f','4',WORK/'unsigned.apk',WORK/'aligned.apk'])
b.run([b.BT/'apksigner.bat','sign','--ks',ROOT/'.signing/tv-personal.keystore','--ks-key-alias','tv-personal','--ks-pass','pass:android','--out',WORK/'test.apk',WORK/'aligned.apk'])
adb=b.SDK/'platform-tools/adb.exe'
SERIAL=os.environ.get('TV_ADB_SERIAL') or (lambda out:([line.split(chr(9))[0] for line in out.splitlines() if line.endswith('device')]or ['emulator-5554'])[0])(subprocess.run([str(adb),'devices'],capture_output=True,text=True).stdout)
b.run([adb,'-s',SERIAL,'install','--no-incremental','-r',WORK/'test.apk'])
b.run([adb,'-s',SERIAL,'shell','am','force-stop','com.seagull.tv'])
if '--fresh' in sys.argv[2:]:b.run([adb,'-s',SERIAL,'shell','pm','clear','com.seagull.tv'])
name=sys.argv[1]if len(sys.argv)>1 else 'NetdiskInstrumentation'
assert name in ('NetdiskInstrumentation','PrivacyInstrumentation','BookmarkInstrumentation','VodInstrumentation','Vod4kvmInstrumentation','VodDuanjuInstrumentation','VodAutoNextInstrumentation','MusicInstrumentation','MusicLyricsInstrumentation','VodCoverInstrumentation','MusicBackgroundInstrumentation','ParallelPlayInstrumentation','PlayerGestureInstrumentation','UpdateE2EInstrumentation','MusicStressInstrumentation',')
r=subprocess.run([str(adb),'-s',SERIAL,'shell','am','instrument','-w','com.seagull.tv.tests/.'+name],capture_output=True,timeout=420)
text=r.stdout.decode('utf-8','replace');print(text);assert 'PASS:'in text and 'FAIL:'not in text,text+r.stderr.decode('utf-8','replace')

