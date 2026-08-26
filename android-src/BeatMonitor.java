package com.larzos.beatstudio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;

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

    void start(String url, int fromMs, boolean loop, Integer outputDeviceId, AudioManager am, final ReadyCallback cb) {
        stop();
        final MediaPlayer p = new MediaPlayer();
        this.mp = p;
        this.prepared = false;
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
                cb.onReady(false, "MediaPlayer error " + what + "/" + extra);
                return true;
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
}
