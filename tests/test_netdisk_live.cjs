const assert=require('node:assert/strict');
const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--mute-audio']});try{
 const page=await b.newPage({viewport:{width:390,height:844}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:8138/');await page.locator('#mode-quark').click();await page.locator('#query').fill('凡人修仙传');await page.locator('#search-form button[type=submit]').click();
 await page.waitForFunction(()=>document.querySelectorAll('.resource-card').length>0||document.querySelector('#netdisk-status').classList.contains('error'),{timeout:40000});
 console.log(await page.locator('#netdisk-status').textContent());assert(await page.locator('.resource-card').count()>0);assert.equal(new URL(page.url()).hostname,'127.0.0.1');
 await page.locator('#query').fill('https://pan.quark.cn/s/98ef14cf3ef1');await page.locator('#search-form button[type=submit]').click();
 await page.waitForFunction(()=>document.querySelectorAll('.file-row').length>0&&document.querySelector('#netdisk-status').textContent.startsWith('已读取'));
 for(let n=0;n<4;n++){
  if(await page.locator('.file-row').filter({hasText:'.mp4'}).count()) break;
  const oldTitle=await page.locator('#netdisk-title').textContent();await page.locator('.file-row').first().click();
  await page.waitForFunction(old=>document.querySelector('#netdisk-title').textContent!==old&&document.querySelector('#netdisk-status').textContent.startsWith('已读取'),oldTitle,{timeout:30000});
 }
 const videoFile=page.locator('.file-row').filter({hasText:'.mp4'}).first();await videoFile.click();
 await page.locator('#preview-video').waitFor({state:'visible',timeout:30000});
 await page.waitForFunction(()=>document.querySelector('#preview-video').readyState>=2,null,{timeout:30000});
 console.log('preview duration:',await page.locator('#preview-video').evaluate(v=>v.duration));
 await page.locator('#preview-video').evaluate(v=>v.pause());
 assert.equal(new URL(page.url()).hostname,'127.0.0.1');
 await page.screenshot({path:'E:/myUnity/TV/work/netdisk-live.png',fullPage:false});assert.deepEqual(errors,[]);
 console.log('PASS: live search results, share directory, nested folders and playable share preview rendered locally without website navigation.');
}finally{await b.close();}})().catch(e=>{console.error(e);process.exitCode=1});
