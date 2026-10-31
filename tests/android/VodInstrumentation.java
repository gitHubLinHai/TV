package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.webkit.WebView;

public class VodInstrumentation extends NetdiskInstrumentation {
 @Override void waitJS(WebView web,String expression,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if("true".equals(eval(web,expression)))return;Thread.sleep(200);}throw new AssertionError("VOD timeout: "+eval(web,"document.getElementById('vod-status').textContent"));}
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity home=startActivitySync(i);WebView web=(WebView)field(home,"web");awaitUI(web,"v"+home.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();document.getElementById('vod-open').click();true");
  waitJS(web,"document.querySelectorAll('.vod-card').length>0",40000);
  eval(web,"document.getElementById('vod-query').value='嫁金钗';document.getElementById('vod-search').dispatchEvent(new Event('submit',{cancelable:true}));true");
  waitJS(web,"Array.from(document.querySelectorAll('.vod-card')).some(x=>x.textContent.indexOf('嫁金钗')>=0)",40000);
  eval(web,"Array.from(document.querySelectorAll('.vod-card')).find(x=>x.textContent.indexOf('嫁金钗')>=0).click();true");
  waitJS(web,"document.querySelectorAll('#vod-routes option').length>0",40000);
  // 多源合并下线路名随来源变化：优先超清线路2，否则取任一直连线路（4KVM 公开线路等）
  require("true".equals(eval(web,"(function(){var s=document.getElementById('vod-routes'),o=Array.from(s.options).find(x=>x.textContent.indexOf('超清线路2 ')===0)||Array.from(s.options).find(x=>x.textContent.indexOf('线路')>=0||x.textContent.indexOf('公开')>=0);if(!o)return false;s.value=o.value;s.dispatchEvent(new Event('change'));return true;})()")),"Known direct route missing");
  ActivityMonitor monitor=addMonitor("com.seagull.tv.VideoActivity",null,false);
  eval(web,"document.querySelector('#vod-episodes button').click();true");Activity player=waitForMonitorWithTimeout(monitor,35000);require(player!=null,"Native VOD player did not open: "+eval(web,"document.getElementById('vod-status').textContent"));
  require(player.getIntent().getBooleanExtra("vodSource",false),"Source referer flag missing");
  Object video=field(player,"video");long[] progress={0,0};String[] failure={null};long until=System.currentTimeMillis()+45000;
  while(System.currentTimeMillis()<until){runOnMainSync(()->{try{Class<?> p=Class.forName("androidx.media3.common.Player");progress[0]=((Number)p.getMethod("getDuration").invoke(video)).longValue();progress[1]=((Number)p.getMethod("getCurrentPosition").invoke(video)).longValue();Object error=p.getMethod("getPlayerError").invoke(video);if(error!=null){Throwable cause=(Throwable)error;String host="";while(cause.getCause()!=null){try{Object spec=cause.getClass().getField("dataSpec").get(cause);android.net.Uri uri=(android.net.Uri)spec.getClass().getField("uri").get(spec);host=uri.getHost();}catch(Exception ignored){}cause=cause.getCause();}failure[0]=host+": "+cause.toString();}}catch(Exception e){failure[0]=e.toString();}});require(failure[0]==null,"Real HLS playback error: "+failure[0]);if(progress[0]>0&&progress[1]>1500)break;Thread.sleep(250);}
  require(progress[0]>0&&progress[1]>1500,"HLS did not advance");
  android.graphics.Bitmap screen=getUiAutomation().takeScreenshot();if(screen!=null)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(home.getExternalFilesDir(null),"vod-player.png"))){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
  runOnMainSync(()->player.finish());Thread.sleep(300);require("true".equals(eval(web,"document.getElementById('vod-dialog').open && !document.getElementById('vod-detail-view').hidden")),"Return lost episode view");
  eval(web,"document.getElementById('vod-back').click();true");require("true".equals(eval(web,"document.querySelectorAll('.vod-card').length>0")),"Back lost catalog");
  result.putString("stream","PASS: Android live 555 catalog/search/routes/episodes, native HLS duration "+progress[0]+" ms, position "+progress[1]+" ms, returned to episode view and original catalog.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
