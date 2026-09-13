package com.larzos.beatstudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that OWNS real playback via a native MediaPlayer once a
 * queue has been loaded (see LarzMediaPlugin.loadQueue), instead of just
 * mirroring a notification for whatever the page's own &lt;audio&gt; element
 * happens to be doing. That distinction is the whole point: BeatStudio's
 * pages are plain server-rendered pages, not an SPA, so navigating to any
 * other page in the app destroys the previous page's JS and its &lt;audio&gt;
 * element entirely - there is nothing left alive to react to a notification
 * button press. A Service has no such lifecycle tie, so play/pause/next/
 * prev from the notification or lock screen keep working regardless of what
 * page (if any) is currently shown, and playback itself keeps going when
 * you navigate away instead of stopping.
 *
 * Falls back to the older passive "mirror whatever nowPlaying() reports"
 * behaviour (applyState/ACTION_UPDATE) when no queue has ever been loaded,
 * so existing integrations that only call nowPlaying() still work.
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
    public static final String ACTION_LOAD_QUEUE = "com.larzos.beatstudio.action.LOAD_QUEUE";
    public static final String ACTION_PLAY_AT = "com.larzos.beatstudio.action.PLAY_AT";
    public static final String ACTION_SEEK = "com.larzos.beatstudio.action.SEEK";
    public static final String ACTION_SET_REPEAT = "com.larzos.beatstudio.action.SET_REPEAT";

    /** Fired for a transport button tap that this service can't fully
     *  handle itself (nothing loaded natively yet) - lets a page that
     *  happens to be alive react the old way. */
    public interface ControlListener { void onControl(String action); }
    private static volatile ControlListener listener;
    public static void setControlListener(ControlListener l) { listener = l; }

    /** Fired on every native playback state change, so a page that's alive
     *  right now (e.g. you just navigated back to it) can sync its own UI -
     *  seek bar, title, play/pause icon - to what's actually already
     *  playing, instead of assuming nothing is. */
    public interface StateListener { void onState(JSONObject state); }
    private static volatile StateListener stateListener;
    public static void setStateListener(StateListener l) { stateListener = l; }

    /** Fired whenever MediaPlayer itself fails to prepare a track - the
     *  `what`/`extra` codes are otherwise only visible in device logcat,
     *  which isn't reachable when debugging a report remotely. Exposing
     *  them here let a single JS-side debug beacon answer "why did it
     *  fail" without ever needing physical/adb access to the device. */
    public interface ErrorListener { void onPlaybackError(JSONObject error); }
    private static volatile ErrorListener errorListener;
    public static void setErrorListener(ErrorListener l) { errorListener = l; }

    /** Last broadcast snapshot, so LarzMediaPlugin.getState() can answer
     *  synchronously (a freshly-loaded page's first render) without waiting
     *  for the next tick. */
    private static volatile JSONObject lastState = null;
    public static JSONObject getLastState() { return lastState; }

    private MediaSessionCompat mediaSession;
    private PowerManager.WakeLock wakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ---- native playback / queue state ----
    private MediaPlayer player;
    private JSONArray queue;      // [{url,title,artist,durationSec}, ...] in original order
    private int[] order;          // permutation of indices into `queue` - identity or shuffled
    private int pos = -1;         // index into `order`; -1 = nothing loaded
    private int repeatMode = 0;   // 0 off, 1 one, 2 all
    private boolean preparing = false;
    // Every track failing to prepare (bad/expired auth cookie, network
    // outage) used to cascade through onError -> handleNext() -> onError
    // forever once repeat-all wrapped it back to track 0 - confirmed
    // on-device as a tight loop hammering the backend at several requests
    // per second indefinitely with nothing ever playing. Counts
    // consecutive prepare failures across the whole queue; reset to 0 the
    // moment any track actually prepares successfully.
    private int consecutiveFailures = 0;
    // One background thread for downloads - both the track actually being
    // played and its prefetch run through it, so at most one download is
    // ever in flight at a time (deliberately conservative: this is a
    // background service, not a download manager, and shouldn't compete
    // with whatever else the user's connection is doing).
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    // Incremented on every handlePlayAt() call - lets a download that
    // finishes after the user has already skipped past that track (next/
    // prev tapped again before the first one even finished downloading)
    // recognise it's stale and quietly do nothing instead of starting
    // playback of a track that's no longer the current selection.
    private volatile int playGeneration = 0;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            broadcastState();
            if (isPlayingSafe()) mainHandler.postDelayed(this, 1000);
        }
    };

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
                @Override public void onPlay() { handlePlay(); }
                @Override public void onPause() { handlePause(); }
                @Override public void onSkipToNext() { handleNext(); }
                @Override public void onSkipToPrevious() { handlePrev(); }
                @Override public void onStop() { handleStop(); }
                @Override public void onSeekTo(long posMs) { handleSeek(posMs); }
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

            // Unlike play/pause/next/prev, this used to fire ACTION_STOP
            // back to JS unconditionally on every stop - not just when
            // nothing was loaded - which is a redundant round trip for
            // the same reason the other actions' unconditional fallback
            // turned into a ping-pong loop (confirmed on-device for play/
            // next/prev/pause this session). handleStop() already does
            // its job regardless of what was loaded, so there's nothing
            // for a live page to additionally handle here.
            if (ACTION_STOP.equals(action)) { handleStop(); return START_NOT_STICKY; }
            else if (ACTION_PLAY.equals(action)) handlePlay();
            else if (ACTION_PAUSE.equals(action)) handlePause();
            else if (ACTION_NEXT.equals(action)) handleNext();
            else if (ACTION_PREV.equals(action)) handlePrev();
            else if (ACTION_LOAD_QUEUE.equals(action)) handleLoadQueue(intent);
            else if (ACTION_PLAY_AT.equals(action)) handlePlayAt(indexOfTrack(intent.getIntExtra("index", 0)));
            else if (ACTION_SEEK.equals(action)) handleSeek(intent.getLongExtra("positionMs", 0));
            else if (ACTION_SET_REPEAT.equals(action)) repeatMode = intent.getIntExtra("repeat", repeatMode);
            else if (intent != null && intent.hasExtra("title")) applyState(intent);
        } catch (Throwable t) {
            Log.e(TAG, "onStartCommand failed - stopping self, background playback unavailable this session", t);
            try { stopSelf(); } catch (Throwable ignored) {}
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    // ---- queue-based playback (the real fix - survives page navigation) ----

    private void handleLoadQueue(Intent intent) {
        try {
            // Captured from the OLD queue/order before we overwrite them -
            // e.g. toggling shuffle mid-song sends a whole new queue+order
            // but the currently-playing track is still the same one, and
            // should keep playing uninterrupted rather than restart from 0.
            String currentUrl = currentTrackUrl();
            consecutiveFailures = 0;

            queue = new JSONArray(intent.getStringExtra("queue"));
            int startTrackIndex = intent.getIntExtra("startIndex", 0);
            repeatMode = intent.getIntExtra("repeat", 0);
            boolean shuffle = intent.getBooleanExtra("shuffle", false);
            buildOrder(shuffle, startTrackIndex);
            int newPos = indexOfTrack(startTrackIndex);

            String targetUrl = null;
            try { targetUrl = queue.getJSONObject(order[newPos]).optString("url", null); } catch (Throwable ignored) {}

            if (player != null && !preparing && targetUrl != null && targetUrl.equals(currentUrl)) {
                pos = newPos;
                pushNotificationForCurrentTrack(isPlayingSafe());
            } else {
                handlePlayAt(newPos);
            }
        } catch (Throwable t) {
            Log.e(TAG, "loadQueue failed", t);
        }
    }

    private String currentTrackUrl() {
        if (queue == null || order == null || pos < 0 || pos >= order.length) return null;
        try { return queue.getJSONObject(order[pos]).optString("url", null); } catch (Throwable t) { return null; }
    }

    private void buildOrder(boolean shuffle, int startTrackIndex) {
        int n = queue.length();
        order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        if (shuffle && n > 0) {
            List<Integer> rest = new ArrayList<>();
            for (int i = 0; i < n; i++) if (i != startTrackIndex) rest.add(i);
            Collections.shuffle(rest);
            order[0] = startTrackIndex;
            for (int i = 0; i < rest.size(); i++) order[i + 1] = rest.get(i);
        }
    }

    /** order[]-position of a raw track index - used when JS asks to play a
     *  specific track (e.g. tapped from a list) rather than next/prev. */
    private int indexOfTrack(int trackIndex) {
        if (order == null) return 0;
        for (int i = 0; i < order.length; i++) if (order[i] == trackIndex) return i;
        return 0;
    }

    /** Downloads the track to local storage first (or reuses an already-
     *  cached copy) and only ever hands MediaPlayer a local file - never a
     *  network URL directly. Streaming straight from the network was
     *  confirmed on-device to be genuinely unreliable here (a CDN serving
     *  a stale cached response at one point, a mobile connection dropping
     *  mid-transfer at another), and for a library of hundreds of songs
     *  that unreliability compounds every single time playback advances.
     *  A completed local file either fully downloaded or doesn't exist -
     *  there's no truncated/corrupt state MediaPlayer could ever see. */
    private void handlePlayAt(int newPos) {
        if (queue == null || order == null || order.length == 0) return;
        if (newPos < 0 || newPos >= order.length) return;
        pos = newPos;
        releasePlayer();
        final int myGeneration = ++playGeneration;
        final String url;
        try {
            JSONObject track = queue.getJSONObject(order[pos]);
            url = track.optString("url", null);
            if (url == null || url.isEmpty()) { Log.e(TAG, "track has no url at order[" + pos + "]"); return; }
        } catch (Throwable t) {
            Log.e(TAG, "playAt failed reading track", t);
            return;
        }
        preparing = true;
        pushNotificationForCurrentTrack(false); // shows the title immediately while it downloads/loads

        bgExecutor.execute(() -> {
            File local = null;
            IOException err = null;
            try {
                local = SongCache.getOrDownload(getApplicationContext(), url);
            } catch (IOException e) {
                err = e;
            } catch (Throwable t) {
                err = new IOException(String.valueOf(t.getMessage()), t);
            }
            final File finalLocal = local;
            final IOException finalErr = err;
            mainHandler.post(() -> {
                if (myGeneration != playGeneration) return; // superseded by a later play/next/prev - stale, drop it
                if (finalLocal != null) startPlayerFromLocalFile(finalLocal, url);
                else handleDownloadFailure(url, finalErr);
            });
        });
    }

    private void startPlayerFromLocalFile(File local, String originalUrl) {
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(local.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                preparing = false;
                consecutiveFailures = 0;
                try { mp.start(); } catch (Throwable ignored) {}
                mainHandler.post(progressTick);
                pushNotificationForCurrentTrack(true);
                prefetchNext();
            });
            player.setOnCompletionListener(mp -> handleTrackCompleted());
            player.setOnErrorListener((mp, what, extra) -> {
                // A LOCAL file failing to play (as opposed to failing to
                // download) points at something other than network
                // flakiness - genuinely corrupt/unsupported audio, most
                // likely - but is otherwise handled exactly the same way.
                Log.e(TAG, "MediaPlayer error (local file) what=" + what + " extra=" + extra + " url=" + originalUrl);
                reportPlaybackError(what, extra, originalUrl, true);
                preparing = false;
                handlePlaybackFailure();
                return true;
            });
            player.prepareAsync();
        } catch (Throwable t) {
            Log.e(TAG, "startPlayerFromLocalFile failed", t);
            preparing = false;
            handlePlaybackFailure();
        }
    }

    private void handleDownloadFailure(String url, IOException err) {
        Log.e(TAG, "download failed for " + url, err);
        reportPlaybackError(-1, 0, url, false);
        preparing = false;
        handlePlaybackFailure();
    }

    private void reportPlaybackError(int what, int extra, String url, boolean hadCookie) {
        ErrorListener el = errorListener;
        if (el == null) return;
        try {
            JSONObject err = new JSONObject();
            err.put("what", what);
            err.put("extra", extra);
            err.put("url", url);
            err.put("hadCookie", hadCookie);
            el.onPlaybackError(err);
        } catch (Throwable ignored) {}
    }

    /** Shared by a failed download and a failed local-file prepare -
     *  advances to the next track, same as the loop-guard already did for
     *  streaming failures, so one bad file doesn't stop a whole "play all"
     *  session. Still stops cleanly instead of looping once every track in
     *  the queue has failed once (confirmed on-device this session: without
     *  this cap, a bad connection plus repeat-all cascaded into thousands
     *  of retries a minute). */
    private void handlePlaybackFailure() {
        consecutiveFailures++;
        int queueLen = order != null ? order.length : 1;
        if (consecutiveFailures >= Math.max(1, queueLen)) {
            Log.e(TAG, "every track in the queue failed to play - stopping instead of looping forever");
            consecutiveFailures = 0;
            handleStop();
        } else {
            handleNext();
        }
    }

    /** Downloads the NEXT track in the background while the current one is
     *  still playing, so by the time playback naturally advances (or the
     *  user taps next) it's already sitting in local storage and starts
     *  instantly - the actual point of caching at all for a "play all"
     *  session across a large library, not just papering over one bad
     *  request. Silently skips whatever's already cached. */
    private void prefetchNext() {
        if (queue == null || order == null || order.length == 0) return;
        int nextIdx = pos + 1;
        if (nextIdx >= order.length) {
            if (repeatMode == 2) nextIdx = 0; else return;
        }
        try {
            String nextUrl = queue.getJSONObject(order[nextIdx]).optString("url", null);
            if (nextUrl == null || nextUrl.isEmpty()) return;
            if (SongCache.isCached(getApplicationContext(), nextUrl)) return;
            bgExecutor.execute(() -> {
                try { SongCache.getOrDownload(getApplicationContext(), nextUrl); }
                catch (Throwable t) { Log.w(TAG, "prefetch failed for " + nextUrl + " - will retry when actually needed"); }
            });
        } catch (Throwable ignored) {}
    }

    private void handleTrackCompleted() {
        if (repeatMode == 1) { handlePlayAt(pos); return; } // repeat-one: replay this slot
        int next = pos + 1;
        if (next >= order.length) {
            if (repeatMode == 2) handlePlayAt(0); else handleStop();
        } else {
            handlePlayAt(next);
        }
    }

    private void handlePlay() {
        if (player != null && !preparing) {
            try { player.start(); mainHandler.post(progressTick); pushNotificationForCurrentTrack(true); }
            catch (Throwable ignored) {}
        } else {
            fire(ACTION_PLAY); // nothing loaded natively yet - a live page can still handle it
        }
    }

    private void handlePause() {
        if (player != null && !preparing) {
            try { player.pause(); pushNotificationForCurrentTrack(false); } catch (Throwable ignored) {}
        } else {
            fire(ACTION_PAUSE);
        }
    }

    private void handleNext() {
        if (order != null && order.length > 0) {
            int next = pos + 1;
            if (next >= order.length) { if (repeatMode == 2) handlePlayAt(0); }
            else handlePlayAt(next);
        } else {
            fire(ACTION_NEXT);
        }
    }

    private void handlePrev() {
        if (order != null && order.length > 0) {
            try {
                if (player != null && player.getCurrentPosition() > 3000) { player.seekTo(0); broadcastState(); return; }
            } catch (Throwable ignored) {}
            if (pos > 0) handlePlayAt(pos - 1);
        } else {
            fire(ACTION_PREV);
        }
    }

    private void handleSeek(long ms) {
        if (player != null) {
            try { player.seekTo((int) ms); broadcastState(); } catch (Throwable ignored) {}
        }
    }

    private void handleStop() {
        releasePlayer();
        queue = null; order = null; pos = -1;
        stopSelfSafely();
    }

    private boolean isPlayingSafe() {
        try { return player != null && player.isPlaying(); } catch (Throwable t) { return false; }
    }

    private void releasePlayer() {
        mainHandler.removeCallbacks(progressTick);
        if (player != null) {
            try { player.reset(); player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }

    // ---- notification / session for the current queue track ----

    private void pushNotificationForCurrentTrack(boolean isPlaying) {
        if (queue == null || order == null || pos < 0 || pos >= order.length) return;
        try {
            JSONObject track = queue.getJSONObject(order[pos]);
            String title = track.optString("title", "BeatStudio");
            String artist = track.optString("artist", "");
            long duration = 0;
            try { if (player != null) duration = player.getDuration(); } catch (Throwable ignored) {}
            if (duration <= 0) duration = (long) (track.optDouble("durationSec", 0) * 1000);
            long position = 0;
            try { if (player != null) position = player.getCurrentPosition(); } catch (Throwable ignored) {}

            applyMediaSession(title, artist, isPlaying, position, duration);

            Notification n = buildNotification(title, artist, isPlaying);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
            if (isPlaying) { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L); }
            else if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            broadcastState();
        } catch (Throwable t) {
            Log.e(TAG, "pushNotificationForCurrentTrack failed", t);
        }
    }

    private void broadcastState() {
        if (queue == null || order == null || pos < 0 || pos >= order.length) return;
        try {
            JSONObject track = queue.getJSONObject(order[pos]);
            JSONObject state = new JSONObject();
            state.put("trackIndex", order[pos]);
            // The one field a page should actually key off of: JS always
            // sends shuffle:false and pre-orders the queue itself, so
            // trackIndex/queuePos are positions in whatever order THIS
            // playback session was loaded with - meaningless to a page that
            // just loaded fresh and built its own (possibly differently
            // shuffled) order[]. Matching on the URL instead needs no
            // shared position scheme at all.
            state.put("url", track.optString("url", ""));
            state.put("title", track.optString("title", ""));
            state.put("artist", track.optString("artist", ""));
            state.put("isPlaying", isPlayingSafe());
            long positionMs = 0, durationMs = 0;
            try { if (player != null) positionMs = player.getCurrentPosition(); } catch (Throwable ignored) {}
            try { if (player != null) durationMs = player.getDuration(); } catch (Throwable ignored) {}
            state.put("positionMs", positionMs);
            state.put("durationMs", durationMs);
            state.put("queuePos", pos);
            state.put("queueLen", order.length);
            lastState = state;
            StateListener l = stateListener;
            if (l != null) l.onState(state);
        } catch (Throwable ignored) {}
    }

    // ---- legacy passive path (nowPlaying() only, no queue ever loaded) ----

    private void applyState(Intent intent) {
        String title = intent.getStringExtra("title");
        String artist = intent.getStringExtra("artist");
        boolean isPlaying = intent.getBooleanExtra("isPlaying", false);
        long position = intent.getLongExtra("position", 0);
        long duration = intent.getLongExtra("duration", 0);
        applyMediaSession(title, artist, isPlaying, position, duration);
        try {
            Notification n = buildNotification(title, artist, isPlaying);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed - stopping self, background playback unavailable this session", t);
            try { stopSelf(); } catch (Throwable ignored) {}
            return;
        }
        try {
            if (isPlaying) { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L); }
            else if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
    }

    private void applyMediaSession(String title, String artist, boolean isPlaying, long position, long duration) {
        if (mediaSession == null) return;
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
                            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_STOP
                            | PlaybackStateCompat.ACTION_SEEK_TO)
                    .setState(state, position, isPlaying ? 1f : 0f);
            mediaSession.setPlaybackState(pb.build());
        } catch (Throwable t) {
            Log.e(TAG, "session metadata/state update failed", t);
        }
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
            // Tapping the notification used to just resume the app on
            // whatever page it happened to be showing (a plain multi-page
            // site, not an SPA, so that could be anywhere - the upload
            // page, an artist profile, wherever navigation last left it),
            // not the player - requested explicitly so it always lands
            // somewhere you can switch tracks or otherwise act on what's
            // playing. MainActivity.EXTRA_OPEN_PLAYER (see
            // patch_mainactivity.py) forces the WebView to the playlist
            // page when this extra is present.
            launch.putExtra("open_player", true);
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
        releasePlayer();
        try { bgExecutor.shutdownNow(); } catch (Throwable ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        try { if (mediaSession != null) mediaSession.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
