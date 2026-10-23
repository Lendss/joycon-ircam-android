#!/usr/bin/env bash
# Builds the APK on the device itself (Termux): clang, aapt2, javac, d8 (r8.jar), zipalign, apksigner.
# The root helper ircam.c goes into the APK as lib/arm64-v8a/libircam.so (extractNativeLibs)
# and the app runs it with su from nativeLibraryDir.
#   ANDROID_JAR  android.jar of API 29 or newer (default: tools/android.jar)
#   R8_JAR       r8.jar for d8 (default: tools/r8.jar)
#   KEYSTORE     signing keystore, created on first build if missing (default: ircam.jks)
#   KS_PASS      its password (default: joyconir)
set -e
cd "$(dirname "$0")"
ANDROID_JAR=${ANDROID_JAR:-tools/android.jar}
R8_JAR=${R8_JAR:-tools/r8.jar}
KEYSTORE=${KEYSTORE:-ircam.jks}
KS_PASS=${KS_PASS:-joyconir}
for f in "$ANDROID_JAR" "$R8_JAR"; do
    [ -f "$f" ] || { echo "missing $f (see README: Building)"; exit 1; }
done
rm -rf build && mkdir -p build/classes build/dex build/lib/arm64-v8a
clang -O2 -Wall -Wextra -o ircam ircam.c
cp ircam build/lib/arm64-v8a/libircam.so
aapt2 compile --dir res -o build/res.zip
aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 29 --target-sdk-version 29 -o build/unaligned.apk build/res.zip
javac -nowarn -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" \
    -d build/classes $(command find src -name '*.java') 2>&1 \
    | command grep -v -E "^warning: \[options\]|^[0-9]+ warnings?$" || true
[ -n "$(command find build/classes -name '*.class')" ]
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api 29 --lib "$ANDROID_JAR" \
    --output build/dex $(command find build/classes -name '*.class')
(cd build/dex && zip -q ../unaligned.apk classes.dex)
(cd build && zip -q unaligned.apk lib/arm64-v8a/libircam.so)
zipalign -f -p 4 build/unaligned.apk build/aligned.apk
[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" \
    -keypass "$KS_PASS" -alias ir -keyalg RSA -keysize 2048 -validity 10000 -dname CN=JoyConIR >/dev/null 2>&1
apksigner sign --ks "$KEYSTORE" --ks-pass pass:"$KS_PASS" --out build/IrCamera.apk build/aligned.apk
echo "built: $(pwd)/build/IrCamera.apk"
