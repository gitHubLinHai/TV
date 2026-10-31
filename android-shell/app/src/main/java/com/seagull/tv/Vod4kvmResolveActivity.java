package com.seagull.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.webkit.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.ByteArrayInputStream;

/** 在隔离 WebView 配置中运行 4KVM 的公开匿名播放器，只回传标准 HLS 地址。 */
public final class Vod4kvmResolveActivity extends Activity {
    private final AtomicBoolean done=new AtomicBoolean();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WebView resolver;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=28)WebView.setDataDirectorySuffix("vod4kvm_resolver");
        final String path=getIntent().getStringExtra("path");
        if(!Vod4kvmHttp.allowedPath(path)||!path.startsWith("/play/")){finishError("4KVM 选集地址无效");return;}
        resolver=new WebView(this);resolver.setAlpha(0.01f);resolver.setSaveEnabled(false);
        WebSettings s=resolver.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setMediaPlaybackRequiresUserGesture(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        resolver.setWebChromeClient(new WebChromeClient());
        resolver.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !url.startsWith("https://"+Vod4kvmHttp.HOST+"/play/");}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                String url=request.getUrl().toString(),resourcePath=request.getUrl().getPath();capture(url);
                try{
                    if(request.isForMainFrame()&&request.getUrl().getHost().equals(Vod4kvmHttp.HOST)){
                        String html=Vod4kvmHttp.fetch(path).getString("html").replace("data-danmaku-enabled=\"true\"","data-danmaku-enabled=\"false\"");
                        return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes("UTF-8")));
                    }
                    if(resourcePath!=null&&resourcePath.contains("artplayer-plugin-danmaku"))return new WebResourceResponse("application/javascript","UTF-8",new ByteArrayInputStream(new byte[0]));
                }catch(Exception e){handler.post(()->finishError("4KVM 播放页读取失败"));}
                return super.shouldInterceptRequest(view,request);
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())finishError("4KVM 播放页连接失败");}
        });
        setContentView(resolver);resolver.loadUrl("https://"+Vod4kvmHttp.HOST+path);
        handler.postDelayed(()->finishError("4KVM 播放地址解析超时，请重试"),30000);
    }
    private void capture(String value){
        try{android.net.Uri u=android.net.Uri.parse(value);String host=String.valueOf(u.getHost()).toLowerCase(Locale.ROOT),path=String.valueOf(u.getPath()).toLowerCase(Locale.ROOT);
            boolean mediaHost=host.equals("douyinbit.com")||host.endsWith(".douyinbit.com")||host.equals("douyinbit.top")||host.endsWith(".douyinbit.top");
            if("https".equalsIgnoreCase(u.getScheme())&&u.getUserInfo()==null&&mediaHost&&path.endsWith(".m3u8"))handler.post(()->finishOk(value));
        }catch(Exception ignored){}
    }
    private void finishOk(String url){if(!done.compareAndSet(false,true))return;handler.removeCallbacksAndMessages(null);setResult(RESULT_OK,new Intent().putExtra("url",url));finish();}
    private void finishError(String message){if(!done.compareAndSet(false,true))return;handler.removeCallbacksAndMessages(null);setResult(RESULT_CANCELED,new Intent().putExtra("error",message));finish();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(resolver!=null){resolver.stopLoading();resolver.destroy();resolver=null;}super.onDestroy();}
}
