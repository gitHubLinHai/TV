const assert=require('node:assert/strict');const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
 const p=await b.newPage({viewport:{width:390,height:844}});const errors=[];p.on('pageerror',e=>errors.push(e.message));
 await p.addInitScript(()=>{window.calls=[];window.failResolve=false;
  window.TVBridge={pageReady(){},startCheck(){},networkType:()=>'other',versionInfo:()=>'{"name":"1.0.28"}',
   musicRequest(id,q){calls.push({action:'music',q});
    if(q.includes('type=lrc')){setTimeout(()=>__musicReply(id,{ok:true,data:{lrc:'[00:01.00]歌词一句\n[00:05.00]歌词两句\n[00:09.00]歌词三句'}}),20);return;}
    if(q.startsWith('kg=1')){
      if(q.includes('type=url')){const h=(q.match(/hash=([0-9a-f]{32})/)||[,"0"])[1];const ok=h==="aabbccdd11223344556677889900aabb";const resp={"url": "https://kgd.example.com/x.mp3", "fileName": "\u9177\u72d7\u6d4b\u8bd5\u66f2", "timeLength": 186};const respbad={"url": "", "timeLength": 0};setTimeout(()=>__musicReply(id,{ok:true,data:{json:JSON.stringify(ok?resp:respbad)}}),20);return;}
      const name=decodeURIComponent((q.match(/name=([^&]+)/)||[,''])[1]);
      const info=name.includes('酷狗词')?[{hash:'AABBCCDD11223344556677889900AABB',songname:'酷狗结果一',singername:'酷狗歌手',duration:186,pay_type:0}]:[];
      setTimeout(()=>__musicReply(id,{ok:true,data:{json:JSON.stringify({status:1,data:{info}})}}),20);return;}
    if(q.startsWith('gds=1')){
      if(q.includes('type=url')){const sid=(q.match(/id=(\d+)/)||[,'0'])[1];if(sid==='104'){setTimeout(()=>__musicReply(id,{ok:true,data:{json:JSON.stringify({url:'',br:0})}}),20);return;}setTimeout(()=>__musicReply(id,{ok:true,data:{json:JSON.stringify({url:'https://gdx.example.com/'+sid+'.mp3',br:320,size:8000000})}}),20);return;}
      setTimeout(()=>__musicReply(id,{ok:true,data:{json:JSON.stringify([{id:'201',name:'GD结果一',artist:'GD歌手'}])}}),20);return;}
    if(q.includes('type=url')){const sid=q.split('id=')[1];const bad=sid==='102'||sid==='104';setTimeout(()=>__musicReply(id,bad?{ok:false,error:'该曲目暂不可播放（可能需要会员或已下架）'}:{ok:true,data:{url:'https://m802.music.126.net/a/'+sid+'.mp3'}}),20);return;}
    const json=JSON.stringify([{name:'热歌一',artist:'歌手A',url:'https://api.injahow.cn/meting/?server=netease&type=url&id=101'},{name:'热歌二',artist:'歌手B',url:'https://api.injahow.cn/meting/?server=netease&type=url&id=102'},{name:'热歌三',artist:'歌手C',url:'https://api.injahow.cn/meting/?server=netease&type=url&id=103'},{name:'热歌四',artist:'歌手D',url:'https://api.injahow.cn/meting/?server=netease&type=url&id=104'}]);
    setTimeout(()=>__musicReply(id,{ok:true,data:{json}}),20);},
   playMusic:(url,title,key,lyrics)=>calls.push({action:'play',url,title,key,lyrics})};});
 await p.goto('http://127.0.0.1:8138/');await p.locator('#music-open').click();
 await p.waitForFunction(()=>document.querySelectorAll('.song-row').length>=2);
 assert.equal(await p.evaluate(()=>window.calls[0].q),'server=netease&type=playlist&id=3778678','default rank not loaded');
 await p.locator('.song-row').first().click();
 await p.waitForFunction(()=>window.calls.some(c=>c.action==='play'));
 const play=await p.evaluate(()=>window.calls.find(c=>c.action==='play'));
 assert.equal(play.url,'https://m802.music.126.net/a/101.mp3');assert.equal(play.key,'music:101');assert.equal(play.title,'歌手A · 热歌一');
 assert(play.lyrics&&play.lyrics.startsWith('[00:01.00]'),'lyrics attached to native music play');
 // 无法播放的曲目（手动点击，网易/GD 失败、酷狗无同名完整版）：明确提示，不调用播放器。
 await p.locator('.song-row').nth(3).click();
 await p.waitForFunction(()=>document.getElementById('music-status').textContent.includes('不可播放'));
 assert.equal(await p.evaluate(()=>window.calls.filter(c=>c.action==='play').length),1,'failed resolve still played');
 // 队列播放：全部播放→第一首；102 Meting 失败但 GD 兜底成功；104 全链失败跳过并结束。
 await p.locator('#music-playall').click();
 await p.waitForFunction(()=>window.calls.filter(c=>c.action==='play').length===2);
 assert((await p.evaluate(()=>window.calls.filter(c=>c.action==='play').at(-1).title)).includes('热歌一'),'queue starts from first song');
 await p.evaluate(()=>window.__videoEnded('music:101'));
 await p.waitForFunction(()=>window.calls.filter(c=>c.action==='play').length===3);
 const after101=await p.evaluate(()=>window.calls.filter(c=>c.action==='play').at(-1));
 assert(after101.title.includes('热歌二')&&after101.url.includes('gdx.example.com'),'meting fail falls back to GD in queue');
 await p.evaluate(()=>window.__videoEnded('music:102'));
 await p.waitForFunction(()=>window.calls.filter(c=>c.action==='play').length===4);
 assert((await p.evaluate(()=>window.calls.filter(c=>c.action==='play').at(-1).title)).includes('热歌三'),'queue advances to third song');
 await p.evaluate(()=>window.__videoEnded('music:103'));
 await p.waitForFunction(()=>document.getElementById('music-status').textContent.includes('队列播放结束'),{timeout:10000});
 const playedAll=await p.evaluate(()=>window.calls.filter(c=>c.action==='play').map(c=>c.title));
 assert(!playedAll.some(x=>x.includes('热歌四')),'chain-exhausted song skipped');
 // 列表循环：重新从第一首开始，102 仍走 GD，103 播完回绕到 101。
 await p.locator('#music-loop').click();await p.locator('#music-playall').click();
 await p.waitForFunction(()=>window.calls.filter(c=>c.action==='play').length===5);
 assert((await p.evaluate(()=>window.calls.filter(c=>c.action==='play').at(-1).title)).includes('热歌一'),'loop restarts from first song');
 await p.evaluate(()=>window.__videoEnded('music:101'));
 await p.waitForFunction(()=>window.calls.filter(c=>c.action==='play').length===6);
 await p.evaluate(()=>window.__videoEnded('music:102'));
 await p.waitForFunction(()=>window.calls.filter(c=>c.action==='play').length===7);
 assert((await p.evaluate(()=>window.calls.filter(c=>c.action==='play').at(-1).title)).includes('热歌三'),'loop second pass uses GD fallback');
 await p.evaluate(()=>window.__videoEnded('music:103'));
 await p.waitForFunction(()=>window.calls.filter(c=>c.action==='play').length===8);
 assert((await p.evaluate(()=>window.calls.filter(c=>c.action==='play').at(-1).title)).includes('热歌一'),'loop wraps around to first song');
 await p.locator('#music-playall').click();
 await p.waitForFunction(()=>document.getElementById('music-status').textContent.includes('队列播放已停止'));
 assert.equal(await p.evaluate(()=>window.calls.filter(c=>c.action==='play').length),8,'queue stopped after toggle');
 // 手动点播 102：Meting 失败改用 GD 成功播放，携带歌词。
 await p.locator('.song-row').nth(1).click();
 await p.waitForFunction(()=>{const plays=window.calls.filter(c=>c.action==='play');return plays.length>=9&&plays.at(-1).key==='music:102'&&plays.at(-1).url.indexOf('gdx.example.com')>=0;},{timeout:15000});
 // 试听片段：原生回调 30 秒时长 → 自动换下一个音源（GD→酷狗）完整版；全部只有试听时明确提示。
 await p.locator('.song-row').first().click();
 await p.waitForFunction(()=>{const plays=window.calls.filter(c=>c.action==='play');return plays.length>=10&&plays.at(-1).key==='music:101'&&plays.at(-1).url.indexOf('m802')>=0;},{timeout:15000});
 await p.evaluate(()=>window.__musicDuration('music:101',30000));
 await p.waitForFunction(()=>document.getElementById('music-status').textContent.includes('试听'),{timeout:5000});
 await p.waitForFunction(()=>{const plays=window.calls.filter(c=>c.action==='play');return plays.length>=11&&plays.at(-1).url.indexOf('gdx.example.com')>=0;},{timeout:15000});
 await p.evaluate(()=>window.__musicDuration('music:101',30000));
 await p.waitForFunction(()=>document.getElementById('music-status').textContent.includes('不可播放'),{timeout:15000});
 // 酷狗第二音源：切换→搜索（新 v3 字段 hash/duration/pay_type）→点播（酷狗取链+歌词换算）。
 await p.locator('#music-src button').filter({hasText:'酷狗'}).click();
 await p.locator('#music-query').fill('酷狗词');await p.locator('#music-search button[type=submit]').click();
 await p.waitForFunction(()=>window.calls.some(c=>c.q&&c.q.startsWith('kg=1&type=search')),{timeout:10000});
 await p.waitForFunction(()=>document.getElementById('music-status').textContent.indexOf('点击播放')>=0,{timeout:10000});
 assert((await p.locator('.song-row strong').first().textContent())==='酷狗结果一','kugou search results shown (v3 hash field)');
 await p.locator('.song-row').first().click();
 await p.waitForFunction(()=>{const plays=window.calls.filter(c=>c.action==='play');return plays.length>=12&&plays.at(-1).url.indexOf('kgd.example.com')>=0&&plays.at(-1).lyrics&&plays.at(-1).lyrics.startsWith('[00:01');},{timeout:15000});
 assert(await p.evaluate(()=>window.calls.some(c=>c.q&&c.q.startsWith('kg=1&type=url'))),'kg url requested');
 // 切回网易音源
 await p.locator('#music-src button').filter({hasText:'网易'}).click();
 // 切换榜单与搜索请求格式。
 await p.locator('#music-ranks button').filter({hasText:'飙升榜'}).click();
 await p.waitForFunction(()=>window.calls.some(c=>c.action==='music'&&c.q.includes('id=19723756')));
 await p.locator('#music-query').fill('周杰伦');await p.locator('#music-search button[type=submit]').click();
 await p.waitForFunction(()=>window.calls.some(c=>c.action==='music'&&c.q.startsWith('gds=1')&&c.q.includes(encodeURIComponent('周杰伦'))));
 await p.waitForFunction(()=>document.querySelectorAll('.song-row').length>=1);
 assert((await p.locator('.song-row strong').first().textContent())==='GD结果一','search powered by GD');
 await p.locator('#music-close').click();assert.equal(await p.locator('#music-audio').isHidden(),true);
 assert.deepEqual(errors,[]);console.log('PASS: music ranks, resolve chain (meting→GD→kugou) with lyrics, native playMusic+lyrics, 30s-trial auto source switch, kg v3 field fix, queue semantics.');
}finally{await b.close();}})().catch(e=>{console.error(e);process.exitCode=1});
