const assert=require('node:assert/strict');const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
 const p=await b.newPage({viewport:{width:390,height:844}});const errors=[];p.on('pageerror',e=>errors.push(e.message));
 await p.addInitScript(()=>{window.calls=[];
  const list='<a class="module-poster-item" href="/voddetail/819619.html" title="测试剧"><div class="module-item-pic"><img data-original="/cover.webp" alt="测试剧"></div></a>';
  const detail='<h1>测试剧</h1><div class="video-info-content">介绍</div><div class="module-tab-item" data-dropdown-value="直链线路"></div><div class="module-play-list"><a href="/vodplay/819619-14-1.html">第01集</a><a href="/vodplay/819619-14-2.html">第02集</a><a href="/vodplay/819619-14-3.html">第03集</a></div>';
  window.TVBridge={pageReady(){},startCheck(){},networkType:()=>'other',versionInfo:()=>'{"name":"1.0.11"}',
   vodRequest(id,host,path){calls.push({action:'fetch',path});const html=path.includes('/voddetail/')?detail:path.includes('/vodplay/')?'<script>var player_aaaa='+JSON.stringify({encrypt:0,url:'https://media.example.org/video.m3u8'})+'<\/script>':list;setTimeout(()=>__vodReply(id,{ok:true,data:{path,html}}),20);},
   playVodVideo:(url,title,key,host)=>calls.push({action:'play',url,title,key})};});
 await p.goto('http://127.0.0.1:8138/');await p.locator('#vod-open').click();await p.locator('.vod-card').waitFor();
 // 自定义路径：合法路径触发抓取并持久化，非法路径被拒。
 await p.evaluate(()=>{const d=document.getElementById('vod-admin');if(!d.open)d.open=true;});await p.locator('#vod-custom').fill('https://www.555yy1.com/vodshow/20-----------.html');await p.locator('#vod-custom-refresh').click();
 await p.waitForFunction(()=>window.calls.some(c=>c.action==='fetch'&&c.path==='/vodshow/20-----------.html'));
 assert.equal(await p.evaluate(()=>localStorage.getItem('tv_vod_custom')),'/vodshow/20-----------.html');
 const before=(await p.evaluate(()=>window.calls.filter(c=>c.action==='fetch').length));
 await p.evaluate(()=>{const d=document.getElementById('vod-admin');if(!d.open)d.open=true;});await p.locator('#vod-custom').fill('https://evil.example/voddetail/1.html');await p.locator('#vod-custom-refresh').click();
 await p.waitForFunction(()=>document.getElementById('vod-status').textContent.includes('不受支持'));
 assert.equal(await p.evaluate(()=>window.calls.filter(c=>c.action==='fetch').length),before,'invalid custom path fetched');
 // 连播：播完第一集后 __videoEnded 自动播第二集、再播第三集，最后一集停下。
 await p.locator('.vod-card').click();await p.locator('#vod-episodes button').first().waitFor();
 await p.locator('#vod-episodes button').nth(0).click();await p.waitForFunction(()=>window.calls.some(c=>c.action==='play'));
 assert.equal(await p.evaluate(()=>window.calls.find(c=>c.action==='play').key),'vod:819619:第01集');
 await p.evaluate(()=>window.__videoEnded('vod:819619:第01集'));
 await p.waitForFunction(()=>window.calls.filter(c=>c.action==='play').length===2);
 assert.equal(await p.evaluate(()=>window.calls.filter(c=>c.action==='play')[1].key),'vod:819619:第02集');
 assert.equal(await p.evaluate(()=>window.calls.some(c=>c.action==='fetch'&&c.path==='/vodplay/819619-14-2.html')),true,'auto next did not resolve episode 2');
 await p.evaluate(()=>window.__videoEnded('vod:819619:第02集'));
 await p.waitForFunction(()=>window.calls.filter(c=>c.action==='play').length===3);
 await p.evaluate(()=>window.__videoEnded('vod:819619:第03集'));
 await p.waitForFunction(()=>document.getElementById('vod-status').textContent.includes('已是最后一集'));
 assert.equal(await p.evaluate(()=>window.calls.filter(c=>c.action==='play').length),3,'no play beyond last episode');
 // 弹窗关闭后连播回调不应触发新播放。
 await p.locator('#vod-close').click();await p.waitForTimeout(150);
 await p.evaluate(()=>window.__videoEnded('vod:819619:第02集'));await p.waitForTimeout(200);
 assert.equal(await p.evaluate(()=>window.calls.filter(c=>c.action==='play').length),3,'closed dialog still auto played');
 assert.deepEqual(errors,[]);console.log('PASS: vod custom path refresh with validation and persistence, native auto-next chains episodes and stops at last, no auto play after close.');
}finally{await b.close();}})().catch(e=>{console.error(e);process.exitCode=1});
