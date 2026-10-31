package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.webkit.WebView;import android.widget.TextView;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 音乐歌词真机验证：点播后歌词解析进播放页、当前行居中显示；试听换源替换播放页后歌词仍在。 */
public class MusicLyricsInstrumentation extends NetdiskInstrumentation {
 @Override void waitJS(WebView web,String expression,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if("true".equals(eval(web,expression)))return;Thread.sleep(200);}throw new AssertionError("lyrics timeout: "+eval(web,"document.getElementById('music-status').textContent"));}
 @Override public void onStart(){Bundle result=new Bundle();try{
  // 全程记录 VideoActivity 实例（试听换源会 CLEAR_TOP 重建播放页，监视器有竞态，这里按创建顺序取最新）。
  final List<Activity> players=new CopyOnWriteArrayList<>();
  ((Application)getTargetContext().getApplicationContext()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
   public void onActivityCreated(Activity a,Bundle b){if(a.getClass().getName().endsWith("VideoActivity"))players.add(a);}
   public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}
   public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
  });
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity home=startActivitySync(i);WebView web=(WebView)field(home,"web");awaitUI(web,"v"+home.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();document.getElementById('music-open').click();true");
  waitJS(web,"document.querySelectorAll('.song-row').length>0",45000);
  // 逐首重试：点行→等播放页→等推进；25 秒不推进（缓冲死链/慢换源）就关页换下一首。
  long[] progress={0,0};Activity done=null;String lastStatus="";
  for(int attempt=0;attempt<5&&done==null;attempt++){
   final int index=attempt;
   eval(web,"(function(){var rows=document.querySelectorAll('.song-row');if(rows.length>"+index+")rows["+index+"].click();return true;})()");
   long end=System.currentTimeMillis()+30000;
   while(System.currentTimeMillis()<end&&players.size()<=attempt)Thread.sleep(300);
   if(players.size()<=attempt){lastStatus=eval(web,"document.getElementById('music-status').textContent");continue;}
   long until=System.currentTimeMillis()+25000;String[] failure={null};
   while(System.currentTimeMillis()<until){
    final Activity cur=players.get(players.size()-1);final long[] p={0,0};final boolean[] finishing={false};
    runOnMainSync(()->{try{Class<?> cl=Class.forName("androidx.media3.common.Player");Object v=field(cur,"video");p[0]=((Number)cl.getMethod("getDuration").invoke(v)).longValue();p[1]=((Number)cl.getMethod("getCurrentPosition").invoke(v)).longValue();Object error=cl.getMethod("getPlayerError").invoke(v);if(error!=null)failure[0]=String.valueOf(error);finishing[0]=cur.isFinishing();}catch(Throwable e){failure[0]=e.toString();}});
    if(failure[0]!=null)break;
    if(p[0]>0&&p[1]>1500&&!finishing[0]){progress[0]=p[0];progress[1]=p[1];done=cur;break;}
    Thread.sleep(300);
   }
   if(done!=null)break;
   runOnMainSync(()->players.get(players.size()-1).finish());Thread.sleep(600);
   lastStatus=eval(web,"document.getElementById('music-status').textContent");
  }
  require(done!=null,"no song advanced for lyrics check; last status: "+lastStatus);
  final Activity donePage=done;
  Thread.sleep(4500);
  final long[] lyricCount={-1};final String[] cur={""};
  runOnMainSync(()->{try{
   long[] times=(long[])field(donePage,"lyricTimes");
   lyricCount[0]=times==null?-1:times.length;
   TextView[] rows=(TextView[])field(donePage,"lyricRows");
   if(rows!=null)cur[0]=String.valueOf(rows[2].getText());
  }catch(Throwable e){throw new RuntimeException(e);}});
  require(lyricCount[0]>0,"lyrics were not parsed into the player (count "+lyricCount[0]+")");
  require(cur[0].length()>0,"current lyric line is empty: count="+lyricCount[0]);
  runOnMainSync(()->donePage.finish());Thread.sleep(400);
  result.putString("stream","PASS: music lyrics live — "+lyricCount[0]+" lines parsed, center row showing (“"+cur[0]+"”), playback "+progress[1]+" ms, player pages "+players.size()+".\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
