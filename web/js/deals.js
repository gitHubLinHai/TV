(function(){
  'use strict';
  var bridge=window.TVBridge,$=function(id){return document.getElementById(id);};

  // ===== 优惠线报：聚合源 + 平台过滤 + 节流熔断 =====
  // 设计原则：只抓站点开放的 feed 出口（RSS/Atom/JSON），绝不抓 HTML 页面。
  // 全局 30 分钟节流 + 单源熔断，宁可少更新也不要把源抓死。

  var GLOBAL_INTERVAL = 30 * 60 * 1000;   // 全局最小刷新间隔：30 分钟
  var SOURCE_INTERVAL = 30 * 60 * 1000;   // 单源最小间隔：30 分钟
  var COOL_AFTER_2   = 6 * 60 * 60 * 1000; // 连续 2 次失败 → 冷却 6 小时
  var COOL_AFTER_3   = 24 * 60 * 60 * 1000;// 连续 3 次失败 → 冷却 24 小时
  var MAX_FAILS      = 3;

  // 内置源：全部实测可免登录访问；源失效时可被远程清单覆盖。
  // 注意：smzdm 公开 RSS 是"首页混合流"（资讯+部分好价），靠"只看线报"过滤兜底；
  // post.smzdm.com 已移除——那是纯文章源，不是线报。
  var DEFAULT_SOURCES = [
    {name:'值值值',      url:'https://www.zhizhizhi.com/feed',   platforms:['jd','tmall','taobao','pdd']},
    {name:'值得买RSS',   url:'https://www.smzdm.com/rss/',        platforms:['jd','tmall','general']}
  ];

  // 平台识别规则：从标题 + 正文里判定所属商城。
  // 注意：按"最强信号优先"只取一个平台，避免"天猫百亿补贴"被误判成拼多多。
  var PLATFORMS = [
    {id:'jd',     label:'京东',   kw:['京东','京喜','京东自营','京东超市','plus会员']},
    {id:'tmall',  label:'天猫',   kw:['天猫','天猫超市','猫超']},
    {id:'taobao', label:'淘宝',   kw:['淘宝','聚划算','淘金币']},
    {id:'pdd',    label:'拼多多', kw:['拼多多']},
    {id:'douyin', label:'抖音',   kw:['抖音','抖店']},
    {id:'vip',    label:'唯品会', kw:['唯品会']}
  ];

  var serial=0,pending={},items=[],allItems=[],activePlatform='all',dealQuery='';

  function store(){return window.TVPrivacy&&window.TVPrivacy.enabled?sessionStorage:localStorage;}
  function read(key,fallback){
    try{var raw=store().getItem(key);if(raw)return JSON.parse(raw);}catch(e){}
    return fallback;
  }
  function write(key,value){
    try{store().setItem(key,JSON.stringify(value));}catch(e){}
  }
  function today(){var d=new Date();return d.getFullYear()+'-'+(d.getMonth()+1)+'-'+d.getDate();}
  function nowStr(){var d=new Date();function p(n){return (n<10?'0':'')+n;}return today()+' '+p(d.getHours())+':'+p(d.getMinutes());}
  function status(v){var el=$('deal-status');if(el)el.textContent=v;}

  // ===== 节流与熔断状态（落盘，跨启动有效）=====
  function loadState(){return read('tv_deal_state',{lastRun:0,sources:{}});}
  function saveState(s){write('tv_deal_state',s);}
  function sourceState(s,url){
    if(!s.sources[url])s.sources[url]={fails:0,lastAt:0,coolUntil:0,okCount:0};
    return s.sources[url];
  }
  function canRun(){
    var s=loadState();
    if(!s.lastRun)return true;
    return Date.now()-s.lastRun>=GLOBAL_INTERVAL;
  }
  function minutesUntilNext(){
    var s=loadState();
    if(!s.lastRun)return 0;
    var left=GLOBAL_INTERVAL-(Date.now()-s.lastRun);
    return left>0?Math.ceil(left/60000):0;
  }

  // ===== 源清单：内置默认，可被远程清单覆盖 =====
  function loadSources(){
    var remote=read('tv_deal_sources',null);
    if(remote&&Array.isArray(remote.list)&&remote.list.length)return remote.list.slice(0,8);
    return DEFAULT_SOURCES.slice();
  }

  // ===== 平台判定：按"最强信号"只取一个，避免叠加误判 =====
  function detectPlatform(text){
    var t=String(text||'');
    // 标题里的商城信号优先于正文
    for(var i=0;i<PLATFORMS.length;i++){
      var p=PLATFORMS[i];
      for(var k=0;k<p.kw.length;k++){
        if(t.indexOf(p.kw[k])>=0)return [p];
      }
    }
    return [];
  }
  function platformLabels(list){
    return list.map(function(p){return p.label;}).join('·');
  }

  // ===== 价格与优惠抽取 =====
  var PRICE_RE=/(\d+(?:\.\d{1,2})?)\s*元/;
  var COUPON_RE=/领券|优惠券|满\d+减\d+|国补|补贴|PLUS|plus|凑单|百亿补贴|折扣|立减|直降|拍\d+件/;
  function extractPrice(text){
    var m=PRICE_RE.exec(String(text||''));
    return m?m[1]:'';
  }
  function extractTags(text){
    var tags=[],t=String(text||'');
    if(/领券|优惠券/.test(t))tags.push('券');
    if(/满\d+减\d+/.test(t))tags.push('满减');
    if(/国补|补贴/.test(t))tags.push('补贴');
    if(/plus|PLUS/.test(t))tags.push('PLUS');
    if(/凑单/.test(t))tags.push('凑单');
    if(/百亿补贴/.test(t))tags.push('百亿补贴');
    if(/立减|直降/.test(t))tags.push('直降');
    return tags;
  }

  // ===== RPC =====
  function rpc(payload){
    if(bridge&&bridge.dealAggRequest){
      return new Promise(function(resolve,reject){
        var id='da'+(++serial);
        var timer=setTimeout(function(){delete pending[id];reject(new Error('优惠源连接超时，请稍后重试'));},60000);
        pending[id]=function(reply){clearTimeout(timer);delete pending[id];if(reply.ok)resolve(reply.data);else reject(new Error(reply.error||'优惠源读取失败'));};
        bridge.dealAggRequest(id,JSON.stringify(payload));
      });
    }
    if(bridge&&bridge.dealRequest){
      // 旧版 APK 兜底：逐个源走 dealRequest（无 ETag，但仍然只抓 feed）
      return Promise.all(payload.map(function(src){
        return new Promise(function(resolve,reject){
          var id='dl'+(++serial);
          var timer=setTimeout(function(){delete pending[id];reject(new Error('连接超时'));},30000);
          pending[id]=function(reply){clearTimeout(timer);delete pending[id];if(reply.ok)resolve({name:src.name,url:src.url,ok:true,body:reply.data.body});else reject(new Error(reply.error||'读取失败'));};
          bridge.dealRequest(id,src.url);
        }).catch(function(e){return {name:src.name,url:src.url,ok:false,error:e.message};});
      })).then(function(rows){return {results:rows};});
    }
    var controller=new AbortController(),timer=setTimeout(function(){controller.abort();},60000);
    return fetch('/api/dealagg',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload),signal:controller.signal})
      .then(function(r){return r.json();})
      .then(function(r){if(!r.ok)throw new Error(r.error||'优惠源读取失败');return r.data;})
      .catch(function(e){clearTimeout(timer);if(e&&(e.name==='TypeError'||e.name==='AbortError'))throw new Error('本地预览服务未连接：请先运行 python tools/preview_server.py --port 8138');throw e;})
      .finally(function(){clearTimeout(timer);});
  }
  window.__dealReply=function(id,reply){var cb=pending[id];if(cb)cb(reply);};

  // ===== 解析 =====
  function validLink(link,base){
    try{var u=new URL(link,base||location.href);if(u.protocol!=='https:'&&u.protocol!=='http:')return '';return u.href.slice(0,1000);}catch(e){return '';}
  }
  function stripTags(html){
    return String(html||'').replace(/<[^>]*>/g,'').replace(/&#\d+;/g,' ').replace(/&[a-z]+;/g,' ').replace(/\s+/g,' ').trim();
  }
  function parseFeed(body,feedUrl,srcName){
    body=String(body||'').trim();
    if(!body)return [];
    if(body.charAt(0)==='{')return parseJson(body,feedUrl,srcName);
    if(body.charAt(0)==='[')return parseJson(body,feedUrl,srcName);
    var doc=new DOMParser().parseFromString(body,'text/xml');
    if(doc.querySelector('parsererror'))throw new Error('不是有效的 RSS/Atom');
    var out=[];
    Array.from(doc.querySelectorAll('item')).forEach(function(it){
      var link=validLink(text(it,'link'),feedUrl)||validLink(text(it,'guid'),feedUrl);
      var row=makeRow(text(it,'title'),link,text(it,'pubDate'),text(it,'description'),srcName);
      if(row)out.push(row);
    });
    Array.from(doc.querySelectorAll('entry')).forEach(function(en){
      var link='';
      Array.from(en.querySelectorAll('link')).forEach(function(l){
        var href=l.getAttribute('href')||text(l);
        if(href&&!link)link=href;
      });
      var row=makeRow(text(en,'title'),validLink(link,feedUrl),text(en,'updated'),text(en,'summary'),srcName);
      if(row)out.push(row);
    });
    return out;
    function text(root,tag){var el=root.querySelector(tag);return el?el.textContent:'';}
  }
  function parseJson(body,feedUrl,srcName){
    var d=JSON.parse(body);
    var arr=Array.isArray(d)?d:(d.list||d.data||d.items||[]);
    if(!Array.isArray(arr))return [];
    return arr.map(function(x){
      return makeRow(x&&(x.title||x.name),x&&(x.url||x.link||x.mobil_url||x.href),x&&(x.time||x.pubDate||x.updated||x.date),x&&(x.desc||x.description||x.summary),srcName);
    }).filter(Boolean);
  }
  function makeRow(title,link,time,desc,srcName){
    title=String(title||'').replace(/\s+/g,' ').trim().slice(0,200);
    link=String(link||'').trim().slice(0,1000);
    if(!title||!link)return null;
    var timeStr=String(time||'').trim().slice(0,32);
    var ts=Date.parse(timeStr);
    var plain=stripTags(desc).slice(0,300);
    var plats=detectPlatform(title+' '+plain);
    var price=extractPrice(title)||extractPrice(plain);
    var tags=extractTags(title+' '+plain);
    // 线报形态判定：只认标题里的信号（正文噪音大，文章里全是"元/车"）；
    // 有价格/优惠标签/限时报("20点：")/"券后·0元·包邮·神价"等线报用词才算，否则视为资讯文章
    var titleTags=extractTags(title);
    var dealLike=!!(extractPrice(title)||titleTags.length||/^\d{1,2}点[:：]/.test(title)||/0元|包邮|到手价|神价|券后|百亿补贴/.test(title));
    return {
      title:title,
      link:link,
      time:timeStr,
      ts:isNaN(ts)?0:ts,
      src:srcName,
      price:price,
      tags:tags,
      plats:plats.map(function(p){return p.id;}),
      platsLabel:platformLabels(plats),
      desc:plain,
      dealLike:dealLike
    };
  }
  // 旧缓存条目没有 dealLike 字段：载入时按同样的"仅标题信号"规则补算
  function ensureDealLike(list){
    list.forEach(function(x){
      if(x&&x.dealLike===undefined)x.dealLike=!!(extractPrice(x.title||'')||extractTags(x.title||'').length||/^\d{1,2}点[:：]/.test(x.title||'')||/0元|包邮|到手价|神价|券后|百亿补贴/.test(x.title||''));
    });
    return list;
  }

  // ===== 渲染 =====
  function render(){
    var box=$('deal-list');
    if(!box)return;
    box.textContent='';
    var q=dealQuery.trim().toLowerCase();
    var onlyDeals=!$('deal-only')||$('deal-only').checked;
    var hidden=0;
    var list=allItems.filter(function(x){
      if(activePlatform!=='all'&&x.plats.indexOf(activePlatform)<0)return false;
      if(q){var hay=(x.title+' '+x.desc+' '+(x.src||'')+' '+(x.platsLabel||'')).toLowerCase();if(hay.indexOf(q)<0)return false;}
      if(onlyDeals&&!x.dealLike){hidden++;return false;}
      return true;
    });
    if(!list.length){
      var empty=document.createElement('p');empty.className='deal-empty';
      empty.textContent=dealQuery.trim()?('没有匹配“'+dealQuery.trim()+'”的条目：换个词、清空搜索或切换平台试试'):(allItems.length?'该平台暂无条目，换个平台或稍后再看':'暂无内容，稍后刷新试试');
      box.appendChild(empty);
      if(hidden)box.appendChild(note('已隐藏 '+hidden+' 条资讯文章，可取消勾选“只看线报”显示'));
      return;
    }
    var frag=document.createDocumentFragment();
    list.forEach(function(item){
      var row=document.createElement('button');row.type='button';row.className='deal-row';

      var head=document.createElement('div');head.className='deal-head';
      if(item.price){var price=document.createElement('b');price.className='deal-price';price.textContent='¥'+item.price;head.appendChild(price);}
      var title=document.createElement('strong');title.textContent=item.title;head.appendChild(title);
      row.appendChild(head);

      if(item.tags.length){
        var tags=document.createElement('div');tags.className='deal-tags';
        item.tags.forEach(function(t){var s=document.createElement('span');s.className='deal-tag';s.textContent=t;tags.appendChild(s);});
        row.appendChild(tags);
      }
      var meta=document.createElement('small');
      meta.textContent=(item.platsLabel?item.platsLabel+' · ':'')+(item.src?item.src+' · ':'')+(item.time||'');
      row.appendChild(meta);
      row.addEventListener('click',function(){openLink(item.link);});
      frag.appendChild(row);
    });
    box.appendChild(frag);
    if(hidden)box.appendChild(note('已按“只看线报”隐藏 '+hidden+' 条资讯文章'));
  }
  function note(text){var s=document.createElement('small');s.className='deal-hidden-note';s.textContent=text;return s;}
  function renderChips(){
    var box=$('deal-platforms');
    if(!box)return;
    box.textContent='';
    var all=[{id:'all',label:'全部'}].concat(PLATFORMS);
    all.forEach(function(p){
      var b=document.createElement('button');b.type='button';
      b.className='chip'+(activePlatform===p.id?' active':'');
      b.textContent=p.label;
      b.addEventListener('click',function(){activePlatform=p.id;renderChips();render();});
      box.appendChild(b);
    });
  }
  function openLink(link){
    if(bridge&&bridge.openUrl){bridge.openUrl(link);return;}
    if(window.TVPrivacy&&window.TVPrivacy.enabled){status('无痕模式不跳转外部页面，请在 APK 中打开');return;}
    // 网页版新开标签页打开原文，避免把应用页本身导航走
    if(!window.open(link,'_blank','noopener'))location.assign(link);
  }

  // ===== 抓取主流程 =====
  function fetchAll(force){
    var srcList=loadSources();
    if(!srcList.length){status('没有可用的优惠源');return Promise.resolve();}

    var st=loadState();
    var now=Date.now();
    var runnable=[],blocked=[];
    srcList.forEach(function(src){
      var ss=sourceState(st,src.url);
      if(ss.coolUntil&&now<ss.coolUntil){blocked.push(src);return;}
      runnable.push(src);
    });
    if(!runnable.length){
      status('所有源都在冷却中，'+Math.ceil((Math.min.apply(null,srcList.map(function(s){return sourceState(st,s.url).coolUntil;}))-now)/60000)+' 分钟后重试');
      return Promise.resolve();
    }

    status('正在拉取优惠线报…（'+runnable.length+' 个源，节流 30 分钟）');
    return rpc(runnable.map(function(s){
      var ss=sourceState(st,s.url);
      return {name:s.name,url:s.url,etag:ss.etag||'',lastModified:ss.lastModified||''};
    })).then(function(data){
      var rows=data&&data.results?data.results:[];
      var diag=[],ok=0,notModified=0;
      var merged=allItems.slice();

      rows.forEach(function(r){
        var src=runnable.filter(function(s){return s.url===r.url;})[0]||{name:r.name,url:r.url};
        var ss=sourceState(st,src.url);
        if(r.ok===false||r.error){
          ss.fails=(ss.fails||0)+1;
          if(ss.fails>=MAX_FAILS)ss.coolUntil=now+COOL_AFTER_3;
          else if(ss.fails>=2)ss.coolUntil=now+COOL_AFTER_2;
          diag.push(src.name+'：'+(r.error||'读取失败'));
          return;
        }
        ss.fails=0;ss.lastAt=now;ss.coolUntil=0;ss.okCount=(ss.okCount||0)+1;
        if(r.etag)ss.etag=r.etag;
        if(r.lastModified)ss.lastModified=r.lastModified;

        if(r.notModified){notModified++;ok++;return;}
        ok++;
        var parsed=[];
        try{parsed=parseFeed(r.body,src.url,src.name);}catch(e){diag.push(src.name+'：'+e.message);return;}
        parsed.forEach(function(x){merged.push(x);});
      });

      // 去重 + 按时间排序 + 上限
      var seen=Object.create(null),uniq=[];
      merged.forEach(function(x){
        var k=x.title+'|'+x.link;
        if(seen[k])return;seen[k]=true;uniq.push(x);
      });
      uniq.sort(function(a,b){return b.ts-a.ts;});
      if(uniq.length>300)uniq.length=300;
      allItems=uniq;

      st.lastRun=now;
      saveState(st);
      write('tv_deals_cache',{day:today(),at:nowStr(),items:allItems});
      render();

      if(!ok){
        status(diag.join('；')||'优惠源没有返回内容');
        return;
      }
      var parts=['共 '+allItems.length+' 条','更新于 '+nowStr()];
      if(notModified)parts.push(notModified+' 个源无变化');
      if(diag.length)parts.push(diag.join('；'));
      if(blocked.length)parts.push(blocked.length+' 个源冷却中');
      status(parts.join(' · '));
    }).catch(function(e){
      // 整体传输失败也必须落盘节流：否则每次打开都全量重抓，"保护源不被限流"就失效了
      try{var s2=loadState();s2.lastRun=Date.now();saveState(s2);}catch(ex){}
      if(!allItems.length){ // 拉取失败时回退旧缓存（哪怕跨天），好过空白
        var stale=read('tv_deals_cache',null);
        if(stale&&Array.isArray(stale.items)&&stale.items.length){allItems=stale.items;render();}
      }
      status((e&&e.message?e.message:'拉取失败')+'；已进入 30 分钟限流，稍后再试'+(allItems.length?' · 先显示缓存内容':''));
    });
  }

  function cached(){
    var c=read('tv_deals_cache',null);
    if(c&&c.day===today()&&Array.isArray(c.items))return c;
    return null;
  }

  function openTab(){
    var dlg=$('deal-dialog');
    if(!dlg)return;
    if(dlg.open)return;
    dlg.show();
    renderChips();
    var c=cached();
    if(c&&c.items.length){allItems=ensureDealLike(c.items);render();}
    else render();

    if(!canRun()){
      var m=minutesUntilNext();
      status('已节流：'+(allItems.length?'共 '+allItems.length+' 条 · ':'')+m+' 分钟后可再次拉取（保护优惠源不被限流）');
      return;
    }
    if(allItems.length){
      status('共 '+allItems.length+' 条 · 后台检查更新中…');
      fetchAll().catch(function(e){status(e.message||'拉取失败');});
      return;
    }
    fetchAll().catch(function(e){status(e.message||'拉取失败');});
  }

  // 远程源清单：走现有更新通道，源失效时改一个 JSON 即可热更。
  function refreshSourceManifest(){
    if(!bridge||!bridge.dealManifest)return;
  }

  function preload(){
    if(!canRun())return;
    if(cached())return;
    fetchAll().catch(function(){});
  }

  if($('deal-open'))$('deal-open').addEventListener('click',openTab);
  if($('deal-close'))$('deal-close').addEventListener('click',function(){$('deal-dialog').close();});
  // 关闭时重置筛选：否则上次留下的平台/搜索词会让下次打开"只剩几条"（真实踩过）。
  // 注意：个别浏览器内核不派发 dialog 的 close 事件，必须再用 MutationObserver 兜底。
  function resetDealFilters(){
    activePlatform='all';dealQuery='';
    var q=$('deal-query');if(q)q.value='';
    renderChips();
  }
  var dealDlg=$('deal-dialog');
  if(dealDlg){
    dealDlg.addEventListener('close',resetDealFilters);
    try{new MutationObserver(function(){if(!dealDlg.open)resetDealFilters();}).observe(dealDlg,{attributes:true,attributeFilter:['open']});}catch(e){}
  }
  if($('deal-only'))$('deal-only').addEventListener('change',function(){render();});
  // ===== 搜索栏：关键词过滤当前条目（标题/摘要/来源/平台），与平台筛选叠加 =====
  if($('deal-search')){
    $('deal-search').addEventListener('submit',function(e){e.preventDefault();dealQuery=$('deal-query').value;render();});
    var deb=null;
    $('deal-query').addEventListener('input',function(){
      clearTimeout(deb);
      deb=setTimeout(function(){dealQuery=$('deal-query').value;render();},250);
    });
  }
  // ESC 关闭优惠页（与影视/音乐页一致）
  document.addEventListener('keydown',function(e){if(e.key==='Escape'&&$('deal-dialog')&&$('deal-dialog').open)$('deal-dialog').close();});
  renderChips();
  window.Deals={open:openTab,preload:preload,reset:function(){if($('deal-dialog')&&$('deal-dialog').open)$('deal-dialog').close();},_state:loadState};
}());
