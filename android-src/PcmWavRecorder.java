package com.larzos.beatstudio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Records raw PCM from a chosen input device straight to a 16-bit WAV file, then
 * hands it back to JS as base64.
 *
 * Why native instead of getUserMedia/MediaRecorder:
 *  - {@link AudioRecord#setPreferredDevice} pins capture to a specific
 *    {@link AudioDeviceInfo} - the USB mic - which the WebView cannot do.
 *  - {@link MediaRecorder.AudioSource#UNPROCESSED} gives a genuinely raw signal
 *    (no AEC / AGC / noise-suppression), so a beat playing on another device is
 *    never "helped" into the capture path by voice-comms DSP.
 *  - It is fully independent of whatever the WebView plays the beat through, so
 *    record and monitor stay on separate devices by construction.
 */
final class PcmWavRecorder {

    interface LevelListener { void onLevel(double rms); }

    static final int MAX_SECONDS = 600; // native single-take cap - the take is served
                                        // over a loopback socket, not base64, so this
                                        // can be generous (covers any song length)

    static final class Result {
        File file;                 // the finished WAV (caller/local server owns it)
        String base64 = "";        // populated only for the legacy small-take path
        long durationMs;
        int sampleRate;
        int channels;
        long firstFrameLatencyMs;
        long firstFrameNanos;      // absolute System.nanoTime() of the first captured sample
        boolean truncated;
        String source;
        String device;
    }

    private final Context ctx;
    private final AudioManager am;
    private final int sampleRate;
    private final int channels;

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private File wav;
    private LevelListener levelListener;

    private String sourceName = "mic";
    private String activeDeviceLabel = "system default";
    private long startNanos;
    private volatile long firstFrameNanos;
    private volatile long dataBytes;
    private volatile boolean truncated;
    private long lastLevelAt = 0;

    PcmWavRecorder(Context ctx, AudioManager am, int sampleRate, int channels) {
        this.ctx = ctx;
        this.am = am;
        this.sampleRate = clampSampleRate(sampleRate);
        this.channels = (channels == 2) ? 2 : 1;
    }

    boolean isRecording() { return running; }
    int getSampleRate() { return sampleRate; }
    int getChannels() { return channels; }
    String getSourceName() { return sourceName; }
    String getActiveDeviceLabel() { return activeDeviceLabel; }
    void setLevelListener(LevelListener l) { this.levelListener = l; }

    void start(Integer preferredInputId) throws Exception {
        int channelMask = (channels == 2) ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            throw new IllegalStateException("Unsupported capture format " + sampleRate + " Hz / " + channels + " ch");
        }
        final int bufBytes = Math.max(minBuf * 4, sampleRate * channels * 2); // ~1s headroom

        int source = MediaRecorder.AudioSource.MIC;
        sourceName = "mic";
        if (Build.VERSION.SDK_INT >= 24
                && "1".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))) {
            source = MediaRecorder.AudioSource.UNPROCESSED;
            sourceName = "unprocessed";
        }

        AudioRecord r = new AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(bufBytes)
                .build();

        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            safeRelease(r);
            throw new IllegalStateException("AudioRecord did not initialise");
        }

        if (preferredInputId != null) {
            AudioDeviceInfo dev = AudioDeviceCatalog.findById(am, AudioManager.GET_DEVICES_INPUTS, preferredInputId);
            if (dev != null) {
                boolean ok = r.setPreferredDevice(dev);
                activeDeviceLabel = AudioDeviceCatalog.labelFor(dev) + (ok ? "" : " (couldn't pin - using default)");
            } else {
                activeDeviceLabel = "requested device #" + preferredInputId + " not connected - system default";
            }
        } else {
            activeDeviceLabel = "system default";
        }

        this.record = r;
        this.wav = new File(ctx.getCacheDir(), "bs_take_" + System.currentTimeMillis() + ".wav");
        this.dataBytes = 0;
        this.firstFrameNanos = 0;
        this.truncated = false;

        r.startRecording();
        if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            safeRelease(r);
            this.record = null;
            throw new IllegalStateException("AudioRecord failed to enter RECORDING state");
        }

        this.startNanos = System.nanoTime();
        this.running = true;
        this.thread = new Thread(() -> loop(bufBytes), "bs-audio-capture");
        this.thread.start();
    }

    private void loop(int bufBytes) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(wav, "rw");
            raf.setLength(0);
            writeWavHeader(raf, 0);
            raf.seek(44);

            // read() BLOCKS until the requested size is captured, so a big
            // read makes firstFrameNanos land a whole buffer late (a 32 KB /
            // ~341 ms read was throwing take placement ~340 ms off-beat).
            // Small reads + back-dating by the returned span pin the first
            // captured sample precisely.
            final int frameBytes = channels * 2;
            int chunk = (sampleRate / 50) * frameBytes;                 // ~20 ms
            chunk = Math.max(frameBytes * 64, Math.min(chunk, 8192));
            byte[] buf = new byte[chunk];
            long capBytes = (long) MAX_SECONDS * sampleRate * channels * 2L;
            boolean first = true;

            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE
                            || n == AudioRecord.ERROR_DEAD_OBJECT) break;
                    continue;
                }
                if (first) {
                    firstFrameNanos = estimateFirstFrameNanos(n);
                    first = false;
                }
                raf.write(buf, 0, n);
                dataBytes += n;
                emitLevel(buf, n);
                if (dataBytes >= capBytes) { truncated = true; running = false; break; }
            }
            writeWavHeader(raf, dataBytes);
        } catch (IOException e) {
            // best effort - stop() still returns whatever landed on disk
        } finally {
            if (raf != null) try { raf.close(); } catch (IOException ignored) {}
        }
    }

    Result stop() throws IOException {
        running = false;
        if (thread != null) {
            try { thread.join(4000); } catch (InterruptedException ignored) {}
        }
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) {}
            safeRelease(record);
            record = null;
        }

        Result out = new Result();
        out.sampleRate = sampleRate;
        out.channels = channels;
        out.source = sourceName;
        out.device = activeDeviceLabel;
        out.truncated = truncated;
        out.durationMs = dataBytes * 1000L / (long) (sampleRate * channels * 2);
        out.firstFrameLatencyMs = firstFrameNanos > 0 ? (firstFrameNanos - startNanos) / 1_000_000L : 0;
        out.firstFrameNanos = firstFrameNanos;

        // Hand the finished WAV file to the caller; it is served over a loopback
        // socket and deleted afterwards. No base64 (a full-song take is tens of MB
        // and must not go through the Capacitor message bridge).
        if (wav != null && wav.exists() && wav.length() > 44) {
            out.file = wav;
        }
        return out;
    }

    void cancel() {
        running = false;
        if (thread != null) {
            try { thread.join(2000); } catch (InterruptedException ignored) {}
        }
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) {}
            safeRelease(record);
            record = null;
        }
        deleteTemp();
    }

    // ---- helpers ----

    /**
     * Absolute {@link System#nanoTime} of the first captured PCM sample.
     * Prefers {@link AudioRecord#getTimestamp} (a hardware-clocked frame
     * position, API 24+); falls back to back-dating the first read() by the
     * span of bytes it returned - either way the anchor no longer lands a
     * whole read-buffer late.
     */
    private long estimateFirstFrameNanos(int firstBytes) {
        long now = System.nanoTime();
        try {
            android.media.AudioTimestamp ts = new android.media.AudioTimestamp();
            if (record != null
                    && record.getTimestamp(ts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC)
                       == AudioRecord.SUCCESS
                    && ts.framePosition > 0) {
                return ts.nanoTime - ts.framePosition * 1_000_000_000L / (long) sampleRate;
            }
        } catch (Throwable ignored) {}
        long spanNanos = (long) firstBytes * 1_000_000_000L / (long) (sampleRate * channels * 2);
        return now - spanNanos;
    }

    private void deleteTemp() {
        if (wav != null && wav.exists()) {
            boolean ignored = wav.delete();
        }
    }

    private static void safeRelease(AudioRecord r) {
        try { r.release(); } catch (Throwable ignored) {}
    }

    private void emitLevel(byte[] buf, int n) {
        LevelListener l = levelListener;
        if (l == null) return;
        long now = System.currentTimeMillis();
        if (now - lastLevelAt < 45) return; // ~22 Hz
        lastLevelAt = now;
        long sumSq = 0;
        int samples = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            short s = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
            sumSq += (long) s * s;
            samples++;
        }
        double rms = samples > 0 ? Math.sqrt((double) sumSq / samples) / 32768.0 : 0.0;
        try { l.onLevel(rms); } catch (Throwable ignored) {}
    }

    private void writeWavHeader(RandomAccessFile raf, long dataLen) throws IOException {
        final int bitsPerSample = 16;
        final int byteRate = sampleRate * channels * bitsPerSample / 8;
        final int blockAlign = channels * bitsPerSample / 8;
        raf.seek(0);
        raf.writeBytes("RIFF");
        writeLE32(raf, (int) (36 + dataLen));
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        writeLE32(raf, 16);
        writeLE16(raf, 1); // PCM
        writeLE16(raf, channels);
        writeLE32(raf, sampleRate);
        writeLE32(raf, byteRate);
        writeLE16(raf, blockAlign);
        writeLE16(raf, bitsPerSample);
        raf.writeBytes("data");
        writeLE32(raf, (int) dataLen);
    }

    private static void writeLE32(RandomAccessFile raf, int v) throws IOException {
        raf.write(v & 0xff); raf.write((v >> 8) & 0xff); raf.write((v >> 16) & 0xff); raf.write((v >> 24) & 0xff);
    }

    private static void writeLE16(RandomAccessFile raf, int v) throws IOException {
        raf.write(v & 0xff); raf.write((v >> 8) & 0xff);
    }

    private static int clampSampleRate(int sr) {
        for (int c : new int[] { 48000, 44100, 32000, 24000, 16000 }) if (c == sr) return sr;
        return 48000;
    }
}
