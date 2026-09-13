package com.larzos.beatstudio;

import android.content.Context;
import android.webkit.CookieManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Downloads a song to app-private internal storage once, then always plays
 * from that local copy afterward - MediaPlayer streaming a song fresh from
 * the network every single play was confirmed on-device to be genuinely
 * unreliable in practice (a Cloudflare-cached stale conditional response at
 * one point, a connection dropping mid-transfer at another - see git log for
 * both), and for "play all" across a large library that unreliability compounds
 * every time the network so much as blinks. A completed local download either
 * fully succeeded (verified against Content-Length) or doesn't exist at all;
 * there is no "half downloaded" state a MediaPlayer could ever be handed.
 *
 * Uses getFilesDir(), not getCacheDir() - the OS is free to wipe a cache dir
 * under storage pressure at any time, which would defeat the entire point of
 * "songs are there and ready" for a library of hundreds of tracks.
 */
final class SongCache {

    private static final String DIR_NAME = "beatstudio_songs";
    // A generous but bounded budget - "hundreds of songs" at a few MB each
    // would otherwise grow without limit. Least-recently-played files are
    // evicted first once this is exceeded, so whatever's actually being
    // listened to regularly stays cached.
    private static final long MAX_CACHE_BYTES = 500L * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private SongCache() {}

    /** Returns the locally cached file for this URL, downloading it first
     *  (with retry) if it isn't already cached. Never returns a partial
     *  file - either this returns a complete download or throws. Runs
     *  network I/O; must be called off the main thread. */
    static File getOrDownload(Context ctx, String url) throws IOException {
        File dir = new File(ctx.getFilesDir(), DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        String key = keyFor(url);
        File dest = new File(dir, key);
        if (dest.exists() && dest.length() > 0) {
            dest.setLastModified(System.currentTimeMillis()); // touch for LRU
            return dest;
        }

        File tmp = new File(dir, key + ".part-" + Thread.currentThread().getId());
        IOException lastErr = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                download(url, tmp);
                if (dest.exists() && dest.length() > 0) {
                    // Another thread (prefetch racing a manual play-tap on
                    // the same track) already finished it while we were
                    // downloading our own copy - use theirs, drop ours.
                    tmp.delete();
                    return dest;
                }
                if (!tmp.renameTo(dest)) throw new IOException("rename to final cache path failed");
                evictIfNeeded(dir, dest);
                return dest;
            } catch (IOException e) {
                lastErr = e;
                tmp.delete();
                if (attempt < MAX_ATTEMPTS) {
                    try { Thread.sleep(attempt * 800L); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw lastErr != null ? lastErr : new IOException("download failed for unknown reason");
    }

    /** True if already fully downloaded - lets a caller decide to skip a
     *  redundant prefetch without doing any I/O. */
    static boolean isCached(Context ctx, String url) {
        File f = new File(new File(ctx.getFilesDir(), DIR_NAME), keyFor(url));
        return f.exists() && f.length() > 0;
    }

    private static void download(String urlStr, File tmp) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        try {
            String cookie = null;
            try { cookie = CookieManager.getInstance().getCookie(urlStr); } catch (Throwable ignored) {}
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " for " + urlStr);
            long expected = conn.getContentLengthLong();

            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); total += n; }
                out.flush();
                // The exact failure mode that started this class: a
                // connection dropping mid-transfer used to hand MediaPlayer
                // a truncated file it would garble or refuse outright.
                // Catch that here, before anything is ever played from it.
                if (expected > 0 && total != expected) {
                    throw new IOException("truncated download: got " + total + " of " + expected + " bytes");
                }
                if (total == 0) throw new IOException("empty response body");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void evictIfNeeded(File dir, File justWritten) {
        File[] files = dir.listFiles((d, name) -> !name.contains(".part-"));
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_CACHE_BYTES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= MAX_CACHE_BYTES) break;
            if (f.equals(justWritten)) continue; // never evict what we just downloaded for this exact play
            long sz = f.length();
            if (f.delete()) total -= sz;
        }
    }

    /** Keyed off the URL path only (query string, e.g. a cache-busting
     *  param, stripped) so the same song always maps to the same local
     *  file regardless of what query string a particular request used. */
    private static String keyFor(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2 + 4);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.append(".cache").toString();
        } catch (Exception e) {
            return "song-" + Math.abs(path.hashCode()) + ".cache";
        }
    }
}
