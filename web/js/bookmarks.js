(function () {
  'use strict';
  var bridge = window.TVBridge, items = [];
  var $ = function (id) { return document.getElementById(id); };
  function normalise(address, title) {
    var url; try { url = new URL(String(address).trim()); } catch(e) { throw new Error('请填写完整的 HTTP 或 HTTPS 网页地址'); }
    if ((url.protocol !== 'https:' && url.protocol !== 'http:') || url.username || url.password || url.href.length > 4096) throw new Error('请填写有效的 HTTP 或 HTTPS 网页地址');
    var share = window.Netdisk.extractShare(url.href), key = url.href, kind = 'web';
    if (share && window.Netdisk.shareId(url.href)) { key = 'https://pan.quark.cn/s/' + share.id; url = new URL(key); if (share.password) url.searchParams.set('pwd',share.password); kind = 'quark'; }
    return { key:key, url:url.href, title:String(title || url.hostname).trim().slice(0,200) || url.hostname, kind:kind };
  }
  function request(input) {
    if (window.TVPrivacy.enabled && input.action !== 'list') throw new Error('无痕模式不保存或修改书签，请关闭无痕后操作');
    if (bridge && bridge.bookmarkRequest) {
      var reply = JSON.parse(bridge.bookmarkRequest(JSON.stringify(input)));
      if (!reply.ok) throw new Error(reply.error || '书签操作未完成');
      return reply.items;
    }
    var list; try { list = JSON.parse(localStorage.getItem('tv_bookmarks') || '[]'); } catch (e) { list = []; }
    if (!Array.isArray(list)) list = [];
    if (input.action === 'add') { var entry = normalise(input.url,input.title); list = [entry].concat(list.filter(function (x) { return x && x.key !== entry.key; })); if (list.length > 200) throw new Error('最多保存 200 个本地书签，请先删除不需要的书签'); }
    if (input.action === 'remove') list = list.filter(function (x) { return x && x.key !== input.key; });
    if (input.action !== 'list') localStorage.setItem('tv_bookmarks',JSON.stringify(list));
    return list;
  }
  function message(value) { $('bookmarks-status').textContent = value; }
  function refresh() {
    try { items = request({action:'list'}); render(); }
    catch (e) { message(e.message); }
  }
  function render() {
    $('bookmarks-list').textContent = '';
    $('bookmark-form').hidden = window.TVPrivacy.enabled;
    message(window.TVPrivacy.enabled ? '无痕模式：可打开已有书签，不保存或删除书签。' : items.length ? items.length + ' 个书签 · 仅保存在本机' : '还没有书签。可在下方添加，或在网页菜单和分享目录中收藏。');
    items.forEach(function (item) {
      if (!item || typeof item.url !== 'string') return;
      var safe; try { safe = normalise(item.url,item.title); } catch (e) { return; }
      var row = document.createElement('article'); row.className = 'bookmark-row';
      var open = document.createElement('button'); open.type = 'button'; open.className = 'bookmark-title'; open.textContent = safe.title;
      open.addEventListener('click',function () {
        if (safe.kind === 'quark') { $('bookmarks-dialog').close(); $('mode-quark').click(); window.Netdisk.openBookmark(safe.url,safe.title); }
        else if (bridge) { $('bookmarks-dialog').close(); bridge.openUrl(safe.url); }
        else if (window.TVPrivacy.enabled) message('网页预览的无痕模式不能跳转外部网站，请在 APK 中打开。');
        else window.location.assign(safe.url);
      }); row.appendChild(open);
      var address = document.createElement('small'); address.textContent = (safe.kind === 'quark' ? '夸克分享 · ' : '网页 · ') + safe.url; row.appendChild(address);
      var remove = document.createElement('button'); remove.type = 'button'; remove.className = 'text-button'; remove.textContent = '删除'; remove.disabled = window.TVPrivacy.enabled;
      remove.addEventListener('click',function () { try { items = request({action:'remove',key:item.key}); render(); } catch (e) { message(e.message); } }); row.appendChild(remove);
      $('bookmarks-list').appendChild(row);
    });
  }
  function add(url,title) {
    try { var entry = normalise(url,title); items = request({action:'add',url:entry.url,title:entry.title}); render(); return true; }
    catch (e) { message(e.message); if (!$('bookmarks-dialog').open) $('bookmarks-dialog').showModal(); return false; }
  }
  function privacyChanged() { $('bookmark-title').value = ''; $('bookmark-url').value = ''; refresh(); }
  $('bookmarks-open').addEventListener('click',function () { refresh(); $('bookmarks-dialog').showModal(); });
  $('bookmarks-close').addEventListener('click',function () { $('bookmarks-dialog').close(); });
  $('bookmark-current').addEventListener('click',function () {
    try {
      var current=window.Netdisk.currentPage();
      if (!current && bridge && bridge.currentPage) current=JSON.parse(bridge.currentPage());
      if (!current || !current.url) { message('请先打开一个网页或夸克分享；网页顶部 ☆ 可直接收藏当前页。'); return; }
      if(add(current.url,current.title))message('已收藏：'+(current.title||current.url));
    } catch(e){message(e.message);}
  });
  $('bookmark-form').addEventListener('submit',function (e) { e.preventDefault(); if (add($('bookmark-url').value,$('bookmark-title').value)) { $('bookmark-url').value = ''; $('bookmark-title').value = ''; } });
  window.Bookmarks = { add:add, refresh:refresh, privacyChanged:privacyChanged };
  refresh();
}());
