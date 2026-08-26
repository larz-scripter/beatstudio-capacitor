#!/usr/bin/env python3
"""Patch the Capacitor-generated android/app/build.gradle:
- bump versionCode / versionName
- add a release signingConfig that reads the keystore + passwords from env
- apply that signingConfig to the release build type
- raise minSdk to 24 (AudioSource.UNPROCESSED; USB audio is reliable well below this)
Run after `npx cap add android`.
"""
import re

P = "android/app/build.gradle"
s = open(P).read()

s = re.sub(r"versionCode\s+\d+", "versionCode 1", s, count=1)
s = re.sub(r'versionName\s+"[^"]*"', 'versionName "1.0"', s, count=1)
s = re.sub(r"minSdkVersion\s+rootProject\.ext\.minSdkVersion", "minSdkVersion 24", s, count=1)
s = re.sub(r"minSdk\s+\d+", "minSdk 24", s, count=1)

SIGN = """
    signingConfigs {
        release {
            storeFile file("release.keystore")
            storePassword System.getenv("KEYSTORE_PASS")
            keyAlias System.getenv("KEY_ALIAS")
            keyPassword System.getenv("KEY_PASS")
        }
    }"""

if "signingConfigs" not in s:
    s = s.replace("android {", "android {" + SIGN, 1)

s = re.sub(r"(buildTypes\s*\{\s*release\s*\{)",
           r"\1\n            signingConfig signingConfigs.release",
           s, count=1)

open(P, "w").write(s)
print("patched android/app/build.gradle (version + signing + minSdk 24)")
