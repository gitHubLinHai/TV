package com.seagull.tv.tests;
import android.app.*;import android.content.*;import android.os.Bundle;import android.content.Intent;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

/** 直链 MP4 多线程并行加载真机验证：字节一致性 + Range 拉取下能起播推进。 */
public class ParallelPlayInstrumentation extends NetdiskInstrumentation {
    private static final String URL = "https://www.w3school.com.cn/i/movie.mp4";

    private static byte[] fetchRange(long start, int len) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)");
        c.setRequestProperty("Range", "bytes=" + start + "-" + (start + len - 1));
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        int code = c.getResponseCode();
        if (code != 206) { c.disconnect(); throw new IllegalStateException("HTTP " + code); }
        java.io.InputStream in = c.getInputStream();
        byte[] out = new byte[len];
        int got = 0;
        while (got < len) { int n = in.read(out, got, len - got); if (n < 0) break; got += n; }
        c.disconnect();
        byte[] exact = new byte[got];
        System.arraycopy(out, 0, exact, 0, got);
        return exact;
    }

    private static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < b.length; i++) sb.append(String.format("%02X ", b[i]));
        return sb.toString();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            byte[] raw = fetchRange(0, 1024 * 1024);
            require(raw.length >= 1024, "raw range fetch too short: " + raw.length);
            require(raw.length > 8 && new String(raw, 4, 4, "UTF-8").equals("ftyp"), "raw source is not an MP4 (ftyp missing): " + hex(raw, 16));
            Class<?> dsClass = Class.forName("androidx.media3.datasource.DataSpec");
            Class<?> pdsClass = Class.forName("com.seagull.tv.ParallelDataSource");
            Object ds = dsClass.getConstructor(android.net.Uri.class, long.class, long.class).newInstance(android.net.Uri.parse(URL), 0L, 1024L * 1024L);
            Object pds = pdsClass.getConstructor(java.util.Map.class).newInstance(new HashMap<String, String>());
            pdsClass.getMethod("open", dsClass).invoke(pds, ds);
            byte[] par = new byte[1024 * 1024];
            int total = 0;
            java.lang.reflect.Method readM = pdsClass.getMethod("read", byte[].class, int.class, int.class);
            while (total < par.length) {
                Integer n = (Integer) readM.invoke(pds, par, total, par.length - total);
                if (n == null || n < 0) break;
                total += n;
            }
            pdsClass.getMethod("close").invoke(pds);
            boolean same = total == raw.length && java.util.Arrays.equals(raw, java.util.Arrays.copyOf(par, total));
            require(same, "byte mismatch: raw=" + raw.length + " par=" + total);
            Intent i = new Intent();
            i.setClassName("com.seagull.tv", "com.seagull.tv.VideoActivity");
            i.putExtra("url", URL); i.putExtra("title", "parallel-test"); i.putExtra("key", "parallel-test"); i.putExtra("publicSource", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity player = startActivitySync(i);
            Object video = field(player, "video");
            long[] progress = {0, 0};
            String[] failure = {null};
            long until = System.currentTimeMillis() + 45000;
            while (System.currentTimeMillis() < until) {
                runOnMainSync(() -> {
                    try {
                        Class<?> p = Class.forName("androidx.media3.common.Player");
                        progress[0] = ((Number) p.getMethod("getDuration").invoke(video)).longValue();
                        progress[1] = ((Number) p.getMethod("getCurrentPosition").invoke(video)).longValue();
                        Object err = p.getMethod("getPlayerError").invoke(video);
                        if (err != null) { Throwable c = (Throwable) err; StringBuilder sb = new StringBuilder(); while (c != null) { sb.append(c.getClass().getSimpleName()).append(':').append(c.getMessage()).append(" <- "); c = c.getCause(); } failure[0] = sb.toString(); }
                    } catch (Exception e) { failure[0] = e.toString(); }
                });
                if (failure[0] != null) break;
                if (progress[0] > 0 && progress[1] > 1200) break;
                Thread.sleep(300);
            }
            require(failure[0] == null, "parallel playback error: " + failure[0]);
            require(progress[0] > 0 && progress[1] > 1200, "parallel playback did not advance (d=" + progress[0] + ",p=" + progress[1] + ")");
            runOnMainSync(() -> player.finish());
            result.putString("stream", "PASS: byte-identical parallel load, mp4 duration " + progress[0] + " ms, advanced to " + progress[1] + " ms.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL: " + e + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
