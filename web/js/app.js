(function () {
  'use strict';
  if (!window.Netdisk) throw new Error('网盘模块未能载入');
  if (!window.Vod) throw new Error('影视模块未能载入');
  if (!window.Bookmarks) throw new Error('书签模块未能载入');
  var bridge = window.TVBridge, mode = 'web', pending = false, busy = false, retryAt = 0, quietCheck = false;
  var $ = function (id) { return document.getElementById(id); };
  var webSources = [
    { name: '夸克', url: 'https://quark.sm.cn/s?q={query}' },
    { name: '百度', url: 'https://www.baidu.com/s?wd={query}' },
    { name: '必应', url: 'https://cn.bing.com/search?q={query}' },
    { name: '搜狗', url: 'https://www.sogou.com/web?query={query}' },
    { name: '360 搜索', url: 'https://www.so.com/s?q={query}' },
    { name: '哔哩哔哩', url: 'https://search.bilibili.com/all?keyword={query}' },
    { name: '腾讯视频', url: 'https://v.qq.com/x/search/?q={query}' },
    { name: '爱奇艺', url: 'https://so.iqiyi.com/so/q_{query}' },
    { name: 'Google', url: 'https://www.google.com/search?q={query}' }
  ];
  function read(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) || fallback; } catch (e) { return fallback; } }
  var custom = read('tv_sources', []), history = read('tv_history', []), privacy = window.TVPrivacy;
  if (!Array.isArray(custom)) custom = [];
  if (!Array.isArray(history)) history = [];
  var seenHistory = Object.create(null);
  history = history.filter(function (item) {
    if (!item || typeof item.q !== 'string' || !item.q.trim()) return false;
    item.q = item.q.trim();
    if (seenHistory[item.q]) return false;
    seenHistory[item.q] = true; return true;
  }).slice(0,18);
  if (!privacy.enabled) localStorage.setItem('tv_history', JSON.stringify(history));
  if (!read('tv_default_quark', false)) {
    localStorage.setItem('tv_source_web', JSON.stringify(webSources[0].url));
    localStorage.setItem('tv_source_quark', JSON.stringify('netdisk'));
    localStorage.setItem('tv_default_quark', 'true');
  }
  function toast(text) { $('toast').textContent = text; $('toast').hidden = false; setTimeout(function () { $('toast').hidden = true; }, 3000); }
  function open(url) {
    if (privacy.enabled && !bridge) { toast('网页预览仅支持站内无痕；网页搜索的无痕浏览请使用 APK'); return; }
    if (bridge) bridge.openUrl(url); else window.location.assign(url);
  }
  function setMode(next) {
    mode = next;
    ['web', 'quark'].forEach(function (m) { $('mode-' + m).classList.toggle('selected', m === mode); $('mode-' + m).setAttribute('aria-selected', String(m === mode)); });
    var sources = mode === 'web' ? webSources.concat(custom) : [{ name: '夸克资源聚合', url: 'netdisk' }];
    $('source').disabled = mode === 'quark';
    $('source').textContent = '';
    sources.forEach(function (s) { var o = document.createElement('option'); o.textContent = s.name; o.value = s.url; $('source').appendChild(o); });
    var saved = read('tv_source_' + mode, '');
    if (sources.some(function (s) { return s.url === saved; })) $('source').value = saved;
    $('search-hint').textContent = mode === 'web' ? (bridge ? '应用内打开网页' : '当前页打开，返回可继续搜索') : '结果直接显示在应用内';
    $('query').placeholder = mode === 'web' ? '片名、教程、感兴趣的任何事…' : '输入关键词，或粘贴夸克分享链接';
  }
  function renderHistory() {
    $('recent-section').hidden = privacy.enabled;
    $('history-count').textContent = String(history.length);
    $('history').textContent = ''; $('history-empty').hidden = history.length > 0;
    history.forEach(function (item) {
      if (!item || typeof item.q !== 'string') return;
      var b = document.createElement('button'); b.className = 'history-item'; b.textContent = item.q;
      b.addEventListener('click', function () { $('query').value = item.q; search(); });
      $('history').appendChild(b);
    });
  }
  function search() {
    var q = $('query').value.trim(); if (!q) { $('query').focus(); return; }
    if (mode === 'quark' && q.length > 60 && !window.Netdisk.extractShare(q)) { toast('网盘搜索请输入不超过 60 字'); return; }
    var template = $('source').value;
    try { if (mode === 'web') { var u = new URL(template.replace(/\{query\}/g, encodeURIComponent(q))); if (u.protocol !== 'https:' || u.username || u.password) throw new Error(); } }
    catch (e) { toast('搜索地址无效，请检查设置'); return; }
    if (!privacy.enabled) { history = [{ q: q, mode: mode }].concat(history.filter(function (x) { return x && x.q !== q; })).slice(0, 18);
    localStorage.setItem('tv_history', JSON.stringify(history)); } renderHistory();
    if (mode === 'quark') window.Netdisk.search(q); else open(u.href);
  }
  function updateCheck(silent) {
    if (!bridge) { toast('APK 内支持检查更新，当前为网页预览'); return; }
    if (busy) { toast('更新任务正在进行'); return; }
    quietCheck = silent === true;
    busy = true; pending = false; window.__updateStatus({ state: 'checking' }); bridge.startCheck('unused');
  }
  var apkPending = false;
  var apkBundled = false; // 新 APK 已打包本次网页资源：装完一步到位；失败可退回仅热更
  function apply(wifiOnly) {
    if (busy || !pending) return;
    busy = true; pending = false;
    if (apkPending) {
      window.__updateStatus({ state: 'downloadingapk', progress: 0 });
      bridge.applyApkUpdate('unused'); return;
    }
    window.__updateStatus({ state: 'downloading', progress: 0 });
    if (wifiOnly) bridge.applyWifiUpdate('unused'); else bridge.applyUpdate('unused');
  }
  window.__updateStatus = function (s) {
    $('update-panel').hidden = quietCheck && (s.state === 'checking' || s.state === 'error' || s.state === 'uptodate'); $('update-apply').hidden = true;
    $('update-progress').hidden = s.state !== 'downloading';
    if (s.state === 'checking') { $('update-title').textContent = '正在检查更新'; $('update-detail').textContent = '并行检查版本，保留本地使用记录。'; }
    else if (s.state === 'found') {
      quietCheck = false;
      busy = false; pending = true; apkPending = false; apkBundled = false; $('update-title').textContent = '发现新版本 ' + s.version; $('update-detail').textContent = s.notes || '资源更新';
      if (bridge.networkType() === 'wifi') apply(true); else { $('update-detail').textContent += ' · 等待 Wi-Fi，或手动下载。'; $('update-apply').hidden = false; }
    } else if (s.state === 'foundapk') {
      quietCheck = false;
      busy = false; pending = true; apkPending = true; apkBundled = !!s.bundled; $('update-title').textContent = '发现新版 APK ' + s.version;
      $('update-detail').textContent = (s.notes || '包含新的原生功能') + (apkBundled ? ' · 新 APK 已包含本次网页资源，安装后一步到位。' : ' · 下载后需在系统弹窗点“安装”。');
      $('update-apply').hidden = false; $('update-apply').textContent = '下载并安装';
    } else if (s.state === 'downloadingapk') {
      busy = true; var pk = Math.max(0, Math.min(1, Number(s.progress) || 0));
      $('update-title').textContent = '正在下载新 APK ' + Math.floor(pk * 100) + '%'; $('update-detail').textContent = '中断会保留进度，下次继续。'; $('update-progress').value = pk;
    } else if (s.state === 'installready') {
      busy = false; pending = false; apkPending = false; $('update-title').textContent = '新 APK 已就绪';
      $('update-detail').textContent = '已拉起系统安装窗口，请点“安装”完成覆盖。若被拒绝，请先在系统设置中允许本应用安装。';
      $('update-apply').hidden = false; $('update-apply').textContent = '再次拉起安装';
      setTimeout(function () { $('update-apply').textContent = '手动下载'; }, 5000);
    } else if (s.state === 'downloading') {
      busy = true; var p = Math.max(0, Math.min(1, Number(s.progress) || 0));
      $('update-title').textContent = '正在更新 ' + Math.floor(p * 100) + '%'; $('update-detail').textContent = '中断会保留进度，下次继续。'; $('update-progress').value = p;
    } else if (s.state === 'done') {
      busy = false; pending = false; $('update-title').textContent = '更新完成 ' + s.version; $('update-detail').textContent = '正在载入新版首页…'; setTimeout(function () { bridge.reloadHome(); }, 700);
    } else if (s.state === 'waitingwifi') {
      busy = false; pending = true; retryAt = Date.now() + 10000; $('update-title').textContent = '等待 Wi-Fi'; $('update-detail').textContent = '下载进度已保留，连接后继续。'; $('update-apply').hidden = false;
    } else if (s.state === 'uptodate') {
      busy = false; pending = false; $('update-title').textContent = '当前版本 ' + s.version;
      $('update-detail').textContent = '更新源未提供更高版本。' + (s.crash ? ' ⚠ 上次异常退出：' + s.crash : '');
    } else if (s.state === 'needapk') {
      busy = false; pending = false; $('update-title').textContent = '需要安装新版 APK ' + s.version;
      $('update-detail').textContent = s.notes || '热更装不下该版本的原生改动，请覆盖安装最新 APK 后再检查更新。';
    } else if (s.state === 'error') {
      busy = false; pending = false; retryAt = Date.now() + 60000; $('update-title').textContent = '本次更新未完成'; $('update-detail').textContent = s.msg || '请稍后重试，当前版本仍可使用。';
      if (apkBundled) { pending = true; apkPending = false; $('update-apply').hidden = false; $('update-apply').textContent = '改为仅更新网页资源'; }
    }
  };
  function showPrivacy() {
    $('privacy-toggle').textContent = privacy.enabled ? '无痕已开启' : '开启无痕';
    $('privacy-toggle').setAttribute('aria-pressed', String(privacy.enabled));
    $('privacy-note').hidden = !privacy.enabled;
    $('privacy-note').textContent = bridge ? '无痕已开启：不保存搜索、地址与播放进度。网页关闭后清理本次浏览资料，登录仅本次会话有效。' : '站内无痕已开启：不保存搜索词和播放记录。完整网页无痕浏览请使用新版 APK。';
    renderHistory();
    window.Bookmarks.privacyChanged();
  }
  $('privacy-toggle').addEventListener('click', function () {
    privacy.enabled = !privacy.enabled;
    if (bridge && bridge.setPrivacy) bridge.setPrivacy(privacy.enabled);
    else localStorage.setItem('tv_private', String(privacy.enabled));
    window.Netdisk.reset(); $('query').value = ''; showPrivacy();
    if(window.Dramas)window.Dramas.reset();
    window.Vod.reset();
    toast(privacy.enabled ? '已开启无痕，本次内容不记入历史' : '已关闭无痕，本次内容已清除');
  });
  window.addEventListener('pagehide', function () { if (privacy.enabled) { window.Netdisk.reset(); $('query').value = ''; } });
  $('search-form').addEventListener('submit', function (e) { e.preventDefault(); search(); });
  $('mode-web').addEventListener('click', function () { setMode('web'); });
  $('mode-quark').addEventListener('click', function () { setMode('quark'); });
  $('source').addEventListener('change', function () { if (!privacy.enabled) localStorage.setItem('tv_source_' + mode, JSON.stringify($('source').value)); });
  $('quark-open').addEventListener('click', function () { open('https://pan.quark.cn/'); });
  $('trends-open').addEventListener('click', function () { open('https://top.baidu.com/board?tab=teleplay'); });
  $('downloads-open').addEventListener('click', function () { if (privacy.enabled) toast('关闭无痕后可查看下载'); else if (bridge) bridge.openDownloads(); else toast('请在 APK 中查看下载'); });
  $('history-clear').addEventListener('click', function () { history = []; localStorage.removeItem('tv_history'); renderHistory(); });
  $('settings-open').addEventListener('click', function () { $('source-config').value = custom.map(function (s) { return s.name + ' | ' + s.url; }).join('\n'); $('settings-error').textContent = ''; $('settings').showModal(); });
  $('settings-close').addEventListener('click', function () { $('settings').close(); });
  // 底部页签：搜索页常驻；“电影电视 / 音乐”以整页形式打开（page-dialog）；短剧在影视页的分类芯片里。
  $('nav-home').addEventListener('click', function () {
    var any = false;
    ['vod-dialog', 'drama-dialog', 'music-dialog', 'deal-dialog'].forEach(function (id) { if ($(id).open) { $(id).close(); any = true; } });
    if (any) return;
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });
  $('settings-save').addEventListener('click', function () {
    try {
      var list = $('source-config').value.split('\n').filter(function (x) { return x.trim(); }).map(function (line) {
        var split = line.indexOf('|'), name = line.slice(0, split).trim(), url = line.slice(split + 1).trim();
        var parsed = new URL(url.replace(/\{query\}/g, 'test'));
        if (split < 1 || !name || name.length > 30 || url.indexOf('{query}') < 0 || parsed.protocol !== 'https:' || parsed.username || parsed.password) throw new Error();
        return { name: name, url: url };
      });
      if (list.length > 12) throw new Error();
      custom = list; localStorage.setItem('tv_sources', JSON.stringify(custom)); setMode(mode); $('settings').close(); toast('已保存');
    } catch (e) { $('settings-error').textContent = '请填写“名称 | HTTPS网址”，包含 {query}，最多 12 个来源。'; }
  });
  $('cache-clear').addEventListener('click',function(){ if(bridge && bridge.clearWebCache)bridge.clearWebCache();else toast('网页预览请在浏览器设置中清理缓存'); });
  $('update-open').addEventListener('click', updateCheck);
  $('settings-update').addEventListener('click', function () { $('settings').close(); updateCheck(); });
  $('update-apply').addEventListener('click', function () { apply(false); });
  $('settings-exit').addEventListener('click', function () { if (bridge) bridge.exitApp(); else $('settings').close(); });
  window.TVHome = { back: function () { if ($('drama-player-dialog').open) $('drama-player-dialog').close(); else if ($('drama-dialog').open) $('drama-dialog').close(); else if ($('deal-dialog') && $('deal-dialog').open) $('deal-dialog').close(); else if ($('vod-dialog').open) window.Vod.back(); else if ($('music-dialog') && $('music-dialog').open) window.Music.back(); else if ($('bookmarks-dialog').open) $('bookmarks-dialog').close(); else if ($('settings').open) $('settings').close(); else if (window.Netdisk && window.Netdisk.back()) return; else if (bridge && confirm('退出搜看？')) bridge.exitApp(); } };
  setMode(new URLSearchParams(window.location.search).get('tab') === 'quark' ? 'quark' : 'web'); showPrivacy();
  if (bridge) {
    try { $('version').textContent = 'v' + JSON.parse(bridge.versionInfo()).name; } catch (e) { $('version').textContent = '版本未知'; }
    bridge.pageReady();
    setTimeout(function () { updateCheck(true); }, 1800);
    setInterval(function () { if (!document.hidden && !busy && pending && Date.now() >= retryAt && bridge.networkType() === 'wifi') apply(true); }, 5000);
  }
}());

