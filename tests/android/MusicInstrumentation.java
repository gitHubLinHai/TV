package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.webkit.WebView;

/** 音乐榜单真实验证：榜单加载、点歌解析直链、原生播放器播放音频。 */
public class MusicInstrumentation extends NetdiskInstrumentation {
 @Override void waitJS(WebView web,String expression,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if("true".equals(eval(web,expression)))return;Thread.sleep(200);}throw new AssertionError("music timeout: "+eval(web,"document.getElementById('music-status').textContent"));}
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity home=startActivitySync(i);WebView web=(WebView)field(home,"web");awaitUI(web,"v"+home.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();document.getElementById('music-open').click();true");
  waitJS(web,"document.querySelectorAll('.song-row').length>0",45000);
  Activity player=null;String lastStatus="";
  for(int attempt=0;attempt<6&&player==null;attempt++){
   ActivityMonitor monitor=addMonitor("com.seagull.tv.VideoActivity",null,false);
   final int index=attempt;
   eval(web,"(function(){var rows=document.querySelectorAll('.song-row');if(rows.length>"+index+")rows["+index+"].click();return true;})()");
   player=waitForMonitorWithTimeout(monitor,30000);
   if(player==null){lastStatus=eval(web,"document.getElementById('music-status').textContent");require(lastStatus.contains("暂不可播放")||lastStatus.contains("失败")||lastStatus.contains("超时"),"unexpected music status: "+lastStatus);}
  }
  require(player!=null,"no chart song resolved to a playable address; last status: "+lastStatus);
  require(player.getIntent().getBooleanExtra("publicSource",false),"public source flag missing");
  Object video=field(player,"video");long[] progress={0,0};String[] failure={null};long until=System.currentTimeMillis()+45000;
  while(System.currentTimeMillis()<until){runOnMainSync(()->{try{Class<?> p=Class.forName("androidx.media3.common.Player");progress[0]=((Number)p.getMethod("getDuration").invoke(video)).longValue();progress[1]=((Number)p.getMethod("getCurrentPosition").invoke(video)).longValue();Object error=p.getMethod("getPlayerError").invoke(video);if(error!=null)failure[0]=String.valueOf(error);}catch(Exception e){failure[0]=e.toString();}});require(failure[0]==null,"music playback error: "+failure[0]);if(progress[0]>0&&progress[1]>1500)break;Thread.sleep(250);}
  require(progress[0]>0&&progress[1]>1500,"music did not advance (duration "+progress[0]+", position "+progress[1]+")");
  final Activity done=player;runOnMainSync(()->done.finish());Thread.sleep(400);
  waitJS(web,"document.querySelectorAll('.song-row').length>0",10000);
  result.putString("stream","PASS: Android music chart loaded, song resolved and native audio played "+progress[0]+" ms, position "+progress[1]+" ms, list intact after return.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
