package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.*;import android.webkit.*;import java.lang.reflect.*;

public class PrivacyInstrumentation extends UpdateInstrumentation {
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent intent=new Intent();intent.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity activity=startActivitySync(intent);WebView web=(WebView)field(activity,"web");
  awaitUI(web,"v"+activity.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();localStorage.setItem('tv_history','[{\"q\":\"normal-history\",\"mode\":\"quark\"}]');true");
  runOnMainSync(()->{CookieManager.getInstance().setCookie("https://pan.quark.cn/","tv_normal_preserved=normal; Path=/; Secure");CookieManager.getInstance().flush();});
  String before=eval(web,"localStorage.getItem('tv_history')");
  eval(web,"document.getElementById('privacy-toggle').click();document.getElementById('mode-quark').click();document.getElementById('query').value='private-keyword';document.getElementById('search-form').dispatchEvent(new Event('submit',{cancelable:true}));true");
  require(before.equals(eval(web,"localStorage.getItem('tv_history')")),"Private search wrote history");
  require((activity.getWindow().getAttributes().flags & 8192)!=0,"Private main not secure");
  // 测试浏览页在另一个进程。Python 驱动读取独立目录并发出返回键。
  eval(web,"TVBridge.openUrl('http://10.0.2.2:8141/private-fixture?private-address-marker');true");
  java.io.File signal=new java.io.File(activity.getFilesDir(),"privacy-test-open");signal.createNewFile();
  long end=System.currentTimeMillis()+90000;
  while(System.currentTimeMillis()<end && signal.exists())Thread.sleep(200);
  require(!signal.exists(),"Driver did not finish private browser checks");
  Thread.sleep(1000);
  String normal=CookieManager.getInstance().getCookie("https://pan.quark.cn/");
  require(normal!=null&&normal.contains("tv_normal_preserved=normal"),"Normal login cookie lost");
  String privateCookie=CookieManager.getInstance().getCookie("http://10.0.2.2:8141/");
  require(privateCookie==null||!privateCookie.contains("tv_private_marker"),"Private cookies leaked to normal profile");
  require(before.equals(eval(web,"localStorage.getItem('tv_history')")),"Private browsing changed normal history");
  eval(web,"document.getElementById('privacy-toggle').click();true");
  require("\"\"".equals(eval(web,"document.getElementById('query').value")),"Private query retained on exit");
  result.putString("stream","PASS: Android private search history isolation, secure window, separate WebView process/profile, private cookie isolation, original login preservation, query cleanup.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