(function(){
  'use strict';
  var $=function(id){return document.getElementById(id);}, bridge=window.TVBridge;
  var catalog=[],category='热门',selected=null,episode=0;
  function validUrl(value){var u=new URL(value);if(u.protocol!=='https:'||u.username||u.password||u.href.length>8192)throw new Error('视频直链必须为 HTTPS 地址');return u.href;}
  function parse(value){
    var data=JSON.parse(value);
    if(!data||!Array.isArray(data.dramas)||data.dramas.length>200)throw new Error('片库需要 dramas 数组，最多 200 部');
    var ids=Object.create(null);
    return data.dramas.map(function(d,index){
      if(!d||typeof d.title!=='string'||!d.title.trim()||!Array.isArray(d.episodes)||!d.episodes.length||d.episodes.length>300)throw new Error('每部短剧需要名称和 1—300 集');
      var id=String(d.id||index);if(ids[id]||id.length>100)throw new Error('短剧 ID 重复或过长');ids[id]=true;
      return {id:id,title:d.title.trim().slice(0,100),category:String(d.category||'其他').slice(0,30),heat:Math.max(0,Number(d.heat)||0),episodes:d.episodes.map(function(e,i){if(!e||typeof e.url!=='string')throw new Error('每集需要视频直链 url');return {title:String(e.title||'第 '+(i+1)+' 集').slice(0,100),url:validUrl(e.url)};})};
    });
  }
  function read(){try{catalog=parse(localStorage.getItem('tv_drama_catalog')||'{"dramas":[]}');}catch(e){catalog=[];$('drama-status').textContent='本地片库格式无效，请重新导入。';}}
  function button(text,fn,cls){var b=document.createElement('button');b.type='button';b.textContent=text;if(cls)b.className=cls;b.addEventListener('click',fn);return b;}
  function render(){
    $('drama-import').hidden=window.TVPrivacy.enabled;
    $('drama-status').textContent=catalog.length?(category==='热门'?'热门按导入片库的热度排序':'分类：'+category)+' · '+catalog.length+' 部本地片库 · '+(window.TVPrivacy.enabled?'无痕不保存播放进度':'点击后在应用内播放'):'尚未接入真实短剧片源。可导入自己的片库；当前没有虚构榜单或红果片库。';
    var categories=['热门','全部'];catalog.forEach(function(d){if(categories.indexOf(d.category)<0)categories.push(d.category);});
    $('drama-categories').textContent='';categories.forEach(function(c){$('drama-categories').appendChild(button(c,function(){category=c;render();},category===c?'selected':''));});
    var list=catalog.filter(function(d){return category==='热门'||category==='全部'||d.category===category;}).slice();
    if(category==='热门')list.sort(function(a,b){return b.heat-a.heat;});
    $('drama-list').textContent='';var frag=document.createDocumentFragment();
    list.forEach(function(d,i){
      var card=button('',function(){selected=d;episode=0;renderPlayer();$('drama-player-dialog').showModal();play();},'drama-card');
      [['b',String(i+1).padStart(2,'0')],['strong',d.title],['small',d.category+' · '+d.episodes.length+' 集']].forEach(function(row){var el=document.createElement(row[0]);el.textContent=row[1];card.appendChild(el);});frag.appendChild(card);
    });
    $('drama-list').appendChild(frag);
  }
  function renderPlayer(){
    $('drama-title').textContent=selected.title+' · '+selected.episodes[episode].title;
    $('drama-episodes').textContent='';selected.episodes.forEach(function(e,i){$('drama-episodes').appendChild(button(e.title,function(){episode=i;renderPlayer();play();},i===episode?'selected':''));});
  }
  function play(){
    if(!selected)return;var e=selected.episodes[episode],video=$('drama-video');
    $('drama-play-status').textContent='正在加载 '+e.title+'…';
    if(bridge&&bridge.playPublicVideo){video.hidden=true;bridge.playPublicVideo(e.url,selected.title+' · '+e.title,'drama:'+selected.id+':'+episode);$('drama-play-status').textContent='已打开内置播放器，返回后可选择其他集。';}
    else {video.hidden=false;video.src=e.url;video.play().catch(function(){$('drama-play-status').textContent='请点击播放按钮，或检查片源是否支持当前浏览器。';});}
  }
  function reset(){if($('drama-player-dialog').open)$('drama-player-dialog').close();$('drama-json').value='';selected=null;if($('drama-dialog').open)render();}
  $('drama-local-open').addEventListener('click',function(){if($('drama-dialog').open)return;read();render();$('drama-dialog').show();});
  $('drama-close').addEventListener('click',function(){$('drama-dialog').close();});
  $('drama-save').addEventListener('click',function(){
    if(window.TVPrivacy.enabled)return;
    try{var list=parse($('drama-json').value);localStorage.setItem('tv_drama_catalog',JSON.stringify({dramas:list}));catalog=list;category='热门';$('drama-json').value='';render();}catch(e){$('drama-status').textContent='导入失败：'+e.message;}
  });
  $('drama-clear').addEventListener('click',function(){if(window.TVPrivacy.enabled)return;localStorage.removeItem('tv_drama_catalog');catalog=[];category='热门';render();});
  $('drama-player-close').addEventListener('click',function(){$('drama-player-dialog').close();});
  $('drama-player-dialog').addEventListener('close',function(){var v=$('drama-video');v.pause();v.removeAttribute('src');v.load();selected=null;});
  $('drama-replay').addEventListener('click',play);
  $('drama-video').addEventListener('playing',function(){$('drama-play-status').textContent='正在播放';});
  $('drama-video').addEventListener('error',function(){if($('drama-video').getAttribute('src'))$('drama-play-status').textContent='加载失败：片源失效、网络不通或格式不受支持。';});
  $('drama-video').addEventListener('ended',function(){if(selected&&episode+1<selected.episodes.length){episode++;renderPlayer();play();}});
  // 原生播放器自然播完（RESULT_OK+ended）时回调这里；手动返回不会触发。
  window.__videoEnded=function(key){
    var name=String(key==null?'':key);
    if(name.indexOf('drama:')===0){
      if(!selected||!$('drama-player-dialog').open)return;
      if(episode+1<selected.episodes.length){episode++;renderPlayer();$('drama-play-status').textContent='本集播完，自动播放下一集…';play();}
      else $('drama-play-status').textContent='已是最后一集';
    }else if(name.indexOf('vod:')===0&&window.Vod&&window.Vod.autoNext)window.Vod.autoNext(name);
    else if(name.indexOf('music:')===0&&window.Music&&window.Music.onEnded)window.Music.onEnded();
  };
  window.Dramas={reset:reset};
}());

