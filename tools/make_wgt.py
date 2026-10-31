"""生成 UI 资源热更包；原生功能修改后应提升 APK 版本并重新构建。"""
from pathlib import Path
import argparse,hashlib,json,re,zipfile
ROOT=Path(__file__).resolve().parents[1]
def package(bump=True,notes='界面与搜索配置更新'):
 p=ROOT/'web/manifest.json'; m=json.loads(p.read_text(encoding='utf-8-sig'))
 if bump:
  m['versionCode']+=1
  parts=m['versionName'].split('.');parts[-1]=str(int(parts[-1])+1);m['versionName']='.'.join(parts)
 release=ROOT/'release';release.mkdir(exist_ok=True)
 name='TV-v%s.wgt'%m['versionName']; archive=release/name
 if archive.exists() and bump:raise SystemExit('Version already exists; increase the version before publishing.')
 p.write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 index=ROOT/'web/index.html'
 text=index.read_text(encoding='utf-8-sig')
 text=re.sub(r'((?:src|href)="[^"?]+\.(?:js|css))(?:\?[^" ]*)?"',lambda x:x[1]+'?v='+str(m['versionCode'])+'"',text)
 index.write_text(text,encoding='utf-8')
 with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
  for f in sorted((ROOT/'web').rglob('*')):
   if f.is_file():z.write(f,f.relative_to(ROOT/'web').as_posix())
 info=dict(m,wgt=name,wgtSize=archive.stat().st_size,wgtSha256=hashlib.sha256(archive.read_bytes()).hexdigest(),notes=notes)
 (release/'version.json').write_text(json.dumps(info,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 print(archive)
 return info
def apk_info():
 '''APK 构建完成后调用：把 apk 文件名/版本/大小/sha 注入 release/version.json。'''
 import hashlib
 p=ROOT/'release/version.json'
 release=ROOT/'release'
 info=json.loads(p.read_text(encoding='utf-8'))
 apk=release/('TV-v%s.apk'%info['versionName'])
 if not apk.exists():raise SystemExit('APK 不存在：'+str(apk))
 content=apk.read_bytes()
 info['apk']=apk.name;info['apkCode']=info['versionCode']
 info['apkSize']=len(content);info['apkSha256']=hashlib.sha256(content).hexdigest()
 p.write_text(json.dumps(info,ensure_ascii=False,indent=2)+chr(10),encoding='utf-8')

if __name__=='__main__':
 a=argparse.ArgumentParser();a.add_argument('--no-bump',action='store_true');a.add_argument('--apk-info',action='store_true');a.add_argument('--notes',default='界面与搜索配置更新');args=a.parse_args()
 if args.apk_info:apk_info()
 else:package(not args.no_bump,args.notes)
