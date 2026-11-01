package com.seagull.tv.tests;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;

/** 临时压力：音乐自动连播多轮 + 前后台切换，检测进程崩溃；用后即删。 */
public class MusicStressInstrumentation extends NetdiskInstrumentation {
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  final java.util.List<Activity> players=new java.util.concurrent.CopyOnWriteArrayList<>();
  ((android.app.Application)getTargetContext().getApplicationContext()).registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks(){
   public void onActivityCreated(Activity a,Bundle b){if(a.getClass().getName().endsWith("VideoActivity"))players.add(a);}
   public void onActivityDestroyed(Activity a){players.remove(a);}
   public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}
   public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}
  });
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
   final int genBefore=intField(pa,"swapGen");
   final long[] dur={0};
   runOnMainSync(()->{try{dur[0]=((Number)p.getMethod("getDuration").invoke(v)).longValue();p.getMethod("seekTo",long.class).invoke(v,Math.max(0,dur[0]-1200));}catch(Throwable e){throw new RuntimeException(e);}});
   if(round%2==1)home.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   long until=System.currentTimeMillis()+40000;boolean advanced=false;
   while(System.currentTimeMillis()<until){
    Thread.sleep(800);
    if(pa.isFinishing())throw new AssertionError("player finished early at round "+round);
    final long[] pos={-1};final String[] keyNow={null};final int[] genNow={-1};
    runOnMainSync(()->{try{Object vv=field(pa,"video");if(vv==null)return;pos[0]=((Number)p.getMethod("getCurrentPosition").invoke(vv)).longValue();keyNow[0]=String.valueOf(field(pa,"key"));genNow[0]=intField(pa,"swapGen");}catch(Throwable e){}});
    // 推进判定：swapGen 递增（原地换曲发生过；同曲换试听源时 key 不变，不能拿 key 判定）
    if(genNow[0]>genBefore&&pos[0]>1200){advanced=true;break;}
   }
   require(advanced,"round "+round+" did not advance (key="+String.valueOf(field(player,"key"))+", home="+(round%2==1)
    +", players="+players.size()+", musicCurrent="+musicCurrentDesc()
    +", paFinishing="+pa.isFinishing()+", paResumed="+boolField(pa,"resumed")+", paSwapGen="+intField(pa,"swapGen")
    +", musicStatus="+eval(web,"document.getElementById('music-status').textContent")+")");
   swaps++;
  }
  Thread.sleep(1500);
  require(!player.isFinishing(),"player finishing after stress");
  result.putString("stream","PASS: music stress "+swaps+" in-place auto-advances (mixed background), process alive.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
 static long posAt(Class<?> p,Activity a){try{java.lang.reflect.Field f=a.getClass().getDeclaredField("video");f.setAccessible(true);Object v=f.get(a);if(v==null)return -2;return ((Number)p.getMethod("getCurrentPosition").invoke(v)).longValue();}catch(Throwable e){return -3;}}
 static long durAt(Class<?> p,Activity a){try{java.lang.reflect.Field f=a.getClass().getDeclaredField("video");f.setAccessible(true);Object v=f.get(a);if(v==null)return -2;return ((Number)p.getMethod("getDuration").invoke(v)).longValue();}catch(Throwable e){return -3;}}
 static String musicCurrentDesc(){try{java.lang.reflect.Field f=Class.forName("com.seagull.tv.VideoActivity").getDeclaredField("musicCurrent");f.setAccessible(true);Object v=f.get(null);return v==null?"null":(v.getClass().getSimpleName()+"@"+Integer.toHexString(System.identityHashCode(v))+" key="+String.valueOf(f2(v,"key")));}catch(Throwable e){return "err:"+e;}}
 static Object f2(Object o,String name){try{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Throwable e){return "?";}}
 static boolean boolField(Object o,String name){Object v=f2(o,name);return v instanceof Boolean&&((Boolean)v).booleanValue();}
 static int intField(Object o,String name){Object v=f2(o,name);return v instanceof Integer?((Integer)v).intValue():-1;}
}
