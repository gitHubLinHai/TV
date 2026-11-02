(function(){
  'use strict';
  var bridge=window.TVBridge,$=function(id){return document.getElementById(id);};
  // 免费来源：Meting 公共实例 + 网易云；榜单 ID 为网易云官方榜单（数量 50—200 由官方决定）。
  var RANKS=[['热歌榜','3778678'],['飙升榜','19723756'],['新歌榜','3779629'],['网络热歌','6723173524'],['潮流风向','13372522766'],['潜力爆款','5338990334'],['实时热度','8246775932'],['原创榜','2884035'],['赏音榜','7775163417'],['欧美热歌','2809513713'],['欧美新歌','2809577409'],['国风榜','5059642708'],['民谣榜','5059661515'],['摇滚榜','5059633707'],['DJ慢摇','6886768100'],['中文说唱','991319590'],['ACG动画','3001835560'],['ACG游戏','3001795926'],['ACG VOCALOID','3001890046'],['日本Oricon','60131'],['Billboard','60198'],['UK排行榜','180106'],['听歌识曲','6688069460']];
  var serial=0,epoch=0,pending={},list=[],playing=null,musicSrc='netease';
  var queue={on:false,loop:false,single:false,pos:0};
  function rpc(query){
    if(bridge){
      if(!bridge.musicRequest)return Promise.reject(new Error('请安装新版 APK 后使用音乐榜单'));
      return new Promise(function(resolve,reject){
        var id='mu'+(++serial);
        var timer=setTimeout(function(){delete pending[id];reject(new Error('音乐来源连接超时，请重试'));},30000);
        pending[id]=function(reply){clearTimeout(timer);delete pending[id];if(reply.ok)resolve(reply.data);else reject(new Error(reply.error||'音乐请求失败'));};
        bridge.musicRequest(id,query);
      });
    }
    var controller=new AbortController(),timer=setTimeout(function(){controller.abort();},30000);
    return fetch('/api/music',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({q:query}),signal:controller.signal})
      .then(function(r){return r.json();})
      .then(function(r){if(!r.ok)throw new Error(r.error||'音乐请求失败');return r.data;})
      .catch(function(e){clearTimeout(timer);if(e&&(e.name==='TypeError'||e.name==='AbortError'))throw new Error('本地预览服务未连接：请先运行 python tools/preview_server.py --port 8138 并保持窗口开启，再刷新本页重试');throw e;})
      .finally(function(){clearTimeout(timer);});
  }
  window.__musicReply=function(id,reply){var cb=pending[id];if(cb)cb(reply);};
  function status(value){$('music-status').textContent=value;}
  function songId(url){try{var u=new URL(url);return u.searchParams.get('id')||'';}catch(e){return '';}}
  // 榜单缓存：同一榜单 10 分钟内直接复用；预载在进入应用后触发。
  var rankCache={},RANK_TTL=600000;
  function fetchList(kind,id){
    var key=kind+' '+id,hit=rankCache[key];
    if(hit&&Date.now()-hit.at<RANK_TTL)return Promise.resolve(hit.songs);
    // 搜索：网易走 GD音乐台（全曲库）；酷狗走酷狗搜索。榜单走 Meting。
    var req=kind==='search'&&musicSrc==='kugou'
      ?rpc('kg=1&type=search&name='+id).then(function(data){
        var d=JSON.parse(data.json);
        var arr=(d.data&&d.data.info)||[];
        if(!Array.isArray(arr))throw new Error('来源返回格式异常');
        // 酷狗 v3 接口字段为小写 hash（旧 FileHash 兼容），附带时长/付费类型供取链预检
        return arr.map(function(s){return {kg:true,id:String(s.hash||s.FileHash||'').toLowerCase(),name:String(s.songname||'未知曲目').slice(0,120),artist:String(s.singername||'').slice(0,80),dur:Number(s.duration||0),pay:Number(s.pay_type||0)};}).filter(function(s){return s.id;});
      })
      :kind==='search'
      ?rpc('gds=1&type=search&name='+id).then(function(data){
        var arr=JSON.parse(data.json);
        if(!Array.isArray(arr))throw new Error('来源返回格式异常');
        return arr.map(function(s){return {id:String(s.id||''),name:String(s.name||'未知曲目').slice(0,120),artist:String(s.artist||'').slice(0,80)};}).filter(function(s){return s.id;});
      })
      :rpc('server=netease&type='+kind+'&id='+id).then(function(data){
        var songs;try{songs=JSON.parse(data.json);}catch(e){throw new Error('来源返回格式异常');}
        if(!Array.isArray(songs)||!songs.length)throw new Error('来源没有返回歌曲');
        return songs.slice(0,500).map(function(song){
          return {id:songId(song.url),name:String(song.name||'未知曲目').slice(0,120),artist:String(song.artist||'').slice(0,80)};
        }).filter(function(song){return song.id;});
      });
    return req.then(function(normalized){
      if(!normalized.length)throw new Error('来源没有返回可播放编号的歌曲');
      rankCache[key]={songs:normalized,at:Date.now()};
      return normalized;
    });
  }
  // 当前视图（榜单 or 搜索）：驱动榜单芯片选中态；搜索时全部芯片不亮。
  var view={kind:'playlist',id:RANKS[0][1]};
  function load(kind,id,label){
    var run=++epoch;view={kind:kind,id:id,label:label};renderRanks();
    status('正在读取'+label+'…');$('music-list').textContent='';
    fetchList(kind,id).then(function(songs){
      if(run!==epoch)return;
      list=songs;render(label);
    }).catch(function(e){if(run===epoch){list=[];$('music-list').textContent='';status(e.message);}});
  }
  function renderRanks(){Array.prototype.forEach.call($('music-ranks').children,function(b){b.classList.toggle('selected',view.kind==='playlist'&&b.dataset.id===view.id);});}
  function render(label){
    var box=$('music-list');box.textContent='';
    // 批量渲染：Fragment 一次性挂载（200 行避免逐个触发重排）。
    var frag=document.createDocumentFragment();
    list.slice(0,500).forEach(function(song,index){
      var row=document.createElement('button');row.type='button';row.className='song-row';row.dataset.idx=index;
      var rank=document.createElement('b');rank.textContent=String(index+1);row.appendChild(rank);
      var info=document.createElement('span');info.className='song-info';
      var name=document.createElement('strong');name.textContent=song.name;info.appendChild(name);
      var artist=document.createElement('small');artist.textContent=song.artist||'—';info.appendChild(artist);
      row.appendChild(info);frag.appendChild(row);
    });
    box.appendChild(frag);
    status('来源：网易 · '+label+' · '+list.length+' 首 · 点击播放');
  }
  // 事件委托：列表容器单监听器处理所有行点击。
  $('music-list').addEventListener('click',function(e){
    var row=e.target&&e.target.closest('.song-row');if(!row)return;
    var song=list[Number(row.dataset.idx)];if(song)play(song,false);
  });
  function highlightPlaying(){
    Array.prototype.forEach.call($('music-list').children,function(row){
      var song=list[Number(row.dataset.idx)];
      row.classList.toggle('playing',!!(song&&playing&&song.id===playing.id&&(!playing.kg===!song.kg)));
    });
  }
  function renderQueue(){
    var all=$('music-playall');
    all.textContent=queue.on?'■':'▶';
    all.setAttribute('aria-label',queue.on?'停止队列':'全部播放');
    all.classList.toggle('selected',queue.on);
    $('music-loop').classList.toggle('selected',queue.loop);
    $('music-single').classList.toggle('selected',queue.single);
  }
  // ===== 取链链路：多音源按序尝试 + 试听预检；原生回调时长确认后自动换完整版 =====
  // 背景：网易对部分歌曲只发 30 秒试听（Meting/GD 照常 302/返回），酷狗曲库常有免费完整版。
  var attempt=null,attemptQueue=false,lyricCache={};
  function enc(s){return encodeURIComponent(String(s||'').slice(0,60));}
  function keyOf(song){return 'music:'+song.id;}
  function metingUrl(id){return rpc('server=netease&type=url&id='+id).then(function(d){if(!d.url)throw new Error('0');return d.url;});}
  function gdUrlFor(id){return rpc('gds=1&type=url&id='+id).then(function(data){
    var d=JSON.parse(data.json||'{}');
    if(!d.url||!/^https:\/\//.test(d.url))throw new Error('0');
    // 试听预检：已知码率与文件大小时估算时长，低于 1 分钟视为片段
    if(d.size&&d.br&&(d.size*8)/(d.br*1000)<60)throw new Error('0');
    return d.url;});}
  function kgUrlByHash(hash){return rpc('kg=1&type=url&hash='+hash).then(function(data){
    var d=JSON.parse(data.json||'{}');
    if(!d.url||!/^https:\/\//.test(d.url))throw new Error('0');
    if(d.timeLength&&d.timeLength<60&&d.timeLength>0)throw new Error('0');
    return d.url;});}
  function kgSearchUrl(song){return rpc('kg=1&type=search&name='+enc((song.artist?song.artist+' ':'')+song.name)).then(function(data){
    var arr=(JSON.parse(data.json||'{}').data||{}).info||[];
    var good=arr.filter(function(s){return (s.hash||s.FileHash)&&Number(s.duration||0)>=60&&Number(s.pay_type||0)===0;});
    var hit=good[0]||arr.filter(function(s){return s.hash||s.FileHash;})[0];
    if(!hit)throw new Error('0');
    return kgUrlByHash(String(hit.hash||hit.FileHash).toLowerCase());});}
  function neteaseIdFor(song){return rpc('gds=1&type=search&name='+enc(song.name)).then(function(data){
    var arr=JSON.parse(data.json||'[]');
    if(!Array.isArray(arr)||!arr.length||!arr[0].id)throw new Error('0');
    return String(arr[0].id);});}
  function lyricsFor(song){ // LRC 歌词：网易 id（酷狗歌先经 GD 搜索换算）；失败返回空串，不影响播放
    var cacheKey=song.kg?('kg '+song.id):('ne '+song.id);
    if(lyricCache[cacheKey]!==undefined)return Promise.resolve(lyricCache[cacheKey]);
    var nid=song.kg?neteaseIdFor(song):Promise.resolve(song.id);
    return nid.then(function(id){
      return rpc('server=netease&type=lrc&id='+id).then(function(data){
        var lrc=String(data.lrc||'').slice(0,65536);
        if(lrc.indexOf('[')<0)throw new Error('0');
        lyricCache[cacheKey]=lrc;return lrc;});
    }).catch(function(){lyricCache[cacheKey]='';return '';});
  }
  function providersFor(song){
    if(song.kg)return [
      {name:'酷狗',make:function(){return kgUrlByHash(song.id);}},
      {name:'网易',make:function(){return neteaseIdFor(song).then(metingUrl);}},
      {name:'网易GD',make:function(){return neteaseIdFor(song).then(gdUrlFor);}}];
    // 音源选择影响取链顺序：选酷狗时优先酷狗直链（完整版命中率高，避开 30 秒试听）
    if(musicSrc==='kugou')return [
      {name:'酷狗',make:function(){return kgSearchUrl(song);}},
      {name:'网易',make:function(){return metingUrl(song.id);}},
      {name:'网易GD',make:function(){return gdUrlFor(song.id);}}];
    return [
      {name:'网易',make:function(){return metingUrl(song.id);}},
      {name:'网易GD',make:function(){return gdUrlFor(song.id);}},
      {name:'酷狗',make:function(){return kgSearchUrl(song);}}];
  }
  function play(song,viaQueue){
    if(playing)return;playing=song;highlightPlaying();
    attemptQueue=!!(viaQueue&&queue.on);
    attempt={song:song,chain:providersFor(song),used:[]};
    status(attemptQueue?('队列播放 第'+(queue.pos+1)+'/'+list.length+' 首《'+song.name+'》…'):('正在获取《'+song.name+'》的播放地址…'));
    tryProvider(0);
  }
  function tryProvider(i){
    if(!attempt)return;
    var song=attempt.song,chain=attempt.chain;
    if(i>=chain.length){
      var msg='《'+song.name+'》各音源都不可播放（可能需要会员或已下架）';
      try{if(window.TVBridge&&TVBridge.logEvent)TVBridge.logEvent('music',msg);}catch(e){}
      attempt=null;
      if(attemptQueue){status('《'+song.name+'》无法播放，已自动跳过');playing=null;nextSong();return;}
      playing=null;highlightPlaying();status(msg);return;
    }
    if(attempt.used[i]){tryProvider(i+1);return;}
    attempt.used[i]=true;
    chain[i].make().then(function(url){
      if(!attempt||attempt.song!==song)return;
      var which=i;
      return lyricsFor(song).then(function(lrc){
        if(!attempt||attempt.song!==song)return;
        status('正在播放《'+song.name+'》· 来源 '+chain[which].name+(lrc?' · 歌词':''));
        var nb=$('music-now');if(nb){nb.hidden=false;$('music-now-title').textContent=song.name;$('music-now-sub').textContent=(song.artist||'—')+' · '+chain[which].name;}
      try{if(window.__reportEvent)window.__reportEvent('music','起播 '+(song.name||'').slice(0,24)+' 来源 '+chain[which].name);}catch(e){}
        if(bridge&&bridge.playMusic)bridge.playMusic(url,(song.artist?song.artist+' · ':'')+song.name,keyOf(song),lrc);
        else if(bridge&&bridge.playPublicVideo)bridge.playPublicVideo(url,(song.artist?song.artist+' · ':'')+song.name,keyOf(song));
        else{var audio=$('music-audio');audio.hidden=false;audio.src=url;audio.play().catch(function(){status('请点击下方播放器开始播放');});}
        playing=null;highlightPlaying();
      });
    }).catch(function(){if(attempt&&attempt.song===song&&attempt.chain===chain)tryProvider(i+1);});
  }
  // 原生取到曲目时长：不足 1 分钟视为试听片段，自动换下一个音源的完整版
  window.__musicDuration=function(key,ms){
    if(!attempt||!ms||ms>=60000||key!==keyOf(attempt.song))return;
    var song=attempt.song,chain=attempt.chain,next=-1;
    for(var i=0;i<chain.length;i++)if(!attempt.used[i]){next=i;break;}
    if(next<0){status('《'+song.name+'》仅有试听片段（来源免费范围限制）');return;}
    status('《'+song.name+'》是试听片段，正在切换到'+chain[next].name+'完整版…');
    tryProvider(next);
  };
  function nextSong(){
    if(!queue.on||!list.length)return;
    if(queue.single){var cur=list[queue.pos];if(cur){play(cur,true);return;}}
    queue.pos++;
    if(queue.pos>=list.length){
      if(queue.loop)queue.pos=0;
      else{queue.on=false;renderQueue();status('队列播放结束：共 '+list.length+' 首');return;}
    }
    var song=list[queue.pos];
    if(song)play(song,true);else nextSong();
  }
  window.Music={back:function(){if($('music-dialog').open)$('music-dialog').close();},reset:function(){if($('music-dialog').open)$('music-dialog').close();},load:load,
    onEnded:function(){
      if(queue.on&&list.length)nextSong();
    },
    preload:function(){fetchList('playlist',RANKS[0][1]).catch(function(){});}};
  function stopAudio(){var audio=$('music-audio');audio.pause();audio.removeAttribute('src');audio.load();audio.hidden=true;}
  $('music-open').addEventListener('click',function(){if($('music-dialog').open)return;$('music-dialog').show();try{if(window.__reportEvent)window.__reportEvent('music','打开');}catch(e){}load('playlist',RANKS[0][1],RANKS[0][0]);});
  $('music-close').addEventListener('click',function(){$('music-dialog').close();});
  $('music-dialog').addEventListener('close',function(){++epoch;list=[];$('music-list').textContent='';playing=null;attempt=null;stopAudio();var nb=$('music-now');if(nb)nb.hidden=true;});
  RANKS.forEach(function(entry){var b=document.createElement('button');b.type='button';b.textContent=entry[0];b.dataset.id=entry[1];b.addEventListener('click',function(){try{if(window.__reportEvent)window.__reportEvent('music','榜单 '+entry[0]);}catch(e){}load('playlist',entry[1],entry[0]);});$('music-ranks').appendChild(b);});
  // 搜索音源切换：网易（GD 全曲库）/ 酷狗（免费直链命中率互补）。
  function renderSrc(){Array.prototype.forEach.call($('music-src').children,function(b){b.classList.toggle('selected',b.dataset.src===musicSrc);});}
  [['网易','netease'],['酷狗','kugou']].forEach(function(pair){
    var b=document.createElement('button');b.type='button';b.textContent=pair[0]+'音源';b.dataset.src=pair[1];
    b.addEventListener('click',function(){musicSrc=pair[1];renderSrc();
      if(view.kind==='search'){load(view.kind,view.id,view.label||('搜索“'+$('music-query').value.trim().slice(0,20)+'”'));return;}
      status(musicSrc==='kugou'?'已切换酷狗音源：之后点歌优先用酷狗直链（完整版更多），搜索走酷狗曲库':'已切换网易音源：之后点歌优先用网易，搜索走网易全曲库');});
    $('music-src').appendChild(b);
  });
  renderSrc();
  // ===== 队列播放：全部播放从第一首到最后一首；循环/单曲循环；无法播放自动跳过。 =====
  $('music-playall').addEventListener('click',function(){
    if(queue.on){queue.on=false;renderQueue();status('队列播放已停止');return;}
    if(!list.length){status('当前榜单还没有歌曲');return;}
    queue.on=true;queue.pos=0;renderQueue();play(list[0],true);
  });
  $('music-loop').addEventListener('click',function(){queue.loop=!queue.loop;if(queue.loop)queue.single=false;renderQueue();status(queue.loop?'列表循环已开启':'列表循环已关闭');});
  $('music-single').addEventListener('click',function(){queue.single=!queue.single;if(queue.single)queue.loop=false;renderQueue();status(queue.single?'单曲循环已开启':'单曲循环已关闭');});
  renderQueue();
  $('music-search').addEventListener('submit',function(e){
    e.preventDefault();var q=$('music-query').value.trim();
    if(q)load('search',encodeURIComponent(q),'搜索“'+q.slice(0,20)+'”');
  });
  $('music-audio').addEventListener('error',function(){if($('music-audio').getAttribute('src'))status('播放失败：链接可能过期或来源限制，请换一首试试。');});
  $('music-audio').addEventListener('ended',function(){if(window.Music)window.Music.onEnded();});
}());
