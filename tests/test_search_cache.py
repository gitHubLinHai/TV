from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
import preview_server as p
calls=[]
def response(url,body=None,quark=False):
 calls.append(url)
 if 'pansearch.me' in url:return {'props':{'pageProps':{'data':{'data':[]}}}}
 if 'qkpanso' in url:return {'code':419,'msg':'该关键词不提供结果','data':None}
 return {'code':0,'data':{'merged_by_type':{'quark':[]}}}
p.cache.clear();p.request=response
p.rpc('search',{'q':'normal'});assert len(calls)==3
p.rpc('search',{'q':'normal'});assert len(calls)==3,'normal cache not reused'
p.rpc('search',{'q':'source-split','source':'pansearch'});assert len(calls)==4,'selected-source request queried other providers'
p.rpc('search',{'q':'source-split','source':'pansearch'});assert len(calls)==4,'selected-source cache not reused'
p.rpc('search',{'q':'source-split','source':'qkpanso'});assert len(calls)==5,'different source reused the wrong cached payload'
p.rpc('search',{'q':'normal','private':True});assert len(calls)==8,'private search used normal cache'
p.rpc('search',{'q':'private-only','private':True});assert 'private-only' not in p.cache,'private query retained'
def failed(*args,**kwargs):return {'code':419,'msg':'拒绝','data':None}
p.request=failed
p.rpc('search',{'q':'failed'});assert 'failed' not in p.cache,'business error cached as success'
print('PASS: private searches neither read nor write search cache; business failures are not cached as successful results.')
