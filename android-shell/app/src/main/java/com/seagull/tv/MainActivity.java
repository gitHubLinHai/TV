package com.seagull.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 本地可热更首页；外部网站只在不带桥接的 BrowserActivity 中打开。 */
public class MainActivity extends Activity {

    /** 多源并行选择最高可安装版本。 */
    private static final String[] CDNS = {
            "https://fastly.jsdelivr.net/gh/gitHubLinHai/TV@main/release/",
            "https://cdn.jsdelivr.net/gh/gitHubLinHai/TV@main/release/",
            "https://raw.githubusercontent.com/gitHubLinHai/TV/main/release/"
    };

    private static final String ORIGIN = "https://app.tv.local/";
    private static final int SHELL_VERSION = 22;
    private final ExecutorService vodPool = Executors.newFixedThreadPool(2);
    private final ExecutorService netdiskPool = Executors.newFixedThreadPool(3);
    private static final AtomicBoolean UPDATING = new AtomicBoolean();
    private WebView web;
    private String pending4kvmId;
    private File wwwDir;
    private boolean pageReady;
    private final android.os.Handler homeHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 崩溃捕获：堆栈写入 last_crash.txt（下次启动在更新面板提示，90 秒后清除），随后交回系统崩溃流程。
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter stack = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(stack));
                String text = "v" + activeName() + " 壳" + SHELL_VERSION + " " + android.text.format.DateFormat.format("MM-dd HH:mm", System.currentTimeMillis()) + "\n" + stack;
                if (text.length() > 8000) text = text.substring(0, 8000);
                try (java.io.FileWriter writer = new java.io.FileWriter(new File(getFilesDir(), "last_crash.txt"), false)) {
                    writer.write(text);
                }
            } catch (Exception ignored) { }
            if (previous != null) previous.uncaughtException(t, e);
        });
        PrivacyState.init(this);
        if (PrivacyState.enabled) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & 2) != 0);
        wwwDir = new File(getFilesDir(), "www");
        try {
            android.content.pm.PackageInfo pkg = getPackageManager().getPackageInfo(getPackageName(), 0);
            synchronized (UpdateStorage.class) {
                if (new File(getFilesDir(), "web_loading").exists() && new File(getFilesDir(), "www_old").exists())
                    rejectCurrentUpdate();
                UpdateStorage.recover(getFilesDir());
                if (!new File(wwwDir, "index.html").exists() || pkg.versionCode > activeCode()) {
                    File bundled = new File(getFilesDir(), "bundled_tmp");
                    deleteRecursive(bundled);
                    copyAssets("www", bundled);
                    UpdateStorage.install(bundled, wwwDir);
                }
            }
        } catch (Exception e) { throw new RuntimeException("初始化资源失败", e); }
        web = new WebView(this);
        // 音乐时长回调：原生取到曲目时长后交给网页（用于识别并跳过 30 秒试听片段）。
        VideoActivity.durationListener=(key,ms)->runOnUiThread(()->{if(web!=null&&!isFinishing())web.evaluateJavascript("window.__musicDuration && window.__musicDuration("+JSONObject.quote(key)+","+ms+")",null);});
        // 音乐播完回调：网页推进队列后 playMusic 原地换曲（后台不触发启动限制）。
        VideoActivity.endListener=(key)->runOnUiThread(()->{if(web!=null&&!isFinishing())web.evaluateJavascript("window.__videoEnded && window.__videoEnded("+JSONObject.quote(key)+")",null);});
        web.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) web.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        web.setBackgroundColor(android.graphics.Color.rgb(16, 19, 24));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith(ORIGIN)) return false;
                openBrowser(url); return true;
            }
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest req) {
                String url = req.getUrl().toString();
                // 封面等图片子资源放行（仅 HTTPS 图片请求）；脚本/接口等其余外部资源一律阻断。
                if (!url.startsWith(ORIGIN)) {
                    String accept = "";
                    java.util.Map<String, String> heads = req.getRequestHeaders();
                    if (heads != null) for (java.util.Map.Entry<String, String> h : heads.entrySet())
                        if ("accept".equalsIgnoreCase(h.getKey())) accept = String.valueOf(h.getValue());
                    String path = req.getUrl().getPath() == null ? "" : req.getUrl().getPath().toLowerCase(java.util.Locale.ROOT);
                    boolean imageByUrl = path.matches(".*\\.(jpe?g|png|webp|gif|avif|bmp|svg)");
                    if (!req.isForMainFrame() && "GET".equals(req.getMethod()) && url.startsWith("https://")
                            && req.getUrl().getUserInfo() == null && (accept.contains("image/") || imageByUrl))
                        return null;
                    return response(403, "Forbidden", "text/plain", "External resource blocked");
                }
                try {
                    String name = req.getUrl().getPath();
                    if (name.equals("/")) name = "/index.html";
                    File file = new File(wwwDir, name.substring(1));
                    if (!file.getCanonicalPath().startsWith(wwwDir.getCanonicalPath() + File.separator) || !file.isFile())
                        return response(404, "Not Found", "text/plain", "Not found");
                    String mime = name.endsWith(".js") ? "application/javascript" : name.endsWith(".css") ? "text/css" : name.endsWith(".json") ? "application/json" : "text/html";
                    java.util.Map<String,String> headers = new java.util.HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    headers.put("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'");
                    return new android.webkit.WebResourceResponse(mime, "UTF-8", 200, "OK", headers, new FileInputStream(file));
                } catch (Exception e) { return response(500, "Error", "text/plain", "Read error"); }
            }
        });
        web.addJavascriptInterface(new Bridge(), "TVBridge");
        setContentView(web);
        loadHome();
        homeHandler.postDelayed(() -> { try { new File(getFilesDir(), "last_crash.txt").delete(); } catch (Exception ignored) { } }, 90_000);
    }

    private void loadHome() {
        pageReady = false;
        homeHandler.removeCallbacksAndMessages(null);
        try { UpdateStorage.startLoad(getFilesDir()); }
        catch (Exception e) { android.widget.Toast.makeText(this, "无法准备首页资源", android.widget.Toast.LENGTH_LONG).show(); return; }
        web.loadUrl(ORIGIN + "index.html?v=" + activeCode());
        homeHandler.postDelayed(() -> {
            if (pageReady || web == null) return;
            try {
                if (new File(getFilesDir(), "www_old").exists()) rejectCurrentUpdate();
                if (UpdateStorage.rollback(getFilesDir())) {
                    android.widget.Toast.makeText(this, "新版首页未能启动，已恢复旧版", android.widget.Toast.LENGTH_LONG).show();
                    loadHome();
                }
            } catch (Exception e) { android.widget.Toast.makeText(this, "恢复资源失败，请重启应用", android.widget.Toast.LENGTH_LONG).show(); }
        }, 15000);
    }

    private void rejectCurrentUpdate() {
        getSharedPreferences("tv_update", MODE_PRIVATE).edit().putInt("rejected", activeCode()).remove("pending").commit();
    }

    private android.webkit.WebResourceResponse response(int code, String reason, String mime, String text) {
        return new android.webkit.WebResourceResponse(mime, "UTF-8", code, reason, null,
            new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void openBrowser(String url) {
        if (!BrowserActivity.isWebUrl(url)) return;
        if (PrivacyState.enabled && Build.VERSION.SDK_INT < 28) {
            android.widget.Toast.makeText(this,"此系统不支持隔离的无痕网页；网盘内搜索和播放仍可无痕使用",android.widget.Toast.LENGTH_LONG).show(); return;
        }
        android.content.Intent intent = new android.content.Intent(this, PrivacyState.enabled ? PrivateBrowserActivity.class : BrowserActivity.class);
        intent.putExtra("url", url);
        if (PrivacyState.enabled) {
            String[] values = new String[PrivacyState.HOSTS.length];
            for (int i=0;i<values.length;i++) values[i]=PrivacyState.cookies(PrivacyState.HOSTS[i]);
            intent.putExtra("cookies",values); startActivityForResult(intent,71);
        } else startActivity(intent);
    }

    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request,result,data);
        if(request==72){
            final String id=pending4kvmId;pending4kvmId=null;
            if(id!=null){JSONObject reply=new JSONObject();try{
                String url=result==RESULT_OK&&data!=null?data.getStringExtra("url"):null;
                if(VideoActivity.allowedPublic(url)){reply.put("ok",true);reply.put("data",new JSONObject().put("url",url));}
                else{reply.put("ok",false);reply.put("error",data==null?"4KVM 播放地址解析失败":data.getStringExtra("error"));}
            }catch(Exception ignored){}
            if(web!=null&&!isFinishing())web.evaluateJavascript("window.__vodReply && window.__vodReply("+JSONObject.quote(id)+","+reply.toString()+")",null);}
        }
        if (request==71 && result==RESULT_OK && PrivacyState.enabled && data!=null) {
            String[] values=data.getStringArrayExtra("cookies");
            if(values!=null)for(int i=0;i<Math.min(values.length,PrivacyState.HOSTS.length);i++)PrivacyState.importSession(PrivacyState.HOSTS[i],values[i]);
        }
        // 视频自然播完（RESULT_OK + ended）时通知网页连播下一集；手动返回不带 ended，不会触发。
        if(result==RESULT_OK&&data!=null&&data.getBooleanExtra("ended",false)){
            final String endedKey=data.getStringExtra("key");
            runOnUiThread(()->{if(web!=null&&!isFinishing())web.evaluateJavascript("window.__videoEnded && window.__videoEnded("+JSONObject.quote(endedKey==null?"":endedKey)+")",null);});
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.Netdisk && window.Netdisk.refreshAccount && window.Netdisk.refreshAccount()",null);
        if (web != null) web.evaluateJavascript("window.Bookmarks && window.Bookmarks.refresh()",null);
    }
    @Override public void onBackPressed() {
        web.evaluateJavascript("window.TVHome && window.TVHome.back()", null);
    }
    @Override protected void onDestroy() {
        VideoActivity.durationListener=null;
        VideoActivity.endListener=null;
        netdiskPool.shutdownNow();
        vodPool.shutdownNow();
        homeHandler.removeCallbacksAndMessages(null);
        if (web != null) { web.removeJavascriptInterface("TVBridge"); web.destroy(); web = null; }
        super.onDestroy();
    }

    /** 递归拷贝 assets 资源到内部存储 */
    private void copyAssets(String assetDir, File target) {
        try {
            String[] list = getAssets().list(assetDir);
            if (!target.exists()) target.mkdirs();
            for (String name : list) {
                String sub = assetDir + "/" + name;
                File out = new File(target, name);
                if (getAssets().list(sub).length > 0) {
                    copyAssets(sub, out);
                } else {
                    InputStream in = getAssets().open(sub);
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    fo.close();
                    in.close();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("初始化资源失败", e);
        }
    }

    private void jsCallback(final String cb, final JSONObject obj) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (web != null && !isFinishing()) {
                    web.evaluateJavascript(
                            "if(window.__updateStatus){window.__updateStatus(" + obj.toString() + ");}", null);
                }
            }
        });
    }

    private Network wifiNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        if (Build.VERSION.SDK_INT >= 23) {
            Network n = cm.getActiveNetwork();
            if (n == null) return null;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? n : null;
        }
        NetworkInfo active = cm.getActiveNetworkInfo();
        if (active == null || !active.isConnected() || active.getType() != ConnectivityManager.TYPE_WIFI) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkInfo info = cm.getNetworkInfo(n);
            if (info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI) return n;
        }
        return null;
    }

    private class Bridge {
        @JavascriptInterface public void vodRequest(final String id,final String host,final String path){
            if(!id.matches("[a-zA-Z0-9_-]{1,40}"))return;
            vodPool.execute(()->{JSONObject reply=new JSONObject();try{reply.put("ok",true);reply.put("data",VodHttp.fetch(host,path));}
                catch(Exception e){try{reply.put("ok",false);reply.put("error",e.getMessage()==null?"影视来源连接失败":e.getMessage());}catch(Exception ignored){}}
                runOnUiThread(()->{if(web!=null&&!isFinishing())web.evaluateJavascript("window.__vodReply && window.__vodReply("+JSONObject.quote(id)+","+reply.toString()+")",null);});});
        }
        @JavascriptInterface public void vodApiRequest(final String id,final String base,final String query){
            if(!id.matches("[a-zA-Z0-9_-]{1,40}"))return;
            vodPool.execute(()->{JSONObject reply=new JSONObject();try{reply.put("ok",true);reply.put("data",VodHttp.fetchApi(base,query));}
                catch(Exception e){try{reply.put("ok",false);reply.put("error",e.getMessage()==null?"接口连接失败":e.getMessage());}catch(Exception ignored){}}
                runOnUiThread(()->{if(web!=null&&!isFinishing())web.evaluateJavascript("window.__vodReply && window.__vodReply("+JSONObject.quote(id)+","+reply.toString()+")",null);});});
        }
        @JavascriptInterface public void vod4kvmResolve(final String id,final String path){
            if(!id.matches("[a-zA-Z0-9_-]{1,40}")||!Vod4kvmHttp.allowedPath(path)||!path.startsWith("/play/"))return;
            runOnUiThread(()->{
                if(pending4kvmId!=null){JSONObject reply=new JSONObject();try{reply.put("ok",false).put("error","已有 4KVM 选集正在解析");}catch(Exception ignored){}
                    web.evaluateJavascript("window.__vodReply && window.__vodReply("+JSONObject.quote(id)+","+reply.toString()+")",null);return;}
                pending4kvmId=id;android.content.Intent intent=new android.content.Intent(MainActivity.this,Vod4kvmResolveActivity.class);intent.putExtra("path",path);startActivityForResult(intent,72);
            });
        }
        @JavascriptInterface public void probeRequest(final String id,final String url){
            if(!id.matches("[a-zA-Z0-9_-]{1,40}")||url==null||url.length()>500)return;
            vodPool.execute(()->{JSONObject reply=new JSONObject();try{reply.put("ok",true);reply.put("data",VodProbe.fetch(url));}
                catch(Exception e){try{reply.put("ok",false);reply.put("error",e.getMessage()==null?"站点探测失败":e.getMessage());}catch(Exception ignored){}}
                runOnUiThread(()->{if(web!=null&&!isFinishing())web.evaluateJavascript("window.__probeReply && window.__probeReply("+JSONObject.quote(id)+","+reply.toString()+")",null);});});
        }
        @JavascriptInterface public void dealRequest(final String id,final String url){
            if(!id.matches("[a-zA-Z0-9_-]{1,40}")||url==null||url.length()>500)return;
            vodPool.execute(()->{JSONObject reply=new JSONObject();try{reply.put("ok",true);reply.put("data",DealHttp.fetch(url));}
                catch(Exception e){try{reply.put("ok",false);reply.put("error",e.getMessage()==null?"订阅源连接失败":e.getMessage());}catch(Exception ignored){}}
                runOnUiThread(()->{if(web!=null&&!isFinishing())web.evaluateJavascript("window.__dealReply && window.__dealReply("+JSONObject.quote(id)+","+reply.toString()+")",null);});});
        }
        @JavascriptInterface public void musicRequest(final String id,final String query){
            if(!id.matches("[a-zA-Z0-9_-]{1,40}"))return;
            vodPool.execute(()->{JSONObject reply=new JSONObject();try{reply.put("ok",true);reply.put("data",MusicHttp.fetch(query));}
                catch(Exception e){try{reply.put("ok",false);reply.put("error",e.getMessage()==null?"音乐来源连接失败":e.getMessage());}catch(Exception ignored){}}
                runOnUiThread(()->{if(web!=null&&!isFinishing())web.evaluateJavascript("window.__musicReply && window.__musicReply("+JSONObject.quote(id)+","+reply.toString()+")",null);});});
        }
        @JavascriptInterface public void playVodVideo(final String url,final String title,final String key,final String refererHost){
            if(!VideoActivity.allowedPublic(url))return;
            runOnUiThread(()->{android.content.Intent i=new android.content.Intent(MainActivity.this,VideoActivity.class);i.putExtra("url",url);i.putExtra("title",title);i.putExtra("key",key);i.putExtra("publicSource",true);i.putExtra("vodSource",true);i.putExtra("refererHost",refererHost==null?"":refererHost);startActivityForResult(i,11);});
        }
        @JavascriptInterface public String currentPage() {
            JSONObject o=new JSONObject();try{if(!PrivacyState.enabled){o.put("url",BrowserActivity.lastUrl);o.put("title",BrowserActivity.lastTitle);}}catch(Exception ignored){}return o.toString();
        }
        @JavascriptInterface public void clearWebCache(){runOnUiThread(()->{web.clearCache(true);android.widget.Toast.makeText(MainActivity.this,"已清理网页缓存；登录、书签和下载文件保留",android.widget.Toast.LENGTH_LONG).show();});}
        @JavascriptInterface public String bookmarkRequest(String json) { return BookmarkStore.request(MainActivity.this,json); }
        @JavascriptInterface public boolean privacyEnabled() { return PrivacyState.enabled; }
        @JavascriptInterface public void setPrivacy(final boolean value) {
            // 不在异步 UI 消息中切换，确保紧随其后的 RPC 已采用新状态。
            PrivacyState.set(MainActivity.this,value);
            runOnUiThread(() -> { if(value)getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); });
        }
        @JavascriptInterface public void netdiskRequest(final String id, final String text) {
            if (!id.matches("[a-zA-Z0-9_-]{1,40}") || text.length() > 32768) return;
            netdiskPool.execute(() -> {
                JSONObject result = new JSONObject();
                try {
                    JSONObject request = new JSONObject(text);
                    result.put("ok", true); result.put("data", NetdiskHttp.call(request.getString("action"), request.optJSONObject("args") == null ? new JSONObject() : request.getJSONObject("args")));
                } catch (Exception e) {
                    try { result.put("ok", false); result.put("error", e.getMessage() == null ? "网盘请求失败" : e.getMessage()); } catch (Exception ignored) { }
                }
                runOnUiThread(() -> {
                    if (web != null && !isFinishing()) web.evaluateJavascript("window.__netdiskReply && window.__netdiskReply(" + JSONObject.quote(id) + "," + result.toString() + ")", null);
                });
            });
        }
        @JavascriptInterface public void playVideo(final String url, final String title, final String key) {
            runOnUiThread(() -> {
                if (!VideoActivity.allowed(url)) return;
                android.content.Intent intent = new android.content.Intent(MainActivity.this, VideoActivity.class);
                intent.putExtra("url", url); intent.putExtra("title", title); intent.putExtra("key", key);
                startActivity(intent);
            });
        }
        @JavascriptInterface public void playPublicVideo(final String url,final String title,final String key){
            if(!VideoActivity.allowedPublic(url))return;
            runOnUiThread(()->{android.content.Intent intent=new android.content.Intent(MainActivity.this,VideoActivity.class);
                intent.putExtra("publicSource",true);intent.putExtra("url",url);intent.putExtra("title",title);intent.putExtra("key",key);startActivityForResult(intent,12);});
        }
        // 音乐播放：附加歌词（播放页居中滚动显示）；已有音乐播放页时原地换曲，否则新开播放页。
        @JavascriptInterface public void playMusic(final String url,final String title,final String key,final String lyrics){
            if(!VideoActivity.allowedPublic(url))return;
            runOnUiThread(()->{
                VideoActivity live=VideoActivity.liveMusic();
                if(live!=null&&live.swapMusic(url,title,key,lyrics))return;
                android.content.Intent intent=new android.content.Intent(MainActivity.this,VideoActivity.class);
                intent.putExtra("publicSource",true);intent.putExtra("url",url);intent.putExtra("title",title);intent.putExtra("key",key);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if(lyrics!=null&&!lyrics.isEmpty())intent.putExtra("lyrics",lyrics.substring(0,Math.min(lyrics.length(),65536)));
                startActivityForResult(intent,12);
            });
        }
        @JavascriptInterface public void openUrl(final String url) {
            runOnUiThread(() -> openBrowser(url));
        }
        @JavascriptInterface public void openDownloads() {
            runOnUiThread(() -> {
                if (PrivacyState.enabled) { android.widget.Toast.makeText(MainActivity.this,"请关闭无痕后查看下载",android.widget.Toast.LENGTH_SHORT).show(); return; }
                try { startActivity(new android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)); }
                catch (Exception e) { android.widget.Toast.makeText(MainActivity.this, "此设备没有下载列表应用", android.widget.Toast.LENGTH_SHORT).show(); }
            });
        }
        @JavascriptInterface public void reloadHome() {
            runOnUiThread(() -> loadHome());
        }
        @JavascriptInterface public void pageReady() {
            runOnUiThread(() -> {
                pageReady = true;
                homeHandler.removeCallbacksAndMessages(null);
                try { UpdateStorage.confirm(getFilesDir()); }
                catch (Exception e) { android.util.Log.w("TV", "Deferred resource cleanup"); }
                if (web != null) web.evaluateJavascript("if(window.__preloadTabs)window.__preloadTabs()", null);
            });
        }
        private volatile JSONObject recentUpdate;
        private volatile long recentAt;
        /** 网页完成首页二次返回确认并保存进度后关闭应用。 */
        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(new Runnable() {
                public void run() { MainActivity.this.finishAndRemoveTask(); }
            });
        }

        @JavascriptInterface
        public String networkType() {
            try {
                if (wifiNetwork() != null) return "wifi";
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected() ? "other" : "none";
            } catch (Exception e) { return "unknown"; }
        }

        @JavascriptInterface
        public void applyWifiUpdate(final String cb) { update(cb, true); }

        @JavascriptInterface
        public boolean supportsResume() { return true; }


        /** 当前生效资源版本，供 JS 显示在家长中心 */
        @JavascriptInterface
        public String versionInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("code", activeCode());
                o.put("name", activeName());
                File crash = new File(getFilesDir(), "last_crash.txt");
                if (crash.isFile()) {
                    try (InputStream input = new FileInputStream(crash)) {
                        byte[] bytes = new byte[240];
                        int n = input.read(bytes);
                        if (n > 0) o.put("crash", new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8).split("\n")[0].trim());
                    }
                }
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** 异步检查更新，状态通过 window.__updateStatus(JSON) 回调 */
        @JavascriptInterface
        public void startCheck(final String cb) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        JSONObject remote = fetchVersionJson();
                        if (remote == null) {
                            recentUpdate = null;
                            cbError(cb, updateCheckError);
                            return;
                        }
                        int next = remote.optInt("versionCode", 0);
                        recentUpdate = remote; recentAt = android.os.SystemClock.elapsedRealtime();
                        JSONObject o = new JSONObject();
                        boolean wgtApplicable = next > activeCode() && remote.optInt("minShellVersion", 1) <= SHELL_VERSION;
                        boolean apkNewer = remote.optInt("apkCode", 0) > packageVersionCode() && remote.optLong("apkSize", 0) > 0;
                        // 网页层可热更：优先走 WGT；装不下/已同步时若远端 APK 更新，则提供应用内下载安装。
                        if (wgtApplicable) {
                            o.put("state", "found");
                        } else if (apkNewer) {
                            o.put("state", "foundapk");
                        } else if (next > activeCode() && remote.optInt("minShellVersion", 1) > SHELL_VERSION) {
                            // 新版本要求更高的原生壳且远端未提供 APK：明确提示覆盖安装。
                            o.put("state", "needapk");
                            o.put("version", remote.optString("versionName"));
                            o.put("notes", "该版本包含新的原生功能，应用内热更装不下；请到发布页下载最新 APK 覆盖安装。");
                            jsCallback(cb, o);
                            return;
                        } else if (next < activeCode()) {
                            cbError(cb, "更新源缓存落后于当前 v" + activeName() + "：请到发布页手动安装最新 APK，或稍后重试");
                            return;
                        } else {
                            o.put("state", "uptodate");
                            File crashFile = new File(getFilesDir(), "last_crash.txt");
                            if (crashFile.isFile()) {
                                try (InputStream input = new FileInputStream(crashFile)) {
                                    byte[] bytes = new byte[240];
                                    int n = input.read(bytes);
                                    if (n > 0) o.put("crash", new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8).split("\n")[0].trim());
                                }
                            }
                        }
                        o.put("version", next == activeCode() ? activeName() : remote.optString("versionName"));
                        o.put("notes", remote.optString("notes"));
                        jsCallback(cb, o);
                    } catch (Exception e) {
                        cbError(cb, String.valueOf(e));
                    }
                }
            }).start();
        }

        /** 异步执行更新：下载 wgt → 解压 → 替换 www */
        /** 异步执行更新：下载 wgt → 解压 → 替换 www */
        @JavascriptInterface public void applyUpdate(final String cb) { update(cb, false); }

        private int packageVersionCode() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; }
            catch (Exception e) { return 0; }
        }

        /** 异步下载新版 APK（断点续传 + SHA-256 校验）并拉起系统安装确认。 */
        @JavascriptInterface public void applyApkUpdate(final String cb) {
            if (!UPDATING.compareAndSet(false, true)) { cbError(cb, "正在更新，请稍后重试"); return; }
            new Thread(new Runnable() { public void run() {
                File archive = new File(getFilesDir(), "update.apk");
                try {
                    JSONObject remote = recentUpdate;
                    if (remote == null || android.os.SystemClock.elapsedRealtime() - recentAt >= 60000)
                        remote = fetchVersionJson(null);
                    if (remote == null) { cbError(cb, "网络不通，稍后再试"); return; }
                    recentUpdate = remote; recentAt = android.os.SystemClock.elapsedRealtime();
                    final String apkName = remote.optString("apk", "");
                    long size = remote.optLong("apkSize", 0);
                    String hash = remote.optString("apkSha256", "");
                    if (!apkName.matches("[A-Za-z0-9._-]+\\.apk") || size <= 0 || size > 64L * 1024 * 1024 || !hash.matches("[a-fA-F0-9]{64}"))
                        throw new Exception("更新信息缺少有效的 APK 下载项");
                    if (remote.optInt("apkCode", 0) <= packageVersionCode()) { cbError(cb, "当前 APK 已是最新"); return; }
                    List<String> bases = new ArrayList<>();
                    bases.add(remote.optString("_base", CDNS[0]));
                    for (String base : CDNS) if (!bases.contains(base)) bases.add(base);
                    Exception failure = null;
                    boolean downloaded = false;
                    for (String base : bases) {
                        try {
                            final String url = base + apkName;
                            UpdateDownload.fetch(new URL(url), archive, size, hash,
                                    u -> {
                                        HttpURLConnection c = (HttpURLConnection) u.openConnection();
                                        c.setConnectTimeout(8000); c.setReadTimeout(15000);
                                        c.setRequestProperty("User-Agent", NetdiskHttp.UA);
                                        c.setRequestProperty("Accept-Encoding", "identity");
                                        return c;
                                    },
                                    () -> { },
                                    (done, total) -> jsCallback(cb, new JSONObject().put("state", "downloadingapk").put("progress", total == 0 ? 0 : (double) done / total)));
                            downloaded = true; break;
                        } catch (Exception e) { failure = e; }
                    }
                    if (!downloaded) throw failure != null ? failure : new java.io.IOException("所有更新源均不可用");
                    if (!hash.equalsIgnoreCase(UpdateDownload.sha256(archive))) throw new Exception("APK 校验不符，请重试");
                    jsCallback(cb, new JSONObject().put("state", "installready").put("version", remote.optString("versionName")));
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, "com.seagull.tv.apk", archive);
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "application/vnd.android.package-archive");
                    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ex) {
                    String msg = ex.getMessage() == null ? String.valueOf(ex) : ex.getMessage();
                    if (msg.contains("INSTALL")) msg = "请在系统设置中允许本应用“安装未知应用”后重试";
                    cbError(cb, msg);
                } finally { UPDATING.set(false); }
            }}).start();
        }

        private void update(final String cb, final boolean wifiOnly) {
            if (!UPDATING.compareAndSet(false, true)) {
                cbError(cb, "正在更新，请稍后重试");
                return;
            }
            new Thread(new Runnable() {
                public void run() {
                    File tmp = new File(getFilesDir(), "update_tmp");
                    File archive = new File(getFilesDir(), "update.wgt");
                    try {
                        Network wifi = wifiOnly ? wifiNetwork() : null;
                        if (wifiOnly && wifi == null) { waitingWifi(cb); return; }
                        JSONObject remote = recentUpdate;
                        if (remote == null || android.os.SystemClock.elapsedRealtime() - recentAt >= 60000)
                            remote = fetchVersionJson(wifi);
                        if (remote == null) {
                            cbError(cb, "网络不通，稍后再试");
                            return;
                        }
                        if (remote.optInt("versionCode") == getSharedPreferences("tv_update", MODE_PRIVATE).getInt("rejected", -1))
                            throw new Exception("该资源版本未能正常启动，已跳过，请等待修复版本");
                        if (remote.optInt("versionCode") > activeCode() && remote.optInt("minShellVersion", 1) > SHELL_VERSION)
                            throw new Exception("该版本需要先覆盖安装新版 APK，无法通过应用内热更获得");
                        if (remote.optInt("versionCode") < activeCode()) {
                            cbError(cb, "更新源缓存落后于当前 v" + activeName() + "：请手动安装最新 APK，或稍后重试");
                            return;
                        }
                        if (remote.optInt("versionCode") == activeCode()) {
                            JSONObject current = new JSONObject(); current.put("state", "uptodate");
                            current.put("version", activeName()); jsCallback(cb, current); return;
                        }
                        long expectedSize = remote.optLong("wgtSize", 0);
                        String expectedHash = remote.optString("wgtSha256", "");
                        if (expectedSize <= 0 || expectedSize > 32L * 1024 * 1024 || !expectedHash.matches("[a-fA-F0-9]{64}")) throw new Exception("更新信息缺少有效的完整性校验，请稍后重试");
                        downloadUpdate(remote, archive, tmp, wifi, wifiOnly, cb);
                        JSONObject packaged = new JSONObject(readLocal(new File(tmp, "manifest.json")));
                        if (!packaged.optString("appId").equals("com.seagull.tv") || packaged.optInt("minShellVersion", 1) > SHELL_VERSION
                                || packaged.optInt("versionCode") != remote.optInt("versionCode")
                                || !packaged.optString("versionName").equals(remote.optString("versionName"))) {
                            UpdateDownload.discard(archive);
                            throw new Exception("更新包版本与更新信息不一致，旧版已保留");
                        }
                        if (wifiOnly && !wifi.equals(wifiNetwork())) { waitingWifi(cb); return; }
                        UpdateStorage.install(tmp, wwwDir);
                        try { UpdateDownload.discard(archive); } catch (java.io.IOException ignored) { }
                        getSharedPreferences("tv_update", MODE_PRIVATE).edit().remove("pending").commit();
                        JSONObject ok = new JSONObject();
                        ok.put("state", "done");
                        ok.put("version", remote.optString("versionName"));
                        jsCallback(cb, ok);
                    } catch (Exception ex) {
                        if (wifiOnly && wifiNetwork() == null) waitingWifi(cb);
                        else cbError(cb, String.valueOf(ex));
                    } finally {
                        deleteRecursive(tmp);
                        UPDATING.set(false);
                    }
                }
            }).start();
        }

        private void downloadUpdate(JSONObject remote, File archive, File tmp, final Network wifi,
                                    final boolean wifiOnly, final String cb) throws Exception {
            long expectedSize = remote.optLong("wgtSize", 0);
            String expectedHash = remote.optString("wgtSha256", "");
            List<String> bases = new ArrayList<>();
            bases.add(remote.optString("_base", CDNS[0]));
            for (String base : CDNS) if (!bases.contains(base)) bases.add(base);
            Exception failure = null;
            for (String base : bases) {
                if (wifiOnly && !wifi.equals(wifiNetwork())) throw new java.io.IOException("Wi-Fi 已断开，等待重连后重试");
                deleteRecursive(tmp);
                if (!tmp.mkdirs()) throw new java.io.IOException("无法创建更新目录，请检查存储空间");
                URL u = new URL(base + remote.optString("wgt"));
                try {
                    UpdateDownload.fetch(u, archive, expectedSize, expectedHash,
                            new UpdateDownload.Connection() {
                                public HttpURLConnection open(URL url) throws Exception {
                                    return (HttpURLConnection) (wifi == null ? url.openConnection() : wifi.openConnection(url));
                                }
                            },
                            new UpdateDownload.Check() {
                                public void run() throws java.io.IOException {
                                    if (wifiOnly && !wifi.equals(wifiNetwork())) throw new java.io.IOException("Wi-Fi 已断开");
                                }
                            },
                            new UpdateDownload.Progress() {
                                public void update(long done, long total) throws Exception {
                                    JSONObject p = new JSONObject(); p.put("state", "downloading");
                                    p.put("progress", (double) done / total); jsCallback(cb, p);
                                }
                            });
                    try { UpdatePackage.verifyAndExtract(archive, tmp, expectedSize, expectedHash); }
                    catch (Exception invalid) { UpdateDownload.discard(archive); throw invalid; }
                    return;
                } catch (Exception e) { failure = e; }
            }
            throw new java.io.IOException("所有更新包下载源均失败，当前版本已保留，请稍后重试", failure);
        }

        private void waitingWifi(String cb) {
            try { JSONObject o = new JSONObject(); o.put("state", "waitingwifi"); jsCallback(cb, o); }
            catch (Exception ignored) { }
        }

        private void cbError(String cb, String msg) {
            try {
                JSONObject o = new JSONObject();
                o.put("state", "error");
                o.put("msg", msg);
                jsCallback(cb, o);
            } catch (Exception ignored) {
            }
        }
    }

    private int activeCode() {
        try {
            int code = new JSONObject(readLocal(new File(wwwDir, "manifest.json"))).optInt("versionCode");
            if (code > 0) return code;
        } catch (Exception ignored) { }
        int c = getSharedPreferences("app", MODE_PRIVATE).getInt("code", -1);
        if (c > 0) return c;
        try {
            return Integer.parseInt(readLocal(new File(wwwDir, "eq_version.txt")).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String activeName() {
        try {
            String name = new JSONObject(readLocal(new File(wwwDir, "manifest.json"))).optString("versionName");
            if (!name.isEmpty()) return name;
        } catch (Exception ignored) { }
        String n = getSharedPreferences("app", MODE_PRIVATE).getString("name", null);
        if (n != null) return n;
        try {
            return readLocal(new File(wwwDir, "eq_version_name.txt")).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String readLocal(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = fi.read(b)) > 0) bo.write(b, 0, n);
        fi.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    /** 已经发现的新版本持久保存，旧CDN响应或暂时离线不能抹掉待完成更新。 */
    private JSONObject pendingUpdate() {
        try {
            String text = getSharedPreferences("tv_update", MODE_PRIVATE).getString("pending", "");
            if (text.isEmpty()) return null;
            JSONObject o = new JSONObject(text);
            if (o.optInt("versionCode") <= activeCode() || o.optInt("versionCode") == getSharedPreferences("tv_update", MODE_PRIVATE).getInt("rejected", -1)) {
                getSharedPreferences("tv_update", MODE_PRIVATE).edit().remove("pending").commit();
                return null;
            }
            if (o.optString("appId").equals("com.seagull.tv") && o.optInt("minShellVersion", 1) <= SHELL_VERSION && o.optString("wgt").matches("[A-Za-z0-9._-]+\\.wgt") && o.optLong("wgtSize", 0) > 0
                    && o.optString("wgtSha256").matches("[a-fA-F0-9]{64}")) return o;
        } catch (Exception ignored) { }
        return null;
    }

    /** 并行读取各源，缓存旧版本不能遮住其他源的新版本。 */
    private JSONObject fetchVersionJson() throws Exception { return fetchVersionJson(null); }

    private volatile String updateCheckError="未取得有效更新清单，请稍后重试；当前版本保留。";

    private JSONObject fetchVersionJson(final Network wifi) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CDNS.length);
        List<Callable<JSONObject>> tasks = new ArrayList<>();
        final java.util.Set<String> failures=java.util.Collections.synchronizedSet(new java.util.TreeSet<String>());
        for (final String base : CDNS) {
            tasks.add(new Callable<JSONObject>() {
                public JSONObject call() throws Exception {
                    URL u = new URL(base + "version.json?t=" + System.currentTimeMillis());
                    HttpURLConnection c = (HttpURLConnection) (wifi == null ? u.openConnection() : wifi.openConnection(u));
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    c.setUseCaches(false);
                    try {
                        int status=c.getResponseCode();
                        if(status!=200){failures.add("HTTP "+status);return null;}
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        try (InputStream in = c.getInputStream()) {
                            byte[] b = new byte[4096];
                            int n;
                            while ((n = in.read(b)) != -1) {
                                if (bo.size() + n > 65536) return null;
                                bo.write(b, 0, n);
                            }
                        }
                        JSONObject o = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
                        if (o.optInt("versionCode") == getSharedPreferences("tv_update", MODE_PRIVATE).getInt("rejected", -1)) return null;
                        if (!o.optString("appId").equals("com.seagull.tv") || o.optInt("minShellVersion", 1) > SHELL_VERSION || o.optInt("versionCode", 0) <= 0 || o.optString("versionName").trim().isEmpty()
                                || !o.optString("wgt").matches("[A-Za-z0-9._-]+\\.wgt")) return null;
                        // 新版本只有具备完整校验信息才可作为可安装候选。
                        if (o.optInt("versionCode") > activeCode() && (o.optLong("wgtSize", 0) <= 0 || o.optLong("wgtSize", 0) > 32L * 1024 * 1024
                                || !o.optString("wgtSha256").matches("[a-fA-F0-9]{64}"))) return null;
                        o.put("_base", base);
                        return o;
                    } catch(Exception e){failures.add(e instanceof java.net.SocketTimeoutException?"连接超时":"连接或清单解析失败");return null;}
                    finally { c.disconnect(); }
                }
            });
        }
        // 第 4 源：GitHub Contents API（api.github.com 与 github.com 链路不同且内容实时），
        // 用于对抗 jsDelivr 边缘节点对 @main 分支文件的长期缓存（缓存穿透参数会被忽略）。
        tasks.add(new Callable<JSONObject>() {
            public JSONObject call() throws Exception {
                URL u = new URL("https://api.github.com/repos/gitHubLinHai/TV/contents/release/version.json");
                HttpURLConnection c = (HttpURLConnection) (wifi == null ? u.openConnection() : wifi.openConnection(u));
                c.setConnectTimeout(4000);
                c.setReadTimeout(6000);
                c.setUseCaches(false);
                c.setRequestProperty("Accept", "application/vnd.github.raw");
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TV-Update-Check");
                try {
                    int status = c.getResponseCode();
                    if (status != 200) { failures.add("API HTTP " + status); return null; }
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    try (InputStream in = c.getInputStream()) {
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = in.read(b)) != -1) {
                            if (bo.size() + n > 262144) return null;
                            bo.write(b, 0, n);
                        }
                    }
                    String body = new String(bo.toByteArray(), "UTF-8").trim();
                    if (body.isEmpty() || body.charAt(0) != '{') { failures.add("API 清单无效"); return null; }
                    JSONObject o = new JSONObject(body);
                    if (!o.optString("appId").equals("com.seagull.tv") || o.optInt("versionCode", 0) <= 0) { failures.add("API 清单无效"); return null; }
                    o.put("_base", CDNS[0]);
                    return o;
                } finally { c.disconnect(); }
            }
        });
        try {
            JSONObject best = pendingUpdate();
            for (Future<JSONObject> result : pool.invokeAll(tasks, 9, TimeUnit.SECONDS)) {
                if (result.isCancelled()) continue;
                try {
                    JSONObject candidate = result.get();
                    if (candidate != null && (best == null || candidate.optInt("versionCode") > best.optInt("versionCode")))
                        best = candidate;
                } catch (Exception ignored) { }
            }
            if (best != null && best.optInt("versionCode") > activeCode())
                getSharedPreferences("tv_update", MODE_PRIVATE).edit().putString("pending", best.toString()).commit();
            if(best==null){synchronized(failures){updateCheckError=failures.size()==1&&failures.contains("HTTP 404")?"更新文件尚未发布（所有源 HTTP 404）。需发布 version.json 和更新包；当前版本可继续使用。":"未取得可安装的更新清单："+(failures.isEmpty()?"清单无效或与当前 APK 不兼容":failures.toString())+"。当前版本保留。";}}
            return best;
        } finally { pool.shutdownNow(); }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
