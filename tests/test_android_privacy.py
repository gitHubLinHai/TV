"""搭配已构建的 instrumentation APK，验证独立无痕 WebView 的清理。"""
from pathlib import Path
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
import os,subprocess,threading,time
ROOT=Path(__file__).resolve().parents[1]
ADB=os.environ.get('TV_ANDROID_SDK',r'D:\SDK')+'\\platform-tools\\adb.exe'
SERIAL=os.environ.get('TV_ADB_SERIAL') or (lambda out:([line.split(chr(9))[0] for line in out.splitlines() if line.endswith('device')]or ['emulator-5554'])[0])(subprocess.run([ADB,'devices'],capture_output=True,text=True).stdout)
def adb(*args):
 return subprocess.run([ADB,'-s',SERIAL]+list(args),stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=25).stdout.decode('utf-8','replace')
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  data=b'''<html><body>private-browser-fixture<script>document.cookie='tv_private_marker=private;path=/';localStorage.setItem('private-address-marker',location.href);document.title='PRIVATE_FIXTURE_READY';</script></body></html>'''
  self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
server=ThreadingHTTPServer(('0.0.0.0',8141),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
try:
 adb('root')
 adb('wait-for-device')
 assert 'uid=0' in adb('shell','id'),'This test requires a rooted emulator (adb root)'
 adb('shell','am','force-stop','com.seagull.tv')
 adb('shell','rm','-f','/data/user/0/com.seagull.tv/files/privacy-test-open')
 process=subprocess.Popen([ADB,'-s',SERIAL,'shell','am','instrument','-w','com.seagull.tv.tests/.PrivacyInstrumentation'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 signal='/data/user/0/com.seagull.tv/files/privacy-test-open'
 end=time.monotonic()+70
 while time.monotonic()<end:
  if 'OPEN' in adb('shell','sh','-c',"'test -f "+signal+" && echo OPEN'"):break
  if process.poll() is not None:raise AssertionError(process.communicate()[0].decode('utf-8','replace'))
  time.sleep(1)
 else:raise AssertionError('Private browser did not open')
 profile='/data/user/0/com.seagull.tv/app_webview_tv_private'
 end=time.monotonic()+20
 while time.monotonic()<end:
  text=adb('shell','sh','-c',"'ls "+profile+"/Default/Local\\ Storage/leveldb 2>/dev/null'")
  if '.log' in text:break
  time.sleep(1)
 else:raise AssertionError('Independent private profile did not create local storage')
 assert 'com.seagull.tv:private' in adb('shell','ps','-A'),'Private browser did not use separate process'
 time.sleep(2)
 adb('shell','input','keyevent','4')
 time.sleep(1)
 if 'PrivateBrowserActivity' in adb('shell','dumpsys','activity','activities'):adb('shell','input','keyevent','4')
 end=time.monotonic()+12
 while time.monotonic()<end:
  if 'com.seagull.tv:private' not in adb('shell','ps','-A'):break
  time.sleep(.5)
 assert 'com.seagull.tv:private' not in adb('shell','ps','-A'),'Private browser process retained'
 assert 'RETAINED' not in adb('shell','sh','-c',"'test -d "+profile+" && echo RETAINED'"),'Private browser data retained after close'
 adb('shell','rm',signal)
 output=process.communicate(timeout=35)[0].decode('utf-8','replace');print(output)
 assert 'PASS:' in output and 'FAIL:' not in output
 print('PASS: private profile existed during session, was removed after close, and separate process exited.')
finally:
 server.shutdown();server.server_close()
