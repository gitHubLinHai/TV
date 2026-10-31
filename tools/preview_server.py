# -*- coding: utf-8 -*-
"""本地网页预览与网盘只读代理；不会接收或保存夸克账号 Cookie。"""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlencode,urlparse,urlunparse,parse_qs
from urllib.request import Request,urlopen,build_opener,HTTPRedirectHandler
from urllib.error import HTTPError
import argparse,json,os,time,threading,concurrent.futures,re,gzip,io
ROOT=Path(__file__).resolve().parents[1]
SEARCH=os.environ.get('TV_PANSOU_API','https://so.252035.xyz/api/search')
QUARK='https://drive-pc.quark.cn/1/clouddrive'
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36'
cache={};lock=threading.Lock()
def request(url,body=None,quark=False):
 headers={'User-Agent':UA,'Accept':'application/json'}
 if body is not None:headers['Content-Type']='application/json'
 if quark:headers.update({'Referer':'https://pan.quark.cn/','Content-Type':'application/json'})
 data=None if body is None else json.dumps(body).encode('utf-8')
 try:
  if url.startswith('https://qkpanso.com/'):headers.update({'Referer':'https://qkpanso.com/search','Content-Type':'application/json'})
  with urlopen(Request(url,data=data,headers=headers),timeout=12)as response:
   raw=response.read(2*1024*1024+1)
   if len(raw)>2*1024*1024:raise ValueError('接口返回内容过大')
   if url.startswith('https://www.pansearch.me/search?'):
    match=re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>',raw.decode('utf-8'),re.S)
    if not match:raise ValueError('来源页面格式已变化')
    return json.loads(match.group(1))
   return json.loads(raw)
 except HTTPError as e:
  try:return json.loads(e.read(65536))
  except Exception:raise ValueError('资源服务返回 HTTP '+str(e.code))
def rpc(action,args):
 if action=='account':return {'loggedIn':False,'preview':True}
 if action=='search':
  q=str(args.get('q','')).strip()
  if not q or len(q)>60:raise ValueError('请输入 1—60 字搜索词')
  selected=str(args.get('source',''))
  if selected not in ('','pansearch','qkpanso','pansou'):raise ValueError('不支持的搜索来源')
  cache_key=q if not selected else q+'\x1f'+selected
  with lock:
   item=cache.get(cache_key)
   if not args.get('private') and not args.get('refresh') and item and time.monotonic()-item[0]<180:return item[1]
  def source(name,fmt,url,body=None):
   try:return {'name':name,'format':fmt,'data':request(url,body)}
   except Exception as e:
    if fmt=='pansou' and 'refresh=true' in url:
     try:return {'name':name,'format':fmt,'data':request(url.replace('refresh=true','refresh=false'),body),'warning':'刷新失败，使用缓存候选；结果仍须逐条校验'}
     except Exception:pass
    return {'name':name,'format':fmt,'error':str(e)}
  body={'page':1,'q':q,'user':'','exact':False,'format':[],'share_time':'','size':30,'type':'','exclude_user':[],'adv_params':{'wechat_pwd':'','platform':'pc'}}
  providers=[('pansearch','PanSearch','pansearch','https://www.pansearch.me/search?'+urlencode({'keyword':q,'pan':'quark'}),None),('qkpanso','夸克盘搜','hunhepan','https://qkpanso.com/v1/search/disk',body),('pansou','PanSou','pansou',SEARCH+'?'+urlencode({'kw':q,'res':'merge','cloud_types':'quark','refresh':'true'}),None)]
  with concurrent.futures.ThreadPoolExecutor(max_workers=3)as pool:
   tasks=[pool.submit(source,name,fmt,url,body) for key,name,fmt,url,body in providers if not selected or key==selected]
   data={'sources':[task.result()for task in tasks]}
  def valid(source):
   raw=source.get('data') or {}
   if source['format']=='pansearch':return isinstance(raw.get('props',{}).get('pageProps',{}).get('data',{}).get('data'),list)
   if source['format']=='hunhepan':return raw.get('code')==200 and isinstance((raw.get('data') or {}).get('list'),list)
   return raw.get('code')==0 and isinstance((raw.get('data') or {}).get('merged_by_type'),dict)
  if not args.get('private') and any(valid(source) for source in data['sources']):
   with lock:
    if len(cache)>64:cache.clear()
    cache[cache_key]=(time.monotonic(),data)
  return data
 if action not in ('token','files','sharePreview'):raise ValueError('登录与转存播放请在 APK 内完成；网页预览支持公开分享搜索和目录浏览')
 common={'pr':'ucpro','fr':'pc'}
 if action=='token':return request(QUARK+'/share/sharepage/token?'+urlencode(common),{'pwd_id':str(args.get('pwd_id','')),'passcode':str(args.get('passcode',''))},True)
 if action=='files':
  common.update({k:str(args.get(k,''))for k in ['pwd_id','stoken']})
  common.update({'pdir_fid':str(args.get('pdir_fid','0')),'_page':max(1,min(100,int(args.get('page',1)))),'_size':50,'_fetch_total':1,'_sort':'file_type:asc,file_name:asc'})
  return request(QUARK+'/share/sharepage/detail?'+urlencode(common),quark=True)
 common.update({k:str(args.get(k,''))for k in ['pwd_id','stoken','fid','fid_token']})
 return request(QUARK+'/share/sharepage/video_preview?'+urlencode(common),quark=True)
