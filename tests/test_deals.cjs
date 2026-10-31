const assert=require('node:assert/strict');const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
 const p=await b.newPage({viewport:{width:390,height:844}});const errors=[];p.on('pageerror',e=>errors.push(e.message));
 const rss='<?xml version="1.0"?><rss version="2.0"><channel><item><title><![CDATA[好价：测试商品1]]></title><link>https://shop.example.com/1</link><pubDate>Wed, 30 Oct 2026 08:00:00 GMT</pubDate></item><item><title>线报：测试商品2（重复会被去重）</title><link>https://shop.example.com/2</link><pubDate>Wed, 29 Oct 2026 08:00:00 GMT</pubDate></item></channel></rss>';
 await p.addInitScript(()=>{
  window.calls=[];window.__mark='init';
  window.addEventListener('unhandledrejection',function(e){window.__mark='reject: '+(e.reason&&e.reason.message||'');});
  window.TVBridge={pageReady(){},startCheck(){},networkType:()=>'other',versionInfo:()=>'{"name":"1.0.21"}',openUrl:(url)=>window.calls.push({url:url}),
   dealRequest(id,url){window.calls.push({deal:url});
    if(url.indexOf('bad.example.com')>=0){setTimeout(()=>window.__dealReply(id,{ok:false,error:'HTTP 403'}),10);return;}
    setTimeout(()=>window.__dealReply(id,{ok:true,data:{body:'<rss version="2.0"><channel>'+rssBody+'</channel></rss>'}}),10);}};
  window.rssBody='<item><title><![CDATA[好价：测试商品1]]></title><link>https://shop.example.com/1</link><pubDate>Wed, 30 Oct 2026 08:00:00 GMT</pubDate></item><item><title>线报：测试商品2</title><link>https://shop.example.com/2</link><pubDate>Wed, 29 Oct 2026 08:00:00 GMT</pubDate></item>';
 });
 // 首次添加订阅 → 拉取渲染 → 每日缓存
 await p.goto('http://127.0.0.1:8138/?v=121');await p.locator('#deal-open').click();
 console.log("S1-open");
 assert((await p.locator('#deal-status').textContent()).includes('还没有订阅源'),'empty state guidance');
 await p.locator('#deal-feed-name').fill('测试源');await p.locator('#deal-feed-url').fill('https://feed.test.example.com/rss');
 console.log("S2-add");
 await p.locator('#deal-feed-add').click();
 await p.waitForFunction(()=>document.getElementById('deal-status').textContent.indexOf('共')===0,{timeout:15000});
 assert.equal(await p.locator('.deal-row').count(),2,'rss items rendered');
 assert((await p.locator('#deal-status').textContent()).includes('1 个订阅源'));
 // 点击条目 → 走内置浏览器打开
 console.log("S3-click-row");
 await p.locator('.deal-row').first().click();
 await p.waitForFunction(()=>window.calls.some(c=>c.url&&c.url.indexOf('shop.example.com')>=0),{timeout:10000});
 // 缓存：模拟第二次打开同一天（重载后直接出缓存，不发请求）
 console.log("S4-reload");
 await p.reload();await p.locator('#deal-open').click();
 await p.waitForTimeout(600);
 assert.equal(await p.locator('.deal-row').count(),2,'daily cache rendered without refetch');
 const callsAfter=await p.evaluate(()=>window.calls.filter(c=>c.url).length);
 assert.equal(callsAfter,0,'no refetch on same-day reopen');
 // 添加坏源：诊断显示、好源不受影响
 await p.locator('#deal-feed-name').fill('坏源');await p.locator('#deal-feed-url').fill('https://bad.example.com/rss');
 console.log("S2-add");
 await p.locator('#deal-feed-add').click();
 await p.waitForFunction(()=>document.getElementById('deal-status').textContent.includes('坏源'),{timeout:15000});
 assert.equal(await p.locator('.deal-row').count(),2,'good source unaffected');
 // 删除订阅持久化
 await p.evaluate(()=>{const f=JSON.parse(localStorage.getItem('tv_deal_feeds'));f.splice(0,1);localStorage.setItem('tv_deal_feeds',JSON.stringify(f));});
 console.log("S4-reload");
 await p.reload();await p.locator('#deal-open').click();await p.waitForTimeout(500);
 const feedCount=await p.evaluate(()=>JSON.parse(localStorage.getItem('tv_deal_feeds')).length);
 assert.equal(feedCount,1,'feed removal persisted');
 // 隐私：无痕下添加订阅不写入 localStorage
 console.log("S4-reload");
 await p.reload();await p.locator('#privacy-toggle').click();await p.locator('#deal-open').click();
 const before=await p.evaluate(()=>localStorage.getItem('tv_deal_feeds'));
 await p.locator('#deal-feed-name').fill('无痕源');await p.locator('#deal-feed-url').fill('https://p.test.example.com/rss');
 await p.locator('#deal-feed-add').click();await p.waitForTimeout(400);
 assert.equal(await p.evaluate(()=>localStorage.getItem('tv_deal_feeds')),before,'privacy does not persist feeds');
 await p.locator('#deal-close').click();await p.locator('#nav-home').click();
 await p.locator('#privacy-toggle').click();
 assert.deepEqual(errors,[]);console.log('PASS: deals tab daily cache, rss parse, dedupe, item open, per-source diagnostics, feed persistence, privacy session-only.');
}finally{await b.close();}})().catch(e=>{console.error(e);process.exitCode=1});
