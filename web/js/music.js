(function(){
  'use strict';
  var bridge=window.TVBridge,$=function(id){return document.getElementById(id);};
  // 免费来源：Meting 公共实例 + 网易云；榜单 ID 为网易云官方榜单（数量 50—200 由官方决定）。
  var RANKS=[['热歌榜','3778678'],['飙升榜','19723756'],['新歌榜','3779629'],['网络热歌','6723173524'],['潮流风向','13372522766'],['潜力爆款','5338990334'],['实时热度','8246775932'],['原创榜','2884035'],['赏音榜','7775163417'],['欧美热歌','2809513713'],['欧美新歌','2809577409'],['国风榜','5059642708'],['民谣榜','5059661515'],['摇滚榜','5059633707'],['DJ慢摇','6886768100'],['中文说唱','991319590'],['ACG动画','3001835560'],['ACG游戏','3001795926'],['ACG VOCALOID','3001890046'],['日本Oricon','60131'],['Billboard','60198'],['UK排行榜','180106'],['听歌识曲','6688069460']];
  var serial=0,epoch=0,pending={},list=[],playing=null,musicSrc='netease';
  var queue={on:false,loop:false,single:false,pos:0};
  var nowSong=null,privacyOn=function(){return !!(window.TVPrivacy&&window.TVPrivacy.enabled);};
  // ===== 本机音乐库：我喜欢 / 最近播放 / 搜索历史（只存本机，不上传；无痕时不记录）=====
  function store(key){try{var v=JSON.parse(localStorage.getItem(key));return Array.isArray(v)?v:[];}catch(e){return [];}}
  function saveStore(key,arr){try{localStorage.setItem(key,JSON.stringify(arr));}catch(e){}}
  function songKey(s){return (s.kg?'kg':'ne')+':'+s.id;}
  var favs=store('tv_music_favs'),recents=store('tv_music_recent'),queries=store('tv_music_queries');
  function isFav(s){for(var i=0;i<favs.length;i++)if(songKey(favs[i])===songKey(s))return true;return false;}
  function toggleFav(s){
    var at=-1;
    for(var i=0;i<favs.length;i++)if(songKey(favs[i])===songKey(s)){at=i;break;}
    if(at>=0)favs.splice(at,1);
    else{favs.unshift({kg:!!s.kg,id:s.id,name:s.name,artist:s.artist||''});if(favs.length>500)favs.length=500;}
    saveStore('tv_music_favs',favs);renderFavStates();
    try{if(window.__reportEvent)window.__reportEvent('music',(at>=0?'取消收藏 ':'收藏 ')+String(s.name||'').slice(0,24));}catch(e){}
    return at<0;
  }
  function recordRecent(s){
    if(privacyOn())return;
    for(var i=0;i<recents.length;i++)if(songKey(recents[i])===songKey(s)){recents.splice(i,1);break;}
    recents.unshift({kg:!!s.kg,id:s.id,name:s.name,artist:s.artist||''});
    if(recents.length>100)recents.length=100;
    saveStore('tv_music_recent',recents);
  }
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
    var run=++epoch;view={kind:kind,id:id,label:label};renderRanks();renderMine();
    // 视图记忆只记榜单：收藏/最近/搜索是临时浏览，下次打开永远回榜单，避免"退不出去"的感觉
    if(kind==='playlist')try{localStorage.setItem('tv_music_last',JSON.stringify({kind:kind,id:id,label:label}));}catch(e){}
    if(kind==='fav'||kind==='recent'){
      list=(kind==='fav'?favs:recents).slice();
      if(queue.on&&nowSong){var at=posOf(nowSong);if(at>=0)queue.pos=at;}
      $('music-list').textContent='';render(kind==='fav'?'我喜欢':'最近播放');
      if(!list.length)status(kind==='fav'?'还没有收藏：点歌曲行右侧的心形，就能在这里随时播放':'还没有播放记录：榜单或搜索里点一首歌就会出现在这里');
      return;
    }
    status('正在读取'+label+'…');$('music-list').textContent='';
    fetchList(kind,id).then(function(songs){
      if(run!==epoch)return;
      list=songs;
      if(queue.on&&nowSong){var at2=posOf(nowSong);if(at2>=0)queue.pos=at2;}
      render(label);
    }).catch(function(e){if(run===epoch){list=[];$('music-list').textContent='';status(e.message);}});
  }
  function renderRanks(){Array.prototype.forEach.call($('music-ranks').children,function(b){b.classList.toggle('selected',view.kind==='playlist'&&b.dataset.id===view.id);});}
  function renderMine(){var box=$('music-mine');if(!box)return;Array.prototype.forEach.call(box.children,function(b){b.classList.toggle('selected',view.kind===b.dataset.kind);});}
  function render(label){
    var box=$('music-list');box.textContent='';
    var src=view.kind==='fav'?'本机收藏':view.kind==='recent'?'本机记录':view.kind==='search'?(musicSrc==='kugou'?'酷狗搜索':'网易全曲库'):'网易榜单';
    // 批量渲染：Fragment 一次性挂载（200 行避免逐个触发重排）。
    var frag=document.createDocumentFragment();
    list.slice(0,500).forEach(function(song,index){
      var row=document.createElement('button');row.type='button';row.className='song-row';row.dataset.idx=index;
      var rank=document.createElement('b');rank.textContent=String(index+1);row.appendChild(rank);
      var info=document.createElement('span');info.className='song-info';
      var name=document.createElement('strong');name.textContent=song.name;info.appendChild(name);
      var artist=document.createElement('small');artist.textContent=song.artist||'—';info.appendChild(artist);
      row.appendChild(info);
      var fav=document.createElement('i');fav.className='fave'+(isFav(song)?' on':'');fav.innerHTML='<svg aria-hidden="true"><use href="#i-heart"/></svg>';row.appendChild(fav);
      frag.appendChild(row);
    });
    box.appendChild(frag);
    status('来源：'+src+' · '+label+' · '+list.length+' 首 · 点击播放');
  }
  // 事件委托：列表容器单监听器处理行点击与收藏点击。
  $('music-list').addEventListener('click',function(e){
    var fave=e.target&&e.target.closest('.fave');
    if(fave){var fr=fave.closest('.song-row');if(fr){var fs=list[Number(fr.dataset.idx)];if(fs)toggleFav(fs);}return;}
    var row=e.target&&e.target.closest('.song-row');if(!row)return;
    var song=list[Number(row.dataset.idx)];if(song)play(song,false);
  });
  function renderFavStates(){
    Array.prototype.forEach.call($('music-list').children,function(row){
      var song=list[Number(row.dataset.idx)],f=row.querySelector('.fave');
      if(!song||!f)return;
      f.classList.toggle('on',isFav(song));
    });
    var hb=$('music-now-fav');
    if(hb&&nowSong){var n=isFav(nowSong);hb.classList.toggle('on',n);hb.setAttribute('aria-label',n?'取消收藏':'收藏本曲');}
  }
  function highlightPlaying(){
    var hit=null;
    Array.prototype.forEach.call($('music-list').children,function(row){
      var song=list[Number(row.dataset.idx)];
      var on=!!(song&&playing&&song.id===playing.id&&(!playing.kg===!song.kg));
      row.classList.toggle('playing',on);
      if(on)hit=row;
    });
    // 队列自动切歌时把正在播放的行滚进视野（nearest：已在视野内不动）
    if(hit&&hit.scrollIntoView){try{hit.scrollIntoView({block:'nearest'});}catch(e){}}
  }
  function renderQueue(){
    var all=$('music-playall');
    all.innerHTML='<svg aria-hidden="true"><use href="#i-'+(queue.on?'square':'play')+'"/></svg>';
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
  function neteaseIdFor(song){return rpc('gds=1&type=search&name='+enc((song.artist?song.artist+' ':'')+song.name)).then(function(data){
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
    if(playing===song&&attempt)return; // 同一首正在取链：忽略重复点击
    attempt=null; // 打断上一首还在取链的请求，立即切到新点的歌
    playing=song;highlightPlaying();
    // 队列播放中手动点歌：队列位置同步到所点歌曲，播完从这里继续
    var at=posOf(song);if(at>=0&&queue.on)queue.pos=at;
    attemptQueue=queue.on;
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
        nowSong=song;recordRecent(song);
        var nb=$('music-now');if(nb){nb.hidden=false;$('music-now-title').textContent=song.name;$('music-now-sub').textContent=(song.artist||'—')+' · '+chain[which].name;}
        var ctr=$('music-now-ctrl');if(ctr)ctr.hidden=!!(bridge&&bridge.playMusic);
        renderFavStates();
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
  $('music-open').addEventListener('click',function(){if($('music-dialog').open)return;$('music-dialog').show();try{if(window.__reportEvent)window.__reportEvent('music','打开');}catch(e){}
    // 视图记忆：回到上次浏览的榜单（只记榜单，收藏/搜索不记）
    var last=null;try{last=JSON.parse(localStorage.getItem('tv_music_last'));}catch(e){}
    if(last&&last.kind==='playlist'&&last.id)load('playlist',last.id,last.label||'音乐');
    else load('playlist',RANKS[0][1],RANKS[0][0]);});
  $('music-close').addEventListener('click',function(){$('music-dialog').close();});
  // 关闭清理：个别内核不派发 dialog close 事件（曾导致关页后音频继续放），用 MutationObserver 兜底
  function musicClosed(){++epoch;list=[];$('music-list').textContent='';playing=null;attempt=null;nowSong=null;stopAudio();var nb=$('music-now');if(nb)nb.hidden=true;}
  var musicDlg=$('music-dialog');
  musicDlg.addEventListener('close',musicClosed);
  try{new MutationObserver(function(){if(!musicDlg.open)musicClosed();}).observe(musicDlg,{attributes:true,attributeFilter:['open']});}catch(e){}
  // ESC 也能关闭音乐页（桌面浏览器习惯）
  document.addEventListener('keydown',function(e){if(e.key==='Escape'&&$('music-dialog').open)$('music-dialog').close();});
  $('music-dialog').addEventListener('close',musicClosed);
  RANKS.forEach(function(entry){var b=document.createElement('button');b.type='button';b.textContent=entry[0];b.dataset.id=entry[1];b.addEventListener('click',function(){try{if(window.__reportEvent)window.__reportEvent('music','榜单 '+entry[0]);}catch(e){}load('playlist',entry[1],entry[0]);});$('music-ranks').appendChild(b);});
  // ===== 我的音乐：我喜欢 / 最近播放（本机数据，随点随播，队列照常可用）=====
  var mineBox=$('music-mine'); // 防御：页面结构是旧缓存（无该区块）时跳过，不影响其余功能
  if(mineBox)[['我喜欢','fav','i-heart'],['最近播放','recent','i-clock']].forEach(function(m){
    var b=document.createElement('button');b.type='button';b.dataset.kind=m[1];
    b.innerHTML='<svg aria-hidden="true"><use href="#'+m[2]+'"/></svg>'+m[0];
    b.addEventListener('click',function(){try{if(window.__reportEvent)window.__reportEvent('music','我的 '+m[1]);}catch(e){}load(m[1],'',m[1]==='fav'?'我喜欢':'最近播放');});
    mineBox.appendChild(b);
  });
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
    if(!q)return;
    queries=[q].concat(queries.filter(function(x){return x!==q;})).slice(0,8);
    saveStore('tv_music_queries',queries);renderQueries();
    load('search',encodeURIComponent(q),'搜索“'+q.slice(0,20)+'”');
  });
  function renderQueries(){
    var block=$('music-history-block'),box=$('music-history');if(!block||!box)return;
    box.textContent='';
    queries.forEach(function(q){var b=document.createElement('button');b.type='button';b.textContent=q;
      b.addEventListener('click',function(){$('music-query').value=q;load('search',encodeURIComponent(q),'搜索“'+q.slice(0,20)+'”');});
      box.appendChild(b);});
    block.hidden=!queries.length;
  }
  renderQueries();
  $('music-audio').addEventListener('error',function(){if($('music-audio').getAttribute('src'))status('播放失败：链接可能过期或来源限制，请换一首试试。');});
  $('music-audio').addEventListener('ended',function(){if(window.Music)window.Music.onEnded();});
  // ===== 正在播放条控制（网页版直连音频时可用；APK 内由原生播放器控制）=====
  function posOf(song){ // 按唯一键找歌在当前列表的位置（跨视图对象不同，不能比引用）
    if(!song)return -1;
    for(var i=0;i<list.length;i++)if(songKey(list[i])===songKey(song))return i;
    return -1;
  }
  function playRelative(delta){
    if(!list.length)return;
    var i=nowSong?posOf(nowSong):-1;
    if(i<0)i=queue.pos;
    i+=delta;if(i<0)i=0;if(i>=list.length)i=list.length-1;
    attempt=null;playing=null;play(list[i],true);
  }
  $('music-now-fav').addEventListener('click',function(){if(nowSong)toggleFav(nowSong);});
  $('music-now-prev').addEventListener('click',function(){playRelative(-1);});
  $('music-now-next').addEventListener('click',function(){playRelative(1);});
  $('music-now-toggle').addEventListener('click',function(){var a=$('music-audio');if(!a.getAttribute('src'))return;if(a.paused)a.play().catch(function(){});else a.pause();});
  $('music-audio').addEventListener('play',function(){$('music-now-toggle').innerHTML='<svg aria-hidden="true"><use href="#i-pause"/></svg>';});
  $('music-audio').addEventListener('pause',function(){$('music-now-toggle').innerHTML='<svg aria-hidden="true"><use href="#i-play"/></svg>';});
}());
