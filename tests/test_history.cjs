const assert=require('node:assert/strict');const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
 const page=await b.newPage();await page.addInitScript(()=>{
  window.historyCalls=[];window.TVBridge={pageReady(){},startCheck(){},networkType:()=> 'other',versionInfo:()=>'{"name":"1.0.7"}',openUrl:url=>historyCalls.push({action:'open',url}),netdiskRequest(id,text){const r=JSON.parse(text);historyCalls.push(r);queueMicrotask(()=>__netdiskReply(id,{ok:true,data:r.action==='account'?{loggedIn:false}:{code:0,data:{merged_by_type:{quark:[]}}}}));}};
 });
 await page.goto('http://127.0.0.1:8138/');await page.evaluate(()=>localStorage.setItem('tv_history',JSON.stringify([{q:'同名记录',mode:'web'},{q:'同名记录',mode:'quark'},{q:' 同名记录 ',mode:'web'},{q:'__proto__',mode:'quark'},null,{q:42}])));await page.reload();
 assert.equal(await page.locator('.history-item').count(),2);assert.equal(await page.locator('#history-count').textContent(),'2');assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('tv_history')).length),2);
 await page.locator('#mode-quark').click();await page.locator('.history-item').filter({hasText:'同名记录'}).click();
 assert.equal(await page.locator('#query').inputValue(),'同名记录');assert.equal(await page.locator('#mode-quark').getAttribute('aria-selected'),'true');
 const queries=await page.evaluate(()=>historyCalls.filter(x=>x.action==='search'));assert.equal(queries.length,3);assert(queries.every(x=>x.args.q==='同名记录'));assert.equal(await page.locator('.history-item').count(),2);
 await page.locator('#mode-web').click();await page.locator('.history-item').filter({hasText:'同名记录'}).click();assert.equal(await page.locator('#mode-web').getAttribute('aria-selected'),'true');assert.equal(await page.evaluate(()=>historyCalls.filter(x=>x.action==='search').length),3);assert((await page.evaluate(()=>historyCalls.find(x=>x.action==='open').url)).includes(encodeURIComponent('同名记录')));
 await page.reload();assert.equal(await page.locator('.history-item').count(),2);console.log('PASS: existing duplicate history cleanup, trim/invalid handling, same keyword across modes stays unique, history click fills and searches the currently selected mode.');
}finally{await b.close();}})().catch(e=>{console.error(e);process.exitCode=1});
