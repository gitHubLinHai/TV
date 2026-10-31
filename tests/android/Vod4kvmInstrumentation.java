package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.webkit.WebView;

public class Vod4kvmInstrumentation extends NetdiskInstrumentation {
 @Override void waitJS(WebView web,String expression,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if("true".equals(eval(web,expression)))return;Thread.sleep(200);}throw new AssertionError("4KVM timeout: "+eval(web,"document.getElementById('vod-status').textContent"));}
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity home=startActivitySync(i);WebView web=(WebView)field(home,"web");awaitUI(web,"v"+home.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();document.getElementById('vod-open').click();true");
  waitJS(web,"document.querySelectorAll('#vod-sources input').length>=2",10000);
  eval(web,"(function(){var b=document.querySelectorAll('#vod-sources input');if(b[0].checked)b[0].click();document.getElementById('vod-query').value='嫁金钗';document.getElementById('vod-search').dispatchEvent(new Event('submit',{cancelable:true}));return true;})()");
  waitJS(web,"Array.from(document.querySelectorAll('.vod-card')).some(x=>x.textContent.indexOf('嫁金钗')>=0)",40000);
  eval(web,"Array.from(document.querySelectorAll('.vod-card')).find(x=>x.textContent.indexOf('嫁金钗')>=0).click();true");
  waitJS(web,"document.querySelectorAll('#vod-episodes button').length>0",40000);
  String episodes=eval(web,"document.querySelectorAll('#vod-episodes button').length");
  ActivityMonitor monitor=addMonitor("com.seagull.tv.VideoActivity",null,false);
  eval(web,"document.querySelector('#vod-episodes button').click();true");
  Activity player=waitForMonitorWithTimeout(monitor,50000);require(player!=null,"4KVM native player did not open: "+eval(web,"document.getElementById('vod-status').textContent"));
  String media=player.getIntent().getStringExtra("url");require(media!=null&&media.startsWith("https://")&&media.contains("douyinbit"),"Unexpected 4KVM media host");
  Object video=field(player,"video");long[] progress={0,0};String[] failure={null};long until=System.currentTimeMillis()+45000;
  while(System.currentTimeMillis()<until){runOnMainSync(()->{try{Class<?> p=Class.forName("androidx.media3.common.Player");progress[0]=((Number)p.getMethod("getDuration").invoke(video)).longValue();progress[1]=((Number)p.getMethod("getCurrentPosition").invoke(video)).longValue();Object error=p.getMethod("getPlayerError").invoke(video);if(error!=null)failure[0]=error.toString();}catch(Exception e){failure[0]=e.toString();}});require(failure[0]==null,"4KVM HLS playback error: "+failure[0]);if(progress[0]>0&&progress[1]>1500)break;Thread.sleep(250);}
  require(progress[0]>0&&progress[1]>1500,"4KVM HLS did not advance");runOnMainSync(()->player.finish());Thread.sleep(500);
  require("true".equals(eval(web,"document.getElementById('vod-dialog').open&&!document.getElementById('vod-detail-view').hidden")),"Return lost 4KVM episode view");
  result.putString("stream","PASS: Android live 4KVM search/detail "+episodes+" episodes, isolated anonymous resolver, native HLS duration "+progress[0]+" ms, position "+progress[1]+" ms, returned to episode view.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
