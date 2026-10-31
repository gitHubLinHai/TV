(function(){
  'use strict';
  var bridge=window.TVBridge,$=function(id){return document.getElementById(id);};
  // 站点解析知识已模块化到 vod-sources.js（片源适配器注册表）。
  var serial=0,epoch=0,pending={},siteNextRefs=[],path='',allItems=[],detail=null,scroll=0,playing=false,currentEpisode=null,currentCat=0,viewIndex=0,totalSitePages=0,sitePageItems=0,loadingMore=false,seenTitles=Object.create(null),sort='',lastPlan=null,apiClassesCache={},noCat=[];var PAGE=10;
  var KIND_KEYWORDS={'电视剧':['电视剧','连续剧','国产剧','剧'],'电影':['电影'],'短剧':['短剧'],'动漫':['动漫','动画'],'综艺纪录':['综艺','纪录','娱乐']};
  var categories=[['最近更新','__latest__'],['电视剧','/vodshow/2-----------.html'],['电影','/vodshow/1-----------.html'],['短剧','/vodshow/125-----------.html'],['动漫','/vodshow/4-----------.html'],['综艺纪录','/vodshow/3-----------.html']];
  var SORTS=[['综合',''],['最热播','hits'],['高分','score']];
  function sourceFor(value){
    if(value&&typeof value==='object')return value;
    return sources&&sources.find(function(s){return s.host===value;})||{name:'555',host:value||'www.555yy1.com',adapter:'maccms-page'};
  }
  function sitePath(value,source){return VodSources.resolve(sourceFor(source)).sitePath(value);}
  function resource(value){return VodSources.resource(value);}
  // ===== 片源管理：可配置多个 maccms 结构站点，勾选参与抓取，重启后保持 =====
  function loadSources(){
    var raw=null;
    try{raw=window.TVPrivacy.enabled?sessionStorage.getItem('tv_vod_sources'):localStorage.getItem('tv_vod_sources');}catch(e){}
    if(raw)try{var s=JSON.parse(raw);if(Array.isArray(s)&&s.length&&s.every(function(x){return x&&(x.type==='api'?typeof x.api==='string':typeof x.host==='string')&&typeof x.name==='string';})){
      if(!s.some(function(x){return x.adapter==='4kvm-page'||x.host==='www.4kvm.net';}))s.push({name:'4KVM',host:'www.4kvm.net',adapter:'4kvm-page',on:true,builtin:true});
      return s;
    }}catch(e){}
    return [{name:'555',host:'www.555yy1.com',adapter:'maccms-page',on:true,builtin:true},{name:'4KVM',host:'www.4kvm.net',adapter:'4kvm-page',on:true,builtin:true}];
  }
  function saveSources(){
    var json=JSON.stringify(sources);
    try{if(window.TVPrivacy.enabled)sessionStorage.setItem('tv_vod_sources',json);else localStorage.setItem('tv_vod_sources',json);}catch(e){}
  }
  var sources=loadSources();
  function loadHostCats(){try{var raw=window.TVPrivacy.enabled?sessionStorage.getItem('tv_vod_cats'):localStorage.getItem('tv_vod_cats');if(raw){var c=JSON.parse(raw);if(c&&typeof c==='object')return c;}}catch(e){}return {};}
  function saveHostCats(){try{var json=JSON.stringify(VodSources.hostCats);if(window.TVPrivacy.enabled)sessionStorage.setItem('tv_vod_cats',json);else localStorage.setItem('tv_vod_cats',json);}catch(e){}}
  VodSources.hostCats=loadHostCats();
  
  function enabledSources(){return sources.filter(function(s){return s.on;});}
  function validHost(host){return /^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/.test(host)&&host.length<=80;}
  function request(target,source){
    source=sourceFor(source);target=sitePath(target,source);var host=source.host;
    if(bridge){if(!bridge.vodRequest)return Promise.reject(new Error('请安装新版 APK 后使用影视来源'));return new Promise(function(resolve,reject){var id='vod'+(++serial);var timer=setTimeout(function(){delete pending[id];reject(new Error('影视来源连接超时，请重试'));},30000);pending[id]={resolve:resolve,reject:reject,timer:timer};bridge.vodRequest(id,host,target);});}
    return fetch('/api/vod',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path:target,host:host})}).then(function(r){return r.json();}).then(function(r){if(!r.ok)throw new Error(r.error||'来源读取失败');return r.data;})
      .catch(function(e){if(e&&(e.name==='TypeError'||e.name==='AbortError'))throw new Error('本地预览服务未连接：请先运行 python tools/preview_server.py --port 8138 并保持窗口开启，再刷新本页重试');throw e;});
  }
  window.__vodReply=function(id,reply){var p=pending[id];if(!p)return;clearTimeout(p.timer);delete pending[id];if(reply.ok)p.resolve(reply.data);else p.reject(new Error(reply.error||'影视来源连接失败'));};
  function resolve4kvm(target){
    target=Vod4KVM.sitePath(target);
    if(!bridge||!bridge.vod4kvmResolve)return Promise.reject(new Error('4KVM 动态播放仅支持新版 APK'));
    return new Promise(function(resolve,reject){var id='vod'+(++serial);var timer=setTimeout(function(){delete pending[id];reject(new Error('4KVM 播放地址解析超时，请重试'));},35000);pending[id]={resolve:resolve,reject:reject,timer:timer};bridge.vod4kvmResolve(id,target);});
  }
  // ===== 接口源（maccms 标准 JSON）：任意提供 /api.php/provide/vod/ 的站点均可添加 =====
  function apiRequest(base,query){
    if(bridge){if(!bridge.vodApiRequest)return Promise.reject(new Error('请安装新版 APK 后使用接口片源'));return new Promise(function(resolve,reject){var id='vod'+(++serial);var timer=setTimeout(function(){delete pending[id];reject(new Error('接口连接超时，请重试'));},30000);pending[id]={resolve:resolve,reject:reject,timer:timer};bridge.vodApiRequest(id,base,query);});}
    return fetch('/api/vod',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({api:base,query:query})}).then(function(r){return r.json();}).then(function(r){if(!r.ok)throw new Error(r.error||'接口读取失败');return r.data;})
      .catch(function(e){if(e&&(e.name==='TypeError'||e.name==='AbortError'))throw new Error('本地预览服务未连接：请先运行 python tools/preview_server.py --port 8138 并保持窗口开启，再刷新本页重试');throw e;});
  }
  var apiCache={},API_TTL=300000;
  function apiFetch(base,query,bypass){
    var key=base+' '+query,hit=apiCache[key];
    if(hit&&!bypass&&Date.now()-hit.at<API_TTL)return Promise.resolve(hit.data);
    return apiRequest(base,query).then(function(data){
      var keys=Object.keys(apiCache);if(keys.length>40)delete apiCache[keys[0]];
      apiCache[key]={data:data,at:Date.now()};return data;});
  }
  function apiClasses(base){
    var hit=apiClassesCache[base];
    if(hit&&Date.now()-hit.at<RANK_TTL_API)return Promise.resolve(hit.list);
    return apiFetch(base,'ac=list').then(function(reply){
      var d=JSON.parse(reply.json);var list=Array.isArray(d.class)?d.class:[];
      apiClassesCache[base]={list:list,at:Date.now()};return list;});
  }
  var RANK_TTL_API=600000;
  function apiListQuery(base,plan){
    if(plan&&plan.cat)return apiClasses(base).then(function(cs){
      return VodSources.maccmsApi.listQuery(base,plan,KIND_KEYWORDS,cs);});
    return Promise.resolve(VodSources.maccmsApi.listQuery(base,plan,KIND_KEYWORDS,null));
  }
  function withPg(query,pg){return query.replace(/&pg=\d+/,'')+'&pg='+pg;}
  function packApiSource(s,plan,d,usedQuery){
    var lst=(d.list||[]).filter(Boolean);
    var items=lst.map(function(v){var title=String(v.vod_name||'').trim();if(!title)return null;
      return {api:true,apiBase:s.api,vodId:String(v.vod_id),title:title.slice(0,200),cover:resource(String(v.vod_pic||'')),note:String(v.vod_remarks||'').slice(0,100),src:s.name,key:VodSources.normalizeKey(title)};}).filter(Boolean);
    var pg=Number(d.page)||1,pc=Number(d.pagecount)||1;
    var nextRef=pg<pc?{kind:'api',base:s.api,query:withPg(usedQuery,pg+1)}:null;
    return {host:s.api,name:s.name,kind:'api',total:pc,items:items,nextRef:nextRef};
  }
    function parsePageList(s,html,current){return VodSources.resolve(s).parseList(html,current);}
    function parseApiList(jsonText,ref){return VodSources.maccmsApi.parseList(jsonText,ref);}
  function parseApiList(d,ref){
    var lst=(d.list||[]).filter(Boolean);
    var items=lst.map(function(v){var title=String(v.vod_name||'').trim();if(!title)return null;
      return {api:true,apiBase:ref.base,vodId:String(v.vod_id),title:title.slice(0,200),cover:resource(String(v.vod_pic||'')),note:String(v.vod_remarks||'').slice(0,100),key:title.replace(/\s+/g,'').toLowerCase()};}).filter(Boolean);
    var pg=Number(d.page)||1,pc=Number(d.pagecount)||1;
    return {items:items,nextRef:pg<pc?{kind:'api',base:ref.base,query:withPg(ref.query,pg+1)}:null,total:pc};
  }
    function parseApiDetail(v,item){return VodSources.maccmsApi.parseDetail(v,item);}
    function parsePageDetail(source,html,target){return VodSources.resolve(sourceFor(source)).parseDetail(html,target);}
    function parsePlayer(source,html){return VodSources.resolve(sourceFor(source)).parsePlayer(html);}
  // 页面缓存：同一片源+地址 5 分钟内直接复用（重进页签/返回片单秒开）；容量上限 40 页。
  var docCache={},DOC_TTL=300000;
  function fetchDoc(target,source,bypass){
    source=sourceFor(source);var key=(source.host||'')+target,hit=docCache[key];
    if(hit&&!bypass&&Date.now()-hit.at<DOC_TTL)return Promise.resolve(hit.data);
    return request(target,source).then(function(data){
      var keys=Object.keys(docCache);if(keys.length>40)delete docCache[keys[0]];
      docCache[key]={data:data,at:Date.now()};return data;});
  }
  // 类型 → 样式类映射（不同类型不同样式的徽标与卡片描边）
  var KIND_CLASS={'电视剧':'tv','电影':'film','短剧':'drama','动漫':'anime','综艺纪录':'variety'};
  var KIND_ALIAS={'连续剧':'tv','国产剧':'tv','美剧':'tv','韩剧':'tv','动画':'anime','综艺':'variety','纪录':'variety','纪录片':'variety'};
  function kindOf(note){
    var n=String(note||'');
    if(!n)return '';
    if(/第\s*\d+\s*集|更新至|连载|全集|完结/.test(n))return '电视剧';
    if(/期\s*$|^\d{6,8}$/.test(n))return '综艺纪录';
    if(/HD|高清|正片|TC|枪版|蓝光|国语|粤语|中字/i.test(n))return '电影';
    return '';
  }
  function button(label,fn,cls){var b=document.createElement('button');b.type='button';b.textContent=label;if(cls)b.className=cls;b.addEventListener('click',fn);return b;}
  function status(value){$('vod-status').textContent=value;}
  function showList(){detail=null;$('vod-list-view').hidden=false;$('vod-detail-view').hidden=true;$('vod-title').textContent='影视片库';}
  function renderList(){
    var box=$('vod-list');box.textContent='';
    var frag=document.createDocumentFragment();
    allItems.slice(viewIndex*PAGE,viewIndex*PAGE+PAGE).forEach(function(item){
      var kind=item.kind||kindOf(item.note);
      var card=button('',function(){openDetail(item);},'vod-card'+(kind?' kind-'+KIND_CLASS[kind]:''));var cover=document.createElement('div');cover.className='vod-cover';cover.textContent='暂无封面';
      if(item.cover){cover.textContent='';var img=document.createElement('img');img.src=item.cover;img.alt='';img.loading='lazy';img.decoding='async';img.referrerPolicy='no-referrer';img.addEventListener('error',function(){
        // 百度图片代理不稳定：失败一次后换上游直链重试，再失败才显示占位。
        if(!img.dataset.retried){img.dataset.retried='1';
          try{var u=new URL(img.src);if(/(^|\.)gimg\d*\.baidu\.com$/.test(u.hostname)){var d=u.searchParams.get('src');if(d){img.src='https://'+d;return;}}}catch(e){}
        }
        cover.textContent='封面暂不可用';
      });cover.appendChild(img);}
      if(kind){var badge=document.createElement('span');badge.className='type-badge badge-'+(KIND_CLASS[kind]||'misc');badge.textContent=kind;cover.appendChild(badge);}card.appendChild(cover);
      var title=document.createElement('strong');title.textContent=item.title;card.appendChild(title);var note=document.createElement('small');note.textContent=(item.src&&sources.length>1?item.src+' · ':'')+(item.note||'点击查看线路与选集');card.appendChild(note);frag.appendChild(card);
    });
    box.appendChild(frag);
  }
  function pageTotal(){return totalSitePages>1?Math.ceil(totalSitePages*(sitePageItems||23)/PAGE):1;}
  function statusFor(){return allItems.length?('已读取 '+allItems.length+' 部 · 第 '+(viewIndex+1)+'/'+pageTotal()+' 页'):'来源没有返回片单，可能无结果或页面结构已变化';}
  function renderPager(){
    var atEnd=!siteNextRefs.length&&(viewIndex+1)*PAGE>=allItems.length;
    $('vod-pager').hidden=viewIndex===0&&atEnd;
    $('vod-prev').disabled=viewIndex===0;
    $('vod-next').disabled=atEnd||loadingMore;
    $('vod-page').textContent='第 '+(viewIndex+1)+' / 约 '+pageTotal()+' 页';
  }
  function showView(){renderList();renderPager();}
  // ===== 多源抓取：并行取所有勾选的片源，按标题去重合并；单源失败不影响其余。 =====
  function packSource(s,d){
    return {host:s.host,name:s.name,total:d.total||0,
      nextRef:d.next?{kind:'page',host:s.host,adapter:s.adapter,path:d.next}:null,
      items:d.items.map(function(x){x.host=s.host;x.adapter=s.adapter;x.src=s.name;x.key=VodSources.normalizeKey(x.title);
        if(x.cover&&x.cover.charAt(0)==='/')x.cover='https://'+s.host+x.cover;
        return x;})};
  }
  function applyResults(results,fromCache){
    var diag=[],ok=0;
    results.forEach(function(r){
      if(r.error){diag.push(r.name+'：'+r.error);return;}
      ok++;totalSitePages=Math.max(totalSitePages,r.total);
      if(r.total>1&&r.items.length>sitePageItems)sitePageItems=r.items.length;
      if(r.nextRef)siteNextRefs.push(r.nextRef);
      r.items.forEach(function(x){if(seenTitles[x.key])return;seenTitles[x.key]=true;allItems.push(x);});
    });
    if(!ok){allItems=[];renderList();renderPager();status(diag.join('；')||'来源没有返回片单，可能无结果或页面结构已变化');return;}
    showView();
    if(noCat.length)diag=diag.length?diag:noCat.map(function(n){return n+'：无分类 '+catName;}).concat(diag);
    status(statusFor()+(ok>1?' · '+ok+'源':'')+(diag.length?' · '+diag.join('；'):'')+(fromCache?' · 缓存':''));
  }
  function loadList(target,bypass,plan,keepScroll){
    var enabled=enabledSources();
    if(plan)lastPlan=plan;else plan=lastPlan;
    var run=++epoch;playing=false;showList();path=target;viewIndex=0;allItems=[];siteNextRefs=[];totalSitePages=0;sitePageItems=0;seenTitles=Object.create(null);noCat=[];$('vod-pager').hidden=true;
    if(!enabled.length){status('没有启用的片源：请在片源管理中至少勾选一个');return Promise.resolve();}
    renderList();var scroller=document.querySelector('#vod-dialog .page-scroll');
    if(!keepScroll)scroller.scrollTop=0;
    var pages=enabled.filter(function(s){return s.type!=='api';});
    var apis=enabled.filter(function(s){return s.type==='api';});
    var catName=plan&&plan.cat;
    var pageTargets=pages.map(function(s){
      var adapter=VodSources.resolve(s),targetPath=null;
      if(catName==='最近更新')targetPath=adapter.latestPath||adapter.defaultPath||null;
      else if(catName)targetPath=adapter.categoryPath?adapter.categoryPath(catName):VodSources.categoryPath(s,catName,categories);
      else if(plan&&plan.q&&adapter.searchPath)targetPath=adapter.searchPath(plan.q);
      else{try{targetPath=adapter.sitePath(target);}catch(e){}if(!targetPath&&adapter.defaultPath)targetPath=adapter.defaultPath;}
      if(targetPath&&adapter.applySort)targetPath=adapter.applySort(targetPath,sort);
      return {s:s,path:targetPath};
    });
    var noCat=pageTargets.filter(function(pt){return !pt.path;}).map(function(pt){return pt.s.name+'：无分类 '+catName;});
    pages=pageTargets.filter(function(pt){return !!pt.path;}).map(function(pt){return pt.s;});
    var catTargets=pageTargets;
    var cached=!bypass&&!apis.length&&catTargets.every(function(pt){var k=pt.s.host+pt.path,hit=docCache[k];return hit&&Date.now()-hit.at<DOC_TTL;});
    if(cached){applyResults(catTargets.filter(function(pt){return !!pt.path;}).map(function(pt){var pk=packSource(pt.s,parsePageList(pt.s,docCache[pt.s.host+pt.path].data.html,pt.path));
      if(catName&&catName!=='最近更新')pk.items.forEach(function(x){if(!x.kind)x.kind=catName;});return pk;}),true);return Promise.resolve();}
    status('正在读取影视片单…（'+enabled.length+'个片源）');
    var pageJobs=catTargets.filter(function(pt){return !!pt.path;}).map(function(pt){
      return fetchDoc(pt.path,pt.s.host,bypass).then(function(reply){var pk=packSource(pt.s,parsePageList(pt.s,reply.html,pt.path));
        if(catName&&catName!=='最近更新')pk.items.forEach(function(x){if(!x.kind)x.kind=catName;});
        return pk;})
        .catch(function(e){return {host:pt.s.host,name:pt.s.name,error:e.message||'连接失败'};});
    });
    var apiJobs=apis.map(function(s){
      return apiListQuery(s.api,plan).then(function(q){
        return apiFetch(s.api,withPg(q,1),bypass).then(function(reply){
          return packApiSource(s,plan,JSON.parse(reply.json),withPg(q,1));});
      }).catch(function(e){return {host:s.api,name:s.name,error:e.message||'接口连接失败'};});
    });
    return Promise.all(pageJobs.concat(apiJobs)).then(function(results){if(run!==epoch)return;applyResults(results,false);if(!keepScroll)scroller.scrollTop=0;});
  }
  // 一页 10 个：目标页不满 10 个时自动向各片源补取，直到凑满或没有更多。
  function gotoPage(target){
    if(loadingMore||target<0||target===viewIndex)return;
    var run=epoch;
    var finish=function(ok){
      loadingMore=false;renderPager();
      var maxPage=Math.max(0,Math.ceil(allItems.length/PAGE)-1);
      if(target<=maxPage){viewIndex=target;showView();status(statusFor()+(ok?'':' · 后面没有更多了'));}
      else{status('后面没有更多内容了');}
    };
    // 无需补取即可显示（整页或末尾残页）
    if((target+1)*PAGE<=allItems.length||!siteNextRefs.length){
      if((target+1)*PAGE<=allItems.length||target*PAGE<allItems.length){viewIndex=Math.min(target,Math.max(0,Math.ceil(allItems.length/PAGE)-1));showView();status(statusFor());}
      else status('后面没有更多内容了');
      return;
    }
    loadingMore=true;status('正在加载下一页…');renderPager();var rounds=0;
    var step=function(){
      if(run!==epoch)return;
      if((target+1)*PAGE<=allItems.length){finish(true);return;}
      var refs=siteNextRefs.slice();
      if(!refs.length||rounds>=10){finish(target*PAGE<allItems.length);return;}
      rounds++;
      Promise.all(refs.map(function(ref){
        if(ref.kind==='api')return apiFetch(ref.base,ref.query).then(function(reply){return {ref:ref,res:parseApiList(JSON.parse(reply.json),ref)};}).catch(function(){return {ref:ref,res:null};});
         var source=sourceFor(ref.host);return fetchDoc(ref.path,source).then(function(reply){return {ref:ref,res:parsePageList(source,reply.html,ref.path)};}).catch(function(){return {ref:ref,res:null};});
      })).then(function(results){
        if(run!==epoch)return;
        siteNextRefs=[];var added=0;
        results.forEach(function(r){
          if(!r.res)return;
          if(r.res.nextRef)siteNextRefs.push(r.res.nextRef);
          else if(r.res.next)siteNextRefs.push({kind:'page',host:r.ref.host,path:r.res.next});
          r.res.items.forEach(function(x){x.key=VodSources.normalizeKey(x.title);
            if(seenTitles[x.key])return;seenTitles[x.key]=true;allItems.push(x);added++;});
        });
        step();
      }).catch(function(){if(run===epoch){loadingMore=false;renderPager();status('加载失败，请重试');}});
    };
    step();
  }
  function nextPage(){gotoPage(viewIndex+1);}
  function prevPage(){gotoPage(viewIndex-1);}
    function openDetail(item,bypass){
    var run=++epoch;scroll=document.querySelector('#vod-dialog .page-scroll').scrollTop;playing=false;$('vod-list-view').hidden=true;$('vod-pager').hidden=true;$('vod-detail-view').hidden=false;$('vod-title').textContent=item.title;status('正在读取详情和选集…');$('vod-routes').textContent='';$('vod-episodes').textContent='';$('vod-description').textContent='';document.querySelector('#vod-dialog .page-scroll').scrollTop=0;
    if(item.apiBase)return apiFetch(item.apiBase,'ac=videolist&ids='+encodeURIComponent(item.vodId),bypass).then(function(reply){if(run!==epoch)return;
      var v=(JSON.parse(reply.json).list||[]).filter(Boolean)[0];
      if(!v){throw new Error('接口没有返回该影片详情');}
      detail=parseApiDetail(v,item);detail.src=item.src;
      $('vod-title').textContent=detail.title||item.title;$('vod-description').textContent=detail.description;
      detail.routes.forEach(function(route,index){var opt=document.createElement('option');opt.value=String(index);opt.textContent=route.name+' · '+route.episodes.length+' 集';$('vod-routes').appendChild(opt);});
      renderEpisodes();status(detail.routes.length?'选择线路和剧集后，使用内置播放器播放':'该接口源没有可解析的 HTTPS 直链选集，可切换其他片源');
    }).catch(function(e){if(run===epoch)status(e.message);});
    var source=sourceFor(item.host);return fetchDoc(item.path,source,bypass).then(function(reply){if(run!==epoch)return;detail=parsePageDetail(source,reply.html,item.path);detail.host=item.host;detail.adapter=item.adapter;detail.itemPath=item.path;detail.src=item.src;$('vod-title').textContent=detail.title||item.title;$('vod-description').textContent=detail.description;
      detail.routes.forEach(function(route,index){var opt=document.createElement('option');opt.value=String(index);opt.textContent=route.name+' · '+route.episodes.length+' 集';$('vod-routes').appendChild(opt);});
      var preferred=detail.routes.findIndex(function(route){return route.name.trim()==='超清线路2';});if(preferred>=0)$('vod-routes').value=String(preferred);
      renderEpisodes();status(detail.routes.length?'选择线路和剧集后，使用内置播放器播放':'没有读到可用选集，可能需要稍后重试');
    }).catch(function(e){if(run===epoch)status(e.message);});
  }
  function renderEpisodes(){
    $('vod-episodes').textContent='';if(!detail)return;var route=detail.routes[Number($('vod-routes').value)];if(!route)return;
    route.episodes.forEach(function(ep){$('vod-episodes').appendChild(button(ep.title,function(){play(ep);},'secondary'));});
  }
  function play(ep){
    if(playing||!detail)return;var run=++epoch,selected=detail;playing=true;currentEpisode=ep;status('正在获取 '+ep.title+' 的播放地址…');
    var directPlay=function(url,referer){
      if(bridge){if(!bridge.playVodVideo)throw new Error('请安装新版 APK 播放此来源');bridge.playVodVideo(url,selected.title+' · '+ep.title,'vod:'+selected.id+':'+ep.title,referer||'');status('已打开内置播放器，返回后可继续选集或切换线路');return Promise.resolve();}
      else {var video=$('vod-video');video.hidden=false;video.src=url;return video.play().catch(function(){status('请点击视频播放；此来源在 APK 内使用原生播放器。');});}
    };
    if(ep.direct)return directPlay(ep.url,'').catch(function(e){if(run===epoch)status(e.message);}).finally(function(){if(run===epoch)playing=false;});
    if(ep.resolve4kvm)return resolve4kvm(ep.path).then(function(reply){if(run!==epoch)return;return directPlay(reply.url,'');}).catch(function(e){if(run===epoch)status(e.message);}).finally(function(){if(run===epoch)playing=false;});
    return request(ep.path,sourceFor(selected.host)).then(function(reply){if(run!==epoch)return;var url=parsePlayer(sourceFor(selected.host),reply.html);
      return directPlay(url,selected.host);
    }).catch(function(e){if(run===epoch)status(e.message);}).finally(function(){if(run===epoch)playing=false;});
  }
  function stop(){var video=$('vod-video');video.pause();video.removeAttribute('src');video.load();video.hidden=true;}
  // 原生播放器自然播完时自动连播当前线路的下一集；没有下一集则停在选集。
  function autoNext(key){
    if(!$('vod-dialog').open||!detail||!currentEpisode)return;
    var route=detail.routes[Number($('vod-routes').value)];if(!route)return;
    var index=-1;route.episodes.forEach(function(ep,i){if(ep.path===currentEpisode.path)index=i;});
    if(index>=0&&index+1<route.episodes.length){status('本集播完，自动播放下一集…');playing=false;play(route.episodes[index+1]);}
    else status('已是最后一集，返回后可选择其他集或线路');
  }
  function customRefresh(){
    var value=$('vod-custom').value.trim();
    if(!value){status('请先填写自定义路径，例如 /vodshow/20-----------.html');return;}
    try{
      var target=sitePath(value);
      if(!window.TVPrivacy.enabled)try{localStorage.setItem('tv_vod_custom',target);}catch(e){}
      loadList(target,true,{},true);
    }catch(e){status('路径不受支持：需为片源站内的分类/搜索/详情页地址，且不含查询参数');}
  }
  function close(){++epoch;playing=false;currentEpisode=null;currentCat=0;viewIndex=0;totalSitePages=0;siteNextRefs=[];allItems=[];loadingMore=false;markChip();stop();$('vod-query').value='';detail=null;$('vod-list').textContent='';$('vod-episodes').textContent='';$('vod-description').textContent='';}
  $('vod-close').addEventListener('click',function(){window.Vod.reset();});$('vod-dialog').addEventListener('close',close);
  function markChip(){Array.prototype.forEach.call($('vod-categories').children,function(b,i){b.classList.toggle('selected',i===currentCat);});}
  function renderChips(){var box=$('vod-categories');box.textContent='';categories.forEach(function(c,i){var b=button(c[0],function(){currentCat=i;markChip();loadList(applySort(c[1]),false,{cat:c[0]});},i===currentCat?'selected':'');box.appendChild(b);});}
  // ===== 排序：综合(默认按更新)/最热播(按人气)/高分(按评分)，仅对分类页生效 =====
  function applySort(target){if(!sort)return target;var m=target.match(/^\/vodshow\/(\d+)-{11}\.html$/);return m?'/vodshow/'+m[1]+'--'+sort+'---------.html':target;}
  function markSorts(){Array.prototype.forEach.call($('vod-sorts').children,function(b,i){b.classList.toggle('selected',SORTS[i][1]===sort);});}
  function renderSorts(){var box=$('vod-sorts');box.textContent='';SORTS.forEach(function(s){var b=button(s[0],function(){sort=s[1];markSorts();if(/^\/vodshow\/\d+-{11}\.html$/.test(path))loadList(applySort(path),false,null,true);else status('当前列表不涉及排序；选择分类后按“'+s[0]+'”加载');},sort===s[1]?'selected':'');box.appendChild(b);});}
  function openVod(){if(!$('vod-dialog').open)$('vod-dialog').show();currentCat=1;markChip();window.__vodTab='vod-open';loadList(applySort(categories[1][1]),false,{cat:categories[1][0]});}
  $('vod-open').addEventListener('click',openVod);
  renderChips();renderSorts();
  // ===== 片源管理 UI =====
  function renderSources(){
    var box=$('vod-sources');box.textContent='';
    sources.forEach(function(s){
      var row=document.createElement('label');row.className='source-check';
      var cb=document.createElement('input');cb.type='checkbox';cb.checked=!!s.on;
      cb.addEventListener('change',function(){s.on=cb.checked;saveSources();if(path)loadList(path);});
      var name=document.createElement('span');name.className='source-name';name.textContent=s.type==='api'?s.name+' · 接口源':s.name+' · '+s.host;
      row.appendChild(cb);row.appendChild(name);
      if(!window.TVPrivacy.enabled&&!s.builtin)row.appendChild(button('删除',function(){sources=sources.filter(function(x){return x!==s;});saveSources();renderSources();},'source-del'));
      box.appendChild(row);
    });
  }
  $('vod-source-add').addEventListener('click',function(){
    if(window.TVPrivacy.enabled){status('无痕模式不保存片源修改');return;}
    var name=$('vod-source-name').value.trim()||('源'+(sources.length+1));
    var addr=$('vod-source-host').value.trim();
    if(!addr){status('请先填写域名或接口地址');return;}
    if(sources.length>=8){status('最多支持 8 个片源');return;}
    var rest=addr.split('://').pop();
    var host=rest.split('/')[0];
    var isApi=rest.indexOf('api.php/provide/vod')>=0;
    if(isApi){
      // 显式接口地址：直接验证 maccms JSON 接口
      if(!validHost(host)){status('接口地址域名无效');return;}
      var base='https://'+host+'/api.php/provide/vod/';
      if(sources.some(function(s){return s.api===base;})){status('该接口源已存在');return;}
      status('正在验证接口…');$('vod-source-add').disabled=true;
      apiFetch(base,'ac=list',true).then(function(reply){
        var classes=VodSources.maccmsApi.classify(reply.json);if(!classes.length)throw new Error('0');
        sources.push({adapter:'maccms-api',type:'api',name:name.slice(0,12),api:base,on:true});
        apiClassesCache[base]={list:classes,at:Date.now()};
        $('vod-source-name').value='';$('vod-source-host').value='';
        saveSources();renderSources();$('vod-source-add').disabled=false;
        loadList(applySort(path||categories[0][1]),true,lastPlan);
        status('接口源已添加：'+name.slice(0,12)+'（'+classes.length+' 个分类）');
      }).catch(function(){$('vod-source-add').disabled=false;status('接口验证失败：请确认地址可访问且为 maccms VOD 接口');});
      return;
    }
    // 纯域名：逐适配器探测（页面模板 → 接口源），全部失败给出明确报错。
    if(!validHost(host)){status('请填写有效站点域名，例如 www.example.com');return;}
    if(sources.some(function(s){return s.host===host;})){status('该站点已存在');return;}
    var tries=VodSources.ADAPTERS.slice();
    var diag=[];
    var tryNext=function(){
      var ad=tries.shift();
      if(!ad){$('vod-source-add').disabled=false;status('无法识别该站点：'+(diag.length?diag.join('；'):'所有适配器均解析失败'));return;}
      status('正在探测：'+ad.label+'…');$('vod-source-add').disabled=true;
      probeRpc('https://'+host+ad.probePath()).then(function(reply){
        if(!ad.probeCheck(reply.body))throw new Error('结构不匹配');
        if(ad.id==='maccms-page'){
          // 页面源：再拉首页发现分类，映射到分类芯片
          return probeRpc('https://'+host+'/').then(function(home){
            var cats=ad.discoverCats(home.body);
            VodSources.hostCats[host]=cats;saveHostCats();
            sources.push({adapter:'maccms-page',name:name.slice(0,12),host:host,on:true});
            $('vod-source-name').value='';$('vod-source-host').value='';
            saveSources();renderSources();$('vod-source-add').disabled=false;
            loadList(applySort(path||categories[0][1]),true,lastPlan);
            status('已添加：'+name.slice(0,12)+'（'+ad.label+'，'+cats.length+' 个分类'+(cats.length?'':'，可通过搜索使用')+'）');
          });
        }
        var classes=VodSources.maccmsApi.classify(reply.body);
        var base='https://'+host+'/api.php/provide/vod/';
        sources.push({adapter:'maccms-api',name:name.slice(0,12),api:base,on:true});
        apiClassesCache[base]={list:classes,at:Date.now()};
        $('vod-source-name').value='';$('vod-source-host').value='';
        saveSources();renderSources();$('vod-source-add').disabled=false;
        loadList(applySort(path||categories[0][1]),true,lastPlan);
        status('已添加：'+name.slice(0,12)+'（'+ad.label+'，'+classes.length+' 个分类）');
      }).catch(function(e){diag.push(ad.label+'：'+(e.message||'连接失败'));$('vod-source-add').disabled=false;tryNext();});
    };
    tryNext();
  });
  renderSources();
  $('vod-search').addEventListener('submit',function(e){e.preventDefault();var q=$('vod-query').value.trim();if(q)loadList('/vodsearch/'+encodeURIComponent(q)+'-------------.html',false,{q:q});});
  $('vod-custom-refresh').addEventListener('click',customRefresh);
  $('vod-custom').addEventListener('keydown',function(e){if(e.key==='Enter'){e.preventDefault();customRefresh();}});
  if(!window.TVPrivacy.enabled){try{$('vod-custom').value=localStorage.getItem('tv_vod_custom')||'';}catch(e){}}
  $('vod-refresh').addEventListener('click',function(){if(detail){var item={path:detail.itemPath||('/voddetail/'+detail.id+'.html'),title:detail.title,host:detail.host,adapter:detail.adapter,src:detail.src};openDetail(item,true);}else loadList(path||categories[0][1],true,lastPlan&&lastPlan.cat?{cat:lastPlan.cat}:lastPlan);});
  $('vod-back').addEventListener('click',function(){++epoch;playing=false;stop();showList();renderList();status('已返回原片单');document.querySelector('#vod-dialog .page-scroll').scrollTop=scroll;renderPager();});
  $('vod-routes').addEventListener('change',function(){++epoch;playing=false;stop();renderEpisodes();status('已切换线路，请选择剧集');});
  $('vod-video').addEventListener('error',function(){if($('vod-video').getAttribute('src'))status('网页预览未能播放此地址，请使用 APK 或切换线路。');});
  // 分页：中间内容区固定，一页 10 个，用上一页/下一页替换内容。
  $('vod-prev').addEventListener('click',prevPage);
  $('vod-next').addEventListener('click',nextPage);
  // 预载：进入应用后后台预热默认分类与短剧分类（由原生 pageReady 触发）。
  function preload(){
    var enabled=enabledSources();if(!enabled.length)return;
    var di=categories.findIndex(function(c){return c[0]==='短剧';});
    enabled.forEach(function(s){
      if(s.type==='api'){apiFetch(s.api,'ac=videolist&pg=1').catch(function(){});return;}
      var adapter=VodSources.resolve(s),first=adapter.categoryPath?adapter.categoryPath(categories[0][0]):categories[0][1],drama=di>=0&&adapter.categoryPath?adapter.categoryPath(categories[di][0]):di>=0?categories[di][1]:null;
      if(first)fetchDoc(first,s).catch(function(){});
      if(drama)fetchDoc(drama,s).catch(function(){});
    });
  }
  window.Vod={back:function(){if(!$('vod-detail-view').hidden)$('vod-back').click();else window.Vod.reset();},reset:function(){close();if($('vod-dialog').open)$('vod-dialog').close();},autoNext:autoNext,preload:preload,applySort:applySort,parseList:function(html){return VodSources.maccmsPage.parseList(html);},parseDetail:function(html,target){return VodSources.maccmsPage.parseDetail(html,target);},parsePlayer:function(html){return VodSources.maccmsPage.parsePlayer(html);},sitePath:sitePath};
}());
