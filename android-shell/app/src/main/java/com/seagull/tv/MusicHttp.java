package com.seagull.tv;

import java.io.*;
import java.net.*;
import java.util.*;
import org.json.JSONObject;

/** 固定免费音乐接口（Meting 公共实例 + 网易云来源）只读访问；不传 Cookie，不下载音频本体。 */
public final class MusicHttp {
    public static final String ORIGIN = "https://api.injahow.cn";
    public static final String GD_ORIGIN = "https://music-api.gdstudio.xyz";
    public static final String KG_SEARCH = "https://mobiles.kugou.com/api/v3/search/song";
    public static final String KG_PLAY = "https://m.kugou.com/app/i/getSongInfo.php";
    private MusicHttp() {}
    public static JSONObject fetch(String query) throws Exception {
        if (query == null || query.length() > 256) throw new IOException("不支持的音乐请求");
        if (query.startsWith("gds=1&")) return fetchGd(query.substring(6));
        if (query.startsWith("kg=1&")) return fetchKg(query.substring(5));
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) throw new IOException("不支持的音乐请求");
            params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        if (!"netease".equals(params.get("server"))) throw new IOException("仅支持网易来源的免费音乐");
        String type = params.get("type"), id = params.get("id");
        if (type == null || id == null || id.length() > 120) throw new IOException("不支持的音乐请求");
        if (type.equals("playlist") || type.equals("url") || type.equals("lrc")) { if (!id.matches("[0-9]{1,12}")) throw new IOException("不支持的榜单或曲目编号"); }
        else throw new IOException("不支持的音乐请求类型");
        for (String key : params.keySet()) if (!key.equals("server") && !key.equals("type") && !key.equals("id"))
            throw new IOException("不支持的请求参数");
        HttpURLConnection c = (HttpURLConnection) new URL(ORIGIN + "/meting/?" + query).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", NetdiskHttp.UA); c.setRequestProperty("Accept", "application/json");
        try {
            int status = c.getResponseCode();
            if (type.equals("url")) {
                String location = c.getHeaderField("Location");
                if (status == 302 && location != null) {
                    URL target = new URL(c.getURL(), location);
                    String host = target.getHost();
                    if (!"https".equals(target.getProtocol()) || host == null || !host.endsWith(".music.126.net")
                        || target.getUserInfo() != null || (target.getPort() != -1 && target.getPort() != 443))
                        throw new IOException("该曲目返回了不可用的播放地址");
                    return new JSONObject().put("url", target.toString());
                }
                throw new IOException("该曲目暂不可播放（可能需要会员或已下架）");
            }
            if (status != 200) throw new IOException("音乐来源返回 HTTP " + status);
            InputStream raw = c.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = raw) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) { if (bytes.size() + n > (type.equals("lrc") ? 512 : 2) * 1024 * 1024) throw new IOException("来源响应过大"); bytes.write(b, 0, n); } }
            String body = new String(bytes.toByteArray(), "UTF-8").trim();
            if (type.equals("lrc")) return new JSONObject().put("lrc", body);
            if (body.isEmpty() || body.charAt(0) != '[') throw new IOException("来源没有返回歌曲列表");
            return new JSONObject().put("json", body);
        } finally { c.disconnect(); }
    }
    /** 酷狗第二音源：type=search（keyword 搜索）与 type=url（hash 取直链），HTTPS。 */
    private static JSONObject fetchKg(String rest) throws Exception {
        String type = null, name = null, hash = null;
        for (String pair : rest.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) throw new IOException("不支持的酷狗参数");
            String k = pair.substring(0, eq), v = pair.substring(eq + 1);
            if (k.equals("type")) { if (!v.equals("search") && !v.equals("url")) throw new IOException("不支持的酷狗参数"); type = v; }
            else if (k.equals("name")) { if (!v.matches("[A-Za-z0-9%._-]{1,120}")) throw new IOException("搜索词包含不支持的字符"); name = v; }
            else if (k.equals("hash")) { if (!v.matches("[0-9a-fA-F]{32}")) throw new IOException("不支持的曲目标识"); hash = v; }
            else throw new IOException("不支持的酷狗参数");
        }
        if ("search".equals(type)) {
            if (name == null) throw new IOException("缺少搜索词");
            return kgGet(KG_SEARCH + "?format=json&keyword=" + name + "&page=1&pagesize=30");
        }
        if ("url".equals(type)) {
            if (hash == null) throw new IOException("缺少曲目标识");
            return kgGet(KG_PLAY + "?cmd=playInfo&hash=" + hash);
        }
        throw new IOException("不支持的酷狗参数");
    }
    private static JSONObject kgGet(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(path).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", NetdiskHttp.UA); c.setRequestProperty("Accept", "application/json");
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("音乐来源返回 HTTP " + status);
            InputStream raw = c.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = raw) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) { if (bytes.size() + n > 2 * 1024 * 1024) throw new IOException("来源响应过大"); bytes.write(b, 0, n); } }
            return new JSONObject().put("json", new String(bytes.toByteArray(), "UTF-8"));
        } finally { c.disconnect(); }
    }
    /** GD音乐台：types=search（全曲库搜索）与 types=url（取直链），仅网易来源、参数白名单。 */
    private static JSONObject fetchGd(String rest) throws Exception {
        String type = null, name = null, id = null;
        for (String pair : rest.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) throw new IOException("不支持的搜索参数");
            String k = pair.substring(0, eq), v = pair.substring(eq + 1);
            if (k.equals("type")) { if (!v.equals("search") && !v.equals("url")) throw new IOException("不支持的搜索参数"); type = v; }
            else if (k.equals("name")) { if (!v.matches("[A-Za-z0-9%._-]{1,120}")) throw new IOException("搜索词包含不支持的字符"); name = v; }
            else if (k.equals("id")) { if (!v.matches("[0-9]{1,12}")) throw new IOException("不支持的曲目编号"); id = v; }
            else throw new IOException("不支持的搜索参数");
        }
        if ("search".equals(type)) {
            if (name == null) throw new IOException("缺少搜索词");
            return gdGet("/api.php?types=search&source=netease&name=" + name + "&count=30&pages=1");
        }
        if ("url".equals(type)) {
            if (id == null) throw new IOException("缺少曲目编号");
            return gdGet("/api.php?types=url&source=netease&id=" + id + "&br=320");
        }
        throw new IOException("不支持的搜索参数");
    }
    private static JSONObject gdGet(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(GD_ORIGIN + path).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", NetdiskHttp.UA); c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("Referer", GD_ORIGIN + "/");
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("音乐来源返回 HTTP " + status);
            InputStream raw = c.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = raw) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) { if (bytes.size() + n > 2 * 1024 * 1024) throw new IOException("来源响应过大"); bytes.write(b, 0, n); } }
            return new JSONObject().put("json", new String(bytes.toByteArray(), "UTF-8"));
        } finally { c.disconnect(); }
    }
}
