// 片源适配器注册表：每个片源站点/接口的解析知识集中在此。
// 新增一个片源 = 在这里添加一个适配器对象（列表/详情/直链解析 + 地址规则），vod.js 主流程不变。
(function(){
  'use strict';
  var SITE='https://www.555yy1.com';

  function resource(value){
    try{
      var u=new URL(value,SITE);
      if(u.protocol==='data:')return value; // 内联占位图（数据 URI）直接保留
      if(u.protocol!=='https:'||u.username||u.password)return '';
      // 站点把封面套进百度图片代理，而代理对部分图床只回 1×1 空白 GIF（不触发 onerror）：
      // 解包 src= 参数直接用真实图床地址（参数无问号，在完整地址里取）。
      if(/(^|\.)gimg\d*\.baidu\.com$/.test(u.hostname)){
        var m=String(u.href).match(/src=([^&]+)/);
        if(!m)return '';
        var inner=String(m[1]).replace(/^https?:\/\//,'');
        if(!/^[\w.-]+\.[\w.-]+\/.+$/.test(inner))return '';
        var d=new URL('https://'+inner);
        return d.protocol==='https:'?d.href:'';
      }
      if(/(^|\.)quark\.cn$/.test(u.hostname))return '';
      return u.href;
    }catch(e){return '';}
  }
  function documentOf(html){
    // 仅解析数据，不插入原站 DOM，也不执行广告、播放器或统计脚本。
    html=html.replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi,'').replace(/<(\/?)(img|iframe|object|embed|link|style|base|video|audio|source)\b/gi,'<$1vod-$2');
    return new DOMParser().parseFromString(html,'text/html');
  }
  function text(node){return node?(node.textContent||'').trim():'';}

  function sitePath(value){
      var u=new URL(value,SITE);
      if(u.origin!==SITE||u.hash)throw new Error('来源页面地址不受支持');
      if(u.pathname==='/index/home'&&!u.search)return u.pathname;
      if(!u.search&&/^\/(?:vod(?:detail|type)\/\d+|vodplay\/\d+-\d+-\d+|vodshow\/[\w%\-]+|vodsearch\/[\w%\-]+)\.html$/.test(u.pathname))return u.pathname;
      throw new Error('来源页面地址不受支持');
    }
  var maccmsPage={
    id:'maccms-page',
    defaultHost:'www.555yy1.com',
    // 最近更新混合流：首页按更新时间聚合全部类型
    latestPath:'/index/home',
    sitePath:sitePath,
    applySort:function(target,sort){
      if(!sort)return target;
      var m=target.match(/^\/vodshow\/(\d+)-{11}\.html$/);
      return m?'/vodshow/'+m[1]+'--'+sort+'---------.html':target;
    },
    parseList:function(html){
      var doc=documentOf(html),list=[],seen=Object.create(null);
      Array.from(doc.querySelectorAll('a[href*="/voddetail/"]')).forEach(function(a){
        var target;try{target=sitePath(a.getAttribute('href'));}catch(e){return;}
        if(!/^\/voddetail\/\d+\.html$/.test(target)||seen[target])return;
        var box=a.closest('.module-card-item')||a,img=box.querySelector('vod-img'),title=a.getAttribute('title')||text(box.querySelector('.module-poster-item-title,.module-item-title,.module-card-item-title'))||(img&&img.getAttribute('alt'));
        if(!title)return;var cover=img&&(img.getAttribute('data-original')||img.getAttribute('data-src')||img.getAttribute('src'))||a.getAttribute('data-bg')||'';
        seen[target]=true;list.push({path:target,title:title.slice(0,200),cover:resource(cover),coverRaw:/^https:\/\//.test(cover)?cover:'',note:text(box.querySelector('.module-item-note')).slice(0,100)});
      });
      var nextNode=Array.from(doc.querySelectorAll('a.page-next')).filter(function(a){return a.getAttribute('title')==='下一页'||text(a)==='下一页';})[0],nextPath='';
      if(nextNode)try{nextPath=sitePath(nextNode.getAttribute('href'));}catch(e){}
      var total=0;
      Array.from(html.matchAll(/\/vodshow\/[^\s"'<>]*?(\d+)---\.html/g)).forEach(function(m){var n=Number(m[1]);if(n>total)total=n;});
      return {items:list,next:nextPath,total:total};
    },
    parseDetail:function(html,target){
      var doc=documentOf(html),id=target.match(/\/(\d+)\.html$/)[1],routes=[],labels=Array.from(doc.querySelectorAll('.module-tab-item[data-dropdown-value]'));
      Array.from(doc.querySelectorAll('.module-play-list')).forEach(function(group,index){
        var episodes=[],seen=Object.create(null),sid='';
        Array.from(group.querySelectorAll('a[href*="/vodplay/"]')).forEach(function(a){var p;try{p=sitePath(a.getAttribute('href'));}catch(e){return;}var m=p.match(/^\/vodplay\/(\d+)-(\d+)-(\d+)\.html$/);if(!m||m[1]!==id||seen[p])return;seen[p]=true;sid=m[2];episodes.push({path:p,title:text(a).slice(0,80)||'第 '+m[3]+' 集'});});
        if(episodes.length&&!routes.some(function(r){return r.id===sid;}))routes.push({id:sid,name:labels[index]&&labels[index].getAttribute('data-dropdown-value')||'线路 '+sid,episodes:episodes});
      });
      var title=text(doc.querySelector('h1'))||text(doc.querySelector('title')).replace(/高清完整版.*$/,'');
      return {id:id,title:title.slice(0,200),description:text(doc.querySelector('.video-info-content')).slice(0,800),routes:routes};
    },
    parsePlayer:function(html){
      var m=html.match(/var\s+player_[A-Za-z0-9_]+\s*=\s*(\{[\s\S]*?\})\s*<\/script\s*>/i);
      if(!m)throw new Error('该线路未提供可直接读取的播放信息，请切换线路');
      var data=JSON.parse(m[1]),url=String(data.url||'');
      if(Number(data.encrypt)===2)url=decodeURIComponent(atob(url));else if(Number(data.encrypt)===1)url=decodeURIComponent(url);else if(Number(data.encrypt)!==0)throw new Error('该线路需要站点播放器，请切换线路');
      var safe=resource(url);if(!safe||!/^https:\/\//.test(url)||!(/\.(m3u8|mp4|m4v|webm)$/i.test(new URL(safe).pathname)))throw new Error('该线路未提供标准 HTTPS 视频直链，请切换线路');
      return safe;
    }
  };

  // ===== 接口源适配器：maccms 标准 JSON（/api.php/provide/vod/）=====
  var maccmsApi={
    id:'maccms-api',
    withPg:function(query,pg){return query.replace(/&pg=\d+/,'')+'&pg='+pg;},
    // 分类名 → 接口 type_id 的关键词映射（由调用方传入类目名）
    listQuery:function(base,plan,kindKeywords,classes){
      if(plan&&plan.cat&&classes){
        var kws=kindKeywords[plan.cat]||[];
        var hit=classes.find(function(c){return kws.some(function(k){return (c.type_name||'').indexOf(k)>=0;});});
        return 'ac=videolist'+(hit?'&t='+hit.type_id:'')+'&pg=1';
      }
      if(plan&&plan.q)return 'ac=videolist&wd='+encodeURIComponent(plan.q)+'&pg=1';
      return 'ac=videolist&pg=1';
    },
    parseList:function(jsonText,ref){
      var d=JSON.parse(jsonText),lst=(d.list||[]).filter(Boolean);
      var items=lst.map(function(v){var title=String(v.vod_name||'').trim();if(!title)return null;
        return {api:true,apiBase:ref.base,vodId:String(v.vod_id),title:title.slice(0,200),cover:resource(String(v.vod_pic||'')),note:String(v.vod_remarks||'').slice(0,100),key:title.replace(/\s+/g,'').toLowerCase()};}).filter(Boolean);
      var pg=Number(d.page)||1,pc=Number(d.pagecount)||1;
      return {items:items,nextRef:pg<pc?{kind:'api',base:ref.base,query:withPg(ref.query,pg+1)}:null,total:pc};
      function withPg(query,pg){return query.replace(/&pg=\d+/,'')+'&pg='+pg;}
    },
    // 接口源详情：解析 vod_play_url（线路$$$分隔，集次#分隔，名称$直链），仅保留 HTTPS 直链集次。
    parseDetail:function(v,item){
      var froms=String(v.vod_play_from||'').split('$$$'),urls=String(v.vod_play_url||'').split('$$$'),routes=[];
      froms.forEach(function(fname,i){
        var seg=(urls[i]||'').split('#'),eps=[];
        seg.forEach(function(entry){
          var kv=entry.split('$'),url=(kv[1]||'').trim(),name=(kv[0]||('第'+(eps.length+1)+'集')).trim();
          try{var u=new URL(url);if(u.protocol==='https:'&&!u.username&&/\.(m3u8|mp4|m4v|webm)$/i.test(u.pathname))eps.push({direct:true,url:url,title:name.slice(0,80)});}catch(e){}
        });
        if(eps.length)routes.push({name:String(fname||'线路'+(routes.length+1)).slice(0,30),episodes:eps});
      });
      var title=String(v.vod_name||item.title||'').slice(0,200);
      return {id:item.vodId,title:title,description:String(v.vod_content||'').replace(/<[^>]+>/g,' ').slice(0,800),routes:routes,host:item.apiBase,api:true};
    },
    classify:function(jsonText){
      var d=JSON.parse(jsonText);return Array.isArray(d.class)?d.class:[];
    }
  };

  // ===== 适配器探测规范：添加自定义站点时逐个试用，匹配者作为该站的适配器 =====
  var ADAPTERS=[
    {id:'maccms-api',label:'maccms 接口源',
      probePath:function(){return '/api.php/provide/vod/?ac=list';},
      probeCheck:function(body){try{var d=JSON.parse(body);return d.code===1&&Array.isArray(d.class)&&d.class.length>0;}catch(e){return false;}},
      classify:function(body){try{return maccmsApi.classify(body);}catch(e){return [];}}},
    {id:'maccms-page',label:'maccms 页面模板',
      probePath:function(){return '/vodshow/1-----------.html';},
      probeCheck:function(body){return body.indexOf('module-card-item')>=0||/\/voddetail\/\d+\.html/.test(body)||/\/vodshow\/\d+-{6,}\d*---?\.html/.test(body);},
      discoverCats:function(homeHtml){
        // 从首页发现分类：/vodshow/{id}-{11 横杠}.html + 中文标签
        var out=[],seen=Object.create(null);
        String(homeHtml).replace(/href="(\/vodshow\/(\d+)-{11}\.html)"[^>]*>\s*([^<\s][^<]{0,12})\s*</g,function(m,u,id,label){
          label=String(label||'').replace(/\s+/g,'');
          if(!label||label.length>10||seen[label])return '';
          seen[label]=true;out.push({path:u,label:label});
          return '';
        });
        return out;
      }}
  ];

  window.VodSources={
    resource:resource,
    normalizeKey:function(title){return String(title||'').replace(/\s+/g,'').toLowerCase();},
    maccmsPage:maccmsPage,
    maccmsApi:maccmsApi,
    ADAPTERS:ADAPTERS,
    // 按片源的适配器字段解析（旧配置无 adapter 字段时按 type 兼容）
    resolve:function(source){
      if(!source)return maccmsPage;
      if(source.adapter==='4kvm-page'&&window.Vod4KVM)return window.Vod4KVM;
      if(source.adapter==='maccms-api'||source.type==='api')return maccmsApi;
      return maccmsPage;
    },
    // 页面源：类目名 → 该站路径（hostCats 为主机发现的类目映射）
    categoryPath:function(source,catLabel,fixedCats){
      var adapter=this.resolve(source);
      if(adapter&&adapter.categoryPath)return adapter.categoryPath(catLabel);
      if(adapter!==maccmsPage)return null;
      var cats=this.hostCats[source.host];
      if(cats){var hit=cats.find(function(c){return c.label===catLabel;});if(hit)return hit.path;}
      // 未发现该主机的类目映射：回退到通用 maccms 路径（保持可浏览）
      var fixed=(fixedCats||[]).find(function(c){return c[0]===catLabel;});
      return fixed?fixed[1]:null;
    },
    hostCats:{}
  };
}());
