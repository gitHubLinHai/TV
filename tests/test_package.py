from pathlib import Path
import hashlib,io,json,subprocess,tempfile,zipfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path(r'C:\Program Files\Unity 6000.0.59f2\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK\bin')
CORE=['index.html','manifest.json','css/style.css','js/app.js','js/netdisk.js','js/bookmarks.js','js/vod-source-4kvm.js','js/vod-sources.js','js/vod.js']
def archive(files):
 b=io.BytesIO()
 with zipfile.ZipFile(b,'w',zipfile.ZIP_DEFLATED) as z:
  for name,data in files:
   item=zipfile.ZipInfo();item.filename=name;item.compress_type=zipfile.ZIP_DEFLATED;z.writestr(item,data)
 return b.getvalue()
with tempfile.TemporaryDirectory(prefix='tv-package-') as d:
 w=Path(d);h=w/'Check.java'
 h.write_text('import java.io.File; import com.seagull.tv.UpdatePackage; public class Check {public static void main(String[] a)throws Exception{ UpdatePackage.verifyAndExtract(new File(a[0]),new File(a[1]),Long.parseLong(a[2]),a[3]);}}',encoding='utf-8')
 subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-d',str(w),str(ROOT/'android-shell/app/src/main/java/com/seagull/tv/UpdatePackage.java'),str(h)],check=True)
 count=0
 def check(data,valid,hash_value=None):
  global count
  count+=1;f=w/'test.wgt';f.write_bytes(data)
  r=subprocess.run([str(JAVA/'java.exe'),'-cp',str(w),'Check',str(f),str(w/('incoming'+str(count))),str(len(data)),hash_value or hashlib.sha256(data).hexdigest()],capture_output=True)
  assert (r.returncode==0)==valid,r.stderr.decode(errors='replace')
 files=[(n,(ROOT/'web'/n).read_bytes())for n in CORE]
 info=json.loads((ROOT/'release/version.json').read_text(encoding='utf-8'))
 check((ROOT/'release'/info['wgt']).read_bytes(),True)
 check(archive(files),True)
 check(archive(files),False,'a'*64)
 check(archive(files)[:-22],False)
 for name in CORE:check(archive([x for x in files if x[0]!=name]),False)
 for name in ['../escape.txt','/escape.txt','x/../../escape.txt','x\\escape.txt','index.html']:
  check(archive(files+[(name,b'attack')]),False)
 check(archive(files+[('too-big.bin',b'0'*(33*1024*1024))]),False)
 check(archive(files+[('entry-%d'%i,b'1')for i in range(4097)]),False)
 print('PASS:',count,'actual Java package cases: valid, hash, truncation, required files, traversal, duplicates, expansion and entry limits.')

