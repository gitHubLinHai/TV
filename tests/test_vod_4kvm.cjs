const assert=require('node:assert/strict');const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});try{
 const p=await b.newPage();await p.goto('about:blank');
 await p.addScriptTag({path:require('node:path').join(process.cwd(),'web/js/vod-source-4kvm.js')});
 const list=`<div class="movie-card"><a href="/play/abc123def" data-vod="vod1"><img data-src="https://img.example/a.jpg" alt="测试剧"><span class="absolute bottom-0">更新至2集</span><h3>测试剧</h3></a></div>`;
 const filterPage=list+`<a href="?page=2" class="px-4">下一页</a><div class="text-center">第 1 页 / 共 55 页，共 1632 个结果</div>`;
 const filterPage2=list+`<a href="?classify=2&page=3">下一页</a><div>第 2 页 / 共 55 页</div>`;
 const lastPage=list+`<div>第 55 页 / 共 55 页</div>`;
 const detail=`<title>测试剧 - 第1集 -4k影视</title><meta name="description" content="简介"><h1>测试剧</h1><a href="/play/abc123def" data-line="1" data-episode="1" dataid="10">1</a><a href="/play/abc123deg" data-line="1" data-episode="2" dataid="11">2</a>`;
 const out=await p.evaluate(({list,filterPage,filterPage2,lastPage,detail})=>({
   list:Vod4KVM.parseList(list),
   filter:Vod4KVM.parseList(filterPage,'/filter?classify=2'),
   filter2:Vod4KVM.parseList(filterPage2,'/filter?classify=2&page=2'),
   last:Vod4KVM.parseList(lastPage,'/filter?classify=2&page=55'),
   detail:Vod4KVM.parseDetail(detail,'/play/abc123def'),
   tv:Vod4KVM.categoryPath('电视剧'),movie:Vod4KVM.categoryPath('电影'),anime:Vod4KVM.categoryPath('动漫'),variety:Vod4KVM.categoryPath('综艺纪录'),drama:Vod4KVM.categoryPath('短剧'),
   sorted:Vod4KVM.applySort('/filter?classify=2','hits'),
   sortedScore:Vod4KVM.applySort('/filter?classify=2&sort_by=hits&order=desc','score'),
   unsorted:Vod4KVM.applySort('/filter?classify=2&sort_by=hits&order=desc',''),
   untouch:Vod4KVM.applySort('/tv','hits'),
   def:Vod4KVM.defaultPath,search:Vod4KVM.searchPath('嫁金钗'),
   okPath:(()=>{try{return Vod4KVM.sitePath('/filter?classify=2&page=2');}catch(e){return 'throw:'+e.message;}})(),
   badPath:(()=>{try{return Vod4KVM.sitePath('/filter?evil=1&page=2');}catch(e){return 'throw:'+e.message;}})(),
   badPath2:(()=>{try{return Vod4KVM.sitePath('/page/sahewngji');}catch(e){return 'throw:'+e.message;}})()
 }),{list,filterPage,filterPage2,lastPage,detail});
 // 列表：卡片解析
 assert.equal(out.list.items.length,1);assert.equal(out.list.items[0].title,'测试剧');
 // 翻页：下一页按当前地址解析出完整路径；共 N 页作为 total
 assert.equal(out.filter.items.length,1);assert.equal(out.filter.next,'/filter?classify=2&page=2');assert.equal(out.filter.total,55);
 assert.equal(out.filter2.next,'/filter?classify=2&page=3');
 assert.equal(out.last.next,'');assert.equal(out.last.total,55);
 // 分类映射走 /filter?classify=N；站点没有短剧分类
 assert.equal(out.tv,'/filter?classify=2');assert.equal(out.movie,'/filter?classify=1');assert.equal(out.anime,'/filter?classify=3');assert.equal(out.variety,'/filter?classify=4');assert.equal(out.drama,null);
 // 排序只作用于 /filter：hits/score 追加、综合移除
 assert.equal(out.sorted,'/filter?classify=2&sort_by=hits&order=desc');
 assert.equal(out.sortedScore,'/filter?classify=2&sort_by=score&order=desc');
 assert.equal(out.unsorted,'/filter?classify=2');
 assert.equal(out.untouch,'/tv');
 // 默认路径与地址白名单
 assert.equal(out.def,'/filter?sort_by=update_time&order=desc');
 assert.equal(out.okPath,'/filter?classify=2&page=2');
 assert.match(out.badPath,/不受支持/);assert.match(out.badPath2,/不受支持/);
 // 详情/搜索不变
 assert.equal(out.detail.routes.length,1);assert.equal(out.detail.routes[0].episodes.length,2);assert.equal(out.detail.routes[0].episodes[0].resolve4kvm,true);
 assert.equal(out.search,'/search?q=%E5%AB%81%E9%87%91%E9%92%97');
 // 封面：解包百度图片代理（代理对轮换图床只回 1x1 空白 GIF），普通 https 地址原样保留
 assert.equal((await p.evaluate(()=>Vod4KVM.resource('https://gimg0.baidu.com/gimg/app=2001&n=0&g=0n&fmt=jpeg&src=4kvm.staticimgjs.org/uploads/a.jpg'))),'https://4kvm.staticimgjs.org/uploads/a.jpg');
 assert.equal((await p.evaluate(()=>Vod4KVM.resource('https://gimg0.baidu.com/gimg/app=2001&fmt=jpeg'))),'');
 assert.equal((await p.evaluate(()=>Vod4KVM.resource('https://img.example.org/pic.jpg'))),'https://img.example.org/pic.jpg');
 console.log('PASS: 4KVM filter paging (next/total), classify categories, sort, whitelist, detail/search adapter.');
}finally{await b.close();}})().catch(e=>{console.error(e);process.exit(1)});
