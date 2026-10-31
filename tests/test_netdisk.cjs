const assert=require('node:assert/strict');const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({headless:true,args:['--mute-audio'],executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});try{
 const c=await b.newContext({viewport:{width:390,height:844}});const calls=[];let errorList=[];
 await c.addInitScript(()=>{
  window.testCalls=[];window.TVBridge={pageReady(){},versionInfo:()=>JSON.stringify({code:102,name:'1.0.2'}),startCheck(){},networkType:()=> 'other',openUrl:url=>testCalls.push({action:'open',url}),playVideo:(url,title,key)=>testCalls.push({action:'play',url,title,key}),
   netdiskRequest:(id,body)=>{const r=JSON.parse(body);testCalls.push(r);let d;
    if(r.action==='search'){
     if(r.args.q==='failed')d={sources:[{name:'a',error:'offline'},{name:'b',error:'offline'}]};
     else if(r.args.q==='empty')d={code:0,data:{merged_by_type:{quark:[]}}};
     else d={sources:[{name:'one',format:'pansou',data:{code:0,data:{merged_by_type:{quark:[{url:'https://pan.quark.cn/s/abc123',note:'<img src=x onerror=alert(1)>测试',source:'one'},{url:'https://evil.example/s/1',note:'bad'},{url:'https://pan.quark.cn.evil.example/s/1',note:'bad'}]}}}},{name:'two',format:'hunhepan',data:{code:200,data:{list:[{link:'https://pan.quark.cn/s/abc123',disk_name:'duplicate'},{link:'https://pan.quark.cn/s/def456',disk_name:'<em>第二个</em>',disk_pass:'abcd'}]}}},{name:'bad',error:'offline'}]};
    }else if(r.action==='token')d={code:0,data:{stoken:'guest-token'}};
    else if(r.action==='files')d={code:0,data:{list:r.args.pdir_fid==='0'?[{fid:'folder',file_name:'目录',dir:true}]:[{fid:'video',file_name:'01.mp4',share_fid_token:'file-token',size:1000,dir:false}]},metadata:{_total:1}};
    else if(r.action==='sharePreview')d={code:0,data:{play_info:{url:'https://video-preview-c-zb.drive.quark.cn/preview.mp4'}}};
    else if(r.action==='save')d={code:0,data:{task_id:'task'}};
    else if(r.action==='task')d={code:0,data:{save_as:{save_as_top_fids:['owned-video']}}};
    else if(r.action==='ownPlay')d={code:0,data:{video_list:[{resolution:'normal',video_info:{url:'https://video-play-c-zb.drive.quark.cn/low.m3u8'}},{resolution:'high',video_info:{url:'https://video-play-c-zb.drive.quark.cn/full.m3u8'}},{resolution:'4k',video_info:{url:'https://video-play-c-zb.drive.quark.cn/4k.m3u8'}}]}};
    else if(r.action==='ownDownload')d={code:0,data:[{download_url:'https://download.quark.cn/original.mp4'}]};
    else d={code:1,message:'unsupported'};
    const delay=r.action==='search'&&r.args.q==='slow'?250:1;setTimeout(()=>__netdiskReply(id,{ok:true,data:d}),delay);
   }};
 });
 const p=await c.newPage();p.on('pageerror',e=>errorList.push(e.message));await p.goto('http://127.0.0.1:8138/');await p.locator('#mode-quark').click();
 async function search(q){await p.locator('#query').fill(q);await p.locator('#search-form button[type=submit]').click();}
 await search('test');await p.waitForFunction(()=>document.querySelectorAll('.share-validity.valid').length===2);assert.equal(await p.locator('.resource-card').count(),2);assert.equal(await p.locator('#netdisk-list img').count(),0);assert((await p.locator('#netdisk-status').textContent()).includes('1 个暂不可用'));assert.equal(new URL(p.url()).hostname,'127.0.0.1');
 await p.locator('.resource-title').first().click();await p.locator('.file-row').waitFor();await p.locator('.file-row').click();await p.locator('.file-row').filter({hasText:'01.mp4'}).waitFor();await p.locator('.file-row').click();
 await p.waitForFunction(()=>testCalls.some(x=>x.action==='play'));assert.equal((await p.evaluate(()=>testCalls.filter(x=>x.action==='save'))).length,0,'never transfer automatically');assert(await p.locator('#media-dialog').evaluate(x=>x.open),'keep full-play controls after returning from native preview');
 await p.locator('#media-save').click();await p.waitForFunction(()=>testCalls.filter(x=>x.action==='play').length===2);const saved=await p.evaluate(()=>testCalls.find(x=>x.action==='save'));assert.deepEqual(saved.args.fid_list,['video']);
 assert.equal(await p.evaluate(()=>testCalls.filter(x=>x.action==='play').at(-1).url),'https://video-play-c-zb.drive.quark.cn/full.m3u8');
 await p.locator('#media-quality').selectOption('4k');await p.locator('#media-save').click();await p.waitForFunction(()=>testCalls.filter(x=>x.action==='play').length===3);assert.equal(await p.evaluate(()=>testCalls.filter(x=>x.action==='play').at(-1).url),'https://video-play-c-zb.drive.quark.cn/4k.m3u8');
 await p.locator('#media-quality').selectOption('original');await p.locator('#media-save').click();await p.waitForFunction(()=>testCalls.filter(x=>x.action==='play').length===4);assert.equal(await p.evaluate(()=>testCalls.filter(x=>x.action==='play').at(-1).url),'https://download.quark.cn/original.mp4');assert.equal(await p.evaluate(()=>testCalls.filter(x=>x.action==='save').length),1,'quality change transferred the same file again');
 await p.locator('#media-close').click();await p.locator('#folder-back').click();await p.waitForFunction(()=>document.querySelector('.file-row').textContent.includes('目录'));await p.locator('#folder-back').click();await p.locator('.resource-card').first().waitFor();
 await search('failed');await p.waitForFunction(()=>document.querySelector('#netdisk-status').classList.contains('error'));assert((await p.locator('#netdisk-status').textContent()).includes('本次未取得搜索结果'));
 await search('empty');await p.waitForFunction(()=>document.querySelector('#netdisk-status').textContent.includes('没有找到'));assert.equal(await p.locator('.resource-card').count(),0);
 await search('slow');await search('empty');await p.waitForTimeout(400);assert.equal(await p.locator('.resource-card').count(),0,'ignore stale response');
 assert.deepEqual(errorList,[]);console.log('PASS: aggregate dedupe, bad URL/XSS filtering, source failure vs empty, folder/back navigation, preview, explicit single-file save/task/full-play flow, no auto transfer, stale responses.');
}finally{await b.close();}})().catch(e=>{console.error(e);process.exitCode=1});
