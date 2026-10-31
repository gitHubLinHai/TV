const assert=require('node:assert/strict');const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});try{
 const p=await b.newPage({viewport:{width:390,height:844}});const errors=[];p.on('pageerror',e=>errors.push(e.message));
 await p.goto('http://127.0.0.1:8138/?v=111');await p.locator('#music-open').click();
 await p.locator('.song-row').first().waitFor({timeout:45000});
 const count=await p.locator('.song-row').count();const status1=await p.locator('#music-status').textContent();
 console.log('hot chart:',count,'|',status1);assert(count>0);
 await p.locator('.song-row').first().click();
 await p.waitForFunction(()=>document.getElementById('music-audio').getAttribute('src'),null,{timeout:45000});
 const src=await p.locator('#music-audio').getAttribute('src');
 console.log('resolved:',src.slice(0,90));
 assert(src.startsWith('https://'));assert(/\.music\.126\.net$/.test(new URL(src).hostname));
 await p.locator('#music-ranks button').filter({hasText:'新歌榜'}).click();
 await p.waitForFunction(()=>document.getElementById('music-status').textContent.includes('新歌榜')&&document.querySelectorAll('.song-row').length>0,{timeout:45000});
 await p.screenshot({path:'work/v111-music.png'});
 assert.deepEqual(errors,[]);console.log('PASS: live music hot chart '+count+' songs, direct HTTPS audio resolution and rank switching.');
}finally{await b.close();}})().catch(e=>{console.error(e);process.exitCode=1});
