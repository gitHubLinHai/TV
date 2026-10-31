const assert=require('node:assert/strict');
const {chromium}=require('playwright');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try {
  const page=await browser.newPage({viewport:{width:390,height:844}}); const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:8138/?tab=quark');
  await page.evaluate(()=>localStorage.setItem('tv_history',JSON.stringify([{q:'原有记录',mode:'quark'}])));await page.reload();
  await page.locator('#privacy-toggle').click();assert.equal(await page.locator('#recent-section').isVisible(),false);
  await page.route('**/api/netdisk',async route=>{
   const body=route.request().postDataJSON();assert.equal(body.args.private,true);
   if(body.action==='token'){await route.fulfill({json:{ok:true,data:{code:0,data:{stoken:'fixture'}}}});return;}
   if(body.action==='files'){await route.fulfill({json:{ok:true,data:{code:0,data:{list:[{fid:'folder',file_name:'目录',dir:true}]}}}});return;}
   await route.fulfill({json:{ok:true,data:{sources:[{name:'拒绝源',format:'hunhepan',data:{code:419,msg:'该关键词不提供结果'}},{name:'备用源',format:'pansearch',data:{props:{pageProps:{data:{data:[{pan:'quark',content:'名称：<b>测试资料</b>\nhttps://pan.quark.cn/s/test123',time:'2026-10-28'}]}}}}}]}}});
  });
  await page.locator('#query').fill('无痕关键词');await page.locator('#search-form button[type=submit]').click();await page.locator('.resource-card').waitFor();
  assert.equal(await page.locator('.resource-title').textContent(),'测试资料');assert((await page.locator('#source-diagnostics').textContent()).includes('该关键词不提供结果'));
  assert.equal(await page.evaluate(()=>localStorage.getItem('tv_history')),JSON.stringify([{q:'原有记录',mode:'quark'}]));
  await page.locator('#privacy-toggle').click();assert.equal(await page.locator('#query').inputValue(),'');assert.equal(await page.locator('#netdisk-panel').isVisible(),false);assert.equal(await page.locator('#netdisk-list').textContent(),'');
  assert.equal(await page.locator('.history-item').textContent(),'原有记录');
  await page.locator('#privacy-toggle').click();await page.locator('#mode-web').click();await page.locator('#query').fill('private-web');await page.locator('#search-form button[type=submit]').click();assert.equal(new URL(page.url()).hostname,'127.0.0.1');
  await page.reload();assert.equal(await page.locator('#privacy-toggle').getAttribute('aria-pressed'),'true');assert.equal(await page.locator('#query').inputValue(),'');
  assert.deepEqual(errors,[]);console.log('PASS: privacy history isolation, RPC private flag, PanSearch parsing, source rejection diagnostics, session cleanup, preview private navigation guard, privacy survives reload without query.');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
