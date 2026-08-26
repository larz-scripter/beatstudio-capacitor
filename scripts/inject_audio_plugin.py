#!/usr/bin/env python3
"""Copy the LarzAudio native plugin sources into the Capacitor-generated Android
project. Run after `npx cap add android` and before patch_mainactivity.py."""
import glob
import os
import re
import shutil

matches = glob.glob("android/app/src/main/java/**/MainActivity.java", recursive=True)
if not matches:
    raise SystemExit("MainActivity.java not found — run `npx cap add android` first")
pkg_dir = os.path.dirname(matches[0])
src = open(matches[0]).read()
pkg = re.search(r"package\s+([\w.]+);", src).group(1)

SOURCES = ["LarzAudioPlugin.java", "AudioDeviceCatalog.java", "PcmWavRecorder.java",
           "BeatMonitor.java", "LocalFileServer.java"]
for name in SOURCES:
    srcpath = os.path.join("android-src", name)
    if not os.path.isfile(srcpath):
        raise SystemExit("missing " + srcpath)
    body = open(srcpath).read()
    # keep the package line in sync with whatever Capacitor generated
    body = re.sub(r"^package\s+[\w.]+;", "package " + pkg + ";", body, count=1, flags=re.M)
    dst = os.path.join(pkg_dir, name)
    open(dst, "w").write(body)
    print("wrote " + dst)

print("injected " + str(len(SOURCES)) + " plugin sources into package " + pkg)
