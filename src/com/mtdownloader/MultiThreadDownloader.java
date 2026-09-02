package com.mtdownloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程分段下载引擎（纯 Java，零三方依赖）。
 *
 * 原理：
 *  1. probe() 用 Range: bytes=0-0 探测服务器是否支持分段(206)，并获取文件总大小。
 *  2. 支持分段时，把文件切分给 N 个线程，每个线程用 Range 请求只取一段，
 *     各线程用独立 RandomAccessFile 写入自己负责的偏移区间（非重叠，安全并行）。
 *  3. 不支持分段或大小未知时，退化为单线程流式下载。
 *  4. 每段失败自动重试最多 3 次；支持手动停止；进度用原子计数器合并。
 *
 * 回调 Listener 可能从工作线程调用，UI 侧需自行切回主线程。
 */
public class MultiThreadDownloader {

    public interface Listener {
        void onStatus(String message);
        void onProgress(long doneBytes, long totalBytes, double speedBps, int percent);
        void onComplete(File file, long totalBytes);
        void onError(String message);
        void onStopped(String message);
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 0;          // 0 = 无限读取超时（大文件下载）
    private static final int BUFFER = 64 * 1024;
    private static final int MAX_RETRY = 3;
    private static final long NOTIFY_INTERVAL_MS = 400;

    private final String urlStr;
    private final File destFile;
    private final int threads;
    private final Listener listener;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong doneBytes = new AtomicLong(0);
    private final Object lock = new Object();

    private volatile long totalBytes = -1;
    private volatile boolean range = false;
    private volatile String lastThreadError;

    private long speedLastMs;
    private long speedLastBytes;
    private ExecutorService pool;
    private CountDownLatch latch;

    public MultiThreadDownloader(String urlStr, File destFile, int threads, Listener listener) {
        this.urlStr = urlStr;
        this.destFile = destFile;
        this.threads = Math.max(1, threads);
        this.listener = listener;
    }

