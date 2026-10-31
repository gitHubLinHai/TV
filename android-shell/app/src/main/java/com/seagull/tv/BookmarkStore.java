package com.seagull.tv;

import android.content.Context;
import android.net.Uri;
import org.json.*;

/** 仅写应用私有 SharedPreferences，不提供任何网络或同步接口。 */
public final class BookmarkStore {
    private static final String PREF = "tv_bookmarks";
    private static JSONObject entry(String address, String title) throws Exception {
        if (address == null || address.length()>4096 || !BrowserActivity.isWebUrl(address)) throw new IllegalArgumentException("请填写有效的 HTTP 或 HTTPS 网页地址");
        Uri uri=Uri.parse(address.trim()).normalizeScheme();
        String host=uri.getHost().toLowerCase(java.util.Locale.ROOT);
        uri=uri.buildUpon().encodedAuthority(host+(uri.getPort()==-1?"":":"+uri.getPort())).build();
        boolean quark=uri.getScheme().equals("https") && host.equals("pan.quark.cn") && uri.getPath()!=null && uri.getPath().matches("/s/[a-zA-Z0-9]+/?");
        String key=uri.toString();
        if(quark){
            String password=uri.getQueryParameter("pwd");if(password==null)password=uri.getQueryParameter("passcode");
            String path=uri.getPath().replaceAll("/$","");key="https://pan.quark.cn"+path;
            uri=Uri.parse(key);if(password!=null&&!password.isEmpty())uri=uri.buildUpon().appendQueryParameter("pwd",password).build();
        }
        String name=title==null?"":title.trim().replaceAll("[\\p{Cntrl}]"," ");if(name.isEmpty())name=host;if(name.length()>200)name=name.substring(0,200);
        JSONObject item=new JSONObject();item.put("key",key);item.put("url",uri.toString());item.put("title",name);item.put("kind",quark?"quark":"web");return item;
    }
    private static JSONArray read(Context context) {
        try{return new JSONArray(context.getSharedPreferences(PREF,0).getString("items","[]"));}catch(Exception e){return new JSONArray();}
    }
    public static synchronized JSONArray list(Context context) {return read(context);}
    public static synchronized JSONObject add(Context context,String url,String title) throws Exception {
        if(PrivacyState.enabled)throw new IllegalStateException("无痕模式不保存书签，请关闭无痕后收藏");
        JSONObject item=entry(url,title);JSONArray old=read(context), next=new JSONArray();next.put(item);
        for(int i=0;i<old.length();i++){JSONObject previous=old.optJSONObject(i);if(previous!=null&&!previous.optString("key").equals(item.getString("key")))next.put(previous);}
        if(next.length()>200)throw new IllegalStateException("最多保存 200 个本地书签，请先删除不需要的书签");
        context.getSharedPreferences(PREF,0).edit().putString("items",next.toString()).apply();return item;
    }
    public static synchronized void remove(Context context,String key) throws Exception {
        if(PrivacyState.enabled)throw new IllegalStateException("无痕模式不修改书签");
        JSONArray next=new JSONArray(),old=read(context);for(int i=0;i<old.length();i++){JSONObject item=old.optJSONObject(i);if(item!=null&&!item.optString("key").equals(key))next.put(item);}
        context.getSharedPreferences(PREF,0).edit().putString("items",next.toString()).apply();
    }
    public static String request(Context context,String json) {
        JSONObject reply=new JSONObject();try{
            if(json==null||json.length()>12000)throw new IllegalArgumentException("书签请求无效");
            JSONObject input=new JSONObject(json);String action=input.optString("action");
            if(action.equals("add"))add(context,input.optString("url"),input.optString("title"));
            else if(action.equals("remove"))remove(context,input.optString("key"));
            else if(!action.equals("list"))throw new IllegalArgumentException("不支持的书签操作");
            reply.put("ok",true);reply.put("items",list(context));
        }catch(Exception e){try{reply.put("ok",false);reply.put("error",e.getMessage());}catch(Exception ignored){}}
        return reply.toString();
    }
}
