(function () {
  'use strict';
  var bridge = window.TVBridge;
  window.TVPrivacy = { enabled: bridge && bridge.privacyEnabled ? bridge.privacyEnabled() : localStorage.getItem('tv_private') === 'true' };
  var pending = {}, sequence = 0, generation = 0;
  var current = null, results = [], folderStack = [], files = [], owned = {}, page = 1, total = 0, selected = null, sourceSummary = '', validating = false;
  var sourcesPending = 0, checkedBudget = 0, validationTask = null, resultScroll = 0, shareMemory = {};
  var account = { loggedIn:false }, accountBusy = false, loginPending = false, searchMemory=Object.create(null);
  var bookmarkOrigin = false;
  var $ = function (id) { return document.getElementById(id); };
  function text(tag, value, cls) { var n = document.createElement(tag); n.textContent = value || ''; if (cls) n.className = cls; return n; }
  function button(label, fn, cls) { var n = text('button', label, cls || 'secondary'); n.type = 'button'; n.addEventListener('click', fn); return n; }
  function rpc(action, args) {
    args.private = window.TVPrivacy.enabled;
    if (bridge) {
      if (!bridge.netdiskRequest) return Promise.reject(new Error('当前 APK 不支持内置网盘，请安装新版 APK'));
      return new Promise(function (resolve, reject) {
        var id = 'n' + (++sequence);
        var timer = setTimeout(function () { delete pending[id]; reject(new Error('网盘服务响应超时，请重试')); }, 35000);
        pending[id] = function (r) { clearTimeout(timer); if (r.ok) resolve(r.data); else { var e = new Error(r.error || '网盘请求失败'); if (r.code) e.code = r.code; reject(e); } };
        bridge.netdiskRequest(id, JSON.stringify({ action: action, args: args }));
      });
    }
    var controller = new AbortController(), timer = setTimeout(function () { controller.abort(); }, 35000);
    return fetch('/api/netdisk', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: action, args: args }), signal: controller.signal })
      .then(function (r) { if (!r.ok) throw new Error('本地网盘服务未启动，请使用新的预览地址'); return r.json(); })
      .then(function (r) { if (!r.ok) throw new Error(r.error || '网盘请求失败'); return r.data; })
      .catch(function (e) { if (e && (e.name === 'TypeError' || e.name === 'AbortError')) throw new Error('本地预览服务未连接：请先运行 python tools/preview_server.py --port 8138 并保持窗口开启，再刷新本页重试'); throw e; })
      .finally(function () { clearTimeout(timer); });
  }
  window.__netdiskReply = function (id, reply) { if (pending[id]) { var cb = pending[id]; delete pending[id]; cb(reply); } };
  function checked(payload) {
    if (!payload || Number(payload.status) >= 400 || (payload.code != null && Number(payload.code) !== 0)) {
      var err = new Error(payload && (payload.message || payload.msg) || '网盘接口未返回有效数据');
      err.code = payload ? (payload.code != null ? payload.code : payload.status) : 0; // 夸克登录失效常见 401/31001
      throw err;
    }
    return payload.data == null ? payload : payload.data;
  }
  function shareId(value) {
    try { var url = new URL(value); if (url.protocol !== 'https:' || url.hostname !== 'pan.quark.cn' || url.username || url.password) return ''; var m = url.pathname.match(/^\/s\/([a-zA-Z0-9]+)\/?$/); return m ? m[1] : ''; } catch (e) { return ''; }
  }
  function extractShare(value) {
    var found = String(value).match(/https:\/\/pan\.quark\.cn\/s\/[a-zA-Z0-9]+(?:\?[^\s]*)?/);
    if (!found) return null;
    var id = shareId(found[0]); if (!id) return null;
    var code = String(value).match(/(?:提取码|访问码|密码)\s*[:：]?\s*([a-zA-Z0-9]{4,20})/);
    return { id: id, url: 'https://pan.quark.cn/s/' + id, title: '夸克分享', source: '分享链接', password: code ? code[1] : new URL(found[0]).searchParams.get('pwd') || new URL(found[0]).searchParams.get('passcode') || '' };
  }
  function normalise(payload) {
    if (Array.isArray(payload.sources)) {
      var combined = [], seen = {}, good = 0, failed = 0, reasons = [];
      payload.sources.forEach(function (source) {
        try {
          if (source.error || !source.data) throw new Error(source.error || "响应为空");
          if (source.warning) reasons.push(source.name + '：' + source.warning);
          var raw = source.data;
          if (source.format === 'hunhepan') {
            if (Number(raw.code) !== 200 || !raw.data || !Array.isArray(raw.data.list)) throw new Error(raw.msg || raw.message || "响应格式不完整");
            raw = { items: raw.data.list.map(function (x) { return { pan: 'quark', share_url: x.link, title: String(x.disk_name || '').replace(/\\u003c/g, '<').replace(/\\u003e/g, '>').replace(/<\/?em>/gi, ''), access_code: x.disk_pass, source: source.name, datetime: x.shared_time }; }) };
          }
          if (source.format === 'pansearch') {
            var rows = raw.props && raw.props.pageProps && raw.props.pageProps.data && raw.props.pageProps.data.data;
            if (!Array.isArray(rows)) throw new Error('响应格式不完整');
            raw = { items: [] };
            rows.forEach(function (row) {
              if (row.pan !== 'quark') return;
              var parsed = new DOMParser().parseFromString(String(row.content || ''), 'text/html');
              var content = parsed.body.textContent || ''; 
              var matches = content.match(/https:\/\/pan\.quark\.cn\/s\/[a-zA-Z0-9]+(?:\?[^\s"<>]*)?/g) || [];
              matches.forEach(function (url) { var share = extractShare(url + ' ' + content); if (share) raw.items.push({ pan:'quark', share_url:share.url, title:content.split('\n')[0].replace(/^名称[:：]\s*/,''), access_code:share.password, source:source.name, datetime:row.time }); });
            });
          }
          var items = normalise(raw); good++;
          items.forEach(function (x) { if (!seen[x.id]) { seen[x.id] = x; combined.push(x); } else if (!seen[x.id].password && x.password) { seen[x.id].password = x.password; seen[x.id].url = x.url; } });
        } catch (e) { failed++; reasons.push(source.name + "：" + (e.message || "暂不可用")); }
      });
      $('source-diagnostics').textContent = reasons.join('；');
      if (!good) throw new Error('本次未取得搜索结果。请重试，或直接粘贴夸克分享链接。');
      sourceSummary = '已查询 ' + good + ' 个来源' + (failed ? '，另有 ' + failed + ' 个暂不可用' : '');
      return combined;
    }
    var data = checked(payload), list = data.merged_by_type && data.merged_by_type.quark || [], seen = {}, out = [];
    if (!list.length && Array.isArray(data.items)) list = data.items.filter(function (x) { return x.pan === 'quark'; });
    list.forEach(function (item) {
      var url = item.url || item.share_url, id = shareId(url); if (!id) return;
      var password = String(item.password || item.access_code || new URL(url).searchParams.get('pwd') || new URL(url).searchParams.get('passcode') || '');
      if (seen[id]) { if (!seen[id].password && password) { seen[id].password = password; seen[id].url = 'https://pan.quark.cn/s/' + id + '?pwd=' + encodeURIComponent(password); } return; }
      var entry = { id: id, url: 'https://pan.quark.cn/s/' + id + (password ? '?pwd=' + encodeURIComponent(password) : ''), title: String(item.note || item.title || '夸克分享').slice(0, 700), password: password, source: String(item.source || '公开分享索引'), date: String(item.datetime || '') };
      seen[id] = entry; out.push(entry);
    }); return out;
  }
  function status(message, error) { $('netdisk-status').textContent = message; $('netdisk-status').classList.toggle('error', !!error); }
  function show() { $('netdisk-panel').hidden = false; $('netdisk-panel').scrollIntoView({ behavior: 'smooth', block: 'start' }); }
  function renderResults() {
    current = null; $('share-tools').hidden = true; $('folder-back').hidden = true; $('files-more').hidden = true;
    $('results-back').hidden = true; $('netdisk-close').textContent = '关闭结果';
    $('share-bookmark').hidden = true;
    $('netdisk-title').textContent = '夸克资源'; $('netdisk-list').textContent = '';
    results.filter(function (item) { return item.validation === 'valid' || item.validation === 'locked' || $('show-unverified').checked; }).forEach(function (item) {
      var card = document.createElement('article'); card.className = 'resource-card';
      var open = button(item.title, function () { openShare(item); }, 'resource-title'); open.disabled = item.validation === 'invalid' || !item.validation || item.validation === 'checking'; card.appendChild(open);
      card.appendChild(text('p', item.validation === 'valid' ? '已验证目录 · ' + item.fileCount + ' 项（本次检查）' : item.validation === 'locked' ? '需要提取码 · 尚未验证目录' : item.validation === 'invalid' ? '已失效 · ' + item.validationMessage : item.validation === 'unknown' ? '暂未确认 · ' + item.validationMessage : '正在等待校验', 'share-validity ' + (item.validation || 'pending')));
      card.appendChild(text('p', item.source + (item.date ? ' · ' + item.date.slice(0, 10) : ''), 'resource-meta'));
      if (item.validation === 'valid' && item.sampleNames) card.appendChild(text('p', '目录预览：' + item.sampleNames.join(' / '), 'resource-meta'));
      var row = document.createElement('div'); row.className = 'resource-actions';
      if (item.password) row.appendChild(text('span', '提取码 ' + item.password, 'access-code'));
      var browse = button(item.validation === 'valid' ? '查看文件 →' : '尝试读取目录 →', function () { openShare(item); }); browse.disabled = open.disabled; row.appendChild(browse); card.appendChild(row); $('netdisk-list').appendChild(card);
      row.appendChild(button('收藏',function () { if (window.Bookmarks.add('https://pan.quark.cn/s/' + item.id + (item.password ? '?pwd=' + encodeURIComponent(item.password) : ''),item.title)) { $('toast').textContent = '已保存到本地书签'; $('toast').hidden = false; setTimeout(function () { $('toast').hidden = true; },2500); } }));
    });
    var good = results.filter(function (x) { return x.validation === 'valid'; }).length;
    var bad = results.filter(function (x) { return x.validation === 'invalid'; }).length;
    var checkedCount = results.filter(function (x) { return x.validation && x.validation !== 'checking'; }).length;
    var remaining = results.filter(function (x) { return !x.validation; }).length;
    $('netdisk-validate-more').hidden = !remaining || validating;
    $('result-filters').hidden = !results.length;
    status((results.length ? '索引返回 ' + results.length + ' 条 · 已验证可打开 ' + good + ' 条 · 已失效 ' + bad + ' 条 · ' + (validating ? '正在校验 ' + checkedCount + '/' + results.length : checkedCount < results.length ? '还有 ' + remaining + ' 条待校验' : good ? '本次校验完成' : '当前没有验证通过的分享；可换词或重试') + ' · ' + sourceSummary : sourcesPending ? '正在查询公开分享…' : '没有找到夸克分享，换个更短的关键词试试。') + (sourcesPending ? ' · 另有 ' + sourcesPending + ' 个来源查询中' : ''));
    if (results.length && !validating && !good) $('netdisk-retry').hidden = false;
  }
  function shareFailure(payload) {
    var message = String(payload && (payload.message || payload.msg) || '网盘接口未返回有效数据').slice(0,200);
    var code = Number(payload && payload.code);
    if ([41006,41010,41011,41012].indexOf(code) >= 0 || /已取消|已失效|分享不存在|已过期/.test(message)) return { validation:'invalid', validationMessage:message };
    if (/提取码|访问码|密码|passcode/i.test(message)) return { validation:'locked', validationMessage:message };
    return { validation:'unknown', validationMessage:message };
  }
  function verify(item) {
    var sentPassword = item.password, memoryKey = item.id + ':' + sentPassword, cached = shareMemory[memoryKey], memoryEpoch = generation;
    if (cached && Date.now()-cached.checkedAt < 60000) return Promise.resolve(cached);
    return rpc('token', { pwd_id:item.id, passcode:item.password }).then(function (reply) {
      if (!reply || Number(reply.code) !== 0 || Number(reply.status) >= 400 || !reply.data || !reply.data.stoken) {
        if (memoryEpoch === generation && item.password && item.password !== sentPassword) return verify(item);
        return shareFailure(reply);
      }
      return rpc('files', { pwd_id:item.id, stoken:reply.data.stoken, pdir_fid:'0', page:1 }).then(function (folder) {
        if (!folder || Number(folder.code) !== 0 || Number(folder.status) >= 400 || !folder.data || !Array.isArray(folder.data.list)) return shareFailure(folder);
        var state = { validation:'valid', fileCount:folder.data.list.length, sampleNames:folder.data.list.slice(0,2).map(function (file) { return String(file.file_name || '未命名文件').slice(0,120); }), checkedAt:Date.now(), stoken:reply.data.stoken, rootPayload:folder };
        if (memoryEpoch === generation) { if (Object.keys(shareMemory).length >= 48) shareMemory = {}; shareMemory[memoryKey] = state; }
        return state;
      });
    }).catch(function () { return { validation:'unknown', validationMessage:'连接失败或超时，可重新搜索后校验' }; });
  }
  function validateBatch(run, manual) {
    if (validating) return validationTask || Promise.resolve();
    var limit = manual ? 12 : Math.max(0,12-checkedBudget);
    var queue = results.filter(function (x) { return !x.validation; }).slice(0,limit), next = 0;
    if (!queue.length) return Promise.resolve();
    checkedBudget += queue.length;
    validating = true; renderResults();
    function worker() {
      if (run !== generation || next >= queue.length) return Promise.resolve();
      var item = queue[next++]; item.validation = 'checking';
      return verify(item).then(function (state) {
        if (run !== generation) { item.validation = ''; return; }
        Object.keys(state).forEach(function (key) { item[key] = state[key]; });
        renderResults(); return worker();
      });
    }
    validationTask = Promise.all([worker(), worker(), worker()]).then(function () {
      if (run === generation) { validating = false; renderResults(); return validateBatch(run,false); }
    });
    return validationTask;
  }
  function search(query, refresh) {
    bookmarkOrigin = false;
    var direct = extractShare(query);
    if (direct) { results = [direct]; sourceSummary = '直接打开分享'; renderResults(); show(); return openShare(direct); }
    var run = ++generation; validating = false; checkedBudget = 0; validationTask = null; sourcesPending = 3;
    if (refresh) { shareMemory = {}; searchMemory=Object.create(null); }
    $('show-unverified').checked = false; $('netdisk-retry').hidden = true; $('source-diagnostics').textContent = ''; results = []; renderResults(); show();
    var packets = {};
    function receive(name, payload) {
      if (run !== generation) return Promise.resolve();
      --sourcesPending; packets[name] = payload.sources || [{name:name,format:'pansou',data:payload}];
      var sources = [], seenSources = {};
      Object.keys(packets).forEach(function (key) { packets[key].forEach(function (source) { var id = source.name + ':' + source.format; if (!seenSources[id]) { seenSources[id] = true; sources.push(source); } }); });
      try {
        var previous = {}; results.forEach(function (item) { previous[item.id] = item; });
        results = normalise({sources:sources}).map(function (item) {
          var old = previous[item.id];
          if (!old) return item;
          if (!old.password && item.password) { old.password = item.password; old.url = item.url; if (old.validation === 'locked') old.validation = ''; }
          return old;
        });
        renderResults(); return validateBatch(run,false);
      } catch (e) {
        if (!sourcesPending && !results.length) { status(e.message,true); $('netdisk-retry').hidden = false; }
        else renderResults();
        return Promise.resolve();
      }
    }
    return Promise.all(['pansearch','qkpanso','pansou'].map(function (name) {
      var key=query+':'+name,cached=searchMemory[key];
      if(!window.TVPrivacy.enabled && cached && Date.now()-cached.at<60000)return receive(name,cached.payload);
      return rpc('search', {q:query,source:name,refresh:refresh===true}).then(function (payload) {
        if(run===generation && !window.TVPrivacy.enabled && payload.sources && payload.sources.some(function(s){return !s.error && s.data && (!s.data.code || Number(s.data.code)===200);})){
          if(Object.keys(searchMemory).length>=18)searchMemory=Object.create(null);
          searchMemory[key]={at:Date.now(),payload:payload};
        }
        return receive(name,payload);
      },function (e) { return receive(name,{sources:[{name:name,error:e.message}]}); });
    }));
  }
  function openShare(item, fromBookmark) {
    bookmarkOrigin = fromBookmark === true;
    resultScroll = window.scrollY; sourcesPending = 0;
    validating = false; results.forEach(function (x) { if (x.validation === 'checking') x.validation = ''; });
    current = item; folderStack = []; files = []; page = 1; ++generation;
    $('netdisk-title').textContent = item.title; $('netdisk-list').textContent = ''; $('share-tools').hidden = false;
    $('share-password').value = item.password; $('folder-back').hidden = false; $('files-more').hidden = true; $('result-filters').hidden = true; $('netdisk-validate-more').hidden = true;
    $('results-back').hidden = false; $('results-back').textContent = bookmarkOrigin ? '← 返回书签' : '← 返回搜索结果'; $('netdisk-close').textContent = bookmarkOrigin ? '返回书签' : '返回搜索结果'; $('folder-back').textContent = bookmarkOrigin ? '‹ 返回书签' : '‹ 返回搜索结果';
    $('share-bookmark').hidden = false;
    var cached = shareMemory[item.id + ':' + item.password];
    if (cached && Date.now()-cached.checkedAt < 60000) {
      item.stoken = cached.stoken; files = cached.rootPayload.data.list; total = cached.rootPayload.metadata && Number(cached.rootPayload.metadata._total) || files.length;
      renderFiles(); $('files-more').hidden = !(files.length < total || files.length === 50); show(); return Promise.resolve();
    }
    show();
    status('正在读取分享目录…'); return tokenAndList();
  }
  function tokenAndList() {
    var item = current, run = generation; if (!item) return;
    return rpc('token', { pwd_id: item.id, passcode: $('share-password').value.trim() }).then(function (payload) {
      if (run !== generation) return;
      if (Number(payload.code) !== 0 || Number(payload.status) >= 400) { var state = shareFailure(payload); item.validation = state.validation; item.validationMessage = state.validationMessage; throw new Error(state.validationMessage); }
      var data = checked(payload); if (!data.stoken) throw new Error('无法取得分享访问权限');
      item.password = $('share-password').value.trim(); item.url = 'https://pan.quark.cn/s/' + item.id + (item.password ? '?pwd=' + encodeURIComponent(item.password) : ''); item.stoken = data.stoken; folderStack = []; return listFolder(false);
    }).catch(function (e) { if (run === generation) status(e.message + (item.validation === 'locked' ? '；请填写正确提取码后重试。' : ''), true); });
  }
  function fileSize(value) { var size = Number(value) || 0; if (!size) return ''; var units = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0; while (size >= 1024 && i < 4) { size /= 1024; i++; } return size.toFixed(i ? 1 : 0) + ' ' + units[i]; }
  function isVideo(file) { return file.obj_category === 'video' || /^video\//.test(file.format_type || '') || /\.(mp4|mkv|mov|avi|webm|m4v|ts|m3u8|flv)$/i.test(file.file_name || ''); }
  function parent() { return folderStack.length ? folderStack[folderStack.length - 1].fid : '0'; }
  function listFolder(more) {
    var run = generation, item = current, desiredPage = more ? page + 1 : 1;
    if (!item) return Promise.resolve(); status('正在读取文件…'); $('files-more').hidden = true;
    return rpc('files', { pwd_id: item.id, stoken: item.stoken, pdir_fid: parent(), page: desiredPage }).then(function (payload) {
      if (run !== generation) return; var data = checked(payload); if (!Array.isArray(data.list)) throw new Error('目录信息不完整');
      if (parent() === '0') { item.validation = 'valid'; item.fileCount = data.list.length; }
      page = desiredPage; files = more ? files.concat(data.list) : data.list; total = payload.metadata && Number(payload.metadata._total) || Number(data.total) || files.length;
      renderFiles(); $('files-more').hidden = !(files.length < total || data.list.length === 50);
    }).catch(function (e) { if (run === generation) status(e.message, true); });
  }
  function renderFiles() {
    $('folder-back').textContent = folderStack.length ? '‹ 返回上一级' : bookmarkOrigin ? '‹ 返回书签' : '‹ 返回搜索结果';
    $('netdisk-title').textContent = folderStack.length ? folderStack[folderStack.length - 1].name : current.title;
    $('netdisk-list').textContent = '';
    files.forEach(function (file) {
      var row = button('', function () {
        if (file.dir === true) { folderStack.push({ fid: file.fid, name: file.file_name }); ++generation; listFolder(false); }
        else showFile(file);
      }, 'file-row');
      var icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); icon.setAttribute('class', 'file-icon');
      var use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
      use.setAttribute('href', file.dir ? '#i-folder' : isVideo(file) ? '#i-play' : '#i-file');
      icon.appendChild(use); row.appendChild(icon);
      var info = text('span', '', 'file-info'); info.appendChild(text('strong', file.file_name || '未命名文件'));
      info.appendChild(text('small', file.dir ? '文件夹' : fileSize(file.size) + (isVideo(file) ? ' · 点击预览' : ' · 文件'))); row.appendChild(info);
      row.appendChild(text('span', '›', 'file-arrow')); $('netdisk-list').appendChild(row);
    }); status(files.length ? '已读取 ' + files.length + ' 项' : '目录为空。');
  }
  function showFile(file) {
    selected = file; $('media-title').textContent = file.file_name; $('media-status').textContent = fileSize(file.size);
    $('media-save').textContent = account.loggedIn ? '转存这一个文件并完整播放' : '完整播放（需登录并转存这一个文件）';
    $('media-save').hidden = !isVideo(file); $('media-retry').hidden = !isVideo(file); $('preview-video').hidden = true;
    $('media-dialog').showModal(); if (isVideo(file)) play(false); else $('media-status').textContent = '此文件暂不支持在线预览，可在夸克中查看。';
  }
  function downloadLink(payload) { var data = checked(payload), list = Array.isArray(data) ? data : data.list || []; return list[0] && list[0].download_url || data.download_url || ''; }
  function safeVideo(url) { try { var u = new URL(url); return u.protocol === 'https:' && /(^|\.)quark\.cn$/.test(u.hostname) && !u.username && !u.password; } catch (e) { return false; } }
  function startVideo(url, item, file, preview) {
    if (!safeVideo(url)) throw new Error('接口未返回可用的夸克播放地址');
    try { if (window.PlayHistory && !preview) window.PlayHistory.add({ kind: 'netdisk', title: file.file_name + ' · ' + item.title, share: { id: item.id, password: item.password || item.passcode || $('share-password').value.trim(), title: item.title, fname: file.file_name } }); } catch (e) { }
    $('media-status').textContent = preview ? '正在播放分享页提供的预览；完整播放可登录后转存。' : '已取得账号播放地址';
    if (bridge) { bridge.playVideo(url, file.file_name + (preview ? '（分享预览）' : ''), item.id + ':' + file.fid + (preview ? ':preview' : '')); }
    else { var video = $('preview-video'); video.hidden = false; video.preload = 'auto'; video.src = '/api/media?url=' + encodeURIComponent(url); video.play().catch(function () { $('media-status').textContent = '请点击播放器开始播放；完整播放需在 APK 内登录后转存。'; }); }
  }
  function play(save) {
    var file = selected, item = current; if (!item || !file) return;
    try{if(window.__reportEvent)window.__reportEvent('netdisk',(save?'转存完整播放':'分享预览')+' '+(file.file_name||'').slice(0,30));}catch(e){}
    var run = generation, mediaKey = item.id + ':' + file.fid;
    $('media-status').textContent = save ? '正在转存你选择的一个文件到网盘根目录…' : '正在获取视频地址…';
    $('media-save').disabled = true; $('media-retry').disabled = true;
    var action;
    if (save) {
      if (!bridge) { $('media-status').textContent = '转存和会员播放需要在新版 APK 内登录夸克。'; $('media-save').disabled = false; $('media-retry').disabled = false; return; }
      action = owned[mediaKey] ? Promise.resolve(owned[mediaKey]) : rpc('save', { pwd_id: item.id, stoken: item.stoken, pdir_fid: parent(), fid_list: [file.fid], fid_token_list: [file.share_fid_token] }).then(function (payload) {
        var data = checked(payload); if (!data.task_id) throw new Error('转存任务未创建');
        function poll(attempt) {
          return rpc('task', { task_id: data.task_id, retry_index: attempt }).then(function (reply) {
            var task = checked(reply), fids = task.save_as && task.save_as.save_as_top_fids;
            if (fids && fids[0]) return fids[0];
            if (attempt >= 15 || task.status === 3) throw new Error('转存未完成，请稍后在夸克网盘中检查');
            return new Promise(function (resolve) { setTimeout(resolve, 800); }).then(function () { return poll(attempt + 1); });
          });
        } return poll(0);
      });
      action = action.then(function (fid) {
        owned[mediaKey] = fid;
        if($('media-quality').value==='original')return rpc('ownDownload',{fids:[fid]}).then(downloadLink);
        return rpc('ownPlay', { fid: fid }).then(function (payload) {
          var data = checked(payload), list = data.video_list || [];
          var available = list.filter(function (x) { return x.video_info && x.video_info.url; });
          if (!available.length) throw new Error('转码暂不可用');
          var rank = { low:0, normal:1, high:2, super:3, '2k':4, '4k':5 };
          available.sort(function(a,b) { return (rank[a.resolution] || 0) - (rank[b.resolution] || 0); });
          var wanted = $('media-quality').value;
          var preferred = available.filter(function(x) { return x.resolution === wanted; })[0] || available.slice().reverse().filter(function(x){return (rank[x.resolution]||0)<=(rank[wanted]||0);})[0] || available[0];
          return preferred.video_info.url;
        }).catch(function () { return rpc('ownDownload', { fids: [fid] }).then(downloadLink); });
      });
    } else action = rpc('sharePreview', { pwd_id: item.id, stoken: item.stoken, fid: file.fid, fid_token: file.share_fid_token }).then(function (payload) { var data = checked(payload); return data.play_info && data.play_info.url || ''; });
    action.then(function (url) { if (run === generation && selected === file) startVideo(url, item, file, !save); })
      .catch(function (e) {
        if (run !== generation || selected !== file) return;
        var code = e && e.code, msg = (e && e.message) || '播放失败';
        try{if(window.TVBridge&&TVBridge.logEvent)TVBridge.logEvent('netdisk','播放失败(转存='+save+') 码='+code+' '+msg);}catch(e2){}
        var authFail = save && (Number(code) === 401 || Number(code) === 31001 || /登录|login/i.test(msg));
        if (authFail) {
          if (typeof refreshAccount === 'function') refreshAccount();
          $('media-status').textContent = msg + '。登录可能已过期：请点上方“登录夸克”重新登录，回来后点重试即可。';
        } else if (save) {
          $('media-status').textContent = msg + (code ? '（码 ' + code + '）' : '') + '。这不是登录问题：可稍后点重试，或把画质切到“原文件（消耗流量较多）”用下载直链播放。';
        } else {
          $('media-status').textContent = msg + '。此分享未能提供免登录预览；可从上方登录夸克后重试，完整播放需明确选择转存。';
        }
      })
      .finally(function () { $('media-save').disabled = false; $('media-retry').disabled = false; });
  }
  function back() {
    if ($('media-dialog').open) { $('media-dialog').close(); return true; }
    if ($('netdisk-panel').hidden) return false;
    if (current && folderStack.length) { folderStack.pop(); ++generation; listFolder(false); }
    else if (current) { returnToResults(); }
    else { ++generation; $('netdisk-panel').hidden = true; }
    return true;
  }
  function returnToResults() {
    ++generation; validating = false; sourcesPending = 0;
    if ($('media-dialog').open) $('media-dialog').close();
    var returnToBookmarks = bookmarkOrigin; bookmarkOrigin = false;
    renderResults(); requestAnimationFrame(function () { window.scrollTo(0,resultScroll); });
    if (returnToBookmarks) $('bookmarks-open').click();
  }
  $('netdisk-close').addEventListener('click', function () { if (current) returnToResults(); else { ++generation; $('netdisk-panel').hidden = true; } });
  $('results-back').addEventListener('click', returnToResults);
  $('share-bookmark').addEventListener('click',function () { if (current && window.Bookmarks.add('https://pan.quark.cn/s/' + current.id + ($('share-password').value.trim() ? '?pwd=' + encodeURIComponent($('share-password').value.trim()) : ''),current.title)) { $('toast').textContent = '已保存到本地书签'; $('toast').hidden = false; setTimeout(function () { $('toast').hidden = true; },2500); } });
  $('folder-back').addEventListener('click', back);
  $('share-retry').addEventListener('click', tokenAndList);
  $('files-more').addEventListener('click', function () { listFolder(true); });
  $('media-close').addEventListener('click', function () { $('media-dialog').close(); });
  $('media-dialog').addEventListener('close', function () { $('preview-video').pause(); $('preview-video').removeAttribute('src'); $('preview-video').load(); selected = null; });
  function login() {
    if (bridge) { loginPending = true; bridge.openUrl('https://pan.quark.cn/'); }
    else { $('account-status').textContent = '网页预览无法同步夸克登录，请安装 APK 后使用此入口登录。'; if ($('media-dialog').open) $('media-status').textContent = '网页预览无法同步夸克登录；请在 APK 首页登录夸克后完整播放。'; }
  }
  function refreshAccount() {
    if (accountBusy) return Promise.resolve();
    if (!bridge) { $('account-status').textContent = '网页预览支持公开分享预览；登录与会员播放请使用 APK。'; return Promise.resolve(); }
    accountBusy = true;
    var requestPrivate = window.TVPrivacy.enabled;
    return rpc('account',{}).then(function (state) {
      if (requestPrivate !== window.TVPrivacy.enabled) return;
      if (typeof state.loggedIn !== 'boolean') throw new Error('无效的登录状态');
      account = state; $('account-login').textContent = state.loggedIn ? '管理夸克账号' : '登录夸克';
      $('account-status').textContent = state.loggedIn ? '夸克已登录' + (window.TVPrivacy.enabled ? ' · 仅本次无痕会话有效' : ' · 返回后可继续浏览和播放') : '夸克未登录 · 部分分享可免登录预览，完整播放需登录';
      if (state.loggedIn && loginPending && selected && $('media-dialog').open) { loginPending = false; return play(false); }
      loginPending = false;
    }).catch(function () { if (requestPrivate === window.TVPrivacy.enabled) $('account-status').textContent = '登录状态暂时无法确认，可点击重新检查'; }).finally(function () { accountBusy = false; if (requestPrivate !== window.TVPrivacy.enabled) refreshAccount(); });
  }
  $('account-login').addEventListener('click',login);
  $('account-check').addEventListener('click',refreshAccount);
  $('media-login').addEventListener('click',login);
  $('media-retry').addEventListener('click', function () { play(false); });
  $('media-save').addEventListener('click', function () { play(true); });
  $('media-open-quark').addEventListener('click', function () { if (current) { if (bridge) bridge.openUrl(current.url); else if (window.TVPrivacy.enabled) $('media-status').textContent = '无痕网页浏览请使用新版 APK，网页预览不会跳转外部分享页。'; else window.location.assign(current.url); } });
  $('preview-video').addEventListener('error', function () { if ($('preview-video').getAttribute('src')) $('media-status').textContent = '视频加载失败，可能是网络或浏览器不支持该格式，可重试分享预览。会员完整播放请在 APK 内登录后使用。'; });
  $('show-unverified').addEventListener('change', renderResults);
  $('netdisk-validate-more').addEventListener('click', function () { validateBatch(generation,true); });
  $('netdisk-retry').addEventListener('click', function () { search($('query').value.trim(), true); });
  function reset() { ++generation; validating = false; sourcesPending = 0; shareMemory = {}; searchMemory=Object.create(null); checkedBudget = 0; resultScroll = 0; loginPending = false; results = []; current = selected = null; files = []; owned = {}; folderStack = []; if ($('media-dialog').open) $('media-dialog').close(); $('netdisk-panel').hidden = true; $('netdisk-list').textContent = ''; $('source-diagnostics').textContent = ''; $('share-password').value = ''; refreshAccount(); }
  window.Netdisk = { currentPage:function(){return current?{title:current.title,url:'https://pan.quark.cn/s/'+current.id+($('share-password').value.trim()?'?pwd='+encodeURIComponent($('share-password').value.trim()):'')}:null;}, reset: reset, search: search, back: back, normalise: normalise, shareId: shareId, extractShare: extractShare, refreshAccount:refreshAccount, openBookmark:function (url,title) { var item = extractShare(url); if (item) { item.title = title || item.title; return openShare(item,true); } },
        replayShare:function (share) { if (!share || !share.id) return;
          var item = { id: share.id, title: share.title || '夸克分享', password: share.password || '', url: 'https://pan.quark.cn/s/' + share.id };
          var opened = openShare(item, false);
          if (share.fname) { // 重跳后自动打开上次播放的文件
            var wait = function (n) { if (n <= 40) setTimeout(function () { var f = files.filter(function (x) { return x.file_name === share.fname; })[0]; if (f) showFile(f); else if (n > 0) wait(n - 1); }, 500); };
            wait(40);
          }
          return opened; } };
  refreshAccount();
}());
