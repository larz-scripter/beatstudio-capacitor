package com.larzos.beatstudio;

import android.Manifest;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Native audio connector for BeatStudio.
 *
 *   LarzAudio.listDevices()                  -> { inputs:[...], outputs:[...], unprocessedSupported }
 *   LarzAudio.checkPermissions()             -> { microphone: 'granted'|'denied'|'prompt' }
 *   LarzAudio.requestPermissions()           -> same
 *   LarzAudio.startCapture({inputDeviceId?}) -> { started, sampleRate, channels, source, device }
 *   LarzAudio.stopCapture()                  -> { base64, mimeType, durationMs, sampleRate, channels,
 *                                                 firstFrameLatencyMs, truncated, source, device }
 *   LarzAudio.cancelCapture()                -> {}
 *
 * Events:
 *   'level' { rms }        ~22 Hz while capturing, for the input meter
 *   'log'   { step, data } every pipeline step, mirrored to logcat tag "BeatStudioAudio"
 *
 * Every step is logged both ways on purpose: there is no server log for anything
 * on the record path, so testing needs a visible trail. Filter logcat by
 * "BeatStudioAudio", or read the 'log' events in the WebView console.
 */
@CapacitorPlugin(
        name = "LarzAudio",
        permissions = {
                @Permission(alias = "microphone", strings = { Manifest.permission.RECORD_AUDIO })
        }
)
public class LarzAudioPlugin extends Plugin {

    static final String TAG = "BeatStudioAudio";

    private PcmWavRecorder recorder;
    private BeatMonitor beatMonitor;
    private LocalFileServer takeServer;
    private long captureStartedAt;

    private void step(String stepName, JSObject data) {
        Log.d(TAG, stepName + (data != null ? " " + data.toString() : ""));
        JSObject ev = new JSObject();
        ev.put("step", stepName);
        ev.put("t", System.currentTimeMillis());
        if (data != null) ev.put("data", data);
        try { notifyListeners("log", ev); } catch (Throwable ignored) {}
    }

    private void stepErr(String stepName, Throwable t) {
        Log.e(TAG, stepName + " FAILED", t);
        JSObject ev = new JSObject();
        ev.put("step", stepName);
        ev.put("t", System.currentTimeMillis());
        ev.put("error", String.valueOf(t.getMessage()));
        try { notifyListeners("log", ev); } catch (Throwable ignored) {}
    }

