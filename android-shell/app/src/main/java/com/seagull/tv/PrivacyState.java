package com.seagull.tv;

import android.content.Context;
import android.webkit.CookieManager;
import java.io.File;
import java.net.*;
import java.util.*;

/** 无痕开关可以落盘，访问内容和会话 Cookie 只留在内存。 */
public final class PrivacyState {
    public static volatile boolean enabled;
    public static final String[] HOSTS = {"https://pan.quark.cn/", "https://drive-pc.quark.cn/", "https://quark.cn/"};
    private static java.net.CookieManager cookies = new java.net.CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    public static void init(Context context) {
        enabled = context.getSharedPreferences("tv_privacy",0).getBoolean("enabled",false);
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = ((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
        boolean active = false;
        if (processes != null) for (android.app.ActivityManager.RunningAppProcessInfo process : processes) if (process.processName.equals(context.getPackageName()+":private")) active=true;
        if (!active) cleanProfile(context);
    }
    public static synchronized void set(Context context, boolean value) {
        cookies.getCookieStore().removeAll();
        enabled = value;
        context.getSharedPreferences("tv_privacy",0).edit().putBoolean("enabled",value).commit();
    }
    public static synchronized String cookies(String url) {
        try { java.util.List<String> values = cookies.get(URI.create(url), Collections.emptyMap()).get("Cookie"); return values == null ? "" : android.text.TextUtils.join("; ", values); }
        catch (Exception e) { return ""; }
    }
    public static synchronized void receive(String url, String value) {
        try { cookies.put(URI.create(url),Collections.singletonMap("Set-Cookie",Collections.singletonList(value))); } catch (Exception ignored) { }
    }
    public static synchronized void importSession(String url, String values) {
        if (values == null) return;
        for (String pair : values.split(";")) receive(url,pair.trim()+"; Domain=.quark.cn; Path=/; Secure");
    }
    public static void cleanProfile(Context context) {
        clean(new File(context.getApplicationInfo().dataDir),"app_webview_tv_private");
        clean(context.getCacheDir(),"webview_tv_private");
        clean(context.getCodeCacheDir(),"webview_tv_private");
    }
    private static void clean(File parent, String name) {
        File root = new File(parent,name);
        try { if (!root.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile())) return; delete(root.getCanonicalFile()); }
        catch (Exception ignored) { }
    }
    private static void delete(File file) {
        try { if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) { file.delete(); return; } } catch (Exception e) { return; }
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
