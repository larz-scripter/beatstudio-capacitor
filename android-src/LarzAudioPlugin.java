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
            ret.put("base64", r.base64);
            ret.put("mimeType", "audio/wav");
            ret.put("durationMs", r.durationMs);
            ret.put("sampleRate", r.sampleRate);
            ret.put("channels", r.channels);
            ret.put("firstFrameLatencyMs", r.firstFrameLatencyMs);
            ret.put("truncated", r.truncated);
            ret.put("source", r.source);
            ret.put("device", r.device);
            ret.put("wallClockMs", System.currentTimeMillis() - captureStartedAt);

            JSObject logData = new JSObject();
            logData.put("durationMs", r.durationMs);
            logData.put("base64Len", r.base64.length());
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

    @PluginMethod
    public void cancelCapture(PluginCall call) {
        step("cancelCapture", null);
        if (recorder != null) {
            try { recorder.cancel(); } catch (Throwable ignored) {}
            recorder = null;
        }
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (recorder != null) {
            try { recorder.cancel(); } catch (Throwable ignored) {}
            recorder = null;
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
