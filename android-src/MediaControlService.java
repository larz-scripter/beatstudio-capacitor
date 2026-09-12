package com.larzos.beatstudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

/**
 * Foreground service that keeps BeatStudio's WebView audio alive in the
 * background: a media-style notification + MediaSession stop Doze/App
 * Standby from freezing the process, and add play/pause/next/prev on the
 * lock screen. Playback itself never moves here - it stays in the WebView's
 * <audio> element. This service only mirrors state LarzMediaPlugin reports
 * from JS, and forwards button taps back to it.
 */
public class MediaControlService extends Service {

    private static final String TAG = "BeatStudioMedia";
    private static final String CHANNEL_ID = "beatstudio_playback";
    private static final int NOTIF_ID = 4271;

    public static final String ACTION_PLAY = "com.larzos.beatstudio.action.PLAY";
    public static final String ACTION_PAUSE = "com.larzos.beatstudio.action.PAUSE";
    public static final String ACTION_NEXT = "com.larzos.beatstudio.action.NEXT";
    public static final String ACTION_PREV = "com.larzos.beatstudio.action.PREV";
    public static final String ACTION_STOP = "com.larzos.beatstudio.action.STOP";
    public static final String ACTION_UPDATE = "com.larzos.beatstudio.action.UPDATE";

    public interface ControlListener { void onControl(String action); }
    private static volatile ControlListener listener;
    public static void setControlListener(ControlListener l) { listener = l; }

    private MediaSessionCompat mediaSession;
    private PowerManager.WakeLock wakeLock;

    // Every entry point below is wrapped defensively: this service exists to
    // ADD background playback, and must never be the reason the whole app
    // (WebView included) gets taken down by an uncaught exception here - an
    // unhandled crash in a Service kills the entire host process. Any
    // failure just means background playback stays unavailable for this
    // session (same as before this feature existed), logged via Log.e so a
    // logcat pull explains why.

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            mediaSession = new MediaSessionCompat(this, "BeatStudioMedia");
            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override public void onPlay() { fire(ACTION_PLAY); }
                @Override public void onPause() { fire(ACTION_PAUSE); }
                @Override public void onSkipToNext() { fire(ACTION_NEXT); }
                @Override public void onSkipToPrevious() { fire(ACTION_PREV); }
                @Override public void onStop() { fire(ACTION_STOP); }
            });
            mediaSession.setActive(true);
        } catch (Throwable t) {
            Log.e(TAG, "MediaSession init failed", t);
            mediaSession = null;
        }

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeatStudio:playback");
            wakeLock.setReferenceCounted(false);
        } catch (Throwable t) {
            Log.e(TAG, "WakeLock init failed", t);
            wakeLock = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            String action = (intent != null && intent.getAction() != null) ? intent.getAction() : ACTION_UPDATE;

            if (ACTION_STOP.equals(action)) {
                fire(ACTION_STOP);
                stopSelfSafely();
                return START_NOT_STICKY;
            }
            if (ACTION_PLAY.equals(action) || ACTION_PAUSE.equals(action)
                    || ACTION_NEXT.equals(action) || ACTION_PREV.equals(action)) {
                fire(action);
            }

            if (intent != null && intent.hasExtra("title")) {
                applyState(intent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "onStartCommand failed - stopping self, background playback unavailable this session", t);
            try { stopSelf(); } catch (Throwable ignored) {}
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void applyState(Intent intent) {
        String title = intent.getStringExtra("title");
        String artist = intent.getStringExtra("artist");
        boolean isPlaying = intent.getBooleanExtra("isPlaying", false);
        long position = intent.getLongExtra("position", 0);
        long duration = intent.getLongExtra("duration", 0);

        if (mediaSession != null) {
            try {
                MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title != null && !title.isEmpty() ? title : "BeatStudio")
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist != null ? artist : "")
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
                mediaSession.setMetadata(meta.build());

                int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
                PlaybackStateCompat.Builder pb = new PlaybackStateCompat.Builder()
                        .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_STOP)
                        .setState(state, position, isPlaying ? 1f : 0f);
                mediaSession.setPlaybackState(pb.build());
            } catch (Throwable t) {
                Log.e(TAG, "session metadata/state update failed", t);
            }
        }

        try {
            Notification n = buildNotification(title, artist, isPlaying);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            // Most likely failure point: ForegroundServiceStartNotAllowedException
            // (Android 12+ background-start restrictions) or a missing/invalid
            // notification channel/icon. Never let this take the app down.
            Log.e(TAG, "startForeground failed - stopping self, background playback unavailable this session", t);
            try { stopSelf(); } catch (Throwable ignored) {}
            return;
        }

        try {
            if (isPlaying) {
                if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
            } else if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {}
    }

    private Notification buildNotification(String title, String artist, boolean isPlaying) {
        ensureChannel();
        int iconRes = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (iconRes == 0) iconRes = android.R.drawable.ic_media_play;

        PendingIntent playPause = actionIntent(isPlaying ? ACTION_PAUSE : ACTION_PLAY, 1);
        PendingIntent next = actionIntent(ACTION_NEXT, 2);
        PendingIntent prev = actionIntent(ACTION_PREV, 3);

        PendingIntent contentIntent = null;
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            contentIntent = PendingIntent.getActivity(this, 0, launch, flags);
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(iconRes)
                .setContentTitle(title != null && !title.isEmpty() ? title : "BeatStudio")
                .setContentText(artist != null && !artist.isEmpty() ? artist : "Now playing")
                .setOngoing(isPlaying)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prev)
                .addAction(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        isPlaying ? "Pause" : "Play", playPause)
                .addAction(android.R.drawable.ic_media_next, "Next", next);
        if (mediaSession != null) {
            // Falls back to a plain notification (still keeps the process
            // alive + still has working buttons) if the session failed to init.
            b.setStyle(new MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        if (contentIntent != null) b.setContentIntent(contentIntent);
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Playback",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("BeatStudio now-playing controls");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private PendingIntent actionIntent(String action, int reqCode) {
        Intent i = new Intent(this, MediaControlService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, reqCode, i, flags);
    }

    private void fire(String action) {
        ControlListener l = listener;
        if (l != null) l.onControl(action);
    }

    private void stopSelfSafely() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        try { if (mediaSession != null) mediaSession.setActive(false); } catch (Throwable ignored) {}
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        try { if (mediaSession != null) mediaSession.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