// 页签高亮与互斥：短剧页签打开影视整页并锁定短剧分类；本地片库是影视页内的浮层。
(function(){
  'use strict';
  var $=function(id){return document.getElementById(id);};
  var TABS=['nav-home','vod-open','music-open'];
  function setTab(id){TABS.forEach(function(x){$(x).classList.toggle('active',x===id);});}
  function refresh(){
    var tab='nav-home';
    if($('deal-dialog').open&&$('vod-dialog').open)tab='vod-open';
    else if($('deal-dialog').open&&!$('vod-dialog').open&&!$('music-dialog').open)tab='deal-open';
    else if($('music-dialog').open)tab='music-open';
    else if($('vod-dialog').open)tab='vod-open';
    setTab(tab);
  }
  function closeOthers(except){
    if(except!=='vod'&&$('vod-dialog').open)$('vod-dialog').close();
    if(except!=='music'&&$('music-dialog').open)$('music-dialog').close();
    if(except!=='drama'&&$('drama-dialog').open)$('drama-dialog').close();
    if(except!=='deal'&&$('deal-dialog').open)$('deal-dialog').close();
  }
  $('vod-open').addEventListener('click',function(){closeOthers('vod');setTimeout(refresh,0);});
  $('deal-open').addEventListener('click',function(){closeOthers('deal');});
  $('music-open').addEventListener('click',function(){closeOthers('music');setTimeout(refresh,0);});
  ['vod-dialog','music-dialog','drama-dialog','deal-dialog'].forEach(function(id){$(id).addEventListener('close',function(){setTimeout(refresh,0);});});
  window.TVTabs={refresh:refresh};
  // 原生 pageReady 后触发：进入应用即后台预热电影电视/短剧/音乐首屏，不等用户点页签。
  window.__preloadTabs=function(){
    setTimeout(function(){
      try{window.Vod&&window.Vod.preload&&window.Vod.preload();}catch(e){}
      try{window.Music&&window.Music.preload&&window.Music.preload();}catch(e){}
      try{window.Deals&&window.Deals.preload&&window.Deals.preload();}catch(e){}
    },600);
  };
}());