def fetch_api(base,query):
 if not isinstance(base,str)or not re.fullmatch(r'https://[A-Za-z0-9.-]+\.[A-Za-z]{2,}/api\.php/provide/vod/?',base):raise ValueError('接口地址无效')
 if not isinstance(query,str)or len(query)>300:raise ValueError('不支持的接口参数')
 for param in query.split('&'):
  if not re.fullmatch(r'ac=(list|videolist)|wd=[A-Za-z0-9%._-]{0,80}|t=\d{0,12}|pg=\d{1,5}|ids=\d{1,40}(,\d{1,40})*',param):raise ValueError('不支持的接口参数')
 with urlopen(Request(base+('?' if base.endswith('/') else '/?')+query,headers={'User-Agent':UA,'Accept':'application/json'}),timeout=20)as response:
  raw=response.read(8*1024*1024+1)
  if len(raw)>8*1024*1024:raise ValueError('接口返回过大')
  return {'json':raw.decode('utf-8','ignore')}

def fetch_vod(path,host='www.555yy1.com'):
 if not isinstance(host,str)or not re.fullmatch(r'[A-Za-z0-9.-]+\.[A-Za-z]{2,}',host)or len(host)>80:raise ValueError('片源地址无效')
 if host=='www.4kvm.net':
  if not isinstance(path,str)or len(path)>512 or not re.fullmatch(r'/(?:movie|tv|anime)?(?:\?page=\d{1,4})?|/filter(?:\?[a-z_]{1,12}=[A-Za-z0-9%._~-]{1,32}(?:&[a-z_]{1,12}=[A-Za-z0-9%._~-]{1,32})*)?|/search\?q=[A-Za-z0-9%._~-]{1,240}(?:&page=\d{1,4})?|/play/[a-z0-9]{6,24}',path):raise ValueError('不支持的 4KVM 页面地址')
  if path.startswith('/filter?') and any(kv.split('=')[0] not in {'classify','types','areas','years','tags','sort_by','order','page'} for kv in path.split('?',1)[1].split('&')):raise ValueError('不支持的 4KVM 页面地址')
 elif not isinstance(path,str)or len(path)>2048 or not re.fullmatch(r'/index/home|/(?:vod(?:detail|type)/[0-9]+|vodplay/[0-9]+-[0-9]+-[0-9]+|vodshow/[A-Za-z0-9%_-]+|vodsearch/[A-Za-z0-9%_-]+)\.html',path):raise ValueError('不支持的影视页面地址')
 # 用户配置的公开片源站，无 Cookie；预览同样只返回文本，不执行站点脚本。
 with urlopen(Request('https://'+host+path,headers={'User-Agent':UA,'Referer':'https://'+host+'/'}),timeout=20)as response:
  target=urlparse(response.url)
  if target.scheme!='https'or target.hostname!=host:raise ValueError('来源跳转到未支持的地址')
  raw=response.read(4*1024*1024+1)
  if len(raw)>4*1024*1024:raise ValueError('来源页面过大')
  if raw.startswith(b'\x1f\x8b'):
   with gzip.GzipFile(fileobj=io.BytesIO(raw))as compressed:raw=compressed.read(4*1024*1024+1)
   if len(raw)>4*1024*1024:raise ValueError('来源页面解压后过大')
  return {'html':raw.decode('utf-8'),'path':target.path+('?' + target.query if target.query else '')}

