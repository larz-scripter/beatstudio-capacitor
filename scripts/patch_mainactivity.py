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

# ------------------------------------------------- network security config
# capacitor.config sets cleartext:false, which blocks ALL http:// including
# 127.0.0.1 - the loopback socket that serves a full-length take. Allow
# cleartext only for localhost.
import glob as _glob
import os
LOCAL_CFG = (
    '    <domain-config cleartextTrafficPermitted="true">\n'
    '        <domain includeSubdomains="false">127.0.0.1</domain>\n'
    '        <domain includeSubdomains="false">localhost</domain>\n'
    '    </domain-config>\n'
)
nsc_dir = "android/app/src/main/res/xml"
os.makedirs(nsc_dir, exist_ok=True)
existing = _glob.glob("android/app/src/main/res/xml/*network*security*config*.xml") \
    + _glob.glob("android/app/src/main/res/xml/network_security_config.xml")
existing = sorted(set(existing))
if existing:
    for p in existing:
        t = open(p).read()
        if "127.0.0.1" not in t:
            t = t.replace("</network-security-config>", LOCAL_CFG + "</network-security-config>")
            open(p, "w").write(t)
            print("merged localhost cleartext into " + os.path.basename(p))
    nsc_name = os.path.splitext(os.path.basename(existing[0]))[0]
else:
    open(os.path.join(nsc_dir, "network_security_config.xml"), "w").write(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<network-security-config>\n' + LOCAL_CFG + '</network-security-config>\n'
    )
    nsc_name = "network_security_config"
    print("wrote res/xml/network_security_config.xml")

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
    print("patched AndroidManifest (RECORD_AUDIO + MODIFY_AUDIO_SETTINGS)")

# point <application> at the network security config
if "networkSecurityConfig" not in mtxt:
    mtxt = re.sub(r"(<application\b)",
                  r'\1 android:networkSecurityConfig="@xml/' + nsc_name + '"',
                  mtxt, count=1)
    print("patched AndroidManifest (networkSecurityConfig -> @xml/" + nsc_name + ")")

open(mf, "w").write(mtxt)
