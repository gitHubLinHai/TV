package com.seagull.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.*;

/** Google、网盘和视频网站共用的独立浏览页，绝不注册 JavascriptInterface。 */
public class BrowserActivity extends Activity {
    private WebView web;
    private LinearLayout layout;
    private FrameLayout full;
    private View video;
    private WebChromeClient.CustomViewCallback videoCallback;
    private EditText address;
    private ProgressBar progress;
    // 仅内存，用于返回首页后一键收藏；无痕浏览不更新此值。
    static volatile String lastUrl="", lastTitle="";
    private void bookmarkCurrent() {
        if(isPrivate())return;
        try { BookmarkStore.add(this,web.getUrl(),web.getTitle()); Toast.makeText(this,"已收藏当前页",Toast.LENGTH_SHORT).show(); }
        catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }

    protected boolean isPrivate() { return false; }

    public static boolean isWebUrl(String value) {
        if (value == null) return false;
        Uri uri = Uri.parse(value);
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (isPrivate()) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & 2) != 0);
        full = new FrameLayout(this);
        layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setPadding(dp(4), dp(4), dp(4), dp(4));
        button(toolbar, "‹", v -> onBackPressed());
        button(toolbar, "首页", v -> finish());
        address = new EditText(this); address.setSingleLine(true); address.setTextSize(13);
        address.setSaveEnabled(false);
        if (isPrivate()) address.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (isPrivate() && android.os.Build.VERSION.SDK_INT >= 26) address.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        toolbar.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1));
        address.setOnEditorActionListener((v, action, event) -> {
            String input = address.getText().toString().trim();
            String url = isWebUrl(input) ? input : "https://quark.sm.cn/s?q=" + Uri.encode(input);
            web.loadUrl(url);
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0);
            web.requestFocus(); return true;
        });
        if(!isPrivate())button(toolbar, "☆", v -> bookmarkCurrent());
        button(toolbar, "⋮", v -> menu());
        layout.addView(toolbar);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        layout.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        web = new WebView(this);
        web.setSaveEnabled(false);
        if (isPrivate() && android.os.Build.VERSION.SDK_INT >= 26) web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        web.setBackgroundColor(android.graphics.Color.rgb(16, 19, 24));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        String initialUrl = getIntent().getStringExtra("url");
        if (isWebUrl(initialUrl) && "pan.quark.cn".equals(Uri.parse(initialUrl).getHost())) {
            settings.setUserAgentString(NetdiskHttp.UA);
            settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true);
        }
        if (isPrivate()) { settings.setCacheMode(WebSettings.LOAD_NO_CACHE); settings.setSaveFormData(false); }
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(true); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                address.setText(url);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isWebUrl(url)) return false;
                if (request.isForMainFrame() && request.hasGesture()) openOther(url);
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                address.setText(url); if (!isPrivate()) CookieManager.getInstance().flush();
                if(!isPrivate() && isWebUrl(url)){lastUrl=url;lastTitle=view.getTitle();}
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) Toast.makeText(BrowserActivity.this,
                    "页面连接失败，可刷新或切换网络。", Toast.LENGTH_LONG).show();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value); progress.setVisibility(value == 100 ? View.GONE : View.VISIBLE);
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (video != null) { callback.onCustomViewHidden(); return; }
                video = view; videoCallback = callback; layout.setVisibility(View.GONE);
                full.addView(view, new FrameLayout.LayoutParams(-1, -1));
                getWindow().getDecorView().setSystemUiVisibility(5894);
            }
            @Override public void onHideCustomView() { leaveFullscreen(); }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, android.os.Message message) {
                if (!gesture) return false;
                final WebView popup = new WebView(BrowserActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView w, String url) {
                        if (isWebUrl(url)) web.loadUrl(url); else openOther(url);
                        popup.destroy(); return true;
                    }
                });
                ((WebView.WebViewTransport)message.obj).setWebView(popup); message.sendToTarget(); return true;
            }
        });
        web.setDownloadListener((url, userAgent, disposition, mime, length) -> {
            if (isPrivate()) { Toast.makeText(this,"无痕模式不创建下载记录；关闭无痕后再下载",Toast.LENGTH_LONG).show(); return; }
            if (!isWebUrl(url)) { Toast.makeText(this, "该下载不是普通 HTTP 文件链接，请使用网页提供的下载方式", Toast.LENGTH_LONG).show(); return; }
            String filename = URLUtil.guessFileName(url, disposition, mime).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            final String name = filename;
            new AlertDialog.Builder(this).setTitle("下载文件").setMessage(name + "\n使用系统下载管理器，可在下载列表查看。")
                .setPositiveButton("下载", (d, which) -> {
                    try {
                        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                        req.setTitle(name).setMimeType(mime).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        req.addRequestHeader("User-Agent", userAgent);
                        String cookie = CookieManager.getInstance().getCookie(url);
                        if (cookie != null) req.addRequestHeader("Cookie", cookie);
                        if (isWebUrl(web.getUrl())) req.addRequestHeader("Referer", web.getUrl());
                        req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, System.currentTimeMillis() + "-" + name);
                        ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
                        Toast.makeText(this, "已加入下载列表", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { Toast.makeText(this, "无法创建下载任务", Toast.LENGTH_LONG).show(); }
                }).setNegativeButton("取消", null).show();
        });
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        full.addView(layout, new FrameLayout.LayoutParams(-1, -1)); setContentView(full);
        if (!isPrivate() && state != null) web.restoreState(state);
        if (web.getUrl() == null) {
            String url = getIntent().getStringExtra("url");
            web.loadUrl(isWebUrl(url) ? url : "https://quark.sm.cn/");
        }
    }
    private void button(LinearLayout bar, String text, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(text); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(2), 0, dp(2), 0); bar.addView(b, new LinearLayout.LayoutParams(dp(48), dp(48))); b.setOnClickListener(listener);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void openOther(String url) {
        if (isPrivate()) { Toast.makeText(this,"无痕模式不跳转外部应用",Toast.LENGTH_SHORT).show(); return; }
        Uri uri = Uri.parse(url); String scheme = uri.getScheme();
        if (scheme == null || scheme.matches("(?i)javascript|file|content|data|intent|about")) return;
        new AlertDialog.Builder(this).setMessage("打开对应应用？")
            .setPositiveButton("打开", (d, which) -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
                catch (Exception e) { Toast.makeText(this, "未安装对应应用", Toast.LENGTH_SHORT).show(); }
            }).setNegativeButton("取消", null).show();
    }
    private AlertDialog menu() {
        if (isPrivate()) return new AlertDialog.Builder(this).setTitle("无痕浏览 · 关闭后清理本次资料").setItems(new String[]{"刷新","前进","返回首页"},(d,w)->{ if(w==0)web.reload();if(w==1&&web.canGoForward())web.goForward();if(w==2)finish(); }).show();
        return new AlertDialog.Builder(this).setItems(new String[]{"刷新", "前进", "复制链接", "系统浏览器打开", "下载列表", "返回首页", "收藏此网页", "网页视频控制", "清理网页缓存"}, (d, which) -> {
            if (which == 0) web.reload();
            if (which == 1 && web.canGoForward()) web.goForward();
            if (which == 2) ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("链接", web.getUrl()));
            if (which == 3 && isWebUrl(web.getUrl())) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(web.getUrl()))); }
                catch (Exception e) { Toast.makeText(this, "未安装浏览器", Toast.LENGTH_SHORT).show(); }
            }
            if (which == 4) {
                try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); }
                catch (Exception e) { Toast.makeText(this, "没有下载列表应用", Toast.LENGTH_SHORT).show(); }
            }
            if (which == 5) finish();
            if (which == 6) {
                bookmarkCurrent();
            }
            if(which==7)videoMenu();
            if(which==8){web.clearCache(true);Toast.makeText(this,"已清理网页缓存，登录和书签保留",Toast.LENGTH_SHORT).show();}
        }).show();
    }
    private void videoMenu(){
        final String[] labels={"视频全屏","适应画面","裁剪铺满","拉伸铺满","0.75 倍速","1 倍速","1.5 倍速","2 倍速","锁定横屏","锁定竖屏","跟随系统"};
        new AlertDialog.Builder(this).setTitle("当前网页视频（需页面允许控制）").setItems(labels,(d,w)->{
            if(w>=8){setRequestedOrientation(new int[]{android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}[w-8]);return;}
            String action=w==0?"if(v.requestFullscreen)v.requestFullscreen();else if(v.webkitEnterFullscreen)v.webkitEnterFullscreen();":w<=3?"v.style.objectFit='"+new String[]{"","contain","cover","fill"}[w]+"';v.style.width='100%';v.style.height='100%';":"v.playbackRate="+new float[]{.75f,1f,1.5f,2f}[w-4]+";";
            web.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return false;try{"+action+"return true;}catch(e){return false;}})()",reply->{if(!"true".equals(reply))Toast.makeText(this,"当前页面未提供可控制的视频，跨域播放器需使用站点自带按钮",Toast.LENGTH_LONG).show();});
        }).show();
    }
    private void leaveFullscreen() {
        if (video == null) return;
        full.removeView(video); video = null; layout.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(0);
        if (videoCallback != null) { videoCallback.onCustomViewHidden(); videoCallback = null; }
    }
    @Override public void onBackPressed() {
        if (video != null) leaveFullscreen(); else if (web.canGoBack()) web.goBack(); else finish();
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); if (!isPrivate()) web.saveState(state); }
    @Override protected void onPause() { super.onPause(); web.onPause(); if (!isPrivate()) CookieManager.getInstance().flush(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() { leaveFullscreen(); web.stopLoading(); if (isPrivate()) { web.clearHistory(); web.clearCache(true); web.clearFormData(); } web.destroy(); super.onDestroy(); }
}