def fetch_music(query):
 # 与 APK 内 MusicHttp 相同的限制：仅网易来源的榜单/搜索/取址，不下载音频本体。
 if not isinstance(query,str)or len(query)>256:raise ValueError('不支持的音乐请求')
 params=dict((p.split('=',1)+[''])[:2] if '=' in p else (p,'') for p in query.split('&'))
 if params.get('kg')=='1':
  if params.get('type')=='search':
   if not re.fullmatch(r'[A-Za-z0-9%._-]{1,120}',params.get('name','')):raise ValueError('不支持的搜索参数')
   with urlopen(Request('https://mobiles.kugou.com/api/v3/search/song?format=json&keyword='+params.get('name','')+'&page=1&pagesize=30',headers={'User-Agent':UA,'Accept':'application/json'}),timeout=15)as response:
    raw=response.read(2*1024*1024+1)
    if len(raw)>2*1024*1024:raise ValueError('来源响应过大')
    return {'json':raw.decode('utf-8','ignore')}
  if params.get('type')=='url':
   if not re.fullmatch(r'[0-9a-fA-F]{32}',params.get('hash','')):raise ValueError('不支持的曲目标识')
   with urlopen(Request('https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash='+params.get('hash',''),headers={'User-Agent':UA,'Accept':'application/json'}),timeout=15)as response:
    raw=response.read(1024*1024+1)
    return {'json':raw.decode('utf-8','ignore')}
  raise ValueError('不支持的酷狗参数')
 if params.get('deal_url'):
  u=params.get('deal_url')
  if not isinstance(u,str)or not u.startswith('https://')or len(u)>500:raise ValueError('订阅地址无效')
  host=u[8:].split('/')[0]
  if not re.fullmatch(r'[A-Za-z0-9.-]+\.[A-Za-z]{2,}',host)or len(host)>80:raise ValueError('订阅域名无效')
  with urlopen(Request(u,headers={'User-Agent':UA,'Accept':'application/rss+xml, application/json, text/xml, */*'}),timeout=15)as response:
   raw=response.read(2*1024*1024+1)
   if len(raw)>2*1024*1024:raise ValueError('订阅源响应过大')
   return {'body':raw.decode('utf-8','ignore')}
 if params.get('gds')=='1':
  if params.get('type')=='search':
   if not re.fullmatch(r'[A-Za-z0-9%._-]{1,120}',params.get('name','')):raise ValueError('不支持的搜索参数')
   with urlopen(Request('https://music-api.gdstudio.xyz/api.php?types=search&source=netease&name='+params.get('name','')+'&count=30&pages=1',headers={'User-Agent':UA,'Accept':'application/json','Referer':'https://music.gdstudio.xyz/'}),timeout=15)as response:
    raw=response.read(2*1024*1024+1)
    if len(raw)>2*1024*1024:raise ValueError('来源响应过大')
    return {'json':raw.decode('utf-8','ignore')}
  if params.get('type')=='url':
   if not re.fullmatch(r'\d{1,12}',params.get('id','')):raise ValueError('不支持的曲目编号')
   with urlopen(Request('https://music-api.gdstudio.xyz/api.php?types=url&source=netease&id='+params.get('id','')+'&br=320',headers={'User-Agent':UA,'Accept':'application/json','Referer':'https://music.gdstudio.xyz/'}),timeout=15)as response:
    raw=response.read(1024*1024+1)
    return {'json':raw.decode('utf-8','ignore')}
  raise ValueError('不支持的搜索参数')
 if params.get('server')!='netease':raise ValueError('仅支持网易来源的免费音乐')
 kind,i=params.get('type',''),params.get('id','')
 if kind!='gds' and kind!='kg' and (kind not in ('playlist','url','search')or not i or len(i)>240):raise ValueError('不支持的音乐请求')
 if kind in ('playlist','url','lrc')and not re.fullmatch(r'[0-9]{1,12}',i):raise ValueError('不支持的榜单或曲目编号')
 if kind=='gds'and not i.startswith('type='):raise ValueError('不支持的搜索参数')
 if kind=='search'and not re.fullmatch(r'[A-Za-z0-9%._\-]{1,120}',i):raise ValueError('搜索词包含不支持的字符')
 if set(params)-{'server','type','id'}:raise ValueError('不支持的请求参数')
 if kind=='lrc':
  with urlopen(Request('https://api.injahow.cn/meting/?'+query,headers={'User-Agent':UA,'Accept':'text/plain'}),timeout=15)as response:
   raw=response.read(512*1024+1)
   if len(raw)>512*1024:raise ValueError('来源响应过大')
   return {'lrc':raw.decode('utf-8','ignore')}
 if kind=='url':
  class _Stop(HTTPRedirectHandler):
   def redirect_request(self,req,fp,code,msg,headers,newurl):return None
  try:
   response=build_opener(_Stop).open(Request('https://api.injahow.cn/meting/?'+query,headers={'User-Agent':UA,'Accept':'application/json'}),timeout=15)
   response.close();raise ValueError('该曲目暂不可播放（可能需要会员或已下架）')
  except HTTPError as e:
   if e.code!=302:raise ValueError('该曲目暂不可播放（可能需要会员或已下架）')
   target=urlparse(e.headers.get('Location')or '')
   if target.scheme!='https'or not(target.hostname or '').endswith('.music.126.net')or target.username or target.password or target.port not in (None,443):raise ValueError('该曲目返回了不可用的播放地址')
   return {'url':urlunparse(target)}
 with urlopen(Request('https://api.injahow.cn/meting/?'+query,headers={'User-Agent':UA,'Accept':'application/json'}),timeout=15)as response:
  raw=response.read(2*1024*1024+1)
  if len(raw)>2*1024*1024:raise ValueError('来源响应过大')
  body=raw.decode('utf-8').strip()
  if not body.startswith('['):raise ValueError('来源没有返回歌曲列表')
  return {'json':body}

