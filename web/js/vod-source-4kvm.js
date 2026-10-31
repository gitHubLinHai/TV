(function(){
  'use strict';
  var SITE='https://www.4kvm.net';
  // /filter 筛选页允许的查询参数名（服务端渲染，支持分页与排序）
  var FILTER_KEYS={classify:1,types:1,areas:1,years:1,tags:1,sort_by:1,order:1,page:1};

  function documentOf(html){
    html=String(html||'').replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi,'')
      .replace(/<(\/?)(img|iframe|object|embed|link|style|base|video|audio|source)\b/gi,'<$1vod-$2');
    return new DOMParser().parseFromString(html,'text/html');
  }
  function text(node){return node?(node.textContent||'').trim():'';}
  function path(value){
    var u=new URL(value,SITE);
    if(u.origin!==SITE||u.hash)throw new Error('4KVM 页面地址不受支持');
    if(/^\/(?:movie|tv|anime|)?$/.test(u.pathname)&&(!u.search||/^\?page=\d{1,4}$/.test(u.search)))return u.pathname+u.search;
    if(u.pathname==='/filter'){
      var q=u.search.replace(/^\?/,'');
      if(!q)return u.pathname;
      if(!/^[a-z_]{1,12}=[A-Za-z0-9%._~-]{1,32}(?:&[a-z_]{1,12}=[A-Za-z0-9%._~-]{1,32})*$/.test(q))throw new Error('4KVM 页面地址不受支持');
      var ok=q.split('&').every(function(kv){return FILTER_KEYS.hasOwnProperty(kv.split('=')[0]);});
      if(ok)return u.pathname+u.search;
    }
    if(u.pathname==='/search'&&/^\?q=[A-Za-z0-9%._~-]{1,240}(?:&page=\d{1,4})?$/.test(u.search))return u.pathname+u.search;
    if(/^\/play\/[a-z0-9]{6,24}$/.test(u.pathname)&&!u.search)return u.pathname;
    throw new Error('4KVM 页面地址不受支持');
  }
  function resource(value){
    try{
      var u=new URL(value,SITE);
      if(u.protocol!=='https:'||u.username||u.password)return '';
      // 站点把封面套在百度图片代理里，而代理对轮换图床只回 1×1 空白 GIF（不触发 onerror）：
      // 解包 src= 参数直接用真实图床地址（注意参数无问号，直接在完整地址里取）。
      if(/(^|\.)gimg\d*\.baidu\.com$/.test(u.hostname)){
        var m=String(u.href).match(/src=([^&]+)/);
        if(!m)return '';
        var inner=String(m[1]).replace(/^https?:\/\//,'');
        if(!inner||!/^[\w.-]+\.[\w.-]+\/.+$/.test(inner))return '';
        return 'https://'+inner;
      }
      return u.href;
    }catch(e){return '';}
  }
  // “下一页”是 ?page=N 纯查询串：以当前列表页参数为基底、仅覆盖 page（防止整体替换丢失 classify 等筛选）。
  function nextOf(nextNode,currentPath){
    if(!nextNode)return '';
    var hrefPage=null;
    try{
      var u=new URL(nextNode.getAttribute('href'),SITE+(currentPath||'/'));
      if(u.searchParams.has('page'))hrefPage=Number(u.searchParams.get('page'));
    }catch(e){}
    if(currentPath){
      try{
        var cur=new URL(currentPath,SITE);
        cur.searchParams.set('page',String(hrefPage>0?hrefPage:(Number(cur.searchParams.get('page'))||1)+1));
        return path(cur.pathname+cur.search);
      }catch(e){return '';}
    }
    try{return path(nextNode.getAttribute('href'));}catch(e){return '';}
  }
  function parseList(html,currentPath){
    var doc=documentOf(html),items=[],seen=Object.create(null);
    Array.from(doc.querySelectorAll('a[data-vod][href^="/play/"]')).forEach(function(a){
      var card=a.closest('.movie-card,.group.relative')||a,target='';
      try{target=path(a.getAttribute('href'));}catch(e){return;}
      if(seen[target])return;
      var img=card.querySelector('vod-img'),title=text(card.querySelector('h3'))||(img&&img.getAttribute('alt'))||'';
      if(!title)return;
      var cover=img&&(img.getAttribute('data-src')||img.getAttribute('src'))||'';
      var note=text(card.querySelector('span.absolute.bottom-0')).slice(0,100);
      seen[target]=true;items.push({path:target,title:title.slice(0,200),cover:resource(cover),note:note});
    });
    var next=nextOf(Array.from(doc.querySelectorAll('a[href]')).find(function(a){return /下一页|下页|›/.test(text(a));}),currentPath);
    var m=String(html).match(/第\s*\d+\s*页\s*\/\s*共\s*(\d+)\s*页/),total=m?Number(m[1]):0;
    return {items:items,next:next,total:total};
  }
  function parseDetail(html,target){
    var doc=documentOf(html),routes=[],groups=Object.create(null),order=[];
    Array.from(doc.querySelectorAll('a[data-line][data-episode][dataid][href^="/play/"]')).forEach(function(a){
      var line=String(a.getAttribute('data-line')||'1'),episode=Number(a.getAttribute('data-episode'))||1,p='';
      try{p=path(a.getAttribute('href'));}catch(e){return;}
      if(!groups[line]){groups[line]=[];order.push(line);}
      if(!groups[line].some(function(ep){return ep.path===p;}))groups[line].push({path:p,title:'第 '+episode+' 集',resolve4kvm:true});
    });
    order.forEach(function(line){routes.push({id:line,name:order.length>1?'线路 '+line:'公开线路',episodes:groups[line]});});
    var title=text(doc.querySelector('h1'))||text(doc.querySelector('h2'))||text(doc.querySelector('title')).replace(/\s*-\s*第.*$/,'');
    var description=(doc.querySelector('meta[name="description"]')||{}).content||'';
    return {id:target.replace(/^\/play\//,''),title:title.slice(0,200),description:description.slice(0,800),routes:routes,adapter:'4kvm-page',itemPath:target};
  }

  window.Vod4KVM={
    id:'4kvm-page',defaultHost:'www.4kvm.net',sitePath:path,parseList:parseList,parseDetail:parseDetail,
    parsePlayer:function(){throw new Error('4KVM 播放地址需要在 APK 内动态解析');},
    // 分类走 /filter 筛选页：真实分页（classify 1电影 2电视剧 3动漫 4综艺）；站点没有短剧分类
    categoryPath:function(label){return {'电视剧':'/filter?classify=2','电影':'/filter?classify=1','动漫':'/filter?classify=3','综艺纪录':'/filter?classify=4'}[label]||null;},
    // 排序：综合=站点默认（按更新），最热播/高分映射 sort_by；仅对 /filter 生效
    applySort:function(target,sort){
      if(!/^\/filter(?:\?|$)/.test(target))return target;
      try{
        var u=new URL(target,SITE);
        u.searchParams.delete('sort_by');u.searchParams.delete('order');
        if(sort){u.searchParams.set('sort_by',sort);u.searchParams.set('order','desc');}
        return path(u.pathname+u.search);
      }catch(e){return target;}
    },
    defaultPath:'/filter?sort_by=update_time&order=desc',
    searchPath:function(query){return '/search?q='+encodeURIComponent(query);},
    resource:resource
  };
}());
