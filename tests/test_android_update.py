# -*- coding: utf-8 -*-
from pathlib import Path
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
import hashlib,io,json,os,subprocess,sys,threading,zipfile
from urllib.parse import urlsplit
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
import build_apk as b
WORK=ROOT/'work/instrumentation';WORK.mkdir(parents=True,exist_ok=True)
for folder in ['classes','dex']:(WORK/folder).mkdir(exist_ok=True)
b.run([b.BT/'aapt.exe','package','-f','-M',ROOT/'tests/android/AndroidManifest.xml','-I',b.JAR,'-F',WORK/'unsigned.apk'])
b.run([b.JAVA/'bin/javac.exe','-encoding','UTF-8','--release','8','-classpath',b.JAR,'-d',WORK/'classes',ROOT/'tests/android/UpdateInstrumentation.java'])
b.run([b.BT/'d8.bat','--lib',b.JAR,'--min-api','23','--output',WORK/'dex']+list((WORK/'classes').rglob('*.class')))
with zipfile.ZipFile(WORK/'unsigned.apk','a',zipfile.ZIP_DEFLATED)as z:z.write(WORK/'dex/classes.dex','classes.dex')
b.run([b.BT/'zipalign.exe','-f','4',WORK/'unsigned.apk',WORK/'aligned.apk'])
b.run([b.BT/'apksigner.bat','sign','--ks',ROOT/'.signing/tv-personal.keystore','--ks-key-alias','tv-personal','--ks-pass','pass:android','--out',WORK/'test.apk',WORK/'aligned.apk'])
packages={};infos={}
base=json.loads((ROOT/'web/manifest.json').read_text(encoding='utf-8-sig'))['versionCode']
good,bad=base+1,base+2
for code in [good,bad]:
 m=json.loads((ROOT/'web/manifest.json').read_text(encoding='utf-8-sig'));m.update(versionCode=code,versionName='1.0.'+str(code-100))
 output=io.BytesIO()
 with zipfile.ZipFile(output,'w',zipfile.ZIP_STORED if code==bad else zipfile.ZIP_DEFLATED)as z:
  for f in (ROOT/'web').rglob('*'):
   if not f.is_file():continue
   name=f.relative_to(ROOT/'web').as_posix();data=f.read_bytes()
   if name=='manifest.json':data=json.dumps(m).encode('utf-8')
   # 在夹具中关闭 Wi-Fi 自动应用，避免与 instrumentation 的手动桥接实例竞争。
   if name=='js/app.js':data=data.replace(b"bridge.networkType() === 'wifi'",b"false")
   if code==bad and name=='js/app.js':data=b'throw new Error("fixture invalid UI");'
   z.writestr(name,data)
 packages[code]=output.getvalue();digest=hashlib.sha256(packages[code]).hexdigest()
 (WORK/('fixture-%d.wgt'%code)).write_bytes(packages[code])
 infos[code]=dict(m,wgt='test-%d-%s.wgt'%(code,digest[:12]),wgtSize=len(packages[code]),wgtSha256=digest)
current=good
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  global current
  path=urlsplit(self.path).path
  if path=='/advance':current=bad;data=b'ok'
  elif path=='/version.json':data=json.dumps(infos[current]).encode('utf-8')
  elif path=='/'+infos[bad]['wgt']:data=packages[bad]
  elif path=='/'+infos[good]['wgt']:data=packages[good]
  else:self.send_error(404);return
  self.send_response(200);self.send_header('Content-Type','application/json'if path=='/version.json'else'application/octet-stream');self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(data)));self.send_header('Connection','close');self.end_headers();self.wfile.write(data);self.wfile.flush()
server=ThreadingHTTPServer(('0.0.0.0',8140),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
adb=b.SDK/'platform-tools/adb.exe'
try:
 b.run([adb,'-s',SERIAL,'install','-r',WORK/'test.apk'])
 b.run([adb,'-s',SERIAL,'shell','am','force-stop','com.seagull.tv'])
 (WORK/'local-bad-fixture.json').write_text(json.dumps(infos[bad]),encoding='utf-8')
 b.run([adb,'-s',SERIAL,'push',WORK/('fixture-%d.wgt'%bad),'/data/user/0/com.seagull.tv/files/local-bad-fixture.wgt'])
 b.run([adb,'-s',SERIAL,'push',WORK/'local-bad-fixture.json','/data/user/0/com.seagull.tv/files/local-bad-fixture.json'])
 r=subprocess.run([str(adb),'-s',SERIAL,'shell','am','instrument','-w','com.seagull.tv.tests/.UpdateInstrumentation'],capture_output=True,timeout=90)
 text=r.stdout.decode('utf-8','replace');print(text);assert 'PASS:'in text and 'FAIL:' not in text,text+r.stderr.decode('utf-8','replace')
finally:server.shutdown();server.server_close()
