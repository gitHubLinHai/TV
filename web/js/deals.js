(function(){
  'use strict';
  var bridge=window.TVBridge,$=function(id){return document.getElementById(id);};
  // 优惠线报：RSS/线报站订阅聚合器（每日首次打开拉取+本机缓存；不内置商家接口）。
  var serial=0,pending={},items=[],lastFetch='';
  function loadFeeds(){
    var raw=null;
    try{raw=window.TVPrivacy.enabled?sessionStorage.getItem('tv_deal_feeds'):localStorage.getItem('tv_deal_feeds');}catch(e){}
    if(raw)try{var s=JSON.parse(raw);if(Array.isArray(s))return s;}catch(e){}
    return [];
  }
  function saveFeeds(){
    var json=JSON.stringify(feeds);
    try{if(window.TVPrivacy.enabled)sessionStorage.setItem('tv_deal_feeds',json);else localStorage.setItem('tv_deal_feeds',json);}catch(e){}
  }
  var feeds=loadFeeds();
  function today(){var d=new Date();return d.getFullYear()+'-'+(d.getMonth()+1)+'-'+d.getDate();}
  function nowStr(){var d=new Date();function p(n){return (n<10?'0':'')+n;}return today()+' '+p(d.getHours())+':'+p(d.getMinutes());}
  function cacheRead(){
    try{var raw=(window.TVPrivacy.enabled?sessionStorage:localStorage).getItem('tv_deals_cache');
      if(raw){var c=JSON.parse(raw);if(c&&c.day===today()&&Array.isArray(c.items))return c;}}catch(e){}
    return null;
  }
  function cacheWrite(){
    try{var store=window.TVPrivacy.enabled?sessionStorage:localStorage;
      store.setItem('tv_deals_cache',JSON.stringify({day:today(),at:nowStr(),items:items}));}catch(e){}
  }
  function rpc(url){
    if(bridge){
      if(!bridge.dealRequest)return Promise.reject(new Error('请安装新版 APK 后使用优惠订阅'));
      return new Promise(function(resolve,reject){
        var id='dl'+(++serial);
        var timer=setTimeout(function(){delete pending[id];reject(new Error('订阅源连接超时，请重试'));},30000);
        pending[id]=function(reply){clearTimeout(timer);delete pending[id];if(reply.ok)resolve(reply.data);else reject(new Error(reply.error||'订阅源读取失败'));};
        bridge.dealRequest(id,url);
      });
    }
    var controller=new AbortController(),timer=setTimeout(function(){controller.abort();},30000);
    return fetch('/api/deal',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({url:url}),signal:controller.signal})
      .then(function(r){return r.json();})
      .then(function(r){if(!r.ok)throw new Error(r.error||'订阅源读取失败');return r.data;})
      .catch(function(e){clearTimeout(timer);if(e&&(e.name==='TypeError'||e.name==='AbortError'))throw new Error('本地预览服务未连接：请先运行 python tools/preview_server.py --port 8138 并保持窗口开启，再刷新本页重试');throw e;})
      .finally(function(){clearTimeout(timer);});
  }
  window.__dealReply=function(id,reply){var cb=pending[id];if(cb)cb(reply);};
  function status(value){$('deal-status').textContent=value;}
  function validLink(link,base){
    try{var u=new URL(link,base||location.href);if(u.protocol!=='https:'&&u.protocol!=='http:')return '';return u.href.slice(0,1000);}catch(e){return '';}
  }
  function parseFeed(body,feedUrl){
    body=String(body||'').trim();
    if(body.charAt(0)==='{'){
      var d=JSON.parse(body);
      var arr=Array.isArray(d)?d:(d.list||d.data||d.items||[]);
      return arr.map(function(x){return item(x&&(x.title||x.name),x&&(x.url||x.link||x.mobil_url||x.href),x&&(x.time||x.pubDate||x.updated||x.date)),null;}).filter(Boolean);
    }
    var doc=new DOMParser().parseFromString(body,'text/xml');
    if(doc.querySelector('parsererror'))throw new Error('订阅源不是有效的 RSS/Atom');
    var out=[];
    Array.from(doc.querySelectorAll('item')).forEach(function(it){
      var link=validLink(text(it,'link'),feedUrl)||validLink(text(it,'guid'),feedUrl);
      var row=item(text(it,'title'),link,text(it,'pubDate'));
      if(row)out.push(row);
    });
    Array.from(doc.querySelectorAll('entry')).forEach(function(en){
      var link='';
      Array.from(en.querySelectorAll('link')).forEach(function(l){
        var href=l.getAttribute('href')||text(l);
        if(href&&!link)link=href;
      });
      var row=item(text(en,'title'),validLink(link,feedUrl),text(en,'updated'));
      if(row)out.push(row);
    });
    return out;
    function item(title,link,time){
      title=String(title||'').replace(/\s+/g,' ').trim().slice(0,200);
      link=String(link||'').trim().slice(0,1000);
      if(!title||!link)return null;
      var timeStr=String(time||'').trim().slice(0,32);
      var ts=Date.parse(timeStr);
      return {title:title,link:link,time:timeStr,ts:isNaN(ts)?0:ts};
    }
    function text(root,tag){var el=root.querySelector(tag);return el?el.textContent:'';}
  }
  function render(){
    var box=$('deal-list');box.textContent='';
    var frag=document.createDocumentFragment();
    items.forEach(function(item){
      var row=document.createElement('button');row.type='button';row.className='deal-row';
      var title=document.createElement('strong');title.textContent=item.title;row.appendChild(title);
      var meta=document.createElement('small');meta.textContent=(item.src?item.src+' · ':'')+(item.time||'');
      row.appendChild(meta);
      row.addEventListener('click',function(){openLink(item.link);});
      frag.appendChild(row);
    });
    box.appendChild(frag);
  }
  function openLink(link){
    if(bridge&&bridge.openUrl){bridge.openUrl(link);return;}
    if(window.TVPrivacy.enabled){status('无痕网页预览不跳转外部页面，请在 APK 中打开');return;}
    location.assign(link);
  }
  function applyResults(results){
    var diag=[],ok=0,seen=Object.create(null);items=[];
    results.forEach(function(r){
      if(r.error){diag.push(r.name+'：'+r.error);return;}
      ok++;r.items.forEach(function(x){
        x.src=r.name;var k=x.title+'|'+x.link;
        if(seen[k])return;seen[k]=true;items.push(x);
      });
    });
    items.sort(function(a,b){return b.ts-a.ts;});
    render();
    if(!ok){status(diag.join('；')||'订阅源没有返回内容');return;}
    cacheWrite();
    status('共 '+items.length+' 条 · '+ok+' 个订阅源'+(diag.length?' · '+diag.join('；'):'')+' · 更新于 '+nowStr());
  }
  function fetchAll(){
    if(!feeds.length){status('还没有订阅源：在上方粘贴 RSS 或线报站地址添加（本应用不内置商家接口）');return Promise.resolve();}
    status('正在拉取优惠线报…（'+feeds.length+'个订阅源）');
    return Promise.all(feeds.map(function(f){
      return rpc(f.url).then(function(reply){return {name:f.name,items:parseFeed(reply.body,f.url)};})
        .catch(function(e){return {name:f.name,error:e.message||'连接失败'};});
    })).then(applyResults);
  }
  function openTab(){
    if($('deal-dialog').open)return;
    $('deal-dialog').show();
    var c=cacheRead();
    if(c){items=c.items;render();status('共 '+items.length+' 条 · 今日已拉取（'+(c.at||'')+'），可点"立即刷新"');return;}
    if(!feeds.length){render();status('还没有订阅源：在上方粘贴 RSS 或线报站地址添加（本应用不内置商家接口）');return;}
    fetchAll();
  }
  function preload(){var c=cacheRead();if(c||!feeds.length)return;fetchAll();}
  $('deal-open').addEventListener('click',openTab);
  $('deal-close').addEventListener('click',function(){$('deal-dialog').close();});
  $('deal-dialog').addEventListener('close',function(){});
  $('deal-feed-add').addEventListener('click',function(){
    if(window.TVPrivacy.enabled){status('无痕模式不保存订阅源修改');return;}
    var name=$('deal-feed-name').value.trim()||('源'+(feeds.length+1));
    var url=$('deal-feed-url').value.trim();
    if(!/^https:\/\//.test(url)||url.length>500){status('请填写 https:// 开头的订阅地址');return;}
    var host=url.split('/')[2]||'';
    if(!/^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/.test(host)){status('订阅域名无效');return;}
    if(feeds.some(function(f){return f.url===url;})){status('该订阅源已存在');return;}
    if(feeds.length>=8){status('最多支持 8 个订阅源');return;}
    feeds.push({name:name.slice(0,12),url:url});$('deal-feed-name').value='';$('deal-feed-url').value='';
    saveFeeds();renderFeeds();fetchAll();
  });
  function renderFeeds(){
    var box=$('deal-feeds');box.textContent='';
    feeds.forEach(function(f){
      var row=document.createElement('label');row.className='source-check';
      var name=document.createElement('span');name.className='source-name';name.textContent=f.name+' · '+f.url.split('/')[2];
      row.appendChild(name);
      if(!window.TVPrivacy.enabled)row.appendChild((function(){var del=document.createElement('button');del.type='button';del.className='source-del';del.textContent='删除';del.addEventListener('click',function(){feeds=feeds.filter(function(x){return x!==f;});saveFeeds();renderFeeds();});return del;})());
      box.appendChild(row);
    });
  }
  renderFeeds();
  window.Deals={open:openTab,preload:preload,reset:function(){if($('deal-dialog').open)$('deal-dialog').close();}};
}());
