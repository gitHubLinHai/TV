package com.seagull.tv;

import android.net.Uri;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/** 直链视频多线程加载：3 个连接按区间并行预取（每块 1MiB），按序交付；不支持 Range 的服务器自动退回单连接。 */
public final class ParallelDataSource extends BaseDataSource {
    private static final int THREADS = 3;
    private static final int BLOCK = 1024 * 1024;
    private static final byte[] EOF_MARK = new byte[0];
    private final Map<String, String> headers;
    private Uri uri;
    private long readPosition;
    private long remaining;
    private boolean rangeSupported;
    private HttpURLConnection single;
    private java.io.InputStream singleStream;
    private final Map<Integer, byte[]> blocks = new HashMap<>();
    private final Object lock = new Object();
    private volatile boolean closed;
    private volatile String failure;
    private Thread[] workers;
    private int served = -1;   // 最后一个完整消费的块号
    private int servedOffset;  // 当前块内偏移

    public ParallelDataSource(Map<String, String> headers) {
        super(/* isNetwork= */ true);
        this.headers = headers;
    }

    public static final class Factory implements DataSource.Factory {
        private final Map<String, String> headers;
        public Factory(Map<String, String> headers) { this.headers = headers; }
        @Override public DataSource createDataSource() { return new ParallelDataSource(headers); }
    }

    @Override public long open(DataSpec dataSpec) throws IOException {
        transferInitializing(dataSpec);
        uri = dataSpec.uri;
        readPosition = dataSpec.position + dataSpec.uriPositionOffset;
        remaining = dataSpec.length == -1 ? Long.MAX_VALUE : dataSpec.length;
        closed = false; rangeSupported = true; served = -1; servedOffset = 0; failure = null;
        blocks.clear();
        HttpURLConnection probe = (HttpURLConnection) new URL(uri.toString()).openConnection();
        try {
            applyHeaders(probe);
            probe.setRequestProperty("Range", "bytes=" + readPosition + "-");
            int status = probe.getResponseCode();
            if (status == 206) {
                startWorkers();
                transferStarted(dataSpec);
                return remaining;
            }
            if (status == 200 && readPosition == 0) {
                rangeSupported = false;
                single = probe;
                singleStream = probe.getInputStream();
                transferStarted(dataSpec);
                return dataSpec.length;
            }
            throw new IOException("来源不支持断点续传，无法多线程加载");
        } catch (IOException e) {
            try { probe.disconnect(); } catch (RuntimeException ignored) {}
            throw e;
        }
    }

    private void applyHeaders(HttpURLConnection c) {
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setRequestProperty("Accept-Encoding", "identity");
    }

    private void startWorkers() {
        workers = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            final int slot = i;
            workers[i] = new Thread(() -> workerLoop(slot), "ParallelDataSource-" + slot);
            workers[i].start();
        }
    }

    private void workerLoop(int slot) {
        long base = readPosition;
        for (int idx = slot; ; idx += THREADS) {
            if (closed) return;
            long start = base + (long) idx * BLOCK;
            byte[] block = new byte[BLOCK];
            int filled = 0;
            boolean eof = false;
            while (filled < BLOCK && !eof && !closed) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(uri.toString()).openConnection();
                    applyHeaders(c);
                    c.setRequestProperty("Range", "bytes=" + (start + filled) + "-" + (start + BLOCK - 1));
                    int status = c.getResponseCode();
                    if (status == 416) { eof = true; }
                    else if (status != 206) { fail("来源返回 HTTP " + status); return; }
                    else {
                        java.io.InputStream in = c.getInputStream();
                        while (filled < BLOCK && !closed) {
                            int n = in.read(block, filled, BLOCK - filled);
                            if (n < 0) { eof = true; break; }
                            filled += n;
                            bytesTransferred(n);
                        }
                    }
                } catch (IOException e) {
                    if (c != null) try { c.disconnect(); } catch (RuntimeException ignored) {}
                    fail(String.valueOf(e.getMessage()));
                    return;
                }
                if (c != null) try { c.disconnect(); } catch (RuntimeException ignored) {}
            }
            if (closed) return;
            synchronized (lock) {
                // 短块（文件尾）必须保留已读取的数据，而不是零数组。
                blocks.put(idx, filled == BLOCK ? block : java.util.Arrays.copyOf(block, filled));
                lock.notifyAll();
            }
            if (eof || filled < BLOCK) return;
        }
    }

    private void fail(String message) {
        synchronized (lock) { if (!closed && failure == null) failure = message; lock.notifyAll(); }
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (!rangeSupported) {
            int max = (int) Math.min(length, remaining == Long.MAX_VALUE ? length : remaining);
            int n = singleStream.read(buffer, offset, max);
            if (n < 0) { if (remaining != Long.MAX_VALUE && remaining > 0) throw new EOFException(); return -1; }
            if (remaining != Long.MAX_VALUE) remaining -= n;
            bytesTransferred(n);
            return n;
        }
        if (remaining <= 0) return -1;
        while (true) {
            if (closed) throw new InterruptedIOException("已关闭");
            synchronized (lock) {
                if (failure != null) { String m = failure; failure = null; throw new IOException("多线程加载失败：" + m); }
            }
            int index = served + 1;
            byte[] block;
            synchronized (lock) { block = blocks.get(index); }
            if (block != null) {
                if (block.length == 0) return -1; // EOF 标记
                if (servedOffset >= block.length) { served = index; servedOffset = 0; synchronized (lock) { blocks.remove(index); } continue; }
                int n = (int) Math.min(Math.min(length, remaining), block.length - servedOffset);
                System.arraycopy(block, servedOffset, buffer, offset, n);
                servedOffset += n;
                if (remaining != Long.MAX_VALUE) remaining -= n;
                bytesTransferred(n);
                return n;
            }
            boolean anyAlive = false;
            for (Thread w : workers) if (w != null && w.isAlive()) anyAlive = true;
            if (!anyAlive) return -1; // 已取完且无后续块：到达文件尾
            try {
                synchronized (lock) { if (blocks.get(index) == null) lock.wait(250); }
            } catch (InterruptedException e) { throw new InterruptedIOException(); }
        }
    }

    @Override public Uri getUri() { return uri; }

    @Override public void close() {
        closed = true;
        synchronized (lock) { lock.notifyAll(); }
        if (workers != null) for (Thread w : workers) if (w != null) w.interrupt();
        if (single != null) try { single.disconnect(); } catch (RuntimeException ignored) {}
        if (singleStream != null) try { singleStream.close(); } catch (IOException ignored) {}
        single = null; singleStream = null; blocks.clear(); workers = null;
    }
}
