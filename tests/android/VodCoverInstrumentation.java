package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.webkit.WebView;

/** 影视封面真机验证：外部图片子资源在 WebView 中真实加载（自然宽度>0）。 */
public class VodCoverInstrumentation extends NetdiskInstrumentation {
 @Override void waitJS(WebView web,String expression,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<end){if("true".equals(eval(web,expression)))return;Thread.sleep(250);}throw new AssertionError("cover timeout: "+eval(web,"document.getElementById('vod-status').textContent"));}
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity home=startActivitySync(i);WebView web=(WebView)field(home,"web");awaitUI(web,"v"+home.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();(function(){var o=VodSources.resource;VodSources.resource=function(v){var r=o(v);window.__covIn=v;window.__covOut=r;return r;};})();document.getElementById('vod-open').click();true");
  waitJS(web,"document.querySelectorAll('.vod-cover img').length>0",45000);
  long end=System.currentTimeMillis()+60000;final int[] stat={0,0};
  while(System.currentTimeMillis()<end){
   eval(web,"(function(){var sc=document.querySelector('#vod-dialog .page-scroll');if(sc)sc.scrollTop=(sc.scrollTop+300)%900;return true;})()"); // 触发懒加载
   String snap=eval(web,"JSON.stringify({total:Array.from(document.querySelectorAll('.vod-cover img')).filter(function(im){return im.getAttribute('src');}).length,loaded:Array.from(document.querySelectorAll('.vod-cover img')).filter(function(im){return im.complete&&im.naturalWidth>0;}).length})");
   snap=snap.replace("\\",""); // eval 结果是带转义引号的 JSON 字符串
   java.util.regex.Matcher mt=java.util.regex.Pattern.compile("\"total\":(\\d+)").matcher(snap);
   java.util.regex.Matcher ml=java.util.regex.Pattern.compile("\"loaded\":(\\d+)").matcher(snap);
   if(mt.find()&&ml.find()){stat[0]=Integer.parseInt(mt.group(1));stat[1]=Integer.parseInt(ml.group(1));}
   if(stat[1]>0)break;
   Thread.sleep(500);
  }
  require(stat[0]>0,"no cover img rendered; cards="+eval(web,"document.querySelectorAll('.vod-card').length")+" imgs="+eval(web,"document.querySelectorAll('.vod-cover img').length")+" status="+eval(web,"document.getElementById('vod-status').textContent"));
  require(stat[1]>0,"covers render but none loaded (loaded "+stat[1]+"/"+stat[0]+") full="+eval(web,"document.querySelector('.vod-cover img').getAttribute('src')"));
  result.putString("stream","PASS: vod covers load in WebView — "+stat[1]+"/"+stat[0]+" images decoded.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
