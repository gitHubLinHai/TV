package com.seagull.tv.tests;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;

/** 临时压力：音乐自动连播多轮 + 前后台切换，检测进程崩溃；用后即删。 */
public class MusicStressInstrumentation extends NetdiskInstrumentation {
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity home=startActivitySync(i);WebView web=(WebView)field(home,"web");awaitUI(web,"v"+home.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();document.getElementById('music-open').click();true");
  long end=System.currentTimeMillis()+45000;
  while(System.currentTimeMillis()<end&&!"true".equals(eval(web,"document.querySelectorAll('.song-row').length>0")))Thread.sleep(300);
  Class<?> p=Class.forName("androidx.media3.common.Player");
  // 全部播放（armed 队列，播完自动推进）；打不开再逐首尝试
  Activity player=null;
  for(int attempt=0;attempt<3&&player==null;attempt++){
   ActivityMonitor monitor=addMonitor("com.seagull.tv.VideoActivity",null,false);
   eval(web,"document.getElementById('music-playall').click();true");
   player=waitForMonitorWithTimeout(monitor,40000);
   if(player==null){ActivityMonitor m2=addMonitor("com.seagull.tv.VideoActivity",null,false);
    eval(web,"(function(){var rows=document.querySelectorAll('.song-row');if(rows.length>"+attempt+")rows["+attempt+"].click();return true;})()");
    player=waitForMonitorWithTimeout(m2,20000);}
  }
  require(player!=null,"music player page never appeared; status="+eval(web,"document.getElementById('music-status').textContent"));
  int swaps=0;
  for(int round=0;round<6;round++){
   final Activity pa=player;final Object v=field(pa,"video");
   if(v==null)throw new AssertionError("video released at round "+round);
   // 先等本曲真正推进（媒体 READY）再跳到结尾，否则时长未就绪 seek 无效
   long ready=System.currentTimeMillis()+40000;
   while(System.currentTimeMillis()<ready){
    final long[] pp={0};final long[] dd={0};
    runOnMainSync(()->{try{pp[0]=((Number)p.getMethod("getCurrentPosition").invoke(v)).longValue();dd[0]=((Number)p.getMethod("getDuration").invoke(v)).longValue();}catch(Throwable e){}});
    if(pp[0]>1500&&dd[0]>5000)break;
    Thread.sleep(500);
   }
   final String keyBefore=String.valueOf(field(pa,"key"));
   final long[] dur={0};
   runOnMainSync(()->{try{dur[0]=((Number)p.getMethod("getDuration").invoke(v)).longValue();p.getMethod("seekTo",long.class).invoke(v,Math.max(0,dur[0]-1200));}catch(Throwable e){throw new RuntimeException(e);}});
   if(round%2==1)home.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   long until=System.currentTimeMillis()+40000;boolean advanced=false;
   while(System.currentTimeMillis()<until){
    Thread.sleep(800);
    if(pa.isFinishing())throw new AssertionError("player finished early at round "+round);
    final long[] pos={-1};final String[] keyNow={null};
    runOnMainSync(()->{try{Object vv=field(pa,"video");if(vv==null)return;pos[0]=((Number)p.getMethod("getCurrentPosition").invoke(vv)).longValue();keyNow[0]=String.valueOf(field(pa,"key"));}catch(Throwable e){}});
    if(keyNow[0]!=null&&!keyNow[0].equals(keyBefore)&&pos[0]>1200){advanced=true;break;} // 原地换曲成功
   }
   require(advanced,"round "+round+" did not advance (key="+String.valueOf(field(player,"key"))+", home="+(round%2==1)+")");
   swaps++;
  }
  Thread.sleep(1500);
  require(!player.isFinishing(),"player finishing after stress");
  result.putString("stream","PASS: music stress "+swaps+" in-place auto-advances (mixed background), process alive.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
