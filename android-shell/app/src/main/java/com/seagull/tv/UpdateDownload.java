package com.seagull.tv;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.FileInputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 保留经同一大小与SHA-256绑定的片段；完整包仍须由UpdatePackage验证。 */
public final class UpdateDownload {
    public interface Connection { HttpURLConnection open(URL url) throws Exception; }
    public interface Progress { void update(long done, long total) throws Exception; }
    public interface Check { void run() throws IOException; }

    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    public static void discard(File archive) throws IOException {
        for (File f : new File[]{archive, new File(archive.getPath() + ".id")})
            if (f.exists() && !f.delete()) throw new IOException("无法清理旧更新片段");
    }

    private static void prepare(File archive, long size, String hash) throws IOException {
        File identity = new File(archive.getPath() + ".id");
        String wanted = size + "\n" + hash.toLowerCase(Locale.US), actual = "";
        if (identity.isFile() && identity.length() < 200) {
            byte[] bytes = new byte[(int) identity.length()];
            try (InputStream input = new FileInputStream(identity)) {
                int offset = 0, n;
                while (offset < bytes.length && (n = input.read(bytes, offset, bytes.length - offset)) > 0) offset += n;
                actual = new String(bytes, 0, offset, StandardCharsets.UTF_8);
            }
        }
        if (!wanted.equals(actual) || archive.length() > size) {
            discard(archive);
            try (FileOutputStream output = new FileOutputStream(identity)) {
                output.write(wanted.getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
        }
    }

    public static void fetch(URL url, File archive, long size, String hash,
                             Connection connection, Check check, Progress progress) throws Exception {
        if (size <= 0 || !hash.matches("[a-fA-F0-9]{64}")) throw new IOException("无效更新包标识");
        prepare(archive, size, hash);
        for (int attempt = 0; attempt < 2; attempt++) {
            check.run();
            long offset = archive.length();
            if (offset == size) { progress.update(size, size); return; }
            HttpURLConnection c = connection.open(url);
            c.setConnectTimeout(8000); c.setReadTimeout(15000);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
            try {
                int status = c.getResponseCode();
                check.run();
                if (status == 416 && offset > 0 && attempt == 0) {
                    discard(archive); prepare(archive, size, hash); continue;
                }
                boolean append = status == 206;
                if (append) {
                    String range = c.getHeaderField("Content-Range");
                    Matcher m = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(range == null ? "" : range);
                    if (!m.matches() || Long.parseLong(m.group(1)) != offset
                            || Long.parseLong(m.group(2)) != size - 1 || Long.parseLong(m.group(3)) != size)
                        throw new IOException("续传响应范围不符，保留原片段并尝试其他源");
                } else if (status == 200) offset = 0; // 不支持Range时从头写，不能将整包追加到片段。
                else throw new IOException("下载失败 HTTP " + status);
                String encoding = c.getHeaderField("Content-Encoding");
                if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("更新包传输编码不符");
                String length = c.getHeaderField("Content-Length");
                if (length != null && Long.parseLong(length) != size - offset) throw new IOException("下载响应大小不符");
                long done = offset, lastPost = 0;
                progress.update(done, size);
                try (InputStream input = c.getInputStream(); FileOutputStream output = new FileOutputStream(archive, append)) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        check.run();
                        if (n > size - done) { output.close(); discard(archive); throw new IOException("更新包大小超出预期"); }
                        output.write(buffer, 0, n); done += n;
                        if (System.currentTimeMillis() - lastPost >= 300) { lastPost = System.currentTimeMillis(); progress.update(done, size); }
                    }
                }
                if (done != size) throw new IOException("下载中断，已保留进度");
                progress.update(done, size); return;
            } finally { c.disconnect(); }
        }
    }
}
