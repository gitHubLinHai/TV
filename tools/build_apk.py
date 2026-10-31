"""使用本机 Android SDK 构建带 Media3 播放器的个人 APK。"""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,zipfile
ROOT=Path(__file__).resolve().parents[1]
SDK=Path(os.environ.get('TV_ANDROID_SDK',r'D:\SDK'))
JAVA=Path(os.environ.get('TV_JAVA_HOME',r'C:\Program Files\Unity 6000.0.59f2\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK'))
BT=SDK/'build-tools/34.0.0'
JAR=SDK/'platforms/android-36/android.jar'
MAIN=ROOT/'android-shell/app/src/main'
WORK=ROOT/'work/build'
ENV=dict(os.environ,JAVA_HOME=str(JAVA),PATH=str(JAVA/'bin')+os.pathsep+os.environ.get('PATH',''))
def run(args):
 subprocess.run([str(x) for x in args],cwd=str(ROOT),env=ENV,check=True)
def build():
 from player_dependencies import prepare
 dependencies,resources,packages=prepare()
 manifest=json.loads((ROOT/'web/manifest.json').read_text(encoding='utf-8-sig'))
 assets=MAIN/'assets/www'
 if assets.exists():shutil.rmtree(assets)
 shutil.copytree(ROOT/'web',assets)
 shutil.copy2(ROOT/'android-shell/third-party-notices.txt',MAIN/'assets/third-party-notices.txt')
 import re
 android=MAIN/'AndroidManifest.xml'
 text=android.read_text(encoding='utf-8-sig')
 text=re.sub(r' android:debuggable="(?:true|false)"','',text)
 if os.environ.get('TV_DEBUG') == '1':text=text.replace('<application ', '<application android:debuggable="true" ')
 text=re.sub(r'android:versionCode="\d+"','android:versionCode="%d"'%manifest['versionCode'],text)
 text=re.sub(r'android:versionName="[^"]+"','android:versionName="%s"'%manifest['versionName'],text)
 android.write_text(text,encoding='utf-8')
 if WORK.exists():shutil.rmtree(WORK)
 for name in ['gen','classes','dex']:(WORK/name).mkdir(parents=True,exist_ok=True)
 unsigned=WORK/'unsigned.apk'
 compiled=[]
 for i,resource in enumerate([MAIN/'res']+resources):
  out=WORK/('res%d.zip'%i)
  run([BT/'aapt2.exe','compile','--dir',resource,'-o',out]);compiled.append(out)
 run([BT/'aapt2.exe','link','--auto-add-overlay','--extra-packages',':'.join(packages),'--java',WORK/'gen','--manifest',android,'-A',MAIN/'assets','-I',JAR,'-o',unsigned]+sum((['-R',r]for r in compiled),[]))
 sources=list((MAIN/'java').rglob('*.java'))+list((WORK/'gen').rglob('*.java'))
 run([JAVA/'bin/javac.exe','-encoding','UTF-8','--release','8','-classpath',os.pathsep.join(str(x)for x in [JAR]+dependencies),'-d',WORK/'classes']+sources)
 classes=list((WORK/'classes').rglob('*.class'))
 # d8.bat 受 Windows cmd 的命令行长度限制；先把项目 class 打成一个输入 JAR。
 project_jar=WORK/'project-classes.jar'
 with zipfile.ZipFile(project_jar,'w',zipfile.ZIP_DEFLATED)as z:
  for file in classes:z.write(file,Path(file).relative_to(WORK/'classes').as_posix())
 run([BT/'d8.bat','--lib',JAR,'--min-api','23','--output',WORK/'dex',project_jar]+dependencies)
 with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
  for dex in (WORK/'dex').glob('*.dex'):z.write(dex,dex.name)
 aligned=WORK/'aligned.apk'
 run([BT/'zipalign.exe','-f','4',unsigned,aligned])
 key=Path(os.environ.get('TV_KEYSTORE',str(ROOT/'.signing/tv-personal.keystore')))
 password=os.environ.get('TV_KEYSTORE_PASSWORD','android')
 alias=os.environ.get('TV_KEY_ALIAS','tv-personal')
 if not key.exists():
  key.parent.mkdir(parents=True,exist_ok=True)
  run([JAVA/'bin/keytool.exe','-genkeypair','-keystore',key,'-storepass',password,'-keypass',password,'-alias',alias,'-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=TV Personal, O=Personal, C=CN'])
 out=ROOT/('release/TV-v%s.apk'%manifest['versionName']);out.parent.mkdir(exist_ok=True)
 run([BT/'apksigner.bat','sign','--ks',key,'--ks-key-alias',alias,'--ks-pass','pass:'+password,'--key-pass','pass:'+password,'--out',out,aligned])
 run([BT/'apksigner.bat','verify','--verbose',out])
 print('APK:',out,'SHA256:',hashlib.sha256(out.read_bytes()).hexdigest())
if __name__=='__main__':build()
