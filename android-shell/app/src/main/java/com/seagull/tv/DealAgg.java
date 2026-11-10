package com.seagull.tv;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

/**
 * 优惠线报聚合抓取：只抓站点主动开放的 feed 出口（RSS/Atom/JSON），不抓 HTML 页面。
 *
 * 降风控设计（核心）：
 * 1) 路径白名单：只允许 /rss/、/feed/ 这类订阅路径，从代码层面杜绝触发页面级验证码。
 * 2) 条件请求：保存并回传 ETag / Last-Modified，服务端返回 304 时不下载正文。
 * 3) 串行温和抓取：源与源之间固定间隔，绝不并发轰炸同一站点。
 * 4) 体积与超时硬上限，任何异常都只在本地记录，不重试。
 */
public final class DealAgg {
    private DealAgg() {}

    /** 源与源之间的最小间隔（毫秒）：温和抓取，避免被识别为爬虫。 */
    private static final long SOURCE_GAP_MS = 2000;
    /** 单次响应上限。 */
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    /** 单域名在本次会话内最多抓取的源数量，防止配置失控。 */
    private static final int MAX_PER_HOST = 3;
    /** 宿主白名单前缀长度上限。 */
    private static final int MAX_HOST = 80;

    /** 只允许订阅出口路径；任何 HTML 页面路径都会被拒绝。 */
    public static boolean allowedFeedPath(String path) {
        if (path == null || path.length() > 200) return false;
        String p = path.toLowerCase(Locale.ROOT);
        // 允许 /rss/ /feed/ /feed /rss，以及带查询串的分类订阅（如 /feed/?cat=1）
        if (!p.startsWith("/rss") && !p.startsWith("/feed")) return false;
        // 禁止任何可疑路径分隔或穿越
        return p.indexOf("..") < 0 && p.indexOf('\\') < 0;
    }

    public static boolean allowedHost(String host) {
        return host != null && host.length() <= MAX_HOST && host.matches("[a-zA-Z0-9.-]+")
            && host.indexOf('.') > 0 && !host.startsWith(".") && !host.endsWith(".") && !host.contains("..");
    }

    /** 单个源的结构化抓取结果：命中 304 时 notModified=true，body 为空。 */
    public static JSONObject fetch(String url, String etag, String lastModified) throws Exception {
        if (url == null || url.length() > 500 || !url.startsWith("https://")) throw new IOException("源地址无效：需为 https:// 开头");
        URI uri = new URI(url);
        String host = uri.getHost();
        if (!allowedHost(host)) throw new IOException("源域名无效");
        if (!allowedFeedPath(uri.getPath())) throw new IOException("只支持订阅源路径（/rss 或 /feed）");
        if (uri.getRawQuery() != null && uri.getRawQuery().length() > 120) throw new IOException("源地址参数过长");

        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", NetdiskHttp.UA);
        c.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, application/json, text/xml;q=0.9, */*;q=0.5");
        c.setRequestProperty("Accept-Encoding", "gzip");
        // 条件请求：内容没变时服务端只回 304，服务器成本接近于零。
        if (etag != null && !etag.isEmpty()) c.setRequestProperty("If-None-Match", etag);
        if (lastModified != null && !lastModified.isEmpty()) c.setRequestProperty("If-Modified-Since", lastModified);
        try {
            int status = c.getResponseCode();
            if (status == 304) {
                return new JSONObject().put("notModified", true);
            }
            if (status != 200) throw new IOException("源返回 HTTP " + status);
            URL finalUrl = c.getURL();
            if (!"https".equals(finalUrl.getProtocol())) throw new IOException("源重定向到了非 HTTPS 地址");

            InputStream raw = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) raw = new GZIPInputStream(raw);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = raw) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (bytes.size() + n > MAX_BYTES) throw new IOException("源响应过大");
                    bytes.write(buf, 0, n);
                }
            }
            String body = new String(bytes.toByteArray(), "UTF-8").trim();
            char first = body.isEmpty() ? ' ' : body.charAt(0);
            if (first != '<' && first != '{' && first != '[') throw new IOException("源不是 RSS/Atom/JSON 格式");

            JSONObject out = new JSONObject();
            out.put("notModified", false);
            out.put("body", body);
            String et = c.getHeaderField("ETag");
            String lm = c.getHeaderField("Last-Modified");
            out.put("etag", et == null ? "" : et);
            out.put("lastModified", lm == null ? "" : lm);
            return out;
        } finally {
            c.disconnect();
        }
    }

    /**
     * 批量抓取：串行 + 源间固定间隔。任何一个源失败只影响它自己。
     * sources 结构：[{name, url, etag, lastModified}]
     */
    public static JSONObject fetchAll(org.json.JSONArray sources) throws Exception {
        if (sources == null || sources.length() == 0) throw new IOException("没有可用的订阅源");
        if (sources.length() > 12) throw new IOException("订阅源数量超出上限");
        org.json.JSONArray results = new org.json.JSONArray();
        Map<String, Integer> hostCount = new HashMap<>();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject src = sources.optJSONObject(i);
            if (src == null) continue;
            String name = src.optString("name", "源" + (i + 1));
            String url = src.optString("url", "");
            if (name.length() > 24) name = name.substring(0, 24);

            JSONObject row = new JSONObject();
            row.put("name", name);
            row.put("url", url);
            String host = "";
            try { host = new URI(url).getHost(); } catch (Exception ignored) { }
            host = host == null ? "" : host.toLowerCase(Locale.ROOT);
            int seen = hostCount.containsKey(host) ? hostCount.get(host) : 0;
            if (seen >= MAX_PER_HOST) {
                row.put("skipped", true);
                row.put("error", "同域名源过多，已跳过");
                results.put(row);
                continue;
            }
            hostCount.put(host, seen + 1);

            try {
                JSONObject data = fetch(url, src.optString("etag", ""), src.optString("lastModified", ""));
                row.put("ok", true);
                row.put("notModified", data.optBoolean("notModified", false));
                if (!data.optBoolean("notModified", false)) {
                    row.put("body", data.optString("body", ""));
                }
                row.put("etag", data.optString("etag", ""));
                row.put("lastModified", data.optString("lastModified", ""));
            } catch (Exception e) {
                row.put("ok", false);
                String msg = e.getMessage();
                row.put("error", msg == null ? "源连接失败" : msg);
            }
            results.put(row);
            // 温和抓取：源与源之间固定间隔，不做并发。
            if (i < sources.length() - 1) {
                try { Thread.sleep(SOURCE_GAP_MS); } catch (InterruptedException ignored) { }
            }
        }
        return new JSONObject().put("results", results);
    }
}
