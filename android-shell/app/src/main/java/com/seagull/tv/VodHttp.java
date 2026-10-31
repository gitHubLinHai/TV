package com.seagull.tv;

import java.io.*;
import java.net.*;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

/** 用户配置的片源站点只读页面适配：路径白名单 + 同域跳转校验；不传 Cookie，不执行站点脚本。 */
public final class VodHttp {
    public static final String DEFAULT_HOST = "www.555yy1.com";
    public static boolean allowedHost(String host) {
        return host != null && host.length() <= 80 && host.matches("[a-zA-Z0-9.-]+") && host.indexOf('.') > 0
            && !host.startsWith(".") && !host.endsWith(".") && !host.contains("..");
    }
    public static boolean allowedApiBase(String base) {
        return base != null && base.startsWith("https://") && base.length() <= 200
            && base.matches("https://[a-zA-Z0-9.-]+\\\\.[a-zA-Z]{2,}(/api\\\\.php/provide/vod/?)");
    }
    private static boolean allowedParam(String p) {
        return p.matches("ac=(list|videolist)") || p.matches("wd=[A-Za-z0-9%._-]{0,80}") || p.matches("t=\\\\d{0,12}")
            || p.matches("pg=\\\\d{1,5}") || p.matches("ids=\\\\d{1,40}(,\\\\d{1,40})*");
    }
    /** maccms 标准接口（通用源）：仅允许 ac/wd/t/pg/ids 白名单参数。 */
    public static JSONObject fetchApi(String base, String query) throws Exception {
        if (!allowedApiBase(base)) throw new IOException("接口地址无效：需为 https://域名/api.php/provide/vod/");
        String q = (query == null || query.isEmpty()) ? "ac=list" : query;
        for (String param : q.split("&")) { if (!allowedParam(param)) throw new IOException("不支持的接口参数"); }
        HttpURLConnection c = (HttpURLConnection) new URL(base + (base.endsWith("/") ? "?" : "/?") + q).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", NetdiskHttp.UA); c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("接口返回 HTTP " + status);
            InputStream raw = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) raw = new GZIPInputStream(raw);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = raw) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) { if (bytes.size() + n > 8 * 1024 * 1024) throw new IOException("接口返回过大"); bytes.write(b, 0, n); } }
            return new JSONObject().put("json", new String(bytes.toByteArray(), "UTF-8"));
        } finally { c.disconnect(); }
    }
    public static boolean allowedPath(String path){
        return path!=null && path.length()<=2048 && path.matches("/index/home|/(?:vod(?:detail|type)/[0-9]+|vodplay/[0-9]+-[0-9]+-[0-9]+|vodshow/[A-Za-z0-9%_-]+|vodsearch/[A-Za-z0-9%_-]+)\\.html");
    }
    public static JSONObject fetch(String host,String path)throws Exception{
        if(!allowedHost(host))throw new IOException("片源地址无效");
        if(Vod4kvmHttp.HOST.equals(host))return Vod4kvmHttp.fetch(path);
        if(!allowedPath(path))throw new IOException("不支持的影视页面地址");
        for(int attempt=0;attempt<3;attempt++){
            HttpURLConnection c=(HttpURLConnection)new URL("https://"+host+path).openConnection();
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent",NetdiskHttp.UA);c.setRequestProperty("Referer","https://"+host+"/");c.setRequestProperty("Accept-Encoding","gzip");
            try{
                int status=c.getResponseCode();
                if(status>=300&&status<400){URL next=new URL(c.getURL(),c.getHeaderField("Location"));
                    if(!"https".equals(next.getProtocol())||!next.getHost().equals(host)||next.getPort()!=-1||next.getQuery()!=null||!allowedPath(next.getPath()))throw new IOException("来源跳转到未支持的地址");
                    path=next.getPath();continue;
                }
                if(status!=200)throw new IOException("影视来源返回 HTTP "+status);
                InputStream raw=c.getInputStream();if("gzip".equalsIgnoreCase(c.getContentEncoding()))raw=new GZIPInputStream(raw);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=raw){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>4*1024*1024)throw new IOException("来源页面过大");bytes.write(b,0,n);}}
                JSONObject out=new JSONObject();out.put("path",path);out.put("html",new String(bytes.toByteArray(),"UTF-8"));return out;
            }finally{c.disconnect();}
        }
        throw new IOException("来源跳转次数过多");
    }
}
