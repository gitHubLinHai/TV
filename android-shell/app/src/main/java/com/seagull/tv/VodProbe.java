package com.seagull.tv;

import java.io.*;
import java.net.*;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

/** 片源站点探测抓取：HTTPS、域名校验、同域跳转、2MiB 上限；返回页面/接口原始文本。 */
public final class VodProbe {
    private VodProbe() {}
    public static JSONObject fetch(String url) throws Exception {
        if (url == null || url.length() > 500 || !url.startsWith("https://")) throw new IOException("探测地址无效：需为 https:// 开头");
        int slash = url.indexOf('/', 8);
        String host = slash < 0 ? url.substring(8) : url.substring(8, slash);
        if (!VodHttp.allowedHost(host)) throw new IOException("站点域名无效");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true); c.setConnectTimeout(8000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", NetdiskHttp.UA);
        c.setRequestProperty("Accept", "text/html, application/json, */*");
        c.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("来源返回 HTTP " + status);
            URL finalUrl = c.getURL();
            if (!"https".equals(finalUrl.getProtocol()) || !VodHttp.allowedHost(finalUrl.getHost())) throw new IOException("来源重定向到了无效地址");
            InputStream raw = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) raw = new GZIPInputStream(raw);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = raw) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) { if (bytes.size() + n > 2 * 1024 * 1024) throw new IOException("来源响应过大"); bytes.write(b, 0, n); } }
            return new JSONObject().put("body", new String(bytes.toByteArray(), "UTF-8")).put("url", finalUrl.toString());
        } finally { c.disconnect(); }
    }
}