    public void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                MultiThreadDownloader.this.run();
            }
        }, "mt-dl");
        t.start();
    }

    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    // ------------------------------------------------------------------ 主体

    private void run() {
        speedLastMs = System.currentTimeMillis();
        speedLastBytes = 0;
        try {
            RangeInfo info = probe();
            totalBytes = info.total;
            range = info.range;

            if (totalBytes > 0) {
                listener.onStatus("文件大小 " + fmtSize(totalBytes) + " · " + threads + " 线程"
                        + (range ? " 分段下载" : " 单线程(服务器未启用分段)"));
            } else {
                listener.onStatus("文件大小未知 · " + threads + " 线程 流式下载");
            }

            if (totalBytes == 0) {
                if (destFile.exists()) destFile.delete();
                destFile.createNewFile();
                listener.onStatus("空文件，无需下载");
                notifyProgress();
                listener.onComplete(destFile, 0);
                return;
            }

            if (totalBytes > 0) {
                prepareFile(totalBytes);
                notifyProgress();
            }

            if (range && totalBytes > 0) {
                downloadParallel();
            } else {
                downloadStream();
            }

            if (!running.get()) {
                listener.onStopped("下载已停止（已下载 " + fmtSize(doneBytes.get()) + "）");
            } else if (totalBytes <= 0 || doneBytes.get() >= totalBytes) {
                notifyProgress();
                listener.onStatus("下载完成");
                listener.onComplete(destFile, totalBytes > 0 ? totalBytes : doneBytes.get());
            } else {
                listener.onError("下载不完整：" + fmtSize(doneBytes.get()) + " / " + fmtSize(totalBytes));
            }
        } catch (Throwable t) {
            if (running.get()) {
                String msg = lastThreadError != null ? lastThreadError : t.getMessage();
                listener.onError("下载出错: " + msg);
            }
        }
    }

    // ------------------------------------------------------------------ 探测

    private RangeInfo probe() throws IOException {
        HttpURLConnection c = open();
        c.setRequestProperty("Range", "bytes=0-0");
        int code = c.getResponseCode();
        if (code == HttpURLConnection.HTTP_PARTIAL) {
            long total = -1;
            String cr = c.getHeaderField("Content-Range");
            if (cr != null) {
                int slash = cr.lastIndexOf('/');
                if (slash >= 0) {
                    String s = cr.substring(slash + 1).trim();
                    if (!"*".equals(s)) {
                        try { total = Long.parseLong(s); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            c.disconnect();
            return new RangeInfo(true, total);
        } else if (code == HttpURLConnection.HTTP_OK) {
            long len = c.getContentLengthLong();
            c.disconnect();
            return new RangeInfo(false, len);
        } else {
            c.disconnect();
            throw new IOException("服务器响应 " + code);
        }
    }

    // ------------------------------------------------------------------ 分段并行

    private void downloadParallel() throws InterruptedException {
        long chunk = (totalBytes + threads - 1) / threads;
        latch = new CountDownLatch(threads);
        pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            long s = i * chunk;
            long e = Math.min(totalBytes - 1, s + chunk - 1);
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int attempt = 1; attempt <= MAX_RETRY && running.get(); attempt++) {
                            try {
                                downloadChunk(s, e);
                                return;
                            } catch (IOException err) {
                                if (!running.get()) return;
                                if (attempt == MAX_RETRY) throw err;
                                // 短暂等待后重试
                                try { Thread.sleep(500L * attempt); } catch (InterruptedException ignored) {}
                            }
                        }
                    } catch (Throwable t) {
                        if (lastThreadError == null) lastThreadError = t.getMessage();
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        latch.await();
        pool.shutdown();
        pool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void downloadChunk(long start, long end) throws IOException {
        HttpURLConnection c = open();
        c.setRequestProperty("Range", "bytes=" + start + "-" + end);
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        try (InputStream in = c.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(destFile, "rw")) {
            raf.seek(start);
            byte[] buf = new byte[BUFFER];
            int n;
            long written = 0;
            while (running.get() && (n = in.read(buf)) != -1) {
                raf.write(buf, 0, n);
                written += n;
                doneBytes.addAndGet(n);
                maybeNotify();
            }
            // 若提前停止，记录不完整状态，交由主流程判断
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ 流式(单线程)

    private void downloadStream() throws IOException {
        HttpURLConnection c = open();
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        Income in = new Income(c.getInputStream(), c);
        byte[] buf = new byte[BUFFER];
        int n;
        try {
            if (totalBytes > 0) {
                // 已知大小：写入预指定区域
                try (RandomAccessFile raf = new RandomAccessFile(destFile, "rw")) {
                    raf.seek(0);
                    while (running.get() && (n = in.read(buf)) != -1) {
                        raf.write(buf, 0, n);
                        doneBytes.addAndGet(n);
                        maybeNotify();
                    }
                }
            } else {
                // 大小未知：顺序追加
                try (FileOutputStream fos = new FileOutputStream(destFile)) {
                    while (running.get() && (n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        doneBytes.addAndGet(n);
                        maybeNotify();
                    }
                }
            }
        } finally {
            in.close();
        }
    }

    // 包装 InputStream 以便在 finally 中一并关闭连接
    private static final class Income implements AutoCloseable {
        final InputStream is;
        final HttpURLConnection c;
        Income(InputStream is, HttpURLConnection c) { this.is = is; this.c = c; }
        int read(byte[] b) throws IOException { return is.read(b); }
        public void close() {
            try { is.close(); } catch (IOException ignored) {}
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ 工具

    private HttpURLConnection open() throws IOException {
        URL u = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    private void prepareFile(long total) throws IOException {
        if (destFile.exists()) destFile.delete();
        try (RandomAccessFile raf = new RandomAccessFile(destFile, "rw")) {
            raf.setLength(total);
        }
    }

    private void maybeNotify() {
        long now = System.currentTimeMillis();
        long done;
        double speed;
        int pct;
        synchronized (lock) {
            if (now - speedLastMs < NOTIFY_INTERVAL_MS) return;
            long dt = Math.max(1, now - speedLastMs);
            done = doneBytes.get();
            speed = (done - speedLastBytes) * 1000.0 / dt;
            speedLastMs = now;
            speedLastBytes = done;
            pct = (totalBytes <= 0) ? -1 : (int) (done * 100 / totalBytes);
        }
        listener.onProgress(done, totalBytes, speed, pct);
    }

    private void notifyProgress() {
        listener.onProgress(doneBytes.get(), totalBytes, 0, totalBytes <= 0 ? -1
                : (int) (doneBytes.get() * 100 / totalBytes));
    }

    static String fmtSize(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.2f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    private static final class RangeInfo {
        final boolean range;
        final long total;
        RangeInfo(boolean range, long total) { this.range = range; this.total = total; }
    }
}
