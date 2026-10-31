package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.webkit.WebView;import android.widget.VideoView;
public class NetdiskInstrumentation extends UpdateInstrumentation {
 android.widget.Button findButton(android.view.View view,String text){
  if(view instanceof android.widget.Button && text.equals(((android.widget.Button)view).getText().toString()))return (android.widget.Button)view;
  if(view instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)view;for(int i=0;i<g.getChildCount();i++){android.widget.Button found=findButton(g.getChildAt(i),text);if(found!=null)return found;}}return null;
 }
 void clickButton(Activity a,String text)throws Exception{runOnMainSync(()->{android.widget.Button b=findButton(a.getWindow().getDecorView(),text);require(b!=null,"Missing player button "+text);b.performClick();});Thread.sleep(150);}
 void choose(Activity player,String label)throws Exception{
  AlertDialog dialog=(AlertDialog)field(player,"optionsDialog");require(dialog!=null&&dialog.isShowing(),"Missing dialog");
  runOnMainSync(()->{android.widget.ListView list=dialog.getListView();int index=-1;for(int i=0;i<list.getAdapter().getCount();i++)if(label.equals(list.getAdapter().getItem(i).toString()))index=i;require(index>=0,"Dialog item unavailable "+label);list.performItemClick(list.getChildAt(index),index,list.getAdapter().getItemId(index));});Thread.sleep(300);
 }
 void playerControls(Activity player,Object video)throws Exception{
  android.view.View frame=(android.view.View)field(player,"frame"),surface=(android.view.View)field(player,"surface");
  clickButton(player,"适应");require((Integer)field(player,"scaleMode")==1,"Fill mode not selected");
  require(surface.getWidth()==frame.getWidth()&&surface.getHeight()==frame.getHeight(),"Stretch did not fill frame");
  clickButton(player,"拉伸铺满");require((Integer)field(player,"scaleMode")==2,"Crop mode not selected");
  require(surface.getWidth()>=frame.getWidth()-1&&surface.getHeight()>=frame.getHeight()-1,"Crop did not cover frame");
  clickButton(player,"1.0×");choose(player,"1.5×");final float[] speed={0};runOnMainSync(()->{try{Object p=Class.forName("androidx.media3.common.Player").getMethod("getPlaybackParameters").invoke(video);speed[0]=p.getClass().getField("speed").getFloat(p);}catch(Exception e){throw new RuntimeException(e);}});require(speed[0]==1.5f,"Playback rate unchanged");
  clickButton(player,"屏幕方向");choose(player,"锁定横屏");Thread.sleep(800);require(player.getRequestedOrientation()==6,"Landscape lock failed");require(field(player,"video")==video,"Rotation recreated player");
  clickButton(player,"横屏已锁");choose(player,"锁定竖屏");Thread.sleep(800);require(player.getRequestedOrientation()==7,"Portrait lock failed");
  clickButton(player,"全屏");require(findButton(player.getWindow().getDecorView(),"全屏").getVisibility()==0&&!findButton(player.getWindow().getDecorView(),"全屏").isShown(),"Fullscreen controls still visible");
  // 播放器为手势控制层：向画面区派发真实触摸（DOWN→UP），单击确认后应恢复控制层。
  runOnMainSync(()->{android.view.MotionEvent down=android.view.MotionEvent.obtain(android.os.SystemClock.uptimeMillis(),android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_DOWN,frame.getWidth()/2f,frame.getHeight()/2f,0);frame.dispatchTouchEvent(down);down.recycle();android.view.MotionEvent up=android.view.MotionEvent.obtain(android.os.SystemClock.uptimeMillis(),android.os.SystemClock.uptimeMillis()+40,android.view.MotionEvent.ACTION_UP,frame.getWidth()/2f,frame.getHeight()/2f,0);frame.dispatchTouchEvent(up);up.recycle();});Thread.sleep(900);require(findButton(player.getWindow().getDecorView(),"全屏").isShown(),"Tap did not restore controls");
 }
 void waitJS(WebView web,String expression,long timeout)throws Exception{
  long end=System.currentTimeMillis()+timeout;
  while(System.currentTimeMillis()<end){if("true".equals(eval(web,expression)))return;Thread.sleep(150);}
  throw new AssertionError("UI wait timeout: "+eval(web,"document.getElementById('netdisk-status').textContent"));
 }
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent intent=new Intent();intent.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity activity=startActivitySync(intent);WebView web=(WebView)field(activity,"web");
  awaitUI(web,"v"+activity.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();true");
  waitJS(web,"document.getElementById('account-status').textContent.indexOf('未登录')>=0",25000);
  ActivityMonitor loginMonitor=addMonitor("com.seagull.tv.BrowserActivity",null,false);
  eval(web,"document.getElementById('account-login').click();true");
  Activity loginPage=waitForMonitorWithTimeout(loginMonitor,15000);require(loginPage!=null,"Homepage login did not open internal browser");
  require("https://pan.quark.cn/".equals(loginPage.getIntent().getStringExtra("url")),"Login entry used wrong URL");
  WebView loginWeb=(WebView)field(loginPage,"web");
  require("true".equals(eval(loginWeb,"typeof window.TVBridge==='undefined'")),"Login page exposed native home bridge");
  runOnMainSync(()->loginPage.finish());Thread.sleep(300);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();document.getElementById('mode-quark').click();document.getElementById('query').value='Blender';document.getElementById('search-form').dispatchEvent(new Event('submit',{cancelable:true}));true");
  waitJS(web,"document.querySelectorAll('.share-validity.valid').length>0",40000);
  String count=eval(web,"document.querySelectorAll('.resource-card').length");
  eval(web,"Array.from(document.querySelectorAll('.resource-card')).find(x=>x.querySelector('.share-validity.valid')).querySelector('.resource-title').click();true");
  waitJS(web,"document.querySelectorAll('.file-row').length>0",25000);
  eval(web,"document.getElementById('results-back').click();true");
  waitJS(web,"document.querySelectorAll('.share-validity.valid').length>0",5000);
  require(eval(web,"document.getElementById('query').value").contains("Blender"),"Returning lost search keyword");
  eval(web,"document.getElementById('query').value='https://pan.quark.cn/s/98ef14cf3ef1';document.getElementById('search-form').dispatchEvent(new Event('submit',{cancelable:true}));true");
  waitJS(web,"document.querySelectorAll('.file-row').length>0 && document.getElementById('netdisk-status').textContent.indexOf('已读取')===0",25000);
  for(int i=0;i<4;i++){
   if("true".equals(eval(web,"Array.from(document.querySelectorAll('.file-row')).some(x=>x.textContent.indexOf('.mp4')>=0)")))break;
   String old=eval(web,"document.getElementById('netdisk-title').textContent");
   eval(web,"document.querySelector('.file-row').click();true");
   waitJS(web,"JSON.stringify(document.getElementById('netdisk-title').textContent)!=="+org.json.JSONObject.quote(old)+" && document.getElementById('netdisk-status').textContent.indexOf('已读取')===0",25000);
  }
  ActivityMonitor monitor=addMonitor("com.seagull.tv.VideoActivity",null,false);
  eval(web,"Array.from(document.querySelectorAll('.file-row')).find(x=>x.textContent.indexOf('.mp4')>=0).click();true");
  Activity player=waitForMonitorWithTimeout(monitor,20000);require(player!=null,"Native player did not open");
  Object video=field(player,"video");long[] values={0,0};long until=System.currentTimeMillis()+25000;
  while(System.currentTimeMillis()<until){runOnMainSync(()->{try{values[0]=((Number)Class.forName("androidx.media3.common.Player").getMethod("getDuration").invoke(video)).longValue();values[1]=((Number)Class.forName("androidx.media3.common.Player").getMethod("getCurrentPosition").invoke(video)).longValue();}catch(Exception e){throw new RuntimeException(e); }});if(values[0]>0&&values[1]>0)break;Thread.sleep(200);}
  require(values[0]>0&&values[1]>0,"Native video did not start");
  playerControls(player,video);
  String mediaUrl=player.getIntent().getStringExtra("url");
  runOnMainSync(()->player.finish());Thread.sleep(500);require("true".equals(eval(web,"document.getElementById('media-dialog').open")),"Full-play controls lost after preview");
  String historyBefore=eval(web,"localStorage.getItem('tv_history')");
  eval(web,"document.getElementById('media-close').click();if(!TVPrivacy.enabled)document.getElementById('privacy-toggle').click();true");
  Intent privateIntent=new Intent();privateIntent.setClassName("com.seagull.tv","com.seagull.tv.VideoActivity");privateIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);privateIntent.putExtra("url",mediaUrl);privateIntent.putExtra("title","privacy playback");privateIntent.putExtra("key","privacy-playback-test");
  Activity privatePlayer=startActivitySync(privateIntent);Object privateVideo=field(privatePlayer,"video");
  long[] privateValues={0,0};until=System.currentTimeMillis()+25000;
  while(System.currentTimeMillis()<until){runOnMainSync(()->{try{privateValues[0]=((Number)Class.forName("androidx.media3.common.Player").getMethod("getDuration").invoke(privateVideo)).longValue();privateValues[1]=((Number)Class.forName("androidx.media3.common.Player").getMethod("getCurrentPosition").invoke(privateVideo)).longValue();}catch(Exception e){throw new RuntimeException(e);}});if(privateValues[0]>0&&privateValues[1]>0)break;Thread.sleep(200);}
  require(privateValues[1]>0,"Private player did not start");
  require(privatePlayer.getIntent().getStringExtra("url")==null,"Private player retained URL in Intent");
  require((privatePlayer.getWindow().getAttributes().flags&8192)!=0,"Private player not secure");
  runOnMainSync(()->privatePlayer.finish());Thread.sleep(500);
  require(!activity.getSharedPreferences("tv_playback",0).contains("privacy-playback-test"),"Private playback wrote progress");
  require(historyBefore.equals(eval(web,"localStorage.getItem('tv_history')")),"Private player altered search history");
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();true");
  result.putString("stream","PASS: Android live search returned "+count+" shares, real share directory/nested folders, native preview playback "+values[0]+" ms, returned to full-play controls, private playback advanced without stored progress or Intent URL.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
