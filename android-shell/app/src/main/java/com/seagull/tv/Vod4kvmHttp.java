package com.seagull.tv;

import java.io.*;
import java.net.*;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

/** 4KVM 页面源：固定域名、只读 GET、路径及查询参数白名单。 */
public final class Vod4kvmHttp {
    public static final String HOST = "www.4kvm.net";
    // /filter 筛选页查询参数（与 web/js/vod-source-4kvm.js 的 FILTER_KEYS 保持一致）
    private static final java.util.Set<String> FILTER_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
        "classify", "types", "areas", "years", "tags", "sort_by", "order", "page"));
    private Vod4kvmHttp() {}

    public static boolean allowedPath(String value) {
        if (value == null || value.length() > 512) return false;
        try {
            URI uri = new URI(value);
            if (uri.isAbsolute() || uri.getFragment() != null) return false;
            String path = uri.getPath(), query = uri.getRawQuery();
            if (path.matches("/(?:movie|tv|anime)?")) return query == null || query.matches("page=[0-9]{1,4}");
            if (path.equals("/filter")) {
                if (query == null) return true;
                if (!query.matches("[a-z_]{1,12}=[A-Za-z0-9%._~-]{1,32}(?:&[a-z_]{1,12}=[A-Za-z0-9%._~-]{1,32})*")) return false;
                for (String kv : query.split("&")) {
                    if (!FILTER_KEYS.contains(kv.substring(0, kv.indexOf('=')))) return false;
                }
                return true;
            }
            if (path.equals("/search")) return query != null && query.matches("q=[A-Za-z0-9%._~-]{1,240}(?:&page=[0-9]{1,4})?");
            return path.matches("/play/[a-z0-9]{6,24}") && query == null;
        } catch (Exception e) { return false; }
    }

    public static JSONObject fetch(String value) throws Exception {
        if (!allowedPath(value)) throw new IOException("不支持的 4KVM 页面地址");
        for (int attempt=0; attempt<3; attempt++) {
            HttpURLConnection c=(HttpURLConnection)new URL("https://"+HOST+value).openConnection();
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(18000);
            c.setRequestProperty("User-Agent",NetdiskHttp.UA);c.setRequestProperty("Referer","https://"+HOST+"/");c.setRequestProperty("Accept-Encoding","gzip");
            try {
                int status=c.getResponseCode();
                if(status>=300&&status<400){URL next=new URL(c.getURL(),c.getHeaderField("Location"));String nextValue=next.getPath()+(next.getQuery()==null?"":"?"+next.getQuery());
                    if(!"https".equals(next.getProtocol())||!HOST.equals(next.getHost())||next.getPort()!=-1||!allowedPath(nextValue))throw new IOException("4KVM 跳转到未支持的地址");
                    value=nextValue;continue;
                }
                if(status!=200)throw new IOException("4KVM 返回 HTTP "+status);
                InputStream raw=c.getInputStream();if("gzip".equalsIgnoreCase(c.getContentEncoding()))raw=new GZIPInputStream(raw);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=raw){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>4*1024*1024)throw new IOException("4KVM 页面过大");bytes.write(b,0,n);}}
                return new JSONObject().put("path",value).put("html",new String(bytes.toByteArray(),"UTF-8"));
            } finally { c.disconnect(); }
        }
        throw new IOException("4KVM 跳转次数过多");
    }
}
