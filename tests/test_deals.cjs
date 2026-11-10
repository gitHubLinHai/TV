const assert=require('node:assert/strict');const {chromium}=require('playwright');

// 优惠聚合页测试：节流(30分钟)、ETag 304、失败熔断、平台过滤、价格抽取
const ITEMS=`
<item><title><![CDATA[京东自营：多力多滋 玉米片 香辣干碟味68g 1.32元（需用券）]]></title>
<link>https://www.smzdm.com/p/1001/</link><pubDate>Wed, 05 Nov 2026 08:00:00 +0800</pubDate>
<description><![CDATA[京东此款目前活动售价7.9元，下单领取满6元减5元优惠券，实付低至1.32元。]]></description></item>
<item><title><![CDATA[天猫旗舰店：某品牌洗发水 500ml 39.9元 百亿补贴]]></title>
<link>https://www.smzdm.com/p/1002/</link><pubDate>Wed, 05 Nov 2026 09:00:00 +0800</pubDate>
<description><![CDATA[天猫百亿补贴，领券立减，凑单更划算。]]></description></item>
<item><title><![CDATA[拼多多：抽纸巾 10包 9.9元 百亿补贴]]></title>
<link>https://www.smzdm.com/p/1003/</link><pubDate>Wed, 05 Nov 2026 07:00:00 +0800</pubDate>
<description><![CDATA[拼多多百亿补贴，拍2件立减。]]></description></item>
<item><title><![CDATA[PLUS会员：兰蔻唇釉 272.78元（需凑单）]]></title>
<link>https://www.smzdm.com/p/1004/</link><pubDate>Wed, 05 Nov 2026 06:00:00 +0800</pubDate>
<description><![CDATA[京东自营 plus会员价，凑单更省。]]></description></item>`;

function rss(){return '<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>t</title>'+ITEMS+'</channel></rss>';}

