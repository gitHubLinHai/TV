package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.webkit.WebView;

/** 短剧分类的真实片单、选集和原生播放验证；内容随来源更新，不固定剧集。 */
public class VodDuanjuInstrumentation extends NetdiskInstrumentation {
 @Override void waitJS(WebView web,String expression,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if("true".equals(eval(web,expression)))return;Thread.sleep(200);}throw new AssertionError("短剧 timeout: "+eval(web,"document.getElementById('vod-status').textContent"));}
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity home=startActivitySync(i);WebView web=(WebView)field(home,"web");awaitUI(web,"v"+home.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();document.getElementById('vod-open').click();true");
  waitJS(web,"Array.from(document.querySelectorAll('#vod-categories button')).some(function(x){return x.textContent==='短剧';})",20000);
  eval(web,"Array.from(document.querySelectorAll('#vod-categories button')).find(function(x){return x.textContent==='短剧';}).click();true");
  waitJS(web,"document.querySelectorAll('.vod-card').length>0",40000);
  int cards=Integer.parseInt(eval(web,"String(document.querySelectorAll('.vod-card').length)").replace("\"",""));
  eval(web,"document.querySelector('.vod-card').click();true");
  waitJS(web,"document.querySelectorAll('#vod-routes option').length>0",40000);
  ActivityMonitor monitor=addMonitor("com.seagull.tv.VideoActivity",null,false);
  eval(web,"document.querySelector('#vod-episodes button').click();true");Activity player=waitForMonitorWithTimeout(monitor,35000);require(player!=null,"短剧 native player did not open: "+eval(web,"document.getElementById('vod-status').textContent"));
  require(player.getIntent().getBooleanExtra("vodSource",false),"Source referer flag missing");
  Object video=field(player,"video");long[] progress={0,0};String[] failure={null};long until=System.currentTimeMillis()+45000;
  while(System.currentTimeMillis()<until){runOnMainSync(()->{try{Class<?> p=Class.forName("androidx.media3.common.Player");progress[0]=((Number)p.getMethod("getDuration").invoke(video)).longValue();progress[1]=((Number)p.getMethod("getCurrentPosition").invoke(video)).longValue();Object error=p.getMethod("getPlayerError").invoke(video);if(error!=null){Throwable cause=(Throwable)error;String host="";while(cause.getCause()!=null){try{Object spec=cause.getClass().getField("dataSpec").get(cause);android.net.Uri uri=(android.net.Uri)spec.getClass().getField("uri").get(spec);host=uri.getHost();}catch(Exception ignored){}cause=cause.getCause();}failure[0]=host+": "+cause.toString();}}catch(Exception e){failure[0]=e.toString();}});require(failure[0]==null,"短剧 playback error: "+failure[0]);if(progress[0]>0&&progress[1]>1500)break;Thread.sleep(250);}
  require(progress[0]>0&&progress[1]>1500,"短剧 HLS did not advance");
  runOnMainSync(()->player.finish());Thread.sleep(300);require("true".equals(eval(web,"document.getElementById('vod-dialog').open && !document.getElementById('vod-detail-view').hidden")),"Return lost episode view");
  eval(web,"document.getElementById('vod-back').click();true");require("true".equals(eval(web,"document.querySelectorAll('.vod-card').length>0")),"Back lost catalog");
  result.putString("stream","PASS: Android 短剧 category "+cards+" cards, native HLS duration "+progress[0]+" ms, position "+progress[1]+" ms, returned to episodes and catalog.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
