const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const { spawn } = require('node:child_process');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
(async () => {
 const server = spawn('python', ['-m','http.server','8138','--bind','127.0.0.1','--directory',path.join(root,'web')],{stdio:'ignore'});
 const browser = await chromium.launch({executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try {
  let ready=false; for(let n=0;n<30;n++){try{await fetch('http://127.0.0.1:8138/');ready=true;break;}catch{await new Promise(r=>setTimeout(r,100));}}
  assert(ready);
  const context=await browser.newContext({viewport:{width:390,height:844}});
  await context.addInitScript(()=>{
   window.calls=[]; window.net='other'; window.TVBridge={
    netdiskRequest:(id,body)=>{const r=JSON.parse(body);const data=r.action==='token'?{code:0,data:{stoken:'fixture'}}:r.action==='files'?{code:0,data:{list:[{fid:'folder',file_name:'目录',dir:true}]}}:{code:0,data:{merged_by_type:{quark:[{url:'https://pan.quark.cn/s/test123',note:'测试用分享',source:'fixture'}]}}};queueMicrotask(()=>window.__netdiskReply(id,{ok:true,data}));},
    pageReady:()=>calls.push('ready'),openUrl:url=>calls.push(url),openDownloads:()=>calls.push('downloads'),reloadHome:()=>calls.push('reload'),exitApp:()=>calls.push('exit'),
    networkType:()=>window.net,versionInfo:()=>JSON.stringify({code:100,name:'1.0.0'}),
    startCheck:()=>calls.push('check'),applyUpdate:()=>calls.push('apply'),applyWifiUpdate:()=>calls.push('wifiApply')
   };
  });
  const page=await context.newPage(); const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:8138/');
  for(const width of [320,390,768,1280]){
   await page.setViewportSize({width,height:844});
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'horizontal overflow '+width);
  }
  await page.setViewportSize({width:390,height:844});
  await page.locator('#query').fill('测试视频 + 中文'); await page.locator('#search-form button[type=submit]').click();
  assert.match(await page.evaluate(()=>calls.at(-1)),/^https:\/\/quark.sm.cn\/s\?q=/);
  assert.equal(await page.locator('#source option:checked').textContent(),'夸克');
  await page.locator('#source').selectOption({label:'哔哩哔哩'});await page.locator('#search-form button[type=submit]').click();assert.match(await page.evaluate(()=>calls.at(-1)),/search.bilibili.com/);
  await page.locator('#mode-quark').click(); await page.locator('#search-form button[type=submit]').click();
  await page.locator('.resource-card').waitFor();assert.equal(new URL(page.url()).hostname,'127.0.0.1');assert.equal(await page.locator('#source option:checked').textContent(),'夸克资源聚合');
  await page.reload();assert.equal(await page.locator('.history-item').count(),1);
  await page.locator('#settings-open').click();
  await page.locator('#source-config').fill('错误 | javascript:alert({query})');await page.locator('#settings-save').click();
  assert(await page.locator('#settings').evaluate(e=>e.open));assert(await page.locator('#settings-error').textContent());
  await page.locator('#source-config').fill('测试源 | https://example.com/search?q={query}');await page.locator('#settings-save').click();
  await page.locator('#mode-web').click();await page.locator('#source').selectOption({label:'测试源'});
  await page.locator('#query').fill('<img src=x onerror=alert(1)>');await page.locator('#search-form button[type=submit]').click();assert.equal(await page.locator('#history img').count(),0);
  await page.evaluate(()=>__updateStatus({state:'found',version:'1.0.1'}));assert(await page.locator('#update-apply').isVisible());
  await page.locator('#update-apply').click();assert.equal(await page.evaluate(()=>calls.at(-1)),'apply');
  await page.evaluate(()=>__updateStatus({state:'downloading',progress:5}));assert.equal(await page.locator('#update-progress').evaluate(e=>e.value),1);
  await page.evaluate(()=>{__updateStatus({state:'error',msg:'断网'});window.net='wifi';__updateStatus({state:'found',version:'1.0.1'});});
  assert.equal(await page.evaluate(()=>calls.at(-1)),'wifiApply');
  await page.evaluate(()=>__updateStatus({state:'done',version:'1.0.1'}));await page.waitForFunction(()=>calls.includes('reload'));
  await page.locator('#quark-open').click();assert.equal(await page.evaluate(()=>calls.at(-1)),'https://pan.quark.cn/');
  await page.locator('#trends-open').click();assert.match(await page.evaluate(()=>calls.at(-1)),/top.baidu.com/);
  await page.locator('#history-clear').click();assert.equal(await page.locator('.history-item').count(),0);
  await page.evaluate(()=>__updateStatus({state:'needapk',version:'1.0.31',notes:'测试提示'}));
  assert.match(await page.evaluate(()=>document.getElementById('update-title').textContent),/需要安装新版 APK 1\.0\.31/);
  assert.match(await page.evaluate(()=>document.getElementById('update-detail').textContent),/测试提示/);
  await page.evaluate(()=>__updateStatus({state:'uptodate',version:'1.0.0'}));
  await page.evaluate(()=>localStorage.clear()); await page.reload();
  await page.locator('#vod-open').click();await page.waitForFunction(()=>document.getElementById('vod-open').classList.contains('active'));assert(await page.locator('#vod-dialog').evaluate(e=>e.open));
  await page.locator('#music-open').click();await page.waitForFunction(()=>document.getElementById('music-open').classList.contains('active'));assert(await page.locator('#music-dialog').evaluate(e=>e.open));assert(await page.locator('#vod-dialog').evaluate(e=>!e.open),'tabs not exclusive');
  await page.locator('#nav-home').click();await page.waitForFunction(()=>document.getElementById('nav-home').classList.contains('active'));assert(await page.locator('#music-dialog').evaluate(e=>!e.open));
  await page.locator('#settings-open').click();assert(await page.locator('#settings').evaluate(e=>e.open));await page.locator('#settings-close').click();
  await page.screenshot({path:path.join(root,'work/home-preview.png'),fullPage:false});
  assert.deepEqual(errors,[]);console.log('PASS: 4 viewport widths, web/quark search, persistence, custom sources, XSS text rendering, Wi-Fi/manual update states, progress clamp, reload, shortcuts.');
  await context.close();
  const real=await browser.newContext({viewport:{width:390,height:844}});
  await real.route(/^https:\/\//,route=>route.fulfill({contentType:'text/html',body:'<html><body>Search destination fixture</body></html>'}));
  const actual=await real.newPage();await actual.goto('http://127.0.0.1:8138/');
  await actual.locator('#query').fill('视频 + 中文');
  await Promise.all([actual.waitForURL('https://quark.sm.cn/**'),actual.locator('#search-form button[type=submit]').click()]);
  assert.equal(real.pages().length,1,'web preview must navigate the same tab');
  assert.equal(new URL(actual.url()).searchParams.get('q'),'视频 + 中文');
  await actual.goBack();
  await actual.locator('#source').selectOption({label:'腾讯视频'});await actual.locator('#query').fill('测试');
  await Promise.all([actual.waitForURL('https://v.qq.com/**'),actual.locator('#search-form button[type=submit]').click()]);
  assert.equal(real.pages().length,1);await actual.goBack();
  assert.equal(await actual.locator('#source option:checked').textContent(),'腾讯视频','remember selected platform');
  await real.close();console.log('PASS: real preview default Quark, domestic platform selection, same-tab navigation, back and source persistence.');
 } finally {await browser.close();server.kill();}
})().catch(e=>{console.error(e);process.exitCode=1;});

