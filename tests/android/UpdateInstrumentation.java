package com.seagull.tv.tests;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.webkit.WebView;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.net.*;
import org.json.JSONObject;
public class UpdateInstrumentation extends Instrumentation {
 @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
 void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
 Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 String eval(WebView web,String js)throws Exception{
  CountDownLatch done=new CountDownLatch(1);String[] value={null};
  runOnMainSync(()->web.evaluateJavascript(js,result->{value[0]=result;done.countDown();}));
  require(done.await(15,TimeUnit.SECONDS),"JS evaluation timeout");return value[0];
 }
 int code(Activity activity)throws Exception{Method m=activity.getClass().getDeclaredMethod("activeCode");m.setAccessible(true);return (Integer)m.invoke(activity);}
 void awaitUI(WebView web,String wanted)throws Exception{
  long limit=System.currentTimeMillis()+20000;String value="";
  while(System.currentTimeMillis()<limit){value=eval(web,"JSON.stringify({url:location.href,version:document.getElementById('version')&&document.getElementById('version').textContent,bridge:TVBridge.versionInfo()})");if(value.contains(wanted))return;Thread.sleep(150);}
  throw new AssertionError("UI version timeout: "+value);
 }
 void awaitCode(Activity activity,int wanted)throws Exception{long limit=System.currentTimeMillis()+25000;while(System.currentTimeMillis()<limit){if(code(activity)==wanted)return;Thread.sleep(100);}throw new AssertionError("Version did not become "+wanted);}
 void checkAndApply(Object bridge,int wanted)throws Exception{
  Method check=bridge.getClass().getDeclaredMethod("startCheck",String.class);check.setAccessible(true);check.invoke(bridge,"test");
  long limit=System.currentTimeMillis()+15000;boolean found=false;
  while(System.currentTimeMillis()<limit){JSONObject info=(JSONObject)field(bridge,"recentUpdate");if(info!=null&&info.optInt("versionCode")==wanted){found=true;break;}Thread.sleep(100);}
  require(found,"Version check did not find fixture "+wanted);
  Method apply=bridge.getClass().getDeclaredMethod("applyUpdate",String.class);apply.setAccessible(true);apply.invoke(bridge,"test");
 }
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent intent=new Intent();intent.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity activity=startActivitySync(intent);
  Field cdns=activity.getClass().getDeclaredField("CDNS");cdns.setAccessible(true);String[] bases=(String[])cdns.get(null);
  for(int i=0;i<bases.length;i++)bases[i]="http://10.0.2.2:8140/";
  WebView web=(WebView)field(activity,"web");Thread.sleep(1000);
  String bookmarksBefore=activity.getSharedPreferences("tv_bookmarks",0).getString("items","");
  eval(web,"localStorage.setItem('tv_update_test','keep')");
  eval(web,"localStorage.setItem('tv_drama_catalog','{\"dramas\":[]}');true");
  eval(web,"window.__testUpdateStatus=window.__updateStatus;window.__updateStatus=function(s){if(s.state!=='found')window.__testUpdateStatus(s);};true");
  Object bridge=null;
  for(Class<?> c:activity.getClass().getDeclaredClasses())if(c.getSimpleName().equals("Bridge")){Constructor<?> ctor=c.getDeclaredConstructors()[0];ctor.setAccessible(true);bridge=ctor.newInstance(activity);}
  int good=code(activity)+1,bad=good+1;String goodName="v1.0."+(good-100);
  require(bridge!=null,"Bridge unavailable");checkAndApply(bridge,good);awaitCode(activity,good);Thread.sleep(2000);
  awaitUI(web,goodName);
  require(eval(web,"localStorage.getItem('tv_update_test')").contains("keep"),"History storage lost");
  require(eval(web,"localStorage.getItem('tv_drama_catalog')").contains("dramas"),"Local drama catalog lost");
  require(bookmarksBefore.equals(activity.getSharedPreferences("tv_bookmarks",0).getString("items","")),"Hot update changed local bookmarks");
  // 将下载传输与坏 UI 看门狗分开验证，避免损坏的 HTTP 测试包根本无法进入试运行。
  java.io.File fixture=new java.io.File(activity.getFilesDir(),"local-bad-fixture.wgt");
  java.io.File metadata=new java.io.File(activity.getFilesDir(),"local-bad-fixture.json");
  byte[] bytes=new byte[(int)metadata.length()];try(java.io.FileInputStream input=new java.io.FileInputStream(metadata)){int offset=0,n;while(offset<bytes.length&&(n=input.read(bytes,offset,bytes.length-offset))>0)offset+=n;}
  JSONObject info=new JSONObject(new String(bytes,"UTF-8"));java.io.File incoming=new java.io.File(activity.getFilesDir(),"local-bad-incoming");incoming.mkdir();
  Class.forName("com.seagull.tv.UpdatePackage").getMethod("verifyAndExtract",java.io.File.class,java.io.File.class,long.class,String.class).invoke(null,fixture,incoming,info.getLong("wgtSize"),info.getString("wgtSha256"));
  Class.forName("com.seagull.tv.UpdateStorage").getMethod("install",java.io.File.class,java.io.File.class).invoke(null,incoming,new java.io.File(activity.getFilesDir(),"www"));
  Method load=activity.getClass().getDeclaredMethod("loadHome");load.setAccessible(true);runOnMainSync(()->{try{load.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});awaitCode(activity,bad);
  // A syntactically valid ZIP with a broken application script must roll back after its readiness timeout.
  awaitCode(activity,good);Thread.sleep(1000);
  awaitUI(web,goodName);
  require(eval(web,"localStorage.getItem('tv_update_test')").contains("keep"),"Rollback lost storage");
  require(bookmarksBefore.equals(activity.getSharedPreferences("tv_bookmarks",0).getString("items","")),"Rollback changed local bookmarks");
  result.putString("stream","PASS: Android HTTP version check, WGT download/hash/install, same-process reload, local storage preservation; separately verified valid local ZIP with failed UI triggers watchdog rollback.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e.toString()+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