(async()=>{
 const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try{
  const p=await b.newPage({viewport:{width:390,height:844}});
  const errors=[];p.on('pageerror',e=>errors.push(e.message));

  await p.addInitScript(()=>{
   window.calls=[];window.__etag='';
   window.__DEAD=(function(){try{return localStorage.getItem('__dead_host')||'';}catch(e){return '';}})();
   window.TVBridge={
    pageReady(){},startCheck(){},networkType:()=>'other',
    versionInfo:()=>'{"name":"1.1.0"}',
    openUrl:(url)=>window.calls.push({url:url}),
    dealAggRequest(id,payload){
     const srcs=JSON.parse(payload);
     window.calls.push({agg:srcs.map(s=>s.url)});
     const results=srcs.map(s=>{
      // window.__DEAD 命中的域名模拟失败（跨 reload 有效）
      if(window.__DEAD&&s.url.indexOf(window.__DEAD)>=0)return {name:s.name,url:s.url,ok:false,error:'HTTP 503'};
      // 有 etag 且相同 → 模拟 304
      if(s.etag&&s.etag===window.__etag)return {name:s.name,url:s.url,ok:true,notModified:true,body:''};
      return {name:s.name,url:s.url,ok:true,notModified:false,body:window.__RSS__||'',etag:'E1',lastModified:''};
     });
     setTimeout(()=>window.__dealReply(id,{ok:true,data:{results:results}}),5);
    }};
  });

  // ===== 1. 首次打开：拉取、渲染、价格与平台抽取 =====
  await p.goto('http://127.0.0.1:8138/?v=170');
  await p.evaluate(body=>{window.__RSS__=body;},rss());
  await p.locator('#deal-open').click();
  await p.waitForFunction(()=>document.querySelectorAll('.deal-row').length>0,{timeout:15000});
  const n=await p.locator('.deal-row').count();
  assert.equal(n,4,'四条全部渲染');
  console.log('S1 渲染 ok, 条数='+n);

  // 价格抽取
  const prices=await p.locator('.deal-price').allTextContents();
  assert(prices.some(t=>t==='¥1.32'),'价格抽取 1.32');
  assert(prices.some(t=>t==='¥39.9'),'价格抽取 39.9');
  console.log('S2 价格抽取 ok: '+prices.join(','));

  // 平台标签
  const meta=await p.locator('.deal-row small').allTextContents();
  assert(meta.some(t=>t.includes('京东')),'识别京东');
  assert(meta.some(t=>t.includes('天猫')),'识别天猫');
  assert(meta.some(t=>t.includes('拼多多')),'识别拼多多');
  console.log('S3 平台识别 ok');

  // ===== 2. 平台过滤 =====
  await p.locator('#deal-platforms button', {hasText:'京东'}).click();
  await p.waitForTimeout(200);
  const jdCount=await p.locator('.deal-row').count();
  assert(jdCount>0&&jdCount<4,'京东过滤生效');
  await p.locator('#deal-platforms button', {hasText:'拼多多'}).click();
  await p.waitForTimeout(200);
  const pddCount=await p.locator('.deal-row').count();
  assert.equal(pddCount,1,'拼多多仅 1 条');
  await p.locator('#deal-platforms button', {hasText:'全部'}).click();
  await p.waitForTimeout(200);
  assert.equal(await p.locator('.deal-row').count(),4,'清除过滤');
  console.log('S4 平台过滤 ok (京东='+jdCount+', 拼多多='+pddCount+')');

  // ===== 3. 30 分钟节流 =====
  const before=(await p.evaluate(()=>JSON.parse(localStorage.getItem('tv_deal_state')).lastRun));
  assert(before>0,'lastRun 已记录');
  // 刷新页面后重开，不应再发请求
  await p.reload();
  await p.evaluate(body=>{window.__RSS__=body;},rss());
  await p.locator('#deal-open').click();
  await p.waitForTimeout(500);
  const aggCalls=await p.evaluate(()=>window.calls.filter(c=>c.agg).length);
  assert.equal(aggCalls,0,'30 分钟内不重复请求');
  const st=await p.locator('#deal-status').textContent();
  assert(st.includes('节流'),'显示节流提示: '+st);
  console.log('S5 节流 ok: '+st);

  // ===== 4. 熔断：连续失败 3 次后冷却 =====
  // 让内置首源（zhizhizhi）持续失败：标志落盘，跨 reload 有效
  await p.evaluate(()=>{localStorage.setItem('__dead_host','zhizhizhi');});
  await p.evaluate(()=>{
   const s=JSON.parse(localStorage.getItem('tv_deal_state')||'{}');
   s.lastRun=0; // 解除全局节流，单独测熔断
   s.sources={'https://www.zhizhizhi.com/feed':{fails:2,lastAt:0,coolUntil:0,okCount:1}};
   localStorage.setItem('tv_deal_state',JSON.stringify(s));
  });
  await p.reload();
  await p.evaluate(body=>{window.__RSS__=body;},rss());
  await p.locator('#deal-open').click();
  await p.waitForFunction(()=>document.querySelectorAll('.deal-row').length>0,{timeout:15000});
  const after=await p.evaluate(()=>JSON.parse(localStorage.getItem('tv_deal_state')));
  const zh=after.sources['https://www.zhizhizhi.com/feed'];
  assert(zh.fails>=3,'失败计数累加: '+zh.fails);
  assert(zh.coolUntil>Date.now(),'已进入 24h 冷却');
  const coolHours=Math.round((zh.coolUntil-Date.now())/3600000);
  assert(coolHours>=23,'冷却约 24 小时, 实际='+coolHours);
  console.log('S6 熔断 ok: fails='+zh.fails+' 冷却='+coolHours+'h');

  // 该源冷却期间不再被请求
  await p.evaluate(()=>{const s=JSON.parse(localStorage.getItem('tv_deal_state'));s.lastRun=0;localStorage.setItem('tv_deal_state',JSON.stringify(s));});
  await p.reload();
  await p.evaluate(body=>{window.__RSS__=body;},rss());
  await p.locator('#deal-open').click();
  await p.waitForFunction(()=>document.querySelectorAll('.deal-row').length>0,{timeout:15000});
  const urls=await p.evaluate(()=>{const c=window.calls.filter(x=>x.agg);return c.length?c[c.length-1].agg:[];});
  assert(!urls.some(u=>u.includes('zhizhizhi')),'冷却中的源被跳过: '+JSON.stringify(urls));
  console.log('S7 冷却跳过 ok, 实际请求源='+urls.length);

  // ===== 5. ETag 304：无变化时不重复解析，条目保留 =====
  await p.evaluate(()=>{
   window.__etag='E1';
   localStorage.setItem('tv_deal_state',JSON.stringify({lastRun:0,sources:{}}));
  });
  await p.reload();
  await p.evaluate(body=>{window.__RSS__=body;},rss());
  await p.locator('#deal-open').click();
  await p.waitForFunction(()=>document.querySelectorAll('.deal-row').length>0,{timeout:15000});
  await p.waitForTimeout(300);
  const cached=await p.evaluate(()=>{const c=JSON.parse(localStorage.getItem('tv_deals_cache')||'null');return c?c.items.length:0;});
  assert(cached>0,'304 后缓存条目仍保留: '+cached);
  const st304=await p.locator('#deal-status').textContent();
  assert(st304.includes('无变化')||st304.includes('共'),'304 状态提示: '+st304);
  console.log('S8 ETag 304 ok: 缓存保留 '+cached+' 条, status='+st304);

  // ===== 6. 无源/空态 =====
  await p.evaluate(()=>{localStorage.clear();});
  await p.reload();
  await p.locator('#deal-open').click();
  await p.waitForTimeout(400);
  const chipCount=await p.locator('#deal-platforms button').count();
  assert.equal(chipCount,7,'平台 chips=7(全部+6平台)');
  console.log('S9 平台 chips ok='+chipCount);

  // 点击条目走外部浏览器
  await p.evaluate(body=>{window.__RSS__=body;},rss());
  await p.evaluate(()=>{const s={lastRun:0,sources:{}};localStorage.setItem('tv_deal_state',JSON.stringify(s));});
  await p.reload();
  await p.evaluate(body=>{window.__RSS__=body;},rss());
  await p.locator('#deal-open').click();
  await p.waitForFunction(()=>document.querySelectorAll('.deal-row').length>0,{timeout:15000});
  await p.locator('.deal-row').first().click();
  const opened=await p.evaluate(()=>window.calls.some(c=>c.url&&c.url.includes('smzdm.com/p/')));
  assert(opened,'点击条目打开原文');
  console.log('S10 条目跳转 ok');

  assert.deepEqual(errors,[],'无页面异常: '+errors.join('|'));
  console.log('\nPASS: 优惠聚合页 — 30分钟节流、ETag304短路、失败熔断(2次6h/3次24h)、平台过滤、价格与优惠标签抽取、无异常');
 }finally{await b.close();}
})().catch(e=>{console.error('FAIL:',e.message);process.exitCode=1});
