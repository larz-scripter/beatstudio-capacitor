package com.larzos.beatstudio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Plays the beat for the performer during recording through a native
 * {@link MediaPlayer} bound to a chosen output device via
 * {@link MediaPlayer#setPreferredDevice}.
 *
 * The WebView's own Web Audio output cannot be routed to a specific device on
 * Android - there is no setSinkId. Playing the beat natively is the only way to
 * give the performer a "listen from here" choice (Bluetooth / wired / speaker)
 * that is independent of what Android would pick for media by default, and
 * independent of the pinned USB/wired capture device.
 */
final class BeatMonitor {

    interface ReadyCallback { void onReady(boolean ok, String err); }

    private final Context ctx;
    private MediaPlayer mp;
    private volatile boolean prepared;

    BeatMonitor(Context ctx) { this.ctx = ctx; }

    boolean isPlaying() {
        try { return mp != null && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    private volatile boolean loopWanted;
    private volatile int completions;
    private volatile String lastError = "";

    int completions() { return completions; }
    String lastError() { return lastError; }

    void start(String url, int fromMs, boolean loop, Integer outputDeviceId, AudioManager am, final ReadyCallback cb) {
        stop();
        final MediaPlayer p = new MediaPlayer();
        this.mp = p;
        this.prepared = false;
        this.loopWanted = loop;
        this.completions = 0;
        this.lastError = "";
        final int seekTo = Math.max(0, fromMs);
        try {
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            p.setDataSource(url);
            p.setLooping(loop);
            if (outputDeviceId != null && Build.VERSION.SDK_INT >= 28) {
                AudioDeviceInfo dev = AudioDeviceCatalog.findById(am, AudioManager.GET_DEVICES_OUTPUTS, outputDeviceId);
                if (dev != null) p.setPreferredDevice(dev);
            }
            p.setOnErrorListener((m, what, extra) -> {
                lastError = "err " + what + "/" + extra;
                if (!prepared) { cb.onReady(false, "MediaPlayer error " + what + "/" + extra); return true; }
                // mid-playback error - try to recover so the beat keeps going
                try { m.reset(); m.setDataSource(url); m.setLooping(loopWanted); m.prepare(); m.start(); }
                catch (Exception e) { lastError = "recover failed: " + e.getMessage(); }
                return true;
            });
            // setLooping should handle it, but on some devices / WAVs it silently
            // stops at EOF - manual restart is the backstop (with looping on,
            // onCompletion normally never fires, so this is harmless when it works)
            p.setOnCompletionListener(m -> {
                completions++;
                lastError = "completion #" + completions;
                if (loopWanted) {
                    try { m.seekTo(0); m.start(); } catch (Exception e) { lastError = "loop restart failed: " + e.getMessage(); }
                }
            });
            p.setOnPreparedListener(m -> {
                prepared = true;
                try {
                    if (seekTo > 0) m.seekTo(seekTo);
                    m.start();
                    cb.onReady(true, null);
                } catch (Exception e) {
                    cb.onReady(false, String.valueOf(e.getMessage()));
                }
            });
            p.prepareAsync();
        } catch (Exception e) {
            cb.onReady(false, String.valueOf(e.getMessage()));
        }
    }

    int positionMs() {
        try { return (mp != null && prepared) ? mp.getCurrentPosition() : -1; }
        catch (Exception e) { return -1; }
    }

    void seek(int ms) {
        try { if (mp != null && prepared) mp.seekTo(Math.max(0, ms)); } catch (Exception e) {}
    }

    String setOutput(Integer outputDeviceId, AudioManager am) {
        try {
            if (mp == null) return "no player";
            if (Build.VERSION.SDK_INT < 28) return "output routing needs Android 9+";
            if (outputDeviceId == null) { mp.setPreferredDevice(null); return "cleared -> default"; }
            AudioDeviceInfo dev = AudioDeviceCatalog.findById(am, AudioManager.GET_DEVICES_OUTPUTS, outputDeviceId);
            if (dev == null) return "device #" + outputDeviceId + " not connected";
            boolean ok = mp.setPreferredDevice(dev);
            return AudioDeviceCatalog.labelFor(dev) + (ok ? "" : " (setPreferredDevice returned false)");
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    void stop() {
        MediaPlayer p = mp;
        mp = null;
        prepared = false;
        if (p != null) {
            try { p.stop(); } catch (Exception e) {}
            try { p.release(); } catch (Exception e) {}
        }
    }

    // ---- local beat cache (so MediaPlayer plays from disk, not a network stream:
    //      streaming a fresh URL adds ~1s+ of prepare latency to record-arm) ----

    static File cachedFileFor(Context ctx, String url) {
        String ext = ".wav";
        int q = url.indexOf('?');
        String clean = q >= 0 ? url.substring(0, q) : url;
        int slash = clean.lastIndexOf('/');
        int dot = clean.lastIndexOf('.');
        if (dot > slash && clean.length() - dot <= 6) ext = clean.substring(dot);
        return new File(ctx.getCacheDir(), "beat_" + Integer.toHexString(url.hashCode()) + ext);
    }

    static void download(String url, File out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        try {
            c.connect();
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code + " for " + url);
            File part = new File(out.getAbsolutePath() + ".part");
            try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(part)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            if (part.length() <= 0) { part.delete(); throw new IOException("empty download"); }
            if (out.exists()) out.delete();
            if (!part.renameTo(out)) throw new IOException("could not finalise " + out.getName());
        } finally {
            c.disconnect();
        }
    }
}