class Handler(SimpleHTTPRequestHandler):
 def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(ROOT/'web'),**kwargs)
 def end_headers(self):self.send_header('Cache-Control','no-store');super().end_headers()
 def do_GET(self):
  if not self.path.startswith('/api/media?'):return super().do_GET()
  try:
   url=parse_qs(urlparse(self.path).query).get('url',[''])[0];target=urlparse(url)
   if target.scheme!='https' or not target.hostname or not target.hostname.endswith('.quark.cn') or target.username or target.password or target.port not in (None,443):self.send_error(400);return
   headers={'User-Agent':UA,'Referer':'https://pan.quark.cn/'}
   if self.headers.get('Range'):headers['Range']=self.headers['Range']
   with urlopen(Request(url,headers=headers),timeout=20)as response:
    if not urlparse(response.url).hostname.endswith('.quark.cn'):self.send_error(502);return
    self.send_response(response.status)
    for key in ['Content-Type','Content-Length','Content-Range','Accept-Ranges']:
     if response.headers.get(key):self.send_header(key,response.headers[key])
    self.end_headers()
    while True:
     block=response.read(65536)
     if not block:break
     self.wfile.write(block)
  except (BrokenPipeError,ConnectionResetError):pass
  except Exception:self.send_error(502,'Media unavailable')
 def do_POST(self):
  if self.path not in ('/api/netdisk','/api/vod','/api/music','/api/deal'):self.send_error(404);return
  origin=self.headers.get('Origin','')
  if origin and origin not in ['http://127.0.0.1:'+str(self.server.server_port),'http://localhost:'+str(self.server.server_port)]:self.send_error(403);return
  try:
   size=int(self.headers.get('Content-Length',0))
   if not 0<size<=32768:raise ValueError('请求大小无效')
   data=json.loads(self.rfile.read(size));payload=fetch_vod(data.get('path'),data.get('host'))if self.path=='/api/vod'and not data.get('api')else fetch_api(data.get('api'),data.get('query'))if self.path=='/api/vod'and data.get('api')else fetch_music(data.get('q'))if self.path=='/api/music'else fetch_deal(data.get('deal_url'))if self.path=='/api/deal'else rpc(data['action'],data.get('args',{}));result={'ok':True,'data':payload}
  except Exception as e:result={'ok':False,'error':str(e)}
  encoded=json.dumps(result,ensure_ascii=False).encode('utf-8');self.send_response(200);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(encoded)));self.end_headers();self.wfile.write(encoded)
 def log_message(self,format,*args):
  # 不记录请求体、分享令牌或账号信息。
  if self.path.startswith('/api/'):return
  # 仅输出方法与路径，不记录 URL 参数。
  super().log_message('%s %s',self.command,urlparse(self.path).path)
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=8138);args=parser.parse_args();ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
