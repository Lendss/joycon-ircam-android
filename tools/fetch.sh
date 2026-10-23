#!/usr/bin/env bash
# Downloads what build.sh needs besides Termux packages: android.jar (API 30) and r8.jar (d8).
set -e
cd "$(dirname "$0")"
if [ ! -f android.jar ]; then
    curl -fL -o platform.zip https://dl.google.com/android/repository/platform-30_r03.zip
    unzip -p platform.zip 'android-11/android.jar' >android.jar
    rm platform.zip
fi
[ -f r8.jar ] || curl -fL -o r8.jar https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar
ls -l android.jar r8.jar
