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

    @Override
    public void onCreate() {
        super.onCreate();
        mediaSession = new MediaSessionCompat(this, "BeatStudioMedia");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { fire(ACTION_PLAY); }
            @Override public void onPause() { fire(ACTION_PAUSE); }
            @Override public void onSkipToNext() { fire(ACTION_NEXT); }
            @Override public void onSkipToPrevious() { fire(ACTION_PREV); }
            @Override public void onStop() { fire(ACTION_STOP); }
        });
        mediaSession.setActive(true);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BeatStudio:playback");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        } else if (!ACTION_UPDATE.equals(action)) {
            // A bare transport-button tap with no state payload - just keep
            // the existing notification alive, JS will push a fresh
            // nowPlaying() once it reacts to the 'control' event.
        }
        return START_STICKY;
    }

    private void applyState(Intent intent) {
        String title = intent.getStringExtra("title");
        String artist = intent.getStringExtra("artist");
        boolean isPlaying = intent.getBooleanExtra("isPlaying", false);
        long position = intent.getLongExtra("position", 0);
        long duration = intent.getLongExtra("duration", 0);

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

        Notification n = buildNotification(title, artist, isPlaying);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }

        try {
            if (isPlaying) {
                if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
            } else if (wakeLock.isHeld()) {
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
                .addAction(android.R.drawable.ic_media_next, "Next", next)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
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
