const assert=require('node:assert/strict');const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
 const p=await b.newPage({viewport:{width:390,height:844}});const errors=[];p.on('pageerror',e=>errors.push(e.message));
 await p.addInitScript(()=>{
  if(!window.localStorage.getItem('tv_vod_sources'))window.localStorage.setItem('tv_vod_sources',JSON.stringify([
   {name:'555',host:'www.a-test.com',on:true},
   {name:'666',host:'www.b-test.com',on:true},
   {name:'4KVM',host:'www.4kvm.net',adapter:'4kvm-page',on:false,builtin:true}]));
  window.calls=[];window.__mark='init';
   window.addEventListener('unhandledrejection',function(e){window.__lastRej=((window.__mark||'?')+': '+(e.reason&&e.reason.stack||e.reason));});
  const mk=title=>'<div class="module-card-item module-item"><div class="module-card-item-class">连续剧</div><a href="/voddetail/'+title.id+'.html" class="module-card-item-poster"><div class="module-item-note">全12集</div><div class="module-item-pic"><img data-original="/c.webp" alt="'+title.name+'"></div></a><div class="module-card-item-title"><a href="/voddetail/'+title.id+'.html"><strong>'+title.name+'</strong></a></div></div><a class="page-next" title="下一页" href="/vodshow/2--------2---.html">下一页</a>';
  const listA='<a class="module-poster-item" href="/voddetail/111.html" title="共享剧A"></a><a class="module-poster-item" href="/voddetail/112.html" title="独有剧B"></a><a class="module-poster-item" href="/voddetail/113.html" title="共享剧C"></a>';
  window.TVBridge={pageReady(){},startCheck(){},networkType:()=>'other',versionInfo:()=>'{"name":"1.0.19"}',
   vodRequest(id,host,path){calls.push({host:host,path:path});
    if(host==='www.b-test.com'&&path.includes('vodshow/2--hits')){setTimeout(()=>__vodReply(id,{ok:false,error:'HTTP 403'}),10);return;}
    const html=host==='www.a-test.com'?listA:mk({id:222,name:'共享剧A'})+mk({id:223,name:'共享剧C'})+mk({id:224,name:'独有剧D'});
    setTimeout(()=>__vodReply(id,{ok:true,data:{path,html}}),10);},
   playVodVideo:(url,title,key,host)=>calls.push({play:url,host2:host}),
   vodApiRequest(id,base,query){calls.push({api:base,query:query});
    let d;
    if(query==='ac=list')d={code:1,class:[{type_id:1,type_name:'电视剧'},{type_id:2,type_name:'电影'}]};
    else if(query.includes('ids='))d={code:1,list:[{vod_id:1,vod_name:'接口剧A',vod_pic:'',vod_remarks:'全12集',vod_play_from:'线路1$$$线路2',vod_play_url:'第01集$https://cdn.example.com/ep1.m3u8#第02集$https://cdn.example.com/ep2.mp4$$$网页线路$https://cdn.example.com/play?1'}]};
    else{const pg=(query.match(/pg=(\d+)/)||[,'1'])[1];
      d={code:1,page:Number(pg),pagecount:2,list:[{vod_id:1,vod_name:'共享剧A',vod_pic:'https://cdn.example.com/a.jpg',vod_remarks:'全12集'},{vod_id:2,vod_name:'接口剧A',vod_pic:'https://cdn.example.com/b.jpg',vod_remarks:'更新至03集'}]};}
    setTimeout(()=>__vodReply(id,{ok:true,data:{json:JSON.stringify(d)}}),10);}};});
 await p.goto('http://127.0.0.1:8138/?v=119');
 
 await p.locator('#vod-open').click();
 await p.waitForFunction(()=>document.getElementById('vod-status').textContent.startsWith('已读取'),{timeout:30000});
 // 合并去重：共享剧A/C 只出现一次；A源3部 + B源新增独有剧D = 4 部
 assert.equal(await p.locator('.vod-card').count(),4,'merged and deduped');
 const status1=await p.locator('#vod-status').textContent();
 assert(status1.includes('2源'),'both sources counted: '+status1);
 // 卡片标注来源
 const notes=await p.evaluate(()=>Array.from(document.querySelectorAll('.vod-card small')).map(x=>x.textContent));
 assert(notes.some(x=>x.startsWith('666')),'source badge shown');
 // 单源失败诊断：切最热播使 B 源 403，A 源正常返回
 
 await p.locator('#vod-sorts button').filter({hasText:'最热播'}).click();
 await p.waitForFunction(()=>window.calls.some(c=>c.path.includes('--hits')),{timeout:10000});
 await p.waitForFunction(()=>document.getElementById('vod-status').textContent.startsWith('已读取'),{timeout:30000});
 assert((await p.locator('#vod-status').textContent()).includes('666'),'failed source diagnosed');
 // 勾选持久化：去掉 666 后刷新页面仍保持
 await p.evaluate(()=>{const s=JSON.parse(localStorage.getItem('tv_vod_sources'));s[1].on=false;localStorage.setItem('tv_vod_sources',JSON.stringify(s));});
 
 await p.reload();await p.locator('#vod-open').click();
 await p.waitForFunction(()=>document.getElementById('vod-status').textContent.startsWith('已读取'),{timeout:30000});
 const states=await p.evaluate(()=>Array.from(document.querySelectorAll('#vod-sources input')).map(x=>x.checked));
 assert.deepEqual(states,[true,false,false],'checkbox state persisted after reload');
 assert.equal(await p.locator('.vod-card').count(),3,'only enabled source fetched');
 // ===== 接口源：合并去重→详情直链→播放 referer 留空 =====
 await p.evaluate(()=>{const s=JSON.parse(localStorage.getItem('tv_vod_sources'));s.push({type:'api',name:'接口源',api:'https://api.test.com/api.php/provide/vod/',on:true});localStorage.setItem('tv_vod_sources',JSON.stringify(s));});
 await p.reload();await p.locator('#vod-open').click();
 await p.waitForFunction(()=>document.getElementById('vod-status').textContent.startsWith('已读取'),{timeout:30000});
 assert.equal(await p.locator('.vod-card').count(),4,'api item added, shared item deduped');
 await p.reload();await p.locator('#vod-open').click();
 await p.waitForFunction(()=>document.getElementById('vod-status').textContent.startsWith('已读取'),{timeout:30000});
 assert.equal(await p.locator('.vod-card').count(),4,'api item added, shared item deduped');
 assert((await p.locator('#vod-status').textContent()).includes('2源'));
 await p.locator('.vod-card').filter({hasText:'接口剧A'}).first().click();
 await p.waitForFunction(()=>document.querySelectorAll('#vod-routes option').length>=1,{timeout:30000});
 assert.equal(await p.locator('#vod-routes option').count(),1,'webpage-only route dropped');
 await p.locator('#vod-episodes button').nth(1).click();
 await p.waitForFunction(()=>window.calls.some(c=>c.play&&c.play.includes('ep2.mp4')),{timeout:30000});
 const apiPlay=await p.evaluate(()=>window.calls.filter(c=>c.play).find(c=>c.play.includes('ep2.mp4')));
 assert.equal(apiPlay.host2,'','api direct play passes empty referer');
 if(errors.length)console.log('PAGE ERRORS:',errors);
 assert.deepEqual(errors,[]);
 console.log('PASS: multi-source merge with title dedupe, source badges, per-source failure diagnostics, sort URL transform, checkbox persistence, api source merge/direct-play.');
}finally{await b.close();}})().catch(e=>{console.error(e);process.exitCode=1});
