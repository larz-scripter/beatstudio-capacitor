#!/usr/bin/env python3
"""Customise the Capacitor-generated Android project for BeatStudio.

MainActivity.java:
  - register the LarzAudio native plugin (device enumeration + AudioRecord capture)
  - grant the WebView's own getUserMedia mic request (fallback path) without a
    second prompt once RECORD_AUDIO is held

AndroidManifest.xml:
  - RECORD_AUDIO + MODIFY_AUDIO_SETTINGS (a Capacitor app still needs these
    declared for the runtime dialog to appear)

Run after `npx cap add android` and after the plugin .java files are copied in.
"""
import glob
import re
import sys

# ------------------------------------------------------------- MainActivity
matches = glob.glob("android/app/src/main/java/**/MainActivity.java", recursive=True)
if not matches:
    raise SystemExit("MainActivity.java not found")
path = matches[0]
src = open(path).read()
m = re.search(r"package\s+([\w.]+);", src)
if not m:
    raise SystemExit("package line not found in MainActivity.java")
pkg = m.group(1)

new = r"""package __PKG__;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;

public class MainActivity extends BridgeActivity {

    private static final int REQ_MIC = 7731;
    private PermissionRequest pendingMicRequest;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Native audio connector — enumerates every input/output device and
        // captures the chosen one via AudioRecord, independent of the WebView.
        registerPlugin(LarzAudioPlugin.class);
        super.onCreate(savedInstanceState);

        try {
            // If the page ever falls back to getUserMedia, grant the WebView's
            // mic request straight away when we already hold RECORD_AUDIO.
            this.getBridge().getWebView().setWebChromeClient(
                new BridgeWebChromeClient(this.getBridge()) {
                    @Override
                    public void onPermissionRequest(final PermissionRequest request) {
                        boolean wantsMic = false;
                        for (String r : request.getResources()) {
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) { wantsMic = true; break; }
                        }
                        if (!wantsMic) { super.onPermissionRequest(request); return; }
                        runOnUiThread(() -> {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                                    || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                        == PackageManager.PERMISSION_GRANTED) {
                                request.grant(new String[]{ PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                            } else {
                                pendingMicRequest = request;
                                requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_MIC);
                            }
                        });
                    }
                });
        } catch (Throwable t) {
            // WebChromeClient customisation is best-effort.
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && pendingMicRequest != null) {
            final boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            final PermissionRequest pr = pendingMicRequest;
            pendingMicRequest = null;
            runOnUiThread(() -> {
                if (granted) pr.grant(new String[]{ PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                else pr.deny();
            });
        }
    }
}
""".replace("__PKG__", pkg)
open(path, "w").write(new)
print("patched MainActivity (package " + pkg + ", registered LarzAudioPlugin)")

# --------------------------------------------------------------- Manifest
mf = "android/app/src/main/AndroidManifest.xml"
mtxt = open(mf).read()
perms = ""
if "android.permission.RECORD_AUDIO" not in mtxt:
    perms += '    <uses-permission android:name="android.permission.RECORD_AUDIO"/>\n'
if "android.permission.MODIFY_AUDIO_SETTINGS" not in mtxt:
    perms += '    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>\n'
if perms:
    m2 = re.search(r"<manifest\b[^>]*>", mtxt)
    if not m2:
        raise SystemExit("no <manifest> tag in AndroidManifest.xml")
    at = m2.end()
    mtxt = mtxt[:at] + "\n" + perms + mtxt[at:]
    open(mf, "w").write(mtxt)
    print("patched AndroidManifest (RECORD_AUDIO + MODIFY_AUDIO_SETTINGS)")
else:
    print("AndroidManifest already has the audio permissions")
