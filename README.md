# BeatStudio — Capacitor Android app

Native Android shell for **larzos.com/beatstudio/**. It loads the live site in a
Capacitor WebView and adds a **native audio connector** so recording works with
any USB / external mic and stays on a separate device from beat playback.

## Why this exists

In a browser / TWA on Android the web layer can only pick the capture *input*,
can't choose the *output* at all, and frequently can't even see a USB mic
(`enumerateDevices` collapses inputs to "Default / Speakerphone / Wired headset /
Headset earpiece"). That makes it impossible to keep the beat and the mic on
separate devices from JS.

The `LarzAudio` native plugin sidesteps that:

- `AudioManager.getDevices()` — enumerates **every** input and output interface
  (built-in, wired, Bluetooth, USB audio devices/interfaces) with a real name and
  a stable id.
- `AudioRecord` + `setPreferredDevice(usbMic)` + `AudioSource.UNPROCESSED` —
  captures **only** the chosen device, no AEC / AGC / noise-suppression, straight
  to a 16-bit WAV.
- Fully independent of whatever the WebView plays the beat through, so record and
  monitor are on separate devices by construction.

Beat playback stays in the web layer (Web Audio) for now — route it to Bluetooth
or wired headphones and there is zero bleed into the pinned USB capture.
Native output-device routing (`AudioTrack.setPreferredDevice`) is phase 2.

## JS API (exposed on the live site when running inside the app)

```js
const LarzAudio = window.Capacitor.registerPlugin('LarzAudio');

await LarzAudio.listDevices();
// { inputs:[{id,type,label,isUsb,channelCounts,sampleRates,...}], outputs:[...],
//   unprocessedSupported, sdkInt }

await LarzAudio.requestPermissions();           // { microphone: 'granted' }
await LarzAudio.startCapture({ inputDeviceId, sampleRate:48000, channels:1 });
// { started, sampleRate, channels, source:'unprocessed'|'mic', device:'<label>' }

const take = await LarzAudio.stopCapture();
// { base64, mimeType:'audio/wav', durationMs, sampleRate, channels,
//   firstFrameLatencyMs, truncated, source, device }

LarzAudio.addListener('level', ({ rms }) => { /* input meter, ~22 Hz */ });
LarzAudio.addListener('log',   (e)      => { /* every pipeline step */ });
```

Native single-take cap: **120 s** (`PcmWavRecorder.MAX_SECONDS`) — matches
BeatStudio's "record in bits" workflow and keeps the base64 hand-off small.
`truncated: true` is returned if the cap is hit.

## Logging

Every step logs twice, on purpose (there is no server log on the record path):

- **logcat** — `adb logcat -s BeatStudioAudio`
- **`log` events** — the frontend pipes them into the page's existing
  `[BeatStudio]` console stream (open DevTools via `chrome://inspect`,
  `webContentsDebuggingEnabled` is on).

## Build

Push to `main` or run **Build BeatStudio APK** manually. Artifact `BeatStudio-apk`
contains `BeatStudio.apk`, `permissions.txt`, and (first run) `keystore.b64`.

### Secrets (optional on first build — a throwaway keystore is generated if unset)
| Secret | What |
|---|---|
| `KEYSTORE_PASS` | keystore password |
| `KEY_PASS` | key password |
| `KEY_ALIAS` | key alias |
| `ANDROID_KEYSTORE_B64` | base64 of `release.keystore` — **save `out/keystore.b64` here after the first build** so every rebuild keeps the same signing identity (installs update in place) |

Package id: `com.larzos.beatstudio`. This is a **new** app (not the LarzOS TWA),
so it installs alongside it.

## Phase 2

1. Native beat playback via `AudioTrack.setPreferredDevice()` → full in-app output
   routing (pick the headphone/BT device from the app).
2. Lift the 120 s cap (stream the WAV over a loopback `127.0.0.1` server instead
   of base64).
3. Native NLMS beat-subtraction as a safety net.
