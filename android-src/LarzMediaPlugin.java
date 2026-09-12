package com.larzos.beatstudio;

import android.Manifest;
import android.content.Intent;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Bridges the web player's play state to a foreground Service + MediaSession
 * so playback - which stays entirely in the WebView's <audio> element -
 * survives the app being backgrounded or the screen turning off, and gets
 * lock-screen / notification transport controls that call back into the page.
 *
 *   LarzMedia.nowPlaying({title, artist, isPlaying, position, duration}) (seconds)
 *   LarzMedia.stop()
 *
 * Event 'control' { action: 'play'|'pause'|'next'|'prev'|'stop' } fires when
 * the user taps a notification / lock-screen / Bluetooth button.
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(i);
            } else {
                getContext().startService(i);
            }
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
}
