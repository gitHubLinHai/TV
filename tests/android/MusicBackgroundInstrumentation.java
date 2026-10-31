package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.webkit.WebView;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 音乐后台播放真机验证：退到后台不暂停、前台服务在位；后台队列切歌原地换曲（不新开播放页）。 */
public class MusicBackgroundInstrumentation extends NetdiskInstrumentation {
 private void progress(String s){Bundle b=new Bundle();b.putString("step",s);sendStatus(2,b);}
 @Override void waitJS(WebView web,String expression,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if("true".equals(eval(web,expression)))return;Thread.sleep(200);}throw new AssertionError("bg timeout: "+eval(web,"document.getElementById('music-status').textContent"));}
 @Override public void onStart(){Bundle result=new Bundle();try{
  final List<Activity> players=new CopyOnWriteArrayList<>();
  ((Application)getTargetContext().getApplicationContext()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
   public void onActivityCreated(Activity a,Bundle b){if(a.getClass().getName().endsWith("VideoActivity"))players.add(a);}
   public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}
   public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
  });
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  final Activity home=startActivitySync(i);WebView web=(WebView)field(home,"web");awaitUI(web,"v"+home.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();document.getElementById('music-open').click();true");
  waitJS(web,"document.querySelectorAll('.song-row').length>0",45000);
  eval(web,"document.getElementById('music-playall').click();true");
  progress("queue-start-wait");
  long end=System.currentTimeMillis()+30000;
  while(System.currentTimeMillis()<end&&players.isEmpty())Thread.sleep(300);
  require(!players.isEmpty(),"queue did not start a player");
  final Activity first=players.get(0);
  long until=System.currentTimeMillis()+45000;final long[] started={0,0};
  while(System.currentTimeMillis()<until){
   runOnMainSync(()->{try{Object v=field(first,"video");Class<?> cl=Class.forName("androidx.media3.common.Player");started[0]=((Number)cl.getMethod("getDuration").invoke(v)).longValue();started[1]=((Number)cl.getMethod("getCurrentPosition").invoke(v)).longValue();}catch(Throwable e){throw new RuntimeException(e);}});
   if(started[0]>0&&started[1]>1200)break;
   Thread.sleep(300);
  }
  require(started[0]>0&&started[1]>1200,"playback never advanced");
  progress("advance-ok");
  // ===== 退到后台：音乐必须继续播放 =====
  runOnMainSync(()->home.moveTaskToBack(true));
  progress("backgrounded");
  Thread.sleep(4000);
  final long[] bg1={0,0};final boolean[] playing={false};
  runOnMainSync(()->{try{Object v=field(first,"video");Class<?> cl=Class.forName("androidx.media3.common.Player");bg1[0]=((Number)cl.getMethod("getCurrentPosition").invoke(v)).longValue();playing[0]=(Boolean)cl.getMethod("isPlaying").invoke(v);}catch(Throwable e){throw new RuntimeException(e);}});
  Thread.sleep(3000);
  final long[] bg2={0};
  runOnMainSync(()->{try{Object v=field(first,"video");bg2[0]=((Number)Class.forName("androidx.media3.common.Player").getMethod("getCurrentPosition").invoke(v)).longValue();}catch(Throwable e){throw new RuntimeException(e);}});
  require(playing[0],"music paused in background");
  require(bg2[0]-bg1[0]>=600,"music stalled in background: "+bg1[0]+" -> "+bg2[0]);
  // 前台保活服务在位
  ActivityManager am=(ActivityManager)getTargetContext().getSystemService(Context.ACTIVITY_SERVICE);
  boolean service=false;for(ActivityManager.RunningServiceInfo rs:am.getRunningServices(100))if(rs.service.getClassName().endsWith("MusicKeepService")){service=true;break;}
  require(service,"MusicKeepService not running");
  progress("bg-keep-ok");
  // ===== 后台队列切歌：歌曲自然播完后原地换曲（同页继续、不新开播放页）=====
  runOnMainSync(()->{try{Object v=field(first,"video");Class<?> cl=Class.forName("androidx.media3.common.Player");long d=((Number)cl.getMethod("getDuration").invoke(v)).longValue();cl.getMethod("seekTo",long.class).invoke(v,Math.max(0,d-800));}catch(Throwable e){throw new RuntimeException(e);}});
  final String[] keyBefore={""};
  runOnMainSync(()->{try{keyBefore[0]=(String)field(first,"key");}catch(Throwable e){throw new RuntimeException(e);}});
  end=System.currentTimeMillis()+60000;boolean swapped=false;final long[] after={0,0};final String[] keyAfter={""};
  progress("swap-wait");
  while(System.currentTimeMillis()<end){
   final Activity cur=players.get(players.size()-1);
   runOnMainSync(()->{try{Object v=field(cur,"video");Class<?> cl=Class.forName("androidx.media3.common.Player");after[0]=((Number)cl.getMethod("getCurrentPosition").invoke(v)).longValue();after[1]=((Number)cl.getMethod("getDuration").invoke(v)).longValue();keyAfter[0]=(String)field(cur,"key");}catch(Throwable e){throw new RuntimeException(e);}});
   if(players.size()==1&&keyAfter[0].length()>0&&!keyAfter[0].equals(keyBefore[0])&&after[1]>0&&after[0]>1200){swapped=true;break;}
   if(players.size()>1)break; // 兜底暴露：新开播放页=后台启动路径，直接判失败
   Thread.sleep(400);
  }
  require(players.size()==1,"background switch opened a new player page ("+players.size()+") — used background activity start");
  require(swapped,"background queue swap did not start next song (key "+keyBefore[0]+" -> "+keyAfter[0]+", pos "+after[0]+")");
  // 回前台并收尾（MainActivity 已存在，startActivitySync 会永远等不到 onCreate，用异步拉起）
  Intent up=new Intent();up.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");up.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  getTargetContext().startActivity(up);Thread.sleep(1500);
  runOnMainSync(()->players.get(players.size()-1).finish());Thread.sleep(400);
  result.putString("stream","PASS: background music keeps playing (+"+(bg2[0]-bg1[0])+" ms) with keepalive service, queue switched in place ("+keyBefore[0]+" -> "+keyAfter[0]+") without a new player page.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
