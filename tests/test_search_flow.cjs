const assert=require('node:assert/strict');const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
 const page=await b.newPage({viewport:{width:390,height:844}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  window.calls=[];window.logged=false;window.slowFinished=false;
  window.TVBridge={pageReady(){},startCheck(){},versionInfo:()=>'{"name":"1.0.6"}',networkType:()=> 'other',openUrl:url=>calls.push({action:'open',url}),playVideo:(url)=>calls.push({action:'play',url}),
   netdiskRequest(id,text){const r=JSON.parse(text);calls.push(r);const reply=data=>__netdiskReply(id,{ok:true,data});
    if(r.action==='account'){reply({loggedIn:logged});return;}
    if(r.action==='search'){
     if(r.args.source==='pansou'){window.releaseSlowSource=()=>{slowFinished=true;reply({sources:[{name:'slow',format:'pansou',data:{code:0,data:{merged_by_type:{quark:[]}}}}]});};return;}
     if(r.args.source==='qkpanso'){reply({sources:[{name:'offline',error:'offline'}]});return;}
     reply({sources:[{name:'fast',format:'pansou',data:{code:0,data:{merged_by_type:{quark:Array.from({length:20},(_,n)=>({url:'https://pan.quark.cn/s/share'+n,note:'share '+n}))}}}}]});return;
    }
    if(r.action==='token')setTimeout(()=>reply({code:0,data:{stoken:r.args.pwd_id}}),20);
    if(r.action==='files')setTimeout(()=>reply({code:0,data:{list:r.args.pdir_fid==='0'?[{fid:'folder',file_name:'目录',dir:true}]:[{fid:'video',file_name:'sample.mp4',share_fid_token:'token',dir:false}]}}),20);
    if(r.action==='sharePreview')reply(logged?{code:0,data:{play_info:{url:'https://video-preview.quark.cn/preview.mp4'}}}:{code:31001,status:401,message:'此分享需要登录'});
   }};
  document.addEventListener('click',e=>{if(e.target.closest('.resource-title'))window.savedResultScroll=scrollY;},true);
 });
 await page.goto('http://127.0.0.1:8138/?tab=quark');await page.locator('#query').fill('flow');const started=Date.now();await page.locator('#search-form button[type=submit]').click();
 await page.locator('.share-validity.valid').first().waitFor();assert.equal(await page.evaluate(()=>slowFinished),false,'fast result waited for slow provider');console.log('Fixture first verified result ms:',Date.now()-started);
 await page.evaluate(()=>releaseSlowSource());await page.waitForFunction(()=>document.querySelectorAll('.share-validity.valid').length===12);
 const readsBefore=await page.evaluate(()=>calls.filter(x=>x.action==='token'||x.action==='files').length);
 await page.locator('.resource-title').nth(6).click();await page.locator('.file-row').waitFor();assert.equal(await page.evaluate(()=>calls.filter(x=>x.action==='token'||x.action==='files').length),readsBefore,'opening just-validated share repeated directory requests');
 await page.locator('.file-row').click();await page.locator('.file-row').filter({hasText:'sample.mp4'}).waitFor();
 await page.locator('#netdisk-close').click();await page.locator('.resource-card').first().waitFor();await page.waitForTimeout(100);
 assert.equal(await page.locator('.resource-card').count(),12);assert.equal(await page.locator('#query').inputValue(),'flow');assert(Math.abs(await page.evaluate(()=>scrollY-savedResultScroll))<3,'result scroll position was not restored');
 await page.locator('#account-login').click();assert((await page.evaluate(()=>calls)).some(x=>x.action==='open'&&x.url==='https://pan.quark.cn/'));
 assert((await page.locator('#account-status').textContent()).includes('未登录'));
 await page.locator('.resource-title').first().click();await page.locator('.file-row').click();await page.locator('.file-row').click();
 await page.waitForFunction(()=>document.querySelector('#media-status').textContent.includes('此分享需要登录'));
 await page.locator('#media-login').click();await page.evaluate(()=>{logged=true;return Netdisk.refreshAccount();});
 await page.waitForFunction(()=>calls.some(x=>x.action==='play'));assert.equal(await page.evaluate(()=>calls.filter(x=>x.action==='save').length),0,'login automatically transferred a file');
 assert((await page.locator('#account-status').textContent()).includes('已登录'));assert(await page.locator('#media-dialog').evaluate(x=>x.open));
 assert.deepEqual(errors,[]);console.log('PASS: progressive providers, root-directory reuse, one-click return from nested directory with restored scroll, visible account entry/status, login-return preview retry without automatic cloud save.');
}finally{await b.close();}})().catch(e=>{console.error(e);process.exitCode=1});
