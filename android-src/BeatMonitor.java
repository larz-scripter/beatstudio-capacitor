package com.larzos.beatstudio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Plays the beat / monitor bed for the performer during recording through a
 * native {@link AudioTrack} in streaming mode, reading a local 16-bit PCM WAV.
 *
 * Why AudioTrack, not MediaPlayer (the v1.7 and earlier implementation):
 *  - {@link AudioTrack#getPlaybackHeadPosition()} is a true hardware frame
 *    counter: it advances at exactly the DAC rate and never freezes. The
 *    song position a recorded take is anchored to is therefore
 *    sample-accurate, and the bed the performer hears plays at exactly real
 *    time - so a take no longer drifts off-beat over its length (the bug
 *    users kept hitting: "on the beat while recording, off by the end").
 *  - {@link AudioTrack#getTimestamp} ties a frame position to a
 *    {@link System#nanoTime} instant (and accounts for output latency), which
 *    the plugin uses to place the take at the exact song position that was
 *    sounding when the mic captured its first sample.
 *  - Reading a local file means no network buffering and none of MediaPlayer's
 *    "position pinned at the seek target for seconds while it buffers".
 *  - {@link AudioTrack#setPreferredDevice} still gives the routable-output
 *    choice (Bluetooth / wired / speaker) the WebView's Web Audio cannot.
 */
final class BeatMonitor {

    interface ReadyCallback { void onReady(boolean ok, String err); }

    private final Context ctx;

    private volatile AudioTrack track;
    private volatile Thread feeder;
    private volatile boolean playing;
    private volatile boolean stopRequested;
    private volatile String lastError = "";
    private volatile int completions;

    private volatile int sampleRate = 48000;
    private volatile int channels = 1;
    private volatile long seekFrames = 0;
    private volatile File srcFile;
    private volatile boolean loopWanted;
    private volatile Integer lastOutputDeviceId;

    BeatMonitor(Context ctx) { this.ctx = ctx; }

    boolean isPlaying() { return playing; }
    int completions() { return completions; }
    String lastError() { return lastError; }

    void start(final String url, final int fromMs, final boolean loop,
               final Integer outputDeviceId, final AudioManager am, final ReadyCallback cb) {
        stop();
        this.stopRequested = false;
        this.loopWanted = loop;
        this.completions = 0;
        this.lastError = "";
        if (outputDeviceId != null) this.lastOutputDeviceId = outputDeviceId;
        final boolean[] answered = { false };
        Thread starter = new Thread(() -> {
            try {
                File f;
                if (url.startsWith("file://")) f = new File(url.substring(7));
                else if (url.startsWith("/")) f = new File(url);
                else {
                    f = cachedFileFor(ctx, url);
                    if (!f.exists() || f.length() <= 44) download(url, f);
                }
                if (!f.exists() || f.length() <= 44) throw new IOException("bed file missing/empty: " + f);
                srcFile = f;

                WavInfo wi = parseWav(f);
                sampleRate = wi.sampleRate;
                channels = wi.channels;
                seekFrames = Math.max(0L, (long) fromMs * (long) wi.sampleRate / 1000L);

                int chConfig = (channels == 2) ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
                int minBuf = AudioTrack.getMinBufferSize(sampleRate, chConfig, AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) throw new IOException("AudioTrack.getMinBufferSize failed for " + sampleRate + " Hz");
                int bufBytes = Math.max(minBuf * 2, sampleRate * channels * 2 / 4); // ~250 ms

                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(chConfig)
                                .build())
                        .setBufferSizeInBytes(bufBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                    try { t.release(); } catch (Exception ignored) {}
                    throw new IOException("AudioTrack did not initialise");
                }
                if (outputDeviceId != null && am != null && Build.VERSION.SDK_INT >= 23) {
                    AudioDeviceInfo dev = AudioDeviceCatalog.findById(am, AudioManager.GET_DEVICES_OUTPUTS, outputDeviceId);
                    if (dev != null) t.setPreferredDevice(dev);
                }

                track = t;
                playing = true;
                t.play();
                if (!answered[0]) { answered[0] = true; cb.onReady(true, null); }

                feedLoop(t, wi);
            } catch (Exception e) {
                lastError = String.valueOf(e.getMessage());
                playing = false;
                if (!answered[0]) { answered[0] = true; cb.onReady(false, lastError); }
            }
        }, "bs-beat-monitor");
        this.feeder = starter;
        starter.start();
    }

    private void feedLoop(AudioTrack t, WavInfo wi) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(srcFile, "r");
            final long dataStart = wi.dataOffset;
            final long dataEnd = wi.dataOffset + wi.dataLen;
            long startByte = dataStart + seekFrames * (long) wi.channels * 2L;
            if (startByte > dataEnd) startByte = dataEnd;
            // keep the seek on a frame boundary
            startByte -= (startByte - dataStart) % ((long) wi.channels * 2L);
            raf.seek(startByte);

            byte[] buf = new byte[16384];
            while (playing && !stopRequested) {
                long remaining = dataEnd - raf.getFilePointer();
                if (remaining <= 0) {
                    completions++;
                    if (loopWanted) { raf.seek(dataStart); continue; }
                    break;
                }
                int want = (int) Math.min((long) buf.length, remaining);
                int n = raf.read(buf, 0, want);
                if (n <= 0) break;
                int off = 0;
                while (off < n && playing && !stopRequested) {
                    int w = t.write(buf, off, n - off);   // blocks in MODE_STREAM -> paces to real playback
                    if (w < 0) { lastError = "AudioTrack.write " + w; playing = false; return; }
                    off += w;
                }
            }
            if (!stopRequested) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}   // let the tail drain
            }
        } catch (Exception e) {
            // includes IllegalStateException if the track is released under a
            // blocked write() during stop() - benign, we're tearing down anyway
            if (!stopRequested) lastError = "feed: " + e.getMessage();
        } finally {
            if (raf != null) try { raf.close(); } catch (IOException ignored) {}
            playing = false;
        }
    }

    private long headFrames() {
        AudioTrack t = track;
        if (t == null) return 0;
        try { return t.getPlaybackHeadPosition() & 0xffffffffL; }
        catch (Exception e) { return 0; }
    }

    int positionMs() {
        if (track == null || !playing) return -1;
        long songFrames = seekFrames + headFrames();
        return (int) (songFrames * 1000L / Math.max(1, sampleRate));
    }

    /**
     * {songFrameNow, nanoTime, sampleRate} - the song frame at the speaker right
     * now and the CLOCK_MONOTONIC instant it holds for. Uses getTimestamp when
     * it can (accounts for output latency), else head position + now.
     */
    long[] snapshot() {
        AudioTrack t = track;
        if (t == null || !playing) return null;
        try {
            AudioTimestamp ts = new AudioTimestamp();
            if (Build.VERSION.SDK_INT >= 23 && t.getTimestamp(ts) && ts.framePosition >= 0) {
                return new long[]{ seekFrames + ts.framePosition, ts.nanoTime, sampleRate };
            }
        } catch (Exception ignored) {}
        return new long[]{ seekFrames + headFrames(), System.nanoTime(), sampleRate };
    }

    void seek(int ms) {
        File f = srcFile;
        if (f == null) return;
        boolean loop = loopWanted;
        String path = f.getAbsolutePath();
        Integer outId = lastOutputDeviceId;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        stop();
        start(path, ms, loop, outId, am, (ok, err) -> {});
    }

    String setOutput(Integer outputDeviceId, AudioManager am) {
        try {
            AudioTrack t = track;
            if (t == null) return "no player";
            if (Build.VERSION.SDK_INT < 23) return "output routing needs Android 6+";
            if (outputDeviceId == null) { t.setPreferredDevice(null); return "cleared -> default"; }
            AudioDeviceInfo dev = AudioDeviceCatalog.findById(am, AudioManager.GET_DEVICES_OUTPUTS, outputDeviceId);
            if (dev == null) return "device #" + outputDeviceId + " not connected";
            boolean ok = t.setPreferredDevice(dev);
            return AudioDeviceCatalog.labelFor(dev) + (ok ? "" : " (setPreferredDevice returned false)");
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    void stop() {
        stopRequested = true;
        playing = false;
        Thread fe = feeder; feeder = null;
        AudioTrack t = track; track = null;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) {}
            try { t.flush(); } catch (Exception ignored) {}
            try { t.stop(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }
        if (fe != null && fe != Thread.currentThread()) {
            try { fe.join(700); } catch (InterruptedException ignored) {}
        }
    }

    // ---- WAV header parsing (16-bit PCM, mono/stereo, any rate) ----

    private static final class WavInfo {
        int sampleRate;
        int channels;
        long dataOffset;
        long dataLen;
    }

    private static WavInfo parseWav(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[12];
            raf.readFully(head);
            if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F'
                    || head[8] != 'W' || head[9] != 'A' || head[10] != 'V' || head[11] != 'E') {
                throw new IOException("not a RIFF/WAVE file");
            }
            WavInfo wi = new WavInfo();
            long len = raf.length();
            while (raf.getFilePointer() + 8 <= len) {
                byte[] id = new byte[4];
                raf.readFully(id);
                long size = readLE32(raf);                 // already unsigned (readLE32 masks)
                long bodyStart = raf.getFilePointer();     // = chunk header + 8
                String cid = new String(id, "US-ASCII");
                if ("fmt ".equals(cid)) {
                    int audioFmt = readLE16(raf);
                    wi.channels = readLE16(raf);
                    wi.sampleRate = (int) readLE32(raf);
                    readLE32(raf);  // byte rate
                    readLE16(raf);  // block align
                    int bits = readLE16(raf);
                    if (bits != 16) throw new IOException("expected 16-bit PCM, got " + bits + "-bit");
                    if (audioFmt != 1 && audioFmt != 0xFFFE) throw new IOException("non-PCM WAV (fmt " + audioFmt + ")");
                } else if ("data".equals(cid)) {
                    wi.dataOffset = bodyStart;
                    long avail = len - bodyStart;
                    // some writers leave a stale/zero data size in a streamed header
                    wi.dataLen = (size > 0 && size <= avail) ? size : avail;
                }
                if (wi.sampleRate > 0 && wi.dataLen > 0) break;   // have both - stop
                long next = bodyStart + size + (size & 1L);       // pad to even
                if (next <= bodyStart || next > len) break;       // malformed size
                raf.seek(next);                                   // jump to the next chunk
            }
            if (wi.sampleRate <= 0 || wi.channels <= 0 || wi.dataLen <= 0) {
                throw new IOException("WAV missing fmt/data chunk");
            }
            if (wi.channels > 2) throw new IOException("unsupported channel count " + wi.channels);
            return wi;
        }
    }

    private static long readLE32(RandomAccessFile raf) throws IOException {
        int b0 = raf.read(), b1 = raf.read(), b2 = raf.read(), b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new IOException("EOF in header");
        return (b0 & 0xffL) | ((b1 & 0xffL) << 8) | ((b2 & 0xffL) << 16) | ((b3 & 0xffL) << 24);
    }

    private static int readLE16(RandomAccessFile raf) throws IOException {
        int b0 = raf.read(), b1 = raf.read();
        if ((b0 | b1) < 0) throw new IOException("EOF in header");
        return (b0 & 0xff) | ((b1 & 0xff) << 8);
    }

    // ---- local cache + download (a local file means no MediaPlayer-style
    //      buffering; the WebView-rendered monitor mix is already local) ----

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
