package com.larzos.beatstudio;

import android.Manifest;
import android.content.Intent;
import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Bridges the web player to a foreground Service (MediaControlService) that
 * OWNS real playback via a native MediaPlayer once a queue is loaded - not
 * just a notification mirroring the page's own &lt;audio&gt; element. That's
 * what makes play/pause/next/prev from the notification or lock screen keep
 * working after you've navigated to a different page in the app: BeatStudio
 * is a plain multi-page site, so navigating tears down the previous page's
 * JS and &lt;audio&gt; element entirely, and there is nothing left alive to
 * react to a button press unless the Service holds the actual playback.
 *
 *   LarzMedia.loadQueue({tracks:[{url,title,artist,durationSec}], startIndex, repeat, shuffle})
 *     - starts native playback of the queue at startIndex. Call this once
 *       whenever the queue/repeat/shuffle mode changes; playAt() for a plain
 *       track change within the same queue.
 *   LarzMedia.playAt({index})   - play a specific track index in the queue already loaded
 *   LarzMedia.resume() / pause() / next() / prev()
 *   LarzMedia.seek({positionMs})
 *   LarzMedia.getState()        - {trackIndex,title,artist,isPlaying,positionMs,durationMs,queuePos,queueLen} or {} if nothing loaded
 *   LarzMedia.stop()
 *
 *   LarzMedia.nowPlaying(...)   - legacy passive mode, kept for compatibility;
 *                                  superseded by loadQueue()+playAt() wherever used.
 *
 * Events:
 *   'control' { action } - a transport button was pressed and nothing was
 *      loaded natively yet, so a live page needs to handle it the old way.
 *   'state' { ...same shape as getState() } - fires on every native playback
 *      state change, so a page that's currently open can sync its own UI.
 */
@CapacitorPlugin(
        name = "LarzMedia",
        permissions = {
                @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
        }
)
public class LarzMediaPlugin extends Plugin {

    @Override
    protected void handleOnStart() {
        super.handleOnStart();
        MediaControlService.setControlListener(action -> {
            JSObject ev = new JSObject();
            ev.put("action", action.substring(action.lastIndexOf('.') + 1).toLowerCase());
            notifyListeners("control", ev);
        });
        MediaControlService.setStateListener(state -> {
            try { notifyListeners("state", JSObject.fromJSONObject(state)); } catch (Throwable ignored) {}
        });
    }

    @PluginMethod
    public void loadQueue(final PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "afterNotifPermissionQueue");
            return;
        }
        doLoadQueue(call);
    }

    @PermissionCallback
    private void afterNotifPermissionQueue(PluginCall call) {
        doLoadQueue(call); // proceed regardless - see afterNotifPermission below
    }

    private void doLoadQueue(PluginCall call) {
        try {
            JSArray tracks = call.getArray("tracks");
            if (tracks == null) { call.reject("tracks required"); return; }
            Intent i = new Intent(getContext(), MediaControlService.class);
            i.setAction(MediaControlService.ACTION_LOAD_QUEUE);
            i.putExtra("queue", tracks.toString());
            i.putExtra("startIndex", call.getInt("startIndex", 0));
            i.putExtra("repeat", call.getInt("repeat", 0));
            Boolean shuffle = call.getBoolean("shuffle", Boolean.FALSE);
            i.putExtra("shuffle", shuffle != null && shuffle);
            startForegroundCompat(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("loadQueue failed: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void playAt(PluginCall call) {
        sendAction(MediaControlService.ACTION_PLAY_AT, i -> i.putExtra("index", call.getInt("index", 0)));
        call.resolve();
    }

    @PluginMethod
    public void resume(PluginCall call) {
        sendAction(MediaControlService.ACTION_PLAY, null);
        call.resolve();
    }

    @PluginMethod
    public void pause(PluginCall call) {
        sendAction(MediaControlService.ACTION_PAUSE, null);
        call.resolve();
    }

    @PluginMethod
    public void next(PluginCall call) {
        sendAction(MediaControlService.ACTION_NEXT, null);
        call.resolve();
    }

    @PluginMethod
    public void prev(PluginCall call) {
        sendAction(MediaControlService.ACTION_PREV, null);
        call.resolve();
    }

    @PluginMethod
    public void seek(PluginCall call) {
        Double ms = call.getDouble("positionMs");
        final long posMs = (long) (ms != null ? ms : 0.0);
        sendAction(MediaControlService.ACTION_SEEK, i -> i.putExtra("positionMs", posMs));
        call.resolve();
    }

    /** Changes repeat mode in place - unlike loadQueue(), does not touch or
     *  restart whatever's currently playing. */
    @PluginMethod
    public void setRepeat(PluginCall call) {
        final int repeat = call.getInt("repeat", 0);
        sendAction(MediaControlService.ACTION_SET_REPEAT, i -> i.putExtra("repeat", repeat));
        call.resolve();
    }

    @PluginMethod
    public void getState(PluginCall call) {
        try {
            org.json.JSONObject state = MediaControlService.getLastState();
            call.resolve(state != null ? JSObject.fromJSONObject(state) : new JSObject());
        } catch (Exception e) {
            call.resolve(new JSObject());
        }
    }

    @PluginMethod
    public void nowPlaying(final PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "afterNotifPermission");
            return;
        }
        pushState(call);
    }

    @PermissionCallback
    private void afterNotifPermission(PluginCall call) {
        // Proceed either way - a denied POST_NOTIFICATIONS still lets the
        // foreground service (and therefore background playback) run, it
        // just won't show a visible notification. Acceptable degrade.
        pushState(call);
    }

    private void pushState(PluginCall call) {
        try {
            Intent i = new Intent(getContext(), MediaControlService.class);
            i.setAction(MediaControlService.ACTION_UPDATE);
            i.putExtra("title", call.getString("title", "BeatStudio"));
            i.putExtra("artist", call.getString("artist", ""));
            Boolean playing = call.getBoolean("isPlaying", Boolean.FALSE);
            i.putExtra("isPlaying", playing != null && playing);
            Double pos = call.getDouble("position");
            Double dur = call.getDouble("duration");
            i.putExtra("position", (long) ((pos != null ? pos : 0.0) * 1000));
            i.putExtra("duration", (long) ((dur != null ? dur : 0.0) * 1000));
            startForegroundCompat(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("nowPlaying failed: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            Intent i = new Intent(getContext(), MediaControlService.class);
            i.setAction(MediaControlService.ACTION_STOP);
            getContext().startService(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("stop failed: " + e.getMessage(), e);
        }
    }

    private interface IntentExtra { void apply(Intent i); }

    private void sendAction(String action, IntentExtra extra) {
        try {
            Intent i = new Intent(getContext(), MediaControlService.class);
            i.setAction(action);
            if (extra != null) extra.apply(i);
            getContext().startService(i);
        } catch (Throwable ignored) {}
    }

    private void startForegroundCompat(Intent i) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(i);
        } else {
            getContext().startService(i);
        }
    }
}
