package com.seagull.tv.tests;

import android.app.*;import android.content.*;import android.os.Bundle;import android.webkit.WebView;
import java.io.*;import java.lang.reflect.Method;

public class BookmarkInstrumentation extends UpdateInstrumentation {
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent intent=new Intent();intent.setClassName("com.seagull.tv","com.seagull.tv.MainActivity");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity activity=startActivitySync(intent);WebView web=(WebView)field(activity,"web");awaitUI(web,"v"+activity.getPackageManager().getPackageInfo("com.seagull.tv",0).versionName);
  activity.getSharedPreferences("tv_bookmarks",0).edit().clear().commit();
  eval(web,"if(TVPrivacy.enabled)document.getElementById('privacy-toggle').click();true");
  require("true".equals(eval(web,"JSON.parse(TVBridge.bookmarkRequest(JSON.stringify({action:'add',url:'javascript:alert(1)',title:'bad'}))).ok===false")),"Native bookmark accepted a script URL");
  require("true".equals(eval(web,"JSON.parse(TVBridge.bookmarkRequest(JSON.stringify({action:'add',url:'https://user:secret@example.com/',title:'bad'}))).ok===false")),"Native bookmark accepted URL credentials");
  require("true".equals(eval(web,"Bookmarks.add('https://pan.quark.cn/s/testbookmark?pwd=aaaa&stoken=discard','native bookmark')")),"Native bookmark add failed");
  require("true".equals(eval(web,"Bookmarks.add('https://pan.quark.cn/s/testbookmark?pwd=bbbb','native bookmark updated')")),"Native bookmark update failed");
  String saved=activity.getSharedPreferences("tv_bookmarks",0).getString("items","");
  require(saved.contains("pwd=bbbb")&&!saved.contains("discard"),"Extraction code lost or transient token persisted");
  eval(web,"document.getElementById('bookmarks-open').click();true");
  require("1".equals(eval(web,"document.querySelectorAll('.bookmark-row').length")),"Native dedupe failed");
  ActivityMonitor monitor=addMonitor("com.seagull.tv.BrowserActivity",null,false);
  eval(web,"document.getElementById('bookmarks-close').click();TVBridge.openUrl('http://10.0.2.2:8138/');true");
  Activity browser=waitForMonitorWithTimeout(monitor,10000);
  require(browser!=null,"Internal browser did not open");
  WebView page=(WebView)field(browser,"web");long limit=System.currentTimeMillis()+15000;
  while(System.currentTimeMillis()<limit && !"true".equals(eval(page,"document.title.length>0")))Thread.sleep(100);
  final Activity browserPage=browser;Method menu=browser.getClass().getDeclaredMethod("menu");menu.setAccessible(true);AlertDialog[] dialog={null};runOnMainSync(()->{try{dialog[0]=(AlertDialog)menu.invoke(browserPage);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
  runOnMainSync(()->{android.widget.ListView list=dialog[0].getListView();int position=-1;for(int i=0;i<list.getAdapter().getCount();i++)if("收藏此网页".equals(list.getAdapter().getItem(i).toString()))position=i;require(position>=0,"Browser bookmark menu entry missing");require(list.performItemClick(list.getChildAt(position-list.getFirstVisiblePosition()),position,list.getAdapter().getItemId(position)),"Browser bookmark menu click failed");});Thread.sleep(500);
  runOnMainSync(()->browserPage.finish());waitForIdleSync();
  eval(web,"Bookmarks.refresh();document.getElementById('bookmarks-open').click();true");
  require("2".equals(eval(web,"document.querySelectorAll('.bookmark-row').length")),"Browser bookmark did not reach homepage store");
  String before=activity.getSharedPreferences("tv_bookmarks",0).getString("items","");
  eval(web,"document.getElementById('bookmarks-close').click();document.getElementById('privacy-toggle').click();true");
  require("2".equals(eval(web,"JSON.parse(TVBridge.bookmarkRequest(JSON.stringify({action:'list'}))).items.length")),"Private mode could not read existing bookmarks");
  eval(web,"document.getElementById('bookmarks-open').click();true");
  require("true".equals(eval(web,"document.querySelectorAll('.bookmark-row').length===2 && document.getElementById('bookmark-form').hidden && Array.from(document.querySelectorAll('.bookmark-row .text-button')).every(x=>x.disabled)")),"Private bookmark UI is not read-only");
  eval(web,"document.getElementById('bookmarks-close').click();true");
  require("true".equals(eval(web,"JSON.parse(TVBridge.bookmarkRequest(JSON.stringify({action:'add',url:'https://private.example/',title:'private'}))).ok===false")),"Native private-mode write was accepted");
  require(before.equals(activity.getSharedPreferences("tv_bookmarks",0).getString("items","")),"Private mode changed bookmark storage");
  eval(web,"document.getElementById('privacy-toggle').click();document.getElementById('bookmarks-open').click();true");
  require("2".equals(eval(web,"document.querySelectorAll('.bookmark-row').length")),"Normal bookmarks lost after private mode");
  eval(web,"Array.from(document.querySelectorAll('.bookmark-row')).find(x=>x.textContent.indexOf('native bookmark updated')>=0).querySelector('.text-button').click();true");
  require("1".equals(eval(web,"document.querySelectorAll('.bookmark-row').length")),"Native bookmark delete failed");
  Thread.sleep(1000);File xml=new File(activity.getApplicationInfo().dataDir,"shared_prefs/tv_bookmarks.xml");require(xml.isFile()&&xml.length()>0,"Bookmarks did not persist to app-private file");
  result.putString("stream","PASS: Android native local bookmark add/update/delete, extraction-code retention, transient-token stripping, browser menu -> homepage shared store, private-mode write rejection, original bookmarks preserved, app-private persistence.\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
