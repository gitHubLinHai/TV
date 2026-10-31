package com.seagull.tv;

import android.webkit.CookieManager;
import org.json.*;
import java.io.*;
import java.net.*;

/** 所有目标及路径固定；搜索服务永远收不到账号 Cookie。 */
public final class NetdiskHttp {
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36";
    private static final String API = "https://drive-pc.quark.cn/1/clouddrive";
    public static JSONObject call(String action, JSONObject args) throws Exception {
        String path, method = "GET";
        JSONObject query = new JSONObject(); query.put("pr", "ucpro"); query.put("fr", "pc");
        JSONObject body = null;
        if (action.equals("account")) {
            JSONObject account = new JSONObject();
            if (cookies(API).isEmpty()) { account.put("loggedIn",false); return account; }
            JSONObject response = request(API+"/member?pr=ucpro&fr=pc","GET",null,true);
            if (response.optInt("code",-1)==0 && response.optJSONObject("data")!=null) account.put("loggedIn",true);
            else if (response.optInt("status")==401 || response.optInt("code")==31001) account.put("loggedIn",false);
            else throw new IOException("暂时无法确认登录状态，请重试");
            return account;
        } else if (action.equals("search")) {
            String keyword = args.optString("q").trim();
            if (keyword.isEmpty() || keyword.length() > 60) throw new IOException("请输入 1—60 字搜索词");
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
            try {
                JSONObject searchBody = new JSONObject(); searchBody.put("page",1); searchBody.put("q",keyword); searchBody.put("user",""); searchBody.put("exact",false);
                searchBody.put("format",new JSONArray()); searchBody.put("size",30); searchBody.put("type",""); searchBody.put("share_time",""); searchBody.put("exclude_user",new JSONArray());
                JSONObject adv = new JSONObject(); adv.put("wechat_pwd",""); adv.put("platform","pc"); searchBody.put("adv_params",adv);
                java.util.List<java.util.concurrent.Callable<JSONObject>> tasks = new java.util.ArrayList<>();
                tasks.add(() -> source("PanSearch","pansearch","https://www.pansearch.me/search?keyword="+enc(keyword)+"&pan=quark",null));
                tasks.add(() -> source("夸克盘搜","hunhepan","https://qkpanso.com/v1/search/disk",searchBody));
                tasks.add(() -> source("PanSou","pansou","https://so.252035.xyz/api/search?kw="+enc(keyword)+"&res=merge&cloud_types=quark&refresh=true",null));
                JSONArray sources = new JSONArray();
                String selected = args.optString("source");
                if (!selected.isEmpty()) {
                    int index = selected.equals("pansearch")?0:selected.equals("qkpanso")?1:selected.equals("pansou")?2:-1;
                    if (index < 0) throw new IOException("不支持的搜索来源");
                    sources.put(tasks.get(index).call()); JSONObject out = new JSONObject(); out.put("sources",sources); return out;
                }
                for (java.util.concurrent.Future<JSONObject> future : pool.invokeAll(tasks,30,java.util.concurrent.TimeUnit.SECONDS)) {
                    if (!future.isCancelled()) try { sources.put(future.get()); } catch (Exception ignored) { }
                }
                JSONObject out = new JSONObject(); out.put("sources",sources); return out;
            } finally { pool.shutdownNow(); }
        } else if (action.equals("token")) {
            path = "/share/sharepage/token"; method = "POST"; body = pick(args, "pwd_id", "passcode");
        } else if (action.equals("files")) {
            path = "/share/sharepage/detail";
            for (String key : new String[]{"pwd_id", "stoken"}) query.put(key, args.optString(key));
            query.put("pdir_fid", args.optString("pdir_fid", "0"));
            query.put("_page", Math.max(1, Math.min(100, args.optInt("page", 1)))); query.put("_size", 50);
            query.put("_fetch_total", 1); query.put("_sort", "file_type:asc,file_name:asc");
        } else if (action.equals("sharePreview")) {
            path = "/share/sharepage/video_preview";
            for (String key : new String[]{"pwd_id","stoken","fid","fid_token"}) query.put(key,args.optString(key));
        } else if (action.equals("save")) {
            path = "/share/sharepage/save"; method = "POST";
            body = pick(args, "pwd_id", "stoken", "fid_list", "fid_token_list");
            if (body.optJSONArray("fid_list") == null || body.optJSONArray("fid_list").length() != 1)
                throw new IOException("每次只转存你选择的一个文件");
            body.put("to_pdir_fid", "0"); body.put("pdir_fid", args.optString("pdir_fid", "0")); body.put("scene", "link");
            body.put("pdir_save_all",false); body.put("exclude_fids",new JSONArray());
        } else if (action.equals("task")) {
            path = "/task"; query.put("task_id", args.optString("task_id")); query.put("retry_index", args.optInt("retry_index"));
        } else if (action.equals("ownPlay")) {
            path = "/file/v2/play/project"; method = "POST"; body = pick(args, "fid");
            body.put("resolutions", "low,normal,high,super,2k,4k"); body.put("supports", "m3u8");
        } else if (action.equals("ownDownload")) {
            path = "/file/download"; method = "POST"; body = pick(args, "fids");
        } else throw new IOException("不支持的网盘操作");
        if ((action.equals("save") || action.equals("ownPlay") || action.equals("ownDownload")) && cookies(API).isEmpty())
            throw new IOException("请先在应用内登录夸克，再返回重试");
        StringBuilder url = new StringBuilder(API + path + "?");
        java.util.Iterator<String> keys = query.keys();
        while (keys.hasNext()) { String key = keys.next(); url.append(enc(key)).append('=').append(enc(query.optString(key))).append('&'); }
        return request(url.toString(), method, body, true);
    }
    private static JSONObject source(String name,String format,String url,JSONObject body) throws JSONException {
        JSONObject out = new JSONObject(); out.put("name",name); out.put("format",format);
        try { out.put("data",request(url,body==null?"GET":"POST",body,false)); }
        catch(Exception e) {
            if (format.equals("pansou") && url.contains("refresh=true")) {
                try {
                    out.put("data",request(url.replace("refresh=true","refresh=false"),"GET",null,false));
                    out.put("warning","刷新失败，使用缓存候选；结果仍须逐条校验"); return out;
                } catch (Exception ignored) { }
            }
            out.put("error", e instanceof java.net.SocketTimeoutException ? "连接超时" : "连接失败，请稍后重试");
        }
        return out;
    }
    private static JSONObject pick(JSONObject source, String... keys) throws JSONException {
        JSONObject out = new JSONObject(); for (String key : keys) out.put(key, source.opt(key)); return out;
    }
    private static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    public static String cookies(String url) {
        if (PrivacyState.enabled) return PrivacyState.cookies(url);
        String value = CookieManager.getInstance().getCookie(url);
        if (value == null || value.isEmpty()) value = CookieManager.getInstance().getCookie("https://pan.quark.cn/");
        return value == null ? "" : value;
    }
    private static JSONObject request(String address, String method, JSONObject body, boolean quark) throws Exception {
        final boolean privateRequest = PrivacyState.enabled;
        HttpURLConnection c = (HttpURLConnection)new URL(address).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(12000);
        if (address.startsWith("https://so.252035.xyz/")) { c.setConnectTimeout(5000); c.setReadTimeout(9000); }
        c.setRequestMethod(method); c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "application/json");
        if (address.startsWith("https://qkpanso.com/")) c.setRequestProperty("Referer","https://qkpanso.com/search");
        if (quark) {
            c.setRequestProperty("Referer", "https://pan.quark.cn/");
            String cookie = cookies(address); if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        }
        try {
            if (body != null) {
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes("UTF-8")); }
            }
            int status = c.getResponseCode();
            if (quark) {
                for (java.util.Map.Entry<String,java.util.List<String>> header : c.getHeaderFields().entrySet())
                    if (header.getKey() != null && header.getKey().equalsIgnoreCase("Set-Cookie"))
                        for (String cookie : header.getValue()) { if (privateRequest) { if (PrivacyState.enabled) PrivacyState.receive(address,cookie); } else CookieManager.getInstance().setCookie(address, cookie); }
            }
            InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (stream == null) throw new IOException("网盘服务返回 HTTP " + status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = stream) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) { if (bytes.size()+n > 2*1024*1024) throw new IOException("接口返回内容过大"); bytes.write(buffer,0,n); }
            }
            try {
                String raw = new String(bytes.toByteArray(), "UTF-8");
                if (address.startsWith("https://www.pansearch.me/search?")) {
                    java.util.regex.Matcher match = java.util.regex.Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>",java.util.regex.Pattern.DOTALL).matcher(raw);
                    if (!match.find()) throw new IOException("来源页面格式已变化");
                    return new JSONObject(match.group(1));
                }
                return new JSONObject(raw);
            }
            catch (JSONException e) { throw new IOException("网盘服务暂不可用 HTTP " + status); }
        } finally { c.disconnect(); }
    }
}
