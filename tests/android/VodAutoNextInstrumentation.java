package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.webkit.WebView;

/** 原生播放器自然播完后的自动连播验证：seek 到片尾触发 ENDED，应自动打开下一集。 */
public class VodAutoNextInstrumentation extends NetdiskInstrumentation {
 @Override void waitJS(WebView web,String expression,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if("true".equals(eval(web,expression)))return;Thread.sleep(200);}throw new AssertionError("autonext timeout: "+eval(web,"document.getElementById('vod-status').textContent"));}
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity home=startActivitySync(i);WebView web=(WebView)field(home,"web");awaitUI(web,"v"+home.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();document.getElementById('vod-open').click();true");
  waitJS(web,"document.querySelectorAll('.vod-card').length>0",40000);
  eval(web,"document.getElementById('vod-query').value='嫁金钗';document.getElementById('vod-search').dispatchEvent(new Event('submit',{cancelable:true}));true");
  waitJS(web,"Array.from(document.querySelectorAll('.vod-card')).some(x=>x.textContent.indexOf('嫁金钗')>=0)",40000);
  eval(web,"Array.from(document.querySelectorAll('.vod-card')).find(x=>x.textContent.indexOf('嫁金钗')>=0).click();true");
  waitJS(web,"document.querySelectorAll('#vod-routes option').length>0",40000);
  require("true".equals(eval(web,"(function(){var s=document.getElementById('vod-routes'),o=Array.from(s.options).find(x=>x.textContent.indexOf('超清线路2 ')===0)||Array.from(s.options).find(x=>x.textContent.indexOf('线路')>=0||x.textContent.indexOf('公开')>=0);if(!o)return false;s.value=o.value;s.dispatchEvent(new Event('change'));return true;})()")),"Known direct route missing");
  ActivityMonitor first=addMonitor("com.seagull.tv.VideoActivity",null,false);
  eval(web,"document.querySelector('#vod-episodes button').click();true");
  Activity player=waitForMonitorWithTimeout(first,35000);require(player!=null,"episode 1 player did not open");
  String key1=String.valueOf(player.getIntent().getStringExtra("key"));
  Object video=field(player,"video");long[] duration={0};String[] failure={null};
  long until=System.currentTimeMillis()+40000;
  while(System.currentTimeMillis()<until){runOnMainSync(()->{try{Class<?> p=Class.forName("androidx.media3.common.Player");duration[0]=((Number)p.getMethod("getDuration").invoke(video)).longValue();Object error=p.getMethod("getPlayerError").invoke(video);if(error!=null)failure[0]=String.valueOf(error);}catch(Exception e){failure[0]=e.toString();}});require(failure[0]==null,"episode 1 error: "+failure[0]);if(duration[0]>0)break;Thread.sleep(250);}
  require(duration[0]>0,"episode 1 duration unknown");
  ActivityMonitor second=addMonitor("com.seagull.tv.VideoActivity",null,false);
  runOnMainSync(()->{try{Class.forName("androidx.media3.common.Player").getMethod("seekTo",long.class).invoke(video,Math.max(0,duration[0]-2000));}catch(Exception e){failure[0]=e.toString();}});
  require(failure[0]==null,"seek failed: "+failure[0]);
  Activity next=waitForMonitorWithTimeout(second,45000);require(next!=null,"auto next episode did not open: "+eval(web,"document.getElementById('vod-status').textContent"));
  require(next!=player,"auto next reused same activity");
  String key2=String.valueOf(next.getIntent().getStringExtra("key"));require(!key1.equals(key2),"auto next replayed same key: "+key1);
  Object video2=field(next,"video");long[] progress={0,0};String[] fail2={null};until=System.currentTimeMillis()+45000;
  while(System.currentTimeMillis()<until){runOnMainSync(()->{try{Class<?> p=Class.forName("androidx.media3.common.Player");progress[0]=((Number)p.getMethod("getDuration").invoke(video2)).longValue();progress[1]=((Number)p.getMethod("getCurrentPosition").invoke(video2)).longValue();Object error=p.getMethod("getPlayerError").invoke(video2);if(error!=null)fail2[0]=String.valueOf(error);}catch(Exception e){fail2[0]=e.toString();}});require(fail2[0]==null,"auto next playback error: "+fail2[0]);if(progress[0]>0&&progress[1]>1500)break;Thread.sleep(250);}
  require(progress[0]>0&&progress[1]>1500,"auto next did not advance");
  runOnMainSync(()->next.finish());Thread.sleep(400);
  result.putString("stream","PASS: Android auto-next: episode 1 ended naturally, episode 2 opened by itself ("+key2+"), duration "+progress[0]+" ms, position "+progress[1]+" ms.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