    @PluginMethod
    public void listDevices(PluginCall call) {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            JSObject ret = new JSObject();
            ret.put("inputs", AudioDeviceCatalog.list(am, AudioManager.GET_DEVICES_INPUTS));
            ret.put("outputs", AudioDeviceCatalog.list(am, AudioManager.GET_DEVICES_OUTPUTS));
            boolean unprocessed = Build.VERSION.SDK_INT >= 24
                    && "1".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
            ret.put("unprocessedSupported", unprocessed);
            ret.put("sdkInt", Build.VERSION.SDK_INT);
            JSObject logData = new JSObject();
            logData.put("inputCount", ret.getJSONArray("inputs").length());
            logData.put("outputCount", ret.getJSONArray("outputs").length());
            logData.put("unprocessedSupported", unprocessed);
            step("listDevices", logData);
            Log.d(TAG, "listDevices full: " + ret.toString());
            call.resolve(ret);
        } catch (Exception e) {
            stepErr("listDevices", e);
            call.reject("listDevices failed: " + e.getMessage(), e);
        }
    }

    // checkPermissions() / requestPermissions() are provided by the base Plugin
    // class from the @Permission annotation above - no override needed.

    @PluginMethod
    public void startCapture(final PluginCall call) {
        step("startCapture:call", argSummary(call));
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            step("startCapture:requestingPermission", null);
            requestPermissionForAlias("microphone", call, "afterMicPermission");
            return;
        }
        doStart(call);
    }

    @PermissionCallback
    private void afterMicPermission(PluginCall call) {
        boolean granted = getPermissionState("microphone") == PermissionState.GRANTED;
        JSObject d = new JSObject();
        d.put("granted", granted);
        step("startCapture:permissionResult", d);
        if (granted) {
            doStart(call);
        } else {
            call.reject("Microphone permission denied");
        }
    }

    private void doStart(PluginCall call) {
        try {
            if (recorder != null && recorder.isRecording()) {
                step("startCapture:alreadyRecording", null);
                call.reject("Already recording");
                return;
            }
            Integer inputDeviceId = call.getInt("inputDeviceId");   // null => system default
            int sampleRate = call.getInt("sampleRate", 48000);
            int channels = call.getInt("channels", 1);

            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            recorder = new PcmWavRecorder(getContext(), am, sampleRate, channels);
            recorder.setLevelListener(rms -> {
                JSObject ev = new JSObject();
                ev.put("rms", rms);
                notifyListeners("level", ev);
            });

            JSObject pre = new JSObject();
            pre.put("inputDeviceId", inputDeviceId == null ? "default" : inputDeviceId);
            pre.put("sampleRate", sampleRate);
            pre.put("channels", channels);
            step("startCapture:starting", pre);

            recorder.start(inputDeviceId);
            captureStartedAt = System.currentTimeMillis();

            JSObject ret = new JSObject();
            ret.put("started", true);
            ret.put("sampleRate", recorder.getSampleRate());
            ret.put("channels", recorder.getChannels());
            ret.put("source", recorder.getSourceName());
            ret.put("device", recorder.getActiveDeviceLabel());
            step("startCapture:started", ret);
            call.resolve(ret);
        } catch (Exception e) {
            stepErr("startCapture", e);
            recorder = null;
            call.reject("startCapture failed: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void stopCapture(PluginCall call) {
        step("stopCapture:call", null);
        if (recorder == null) {
            step("stopCapture:notRecording", null);
            call.reject("Not recording");
            return;
        }
        try {
            PcmWavRecorder.Result r = recorder.stop();
            recorder = null;

            JSObject ret = new JSObject();
            ret.put("mimeType", "audio/wav");
            ret.put("durationMs", r.durationMs);
            ret.put("sampleRate", r.sampleRate);
            ret.put("channels", r.channels);
            ret.put("firstFrameLatencyMs", r.firstFrameLatencyMs);
            ret.put("truncated", r.truncated);
            ret.put("source", r.source);
            ret.put("device", r.device);
            ret.put("wallClockMs", System.currentTimeMillis() - captureStartedAt);

            long bytes = 0;
            String url = "";
            String b64 = "";
            if (r.file != null && r.file.exists()) {
                bytes = r.file.length();
                if (takeServer != null) takeServer.stop();
                takeServer = new LocalFileServer();
                int port = takeServer.start(r.file, "audio/wav");
                url = "http://127.0.0.1:" + port + "/take.wav";
                // safety net for takes small enough to survive the bridge: also
                // hand back base64 so a blocked loopback fetch never loses a take
                if (bytes > 0 && bytes <= 12_000_000L) {
                    try { b64 = android.util.Base64.encodeToString(LocalFileServer.readAll(r.file), android.util.Base64.NO_WRAP); }
                    catch (Exception ignored) {}
                }
            }
            ret.put("url", url);
            ret.put("base64", b64);
            ret.put("bytes", bytes);

            JSObject logData = new JSObject();
            logData.put("durationMs", r.durationMs);
            logData.put("bytes", bytes);
            logData.put("url", url);
            logData.put("base64Len", b64.length());
            logData.put("firstFrameLatencyMs", r.firstFrameLatencyMs);
            logData.put("truncated", r.truncated);
            logData.put("source", r.source);
            logData.put("device", r.device);
            step("stopCapture:done", logData);
            call.resolve(ret);
        } catch (Exception e) {
            stepErr("stopCapture", e);
            recorder = null;
            call.reject("stopCapture failed: " + e.getMessage(), e);
        }
    }

    // Chunked base64 fallback for when the loopback socket is blocked and the
    // take is too big for a single base64 payload. Reads the finished WAV file
    // the takeServer still holds, `size` bytes at a time.
    @PluginMethod
    public void getTakeChunk(PluginCall call) {
        try {
            java.io.File f = (takeServer != null) ? takeServer.getFile() : null;
            if (f == null || !f.exists()) { call.reject("no take file"); return; }
            int index = call.getInt("index", 0);
            int size = call.getInt("size", 3_000_000);
            if (size < 1 || size > 8_000_000) size = 3_000_000;
            long off = (long) index * (long) size;
            long flen = f.length();
            JSObject r = new JSObject();
            if (off >= flen) {
                r.put("base64", "");
                r.put("eof", true);
                call.resolve(r);
                return;
            }
            int n = (int) Math.min((long) size, flen - off);
            byte[] buf = new byte[n];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                raf.seek(off);
                raf.readFully(buf);
            }
            r.put("base64", android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP));
            r.put("index", index);
            r.put("bytes", n);
            r.put("eof", off + n >= flen);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("getTakeChunk failed: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void releaseTake(PluginCall call) {
        if (takeServer != null) { takeServer.stop(); takeServer = null; }
        step("releaseTake", null);
        call.resolve();
    }

    @PluginMethod
    public void cancelCapture(PluginCall call) {
        step("cancelCapture", null);
        if (recorder != null) {
            try { recorder.cancel(); } catch (Throwable ignored) {}
            recorder = null;
        }
        call.resolve();
    }

    // ---- beat monitor (native MediaPlayer, routable output) ----

    @PluginMethod
    public void prepareBeat(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("url required"); return; }
        final java.io.File out = BeatMonitor.cachedFileFor(getContext(), url);
        if (out.exists() && out.length() > 0) {
            JSObject r = new JSObject();
            r.put("localPath", out.getAbsolutePath());
            r.put("cached", true);
            r.put("bytes", out.length());
            call.resolve(r);
            return;
        }
        JSObject dl = new JSObject();
        dl.put("url", url);
        step("prepareBeat:downloading", dl);
        new Thread(() -> {
            try {
                BeatMonitor.download(url, out);
                JSObject r = new JSObject();
                r.put("localPath", out.getAbsolutePath());
                r.put("cached", false);
                r.put("bytes", out.length());
                step("prepareBeat:done", r);
                call.resolve(r);
            } catch (Exception e) {
                try { if (out.exists()) out.delete(); } catch (Exception ex) {}
                stepErr("prepareBeat", e);
                call.reject("prepareBeat failed: " + e.getMessage(), e);
            }
        }, "bs-beat-prefetch").start();
    }

    @PluginMethod
    public void startBeatMonitor(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("url required"); return; }
        final int fromMs = call.getInt("fromMs", 0);
        Boolean loopB = call.getBoolean("loop", Boolean.TRUE);
        final boolean loop = loopB == null || loopB;
        final Integer outId = call.getInt("outputDeviceId");
        final AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (beatMonitor == null) beatMonitor = new BeatMonitor(getContext());

        JSObject pre = new JSObject();
        pre.put("fromMs", fromMs);
        pre.put("loop", loop);
        pre.put("outputDeviceId", outId == null ? "default" : outId);
        pre.put("url", url);
        step("startBeatMonitor:call", pre);

        beatMonitor.start(url, fromMs, loop, outId, am, (ok, err) -> {
            if (ok) {
                JSObject r = new JSObject();
                r.put("playing", true);
                r.put("positionMs", beatMonitor.positionMs());
                step("startBeatMonitor:playing", r);
                call.resolve(r);
            } else {
                JSObject fail = new JSObject();
                fail.put("error", err);
                step("startBeatMonitor:failed", fail);
                call.reject("beat monitor failed: " + err);
            }
        });
    }

    @PluginMethod
    public void stopBeatMonitor(PluginCall call) {
        if (beatMonitor != null) beatMonitor.stop();
        step("stopBeatMonitor", null);
        call.resolve();
    }

    @PluginMethod
    public void getBeatMonitorPosition(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ms", beatMonitor != null ? beatMonitor.positionMs() : -1);
        r.put("playing", beatMonitor != null && beatMonitor.isPlaying());
        r.put("completions", beatMonitor != null ? beatMonitor.completions() : 0);
        r.put("lastError", beatMonitor != null ? beatMonitor.lastError() : "");
        call.resolve(r);
    }

    @PluginMethod
    public void seekBeatMonitor(PluginCall call) {
        if (beatMonitor != null) beatMonitor.seek(call.getInt("ms", 0));
        call.resolve();
    }

    @PluginMethod
    public void setMonitorOutput(PluginCall call) {
        Integer outId = call.getInt("outputDeviceId");
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        String result = (beatMonitor != null) ? beatMonitor.setOutput(outId, am) : "no monitor running";
        JSObject r = new JSObject();
        r.put("outputDeviceId", outId == null ? "default" : outId);
        r.put("result", result);
        step("setMonitorOutput", r);
        call.resolve(r);
    }

    @Override
    protected void handleOnDestroy() {
        if (recorder != null) {
            try { recorder.cancel(); } catch (Throwable ignored) {}
            recorder = null;
        }
        if (beatMonitor != null) {
            try { beatMonitor.stop(); } catch (Throwable ignored) {}
            beatMonitor = null;
        }
        if (takeServer != null) {
            try { takeServer.stop(); } catch (Throwable ignored) {}
            takeServer = null;
        }
        super.handleOnDestroy();
    }

    private static JSObject argSummary(PluginCall call) {
        JSObject o = new JSObject();
        o.put("inputDeviceId", call.getInt("inputDeviceId") == null ? "default" : call.getInt("inputDeviceId"));
        o.put("sampleRate", call.getInt("sampleRate", 48000));
        o.put("channels", call.getInt("channels", 1));
        return o;
    }
}
