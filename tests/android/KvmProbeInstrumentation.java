package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.webkit.WebView;

/** 临时：仅启用 4KVM 源加载电视剧分类，dump 状态与卡片数。 */
public class KvmProbeInstrumentation extends NetdiskInstrumentation {
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent i=new Intent();i.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity home=startActivitySync(i);WebView web=(WebView)field(home,"web");
  long bootEnd=System.currentTimeMillis()+20000;
  while(System.currentTimeMillis()<bootEnd){if("true".equals(eval(web,"!!window.TVBridge&&!!document.getElementById('vod-open')&&typeof window.Vod!=='undefined'")))break;Thread.sleep(300);}
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();localStorage.setItem('tv_vod_sources',JSON.stringify([{name:'4KVM',host:'www.4kvm.net',adapter:'4kvm-page',on:true}]));document.getElementById('vod-open').click();true");
  long waitEnd=System.currentTimeMillis()+45000;
  while(System.currentTimeMillis()<waitEnd){if("true".equals(eval(web,"document.querySelectorAll('.vod-card').length>0||document.getElementById('vod-status').textContent.length>3")))break;Thread.sleep(400);}
  String snap=eval(web,"JSON.stringify({status:document.getElementById('vod-status').textContent,cards:document.querySelectorAll('.vod-card').length,srcs:localStorage.getItem('tv_vod_sources')})");
  result.putString("stream","SNAP "+snap);
  finish(Activity.RESULT_CANCELED,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,result);}}
}
