const assert=require('node:assert/strict');
const {chromium}=require('playwright');
(async()=>{
 const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try {
  const page=await b.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{
   window.calls=[];window.activeChecks=0;window.maxChecks=0;
   window.TVBridge={pageReady(){},startCheck(){},versionInfo:()=>'{"name":"1.0.5"}',networkType:()=> 'other',
    netdiskRequest(id,text){
     const r=JSON.parse(text);calls.push(r);let data,ok=true;
     if(r.action==='search'){
      if(r.args.q==='latepass'){
       const row={url:'https://pan.quark.cn/s/late'+(r.args.source==='pansou'?'?pwd=abcd':''),note:'late password'};
       setTimeout(()=>__netdiskReply(id,{ok:true,data:{sources:[{name:r.args.source,format:'pansou',data:{code:0,data:{merged_by_type:{quark:r.args.source==='qkpanso'?[]:[row]}}}}]}}),r.args.source==='pansou'?20:1);return;
      }
      const ids=r.args.q==='batch'?Array.from({length:15},(_,n)=>'good'+n):r.args.q==='empty'?[]:['good','dead','locked','offline','nodir'];
      data={code:0,data:{merged_by_type:{quark:ids.map(n=>({url:'https://pan.quark.cn/s/'+n,note:n}))}}};
     } else if(r.action==='token') {
      if(r.args.pwd_id==='late'&&r.args.passcode!=='abcd')data={code:41008,status:400,message:'提取码错误'};
      else if(r.args.pwd_id==='dead')data={code:41012,status:404,message:'好友已取消了分享'};
      else if(r.args.pwd_id==='locked')data={code:41008,status:400,message:'请输入提取码'};
      else if(r.args.pwd_id==='offline'){ok=false;data=null;}
      else data={code:0,data:{stoken:r.args.pwd_id}};
     } else if(r.action==='files')data=r.args.stoken==='nodir'?{code:41011,status:404,message:'分享地址已失效'}:{code:0,data:{list:[{fid:'folder',file_name:'目录',dir:true}]}};
     if(r.action!=='search'){activeChecks++;maxChecks=Math.max(maxChecks,activeChecks);}
     setTimeout(()=>{if(r.action!=='search')activeChecks--;__netdiskReply(id,{ok,data,error:'网络超时'});},r.action==='search'?5:50);
    }};
  });
  await page.goto('http://127.0.0.1:8138/?tab=quark');
  async function search(q){await page.locator('#query').fill(q);await page.locator('#search-form button[type=submit]').click();}
  await search('mixed');await page.waitForFunction(()=>document.querySelector('#netdisk-status').textContent.includes('本次校验完成'));
  assert.equal(await page.locator('.share-validity.valid').count(),1);assert.equal(await page.locator('.resource-card').count(),2);
  assert((await page.locator('#netdisk-status').textContent()).includes('已失效 2 条'));
  await page.locator('#show-unverified').check();assert.equal(await page.locator('.resource-card').count(),5);
  assert.equal(await page.locator('.share-validity.unknown').count(),1);assert.equal(await page.locator('.share-validity.locked').count(),1);
  const dead=page.locator('.resource-card').filter({has:page.locator('.share-validity.invalid')});assert.equal(await dead.count(),2);assert.equal(await dead.first().locator('.resource-title').isDisabled(),true);
  const preserved=await page.evaluate(()=>Netdisk.normalise({sources:[{format:'pansou',name:'one',data:{code:0,data:{merged_by_type:{quark:[{url:'https://pan.quark.cn/s/same',note:'same'}]}}}},{format:'pansou',name:'two',data:{code:0,data:{merged_by_type:{quark:[{url:'https://pan.quark.cn/s/same?pwd=abcd',note:'same'}]}}}}]}));
  assert.equal(preserved.length,1);assert.equal(preserved[0].password,'abcd');assert(preserved[0].url.endsWith('?pwd=abcd'));
  await search('batch');await page.waitForFunction(()=>document.querySelectorAll('.share-validity.valid').length===12);assert.equal(await page.locator('#netdisk-validate-more').isVisible(),true);
  await page.locator('#netdisk-validate-more').click();await page.waitForFunction(()=>document.querySelectorAll('.share-validity.valid').length===15);
  assert((await page.evaluate(()=>maxChecks))<=3,'validation concurrency exceeds three');
  await search('batch');await page.locator('.resource-title').first().waitFor();await page.locator('.resource-title').first().click();await page.locator('.file-row').waitFor();await page.waitForTimeout(300);assert.equal(await page.locator('.file-row').count(),1,'old validation overwrote opened directory');
  await search('mixed');await page.waitForTimeout(20);await search('empty');await page.waitForTimeout(400);assert.equal(await page.locator('.resource-card').count(),0,'stale checks repopulated results');
  await search('latepass');await page.locator('.share-validity.valid').waitFor();assert.deepEqual(await page.evaluate(()=>calls.filter(x=>x.action==='token'&&x.args.pwd_id==='late').map(x=>x.args.passcode)),['','abcd'],'late extraction code was not applied during checking');
  assert.deepEqual(errors,[]);console.log('PASS: token and directory validation, canceled/expired filtering, password vs timeout classification, three-worker limit, 12-item batches, extraction-code preservation, stale checks and directory navigation isolation.');
 }finally{await b.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
