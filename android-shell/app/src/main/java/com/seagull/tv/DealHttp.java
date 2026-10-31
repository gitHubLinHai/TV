package com.seagull.tv;

import java.io.*;
import java.net.*;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

/** 优惠订阅源只读抓取：HTTPS、域名校验、2MiB 上限；内容须为 RSS/Atom/XML 或 JSON。 */
public final class DealHttp {
    private DealHttp() {}
    public static JSONObject fetch(String url) throws Exception {
        if (url == null || url.length() > 500 || !url.startsWith("https://")) throw new IOException("订阅地址无效：需为 https:// 开头");
        int slash = url.indexOf('/', 8);
        String host = slash < 0 ? url.substring(8) : url.substring(8, slash);
        if (!VodHttp.allowedHost(host)) throw new IOException("订阅域名无效");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true); c.setConnectTimeout(8000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", NetdiskHttp.UA);
        c.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/json, text/xml, text/html;q=0.8, */*;q=0.5");
        c.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("订阅源返回 HTTP " + status);
            URL finalUrl = c.getURL();
            if (!"https".equals(finalUrl.getProtocol())) throw new IOException("订阅源重定向到了非 HTTPS 地址");
            InputStream raw = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) raw = new GZIPInputStream(raw);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = raw) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) { if (bytes.size() + n > 2 * 1024 * 1024) throw new IOException("订阅源响应过大"); bytes.write(b, 0, n); } }
            String body = new String(bytes.toByteArray(), "UTF-8").trim();
            char first = body.isEmpty() ? ' ' : body.charAt(0);
            if (first != '<' && first != '{' && first != '[') throw new IOException("订阅源不是 RSS/Atom/JSON 格式");
            return new JSONObject().put("body", body);
        } finally { c.disconnect(); }
    }
}
