"""在 JVM 执行实际安卓多源选择方法；仅替换 Android Network、JSON 和 HTTP 边界。"""
from pathlib import Path
import subprocess
import tempfile
import zipfile
import hashlib

root = Path(__file__).resolve().parents[1]
java = Path(r'C:\Program Files\Unity 6000.0.59f2\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK\bin')
source = (root / 'android-shell/app/src/main/java/com/seagull/tv/MainActivity.java').read_text(encoding='utf-8')
start = source.index('    private JSONObject fetchVersionJson()')
method = source[start:source.index('    private void deleteRecursive', start)]
download = source[source.index('        private void downloadUpdate('):source.index('        private void waitingWifi')]
versions = source[source.index('    private int activeCode()'):source.index('    /** 并行读取各源')]
delete = source[source.index('    private void deleteRecursive'):source.rfind('}')]
harness = r'''
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import com.seagull.tv.UpdatePackage;
import com.seagull.tv.UpdateStorage;
import com.seagull.tv.UpdateDownload;
public class NativeUpdateTest { static final int SHELL_VERSION = 7;
    static String[] CDNS = {"https://one/", "https://two/", "https://three/"};
    static String API_RELEASE = "https://api/";
    static volatile String[] replies;
    static CountDownLatch started;
    static String[] packageReplies;
    static byte[] packageBytes;
    static int packageAttempts;
    static Network connectedWifi;
    static Network wifiNetwork() { return connectedWifi; }
    static void jsCallback(String cb, JSONObject value) { }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void write(File file, String content) throws Exception {
        file.getParentFile().mkdirs(); Files.write(file.toPath(), content.getBytes("UTF-8"));
    }
    static void storageTests(File root) throws Exception {
        File current = new File(root, "www"), old = new File(root, "www_old"), incoming = new File(root, "incoming");
        write(new File(current, "index.html"), "old");
        require(current.renameTo(old), "simulate kill between renames");
        UpdateStorage.recover(root);
        require(new File(current, "index.html").isFile(), "recover previous resources");
        write(new File(incoming, "index.html"), "new");
        write(new File(incoming, "manifest.json"), "109");
        UpdateStorage.install(incoming, current);
        // 目录切换完成、尚未开始加载页面时重启，允许首次试运行。
        UpdateStorage.recover(root);
        NativeUpdateTest activity = new NativeUpdateTest(); activity.wwwDir = current;
        require(activity.activeCode() == 109, "manifest overrides stale version preferences");
        require(activity.activeName().equals("1.0.9"), "version name follows committed manifest");
        require(new String(Files.readAllBytes(new File(current, "index.html").toPath()), "UTF-8").equals("new"), "keep committed new resources");
        require(new File(current, "manifest.json").isFile(), "manifest follows actual resources");
        try { UpdateStorage.install(incoming, current); throw new AssertionError("missing incoming should fail"); }
        catch (IOException expected) { }
        require(new File(current, "index.html").isFile(), "restore after rename failure");
        require(!old.exists(), "rollback restores original path");
    }
    static void downloadTests(File root, String hash) throws Exception {
        JSONObject remote = new JSONObject("109");
        remote.put("wgtSize", packageBytes.length); remote.put("wgtSha256", hash);
        remote.put("_base", CDNS[0]);
        NativeUpdateTest test = new NativeUpdateTest();
        File archive = new File(root, "test.wgt"), tmp = new File(root, "incoming");
        for (String first : new String[]{"404", "timeout", "corrupt"}) {
            UpdateDownload.discard(archive);
            packageReplies = new String[]{first, "ok", "ok"}; packageAttempts = 0;
            test.downloadUpdate(remote, archive, tmp, null, false, "cb");
            require(packageAttempts == 2, "fallback after " + first);
            require(new File(tmp, "js/app.js").isFile(), "verified complete package");
        }
        UpdateDownload.discard(archive);
        packageReplies = new String[]{"404", "timeout", "corrupt"}; packageAttempts = 0;
        try { test.downloadUpdate(remote, archive, tmp, null, false, "cb"); throw new AssertionError("all sources failed"); }
        catch (IOException expected) { }
        require(packageAttempts == 4, "try all download sources");
        Network wifi = new Network(); connectedWifi = wifi;
        UpdateDownload.discard(archive);
        packageReplies = new String[]{"dropwifi", "ok", "ok"}; packageAttempts = 0;
        try { test.downloadUpdate(remote, archive, tmp, wifi, true, "cb"); throw new AssertionError("wifi disconnect must stop"); }
        catch (IOException expected) { }
        require(packageAttempts == 1, "must not switch to mobile or another network after wifi loss");
    }
    File wwwDir;
    static final int MODE_PRIVATE = 0;
    static class Prefs {
        static String pending;
        int getInt(String key, int fallback) { return key.equals("rejected") ? fallback : 105; }
        String getString(String key, String fallback) { return key.equals("pending") ? (pending == null ? fallback : pending) : "1.0.5"; }
        Prefs edit() { return this; }
        Prefs putString(String key, String value) { pending = value; return this; }
        Prefs remove(String key) { pending = null; return this; }
        boolean commit() { return true; }
    }
    Prefs getSharedPreferences(String name, int mode) { return new Prefs(); }
    static class Network {
        URLConnection openConnection(URL u) throws Exception { return u.openConnection(); }
    }
    // 固定响应夹具不测试平台 JSON 解析器；参与选择的是实际生产方法。
    static class JSONObject {
        final Map<String,Object> values = new HashMap<>();
        JSONObject() { }
        JSONObject(String body) {
            String[] saved = body.split("\\|", 2);
            int code = Integer.parseInt(saved[0]);
            if (saved.length == 2) values.put("_base", saved[1]);
            values.put("appId", code == 997 ? "other.app" : "com.seagull.tv");
            if (code == 998) values.put("minShellVersion", 8);
            values.put("versionCode", code);
            values.put("versionName", "1.0." + (code - 100));
            values.put("wgt", "EnglishQuest.wgt");
            if (code != 999) {
                values.put("wgtSize", 123);
                values.put("wgtSha256", String.join("", Collections.nCopies(64, "a")));
            }
        }
        int optInt(String key) { return optInt(key, 0); }
        int optInt(String key, int fallback) { Object v=values.get(key); return v instanceof Number ? ((Number)v).intValue() : fallback; }
        long optLong(String key, long fallback) { return optInt(key, (int)fallback); }
        String optString(String key) { Object v=values.get(key); return v == null ? "" : v.toString(); }
        String optString(String key, String fallback) { return values.containsKey(key) ? optString(key) : fallback; }
        void put(String key, Object value) { values.put(key, value); }
        public String toString() { return optInt("versionCode") + "|" + optString("_base"); }
    }
    static void verify(String[] data, int expected, String host, boolean wifi) throws Exception {
        Prefs.pending = null;
        verifyRemembered(data, expected, host, wifi);
    }
    static void verifyRemembered(String[] data, int expected, String host, boolean wifi) throws Exception {
        replies = data; started = new CountDownLatch(3);
        JSONObject result = new NativeUpdateTest().fetchVersionJson(wifi ? new Network() : null);
        if (expected == 0) {
            if (result != null) throw new AssertionError("Expected no usable source");
        } else if (result == null || result.optInt("versionCode") != expected || !result.optString("_base").equals("https://" + host + "/")) {
            throw new AssertionError("Wrong selected source/version");
        }
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler() {
            protected URLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
                    boolean counted;
                    public void connect() {}
                    public void disconnect() {}
                    public boolean usingProxy() { return false; }
                    public int getResponseCode() throws IOException {
                        if (url.getPath().endsWith("version.json")) {
                            int sourceIndex=Arrays.asList("one", "two", "three").indexOf(url.getHost());
                            if ("http404".equals(replies[sourceIndex])) return 404;
                        }
                        if (url.getPath().endsWith(".wgt")) {
                            if (!counted) { packageAttempts++; counted = true; }
                            int index = Arrays.asList("one", "two", "three").indexOf(url.getHost());
                            if (index < 0) return 404; // api.github.com 兜底源在夹具中按 404 计，只验证会被尝试
                            String reply = packageReplies[index];
                            if (reply.equals("timeout")) throw new SocketTimeoutException("fixture timeout");
                            if (reply.equals("404")) return 404;
                            if (reply.equals("dropwifi")) connectedWifi = null;
                            return 200;
                        }
                        started.countDown();
                        try { if (!started.await(2, TimeUnit.SECONDS)) throw new AssertionError("Sources were checked serially"); }
                        catch (InterruptedException e) { throw new IOException(e); }
                        return 200;
                    }
                    public InputStream getInputStream() throws IOException {
                        int index = Arrays.asList("one", "two", "three").indexOf(url.getHost());
                        if (url.getPath().endsWith(".wgt")) {
                            byte[] bytes = packageBytes.clone();
                            if (packageReplies[index].equals("corrupt")) bytes[0] ^= 1;
                            return new ByteArrayInputStream(bytes);
                        }
                        String reply = replies[index];
                        if (reply == null) throw new SocketTimeoutException("fixture timeout");
                        return new ByteArrayInputStream(reply.getBytes("UTF-8"));
                    }
                };
            }
        });
        packageBytes = Files.readAllBytes(Paths.get(args[0]));
        storageTests(new File(args[1], "storage"));
        File health = new File(args[1], "health");
        File healthCurrent = new File(health, "www");
        File healthIncoming = new File(health, "incoming");
        write(new File(healthCurrent, "index.html"), "good");
        write(new File(healthIncoming, "index.html"), "bad");
        UpdateStorage.install(healthIncoming, healthCurrent);
        UpdateStorage.startLoad(health);
        UpdateStorage.recover(health);
        require(new String(Files.readAllBytes(new File(healthCurrent,"index.html").toPath()),"UTF-8").equals("good"), "unconfirmed new UI rolls back after process death");
        write(new File(healthIncoming,"index.html"),"good-new");
        UpdateStorage.install(healthIncoming,healthCurrent);
        UpdateStorage.startLoad(health); UpdateStorage.confirm(health); UpdateStorage.recover(health);
        require(new String(Files.readAllBytes(new File(healthCurrent,"index.html").toPath()),"UTF-8").equals("good-new"), "confirmed new UI stays installed");
        downloadTests(new File(args[1], "download"), args[2]);
        verify(new String[]{"103", "108", "107"}, 108, "two", false);
        verify(new String[]{"103", null, "108"}, 108, "three", false);
        verify(new String[]{"999", "108", null}, 108, "two", false);
        verify(new String[]{"997", "998", "108"}, 108, "three", false);
        verify(new String[]{"103", "104", null}, 104, "two", false);
        verify(new String[]{null, null, null}, 0, "", false);
        verify(new String[]{"108", "107", "103"}, 108, "one", true);
        verify(new String[]{"110", "111", "109"}, 111, "two", false);
        verifyRemembered(new String[]{"110", "109", null}, 111, "two", false);
        verifyRemembered(new String[]{null, null, null}, 111, "two", false);
        Prefs.pending = "103|https://one/";
        verifyRemembered(new String[]{null, null, null}, 0, "", false);
        require(Prefs.pending == null, "clear pending update already installed");
        NativeUpdateTest diagnostics=new NativeUpdateTest();
        replies=new String[]{"http404","http404","http404"};started=new CountDownLatch(3);
        require(diagnostics.fetchVersionJson()==null && diagnostics.updateCheckError.contains("404"),"Missing publication not diagnosed as HTTP 404");
        replies=new String[]{null,null,null};started=new CountDownLatch(3);
        require(diagnostics.fetchVersionJson()==null && !diagnostics.updateCheckError.contains("404"),"Timeout incorrectly reported as unpublished HTTP 404");
        System.out.println("Native source selection passed: parallel, stale primary, timeout, invalid integrity, all stale, all offline, Wi-Fi binding path.");
    }
'''
harness += '\n}\n'
harness = harness.replace('    public static void main', method + versions + download + delete + '\n    public static void main')
with tempfile.TemporaryDirectory(prefix='yyapp-update-') as temp:
    work = Path(temp)
    (work / 'NativeUpdateTest.java').write_text(harness, encoding='utf-8')
    subprocess.run([str(java / 'javac.exe'), '-encoding', 'UTF-8', '-d', str(work), str(root / 'android-shell/app/src/main/java/com/seagull/tv/UpdatePackage.java'), str(root / 'android-shell/app/src/main/java/com/seagull/tv/UpdateStorage.java'), str(root / 'android-shell/app/src/main/java/com/seagull/tv/UpdateDownload.java'), str(work / 'NativeUpdateTest.java')], check=True)
    package = work / 'fixture.wgt'
    core = ['index.html', 'manifest.json', 'css/style.css', 'js/app.js', 'js/netdisk.js', 'js/bookmarks.js', 'js/vod-source-4kvm.js', 'js/vod-sources.js', 'js/vod.js']
    with zipfile.ZipFile(package, 'w') as z:
        for name in core:
            z.writestr(name, b'fixture')
    subprocess.run([str(java / 'java.exe'), '-cp', str(work), 'NativeUpdateTest', str(package),
                    str(work), hashlib.sha256(package.read_bytes()).hexdigest()], check=True)
    print('Download failover, corrupt package rejection, Wi-Fi loss, interrupted swap recovery and rollback passed.')

