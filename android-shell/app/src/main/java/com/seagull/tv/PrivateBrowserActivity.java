package com.seagull.tv;

import android.os.Bundle;
import android.webkit.*;

/** 独立进程、独立 WebView 目录；结束时清理，不接触普通浏览资料。 */
public final class PrivateBrowserActivity extends BrowserActivity {
    @Override public void onCreate(Bundle state) {
        if (android.os.Build.VERSION.SDK_INT < 28) { super.onCreate(null); finish(); return; }
        PrivacyState.cleanProfile(this);
        WebView.setDataDirectorySuffix("tv_private");
        PrivacyState.enabled = true;
        String[] seed = getIntent().getStringArrayExtra("cookies");
        if (seed != null) for (int i=0;i<Math.min(seed.length,PrivacyState.HOSTS.length);i++)
            if (seed[i] != null) for (String pair : seed[i].split(";"))
                CookieManager.getInstance().setCookie(PrivacyState.HOSTS[i],pair.trim()+"; Domain=.quark.cn; Path=/; Secure");
        super.onCreate(null);
        getIntent().removeExtra("cookies"); getIntent().removeExtra("url");
    }
    @Override protected boolean isPrivate() { return true; }
    @Override public void finish() {
        android.content.Intent result = new android.content.Intent();
        String[] values = new String[PrivacyState.HOSTS.length];
        for (int i=0;i<values.length;i++) values[i] = CookieManager.getInstance().getCookie(PrivacyState.HOSTS[i]);
        result.putExtra("cookies",values); setResult(RESULT_OK,result); super.finish();
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        // 先终止 WebView 写入，再移除独立目录；异常退出留下的目录在下次启动清理。
        CookieManager.getInstance().removeAllCookies(null);
        WebStorage.getInstance().deleteAllData();
        new android.os.Handler().postDelayed(() -> {
            PrivacyState.cleanProfile(this);
            android.os.Process.killProcess(android.os.Process.myPid());
        },150);
    }
}
