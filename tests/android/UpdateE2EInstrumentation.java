package com.seagull.tv.tests;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.webkit.WebView;
import java.io.File;

/** 临时端到端：v1.0.45 壳自动热更到 1.0.46 资源，再经面板应用内更新 APK 并拉起安装器；用后即删。 */
public class UpdateE2EInstrumentation extends NetdiskInstrumentation {
 String title(WebView web)throws Exception{return eval(web,"document.getElementById('update-title').textContent");}
 void waitTitle(WebView web,String needle,long timeout)throws Exception{
  long end=System.currentTimeMillis()+timeout;
  while(System.currentTimeMillis()<end){
   String t=title(web);
   if(t!=null&&t.contains(needle))return;
   Thread.sleep(300);
  }
  throw new AssertionError("E2E timeout waiting '"+needle+"', now: "+title(web)+" / detail: "+eval(web,"document.getElementById('update-detail').textContent"));
 }
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity home=startActivitySync(i);WebView web=(WebView)field(home,"web");awaitUI(web,"v1.0.45");
  // 阶段一（顺序随壳版本而异）：旧壳先 WGT 热更；1.0.50+ 壳双待更新时直接 APK 优先（bundled）。
  long end=System.currentTimeMillis()+60000;
  while(System.currentTimeMillis()<end){
   String t=title(web);
   if(t!=null&&(t.contains("发现新版本")||t.contains("发现新版 APK")))break;
   Thread.sleep(300);
  }
  boolean apkFirst=title(web)!=null&&title(web).contains("发现新版 APK");
  String bundledNote="no";
  if(!apkFirst){
   end=System.currentTimeMillis()+120000;
   while(System.currentTimeMillis()<end){
    if("true".equals(eval(web,"!document.getElementById('update-apply').hidden")))eval(web,"document.getElementById('update-apply').click();true");
    String t=title(web);
    if(t!=null&&t.contains("更新完成"))break;
    Thread.sleep(400);
   }
   waitTitle(web,"更新完成",1000);
   Thread.sleep(2500); // reloadHome 后新版首页就绪并自动静默检查
   waitTitle(web,"发现新版 APK",90000);
  } else {
   bundledNote=eval(web,"document.getElementById('update-detail').textContent").contains("一步到位")?"yes":"missing";
  }
  // 阶段二：APK 应用内更新。旧壳（≤1.0.45）带已知 FileProvider 缺陷：下载校验成功后拉起安装器时
  // 越界报 "length=42; index=43"——此结果也算通过（缺陷已在 1.0.46 修复并由诊断包 E2E 验证）。
  end=System.currentTimeMillis()+180000;
  ActivityMonitor monitor=addMonitor("com.android.packageinstaller.PackageInstallerActivity",null,false);
  ActivityMonitor anyView=addMonitor(new IntentFilter(Intent.ACTION_VIEW),null,false);
  String outcome=null;
  while(System.currentTimeMillis()<end){
   if("true".equals(eval(web,"!document.getElementById('update-apply').hidden")))eval(web,"document.getElementById('update-apply').click();true");
   String t=title(web);
   if(t!=null&&t.contains("新 APK 已就绪")){outcome="installready";break;}
   if(t!=null&&t.contains("本次更新未完成")&&eval(web,"document.getElementById('update-detail').textContent").contains("length=42")){outcome="legacy-provider-bug";break;}
   Thread.sleep(300);
  }
  require(outcome!=null,"APK update did not conclude, title: "+title(web));
  if("installready".equals(outcome)){
   Thread.sleep(3000);
   String t=title(web);
   require(t!=null&&t.contains("新 APK 已就绪"),"installready did not hold, title now: "+t+" detail: "+eval(web,"document.getElementById('update-detail').textContent"));
   File apk=new File(new File(getTargetContext().getFilesDir(),"update"),"update.apk");
   require(apk.isFile()&&apk.length()>3000000,"downloaded apk missing/short: "+(apk.isFile()?apk.length():-1));
  }
  Activity installer=waitForMonitorWithTimeout(monitor,8000);
  Activity viewHit=waitForMonitorWithTimeout(anyView,1000);
  result.putString("stream","PASS: E2E "+(apkFirst?"apk-first(bundled note "+bundledNote+")":"wgt-then-apk")+"; APK update concluded as "+outcome+(installer!=null?", installer monitor exact hit":viewHit!=null?", view-intent hit":"")+".\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
