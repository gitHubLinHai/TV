const assert=require('node:assert/strict');const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
 const p=await b.newPage({viewport:{width:390,height:844}});await p.goto('http://127.0.0.1:8138/?tab=quark');
 for(const q of ['凡人修仙传','Blender']){
  await p.locator('#query').fill(q);await p.evaluate(q=>Netdisk.search(q,true),q);
  console.log(q+': '+await p.locator('#netdisk-status').textContent());
  const count=await p.locator('.share-validity.valid').count();
  if(!count && await p.locator('#netdisk-validate-more').isVisible())await p.locator('#netdisk-validate-more').click();
  await p.waitForFunction(()=>!document.querySelector('#netdisk-status').textContent.includes('正在校验'),null,{timeout:90000});
  const valid=p.locator('.resource-card').filter({has:p.locator('.share-validity.valid')});
  assert(await valid.count()>0,q+': no share directory passed validation');
  await valid.first().locator('.resource-title').click();
  await p.waitForFunction(()=>document.querySelectorAll('.file-row').length>0,null,{timeout:30000});
  console.log('Opened verified share directory: '+await p.locator('#netdisk-title').textContent()+' / '+await p.locator('#netdisk-status').textContent());
  assert.equal(new URL(p.url()).hostname,'127.0.0.1');
  await p.screenshot({path:'E:/myUnity/TV/work/verified-'+(q==='Blender'?'blender':'fanren')+'.png'});
 }
 console.log('PASS: both live searches produced shares whose Quark token and root directory were actually readable; clicked returned search results and read file lists.');
}finally{await b.close();}})().catch(e=>{console.error(e);process.exitCode=1});
