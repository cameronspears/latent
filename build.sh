#!/usr/bin/env bash
# Builds FilmLab.apk for Sony PlayMemories cameras (Android 2.3.7, API 10, armeabi).
#
#   FILMLAB_TOOLCHAIN=/path/to/toolchain ./build.sh
#
# The toolchain folder holds android-ndk-r16b/ and sdk/ (build-tools 30.0.3, platforms/android-28);
# tools/setup-toolchain.sh downloads both. Neither ndk-build nor the SDK scripts cope with spaces in
# paths, so the build runs in a space-free copy of the project and the APK is copied back.
set -euo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
TC="${FILMLAB_TOOLCHAIN:-$HOME/.filmlab-toolchain}"
NDK="$TC/android-ndk-r16b"
BT="$TC/sdk/build-tools/30.0.3"
AJ="$TC/sdk/platforms/android-28/android.jar"
# d8 from build-tools 30.0.3 crashes on class files from JDK 21 javac; JDK 11 is known good.
JAVA_HOME="${FILMLAB_JAVA_HOME:-$(/usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home)}"
export PATH="$JAVA_HOME/bin:$PATH"

for f in "$NDK/ndk-build" "$BT/aapt" "$BT/zipalign" "$BT/apksigner" "$BT/lib/d8.jar" "$AJ"; do
  [ -e "$f" ] || { echo "missing: $f (run tools/setup-toolchain.sh)" >&2; exit 1; }
done
case "$TC" in *" "*) echo "toolchain path must not contain spaces: $TC" >&2; exit 1;; esac

W="${TMPDIR:-/tmp}/filmlab-build"
W="${W// /_}"
rm -rf "$W"
mkdir -p "$W"
rsync -a --exclude out --exclude testdata --exclude '*.apk' "$SRC/" "$W/src/"
cd "$W/src"
mkdir -p out/gen out/classes out/dex out/apk/lib/armeabi out/apk/lib/armeabi-v7a

echo "[1/8] native library (ndk-build, armeabi-v7a + armeabi, android-14 headers)"
# NDK r16b only knows Intel Macs; on Apple Silicon it runs under Rosetta.
ROSETTA=""; [ "$(uname -m)" = arm64 ] && ROSETTA="arch -x86_64 /bin/bash"
$ROSETTA "$NDK/ndk-build" -s NDK_PROJECT_PATH="$PWD" APP_BUILD_SCRIPT="$PWD/jni/Android.mk" \
  NDK_APPLICATION_MK="$PWD/jni/Application.mk" NDK_LIBS_OUT="$PWD/out/libs" NDK_OUT="$PWD/out/obj" -j8
cp out/libs/armeabi/libfilmlab.so out/apk/lib/armeabi/
cp out/libs/armeabi-v7a/libfilmlab.so out/apk/lib/armeabi-v7a/

echo "[2/8] check the native libraries load on Android 2.3"
python3 tools/check_so.py out/apk/lib/armeabi/libfilmlab.so
python3 tools/check_so.py out/apk/lib/armeabi-v7a/libfilmlab.so

echo "[3/8] R.java"
"$BT/aapt" package -f -m -J out/gen -M AndroidManifest.xml -S res -I "$AJ"

echo "[4/8] javac"
javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$AJ" -d out/classes \
  $(find out/gen src -name '*.java')

echo "[5/8] check every Android API used exists in API 10"
python3 tools/check_api.py "$TC/sdk/platforms/android-28/data/api-versions.xml" out/classes 10

echo "[6/8] d8 (dex 035 for Dalvik on Android 2.3)"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 10 --lib "$AJ" \
  --output out/dex $(find out/classes -name '*.class')

echo "[7/8] package"
"$BT/aapt" package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" -F out/unaligned.apk
(cd out/dex && "$BT/aapt" add ../unaligned.apk classes.dex)
(cd out/apk && "$BT/aapt" add ../unaligned.apk lib/armeabi-v7a/libfilmlab.so lib/armeabi/libfilmlab.so)
"$BT/zipalign" -f 4 out/unaligned.apk out/aligned.apk

echo "[8/8] sign (v1 only: the camera does not understand v2/v3)"
KS="$SRC/filmlab.keystore"
[ -e "$KS" ] || keytool -genkeypair -keystore "$KS" -alias filmlab -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass filmlab -keypass filmlab -dname "CN=Film Lab" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:filmlab --key-pass pass:filmlab \
  --min-sdk-version 10 --v1-signing-enabled true --v2-signing-enabled false \
  --v3-signing-enabled false --out FilmLab.apk out/aligned.apk
"$BT/apksigner" verify --min-sdk-version 10 FilmLab.apk

cp FilmLab.apk "$SRC/FilmLab.apk"
shasum -a 256 "$SRC/FilmLab.apk" | sed "s|  .*|  FilmLab.apk|" > "$SRC/FilmLab.apk.sha256"
"$BT/aapt" dump badging "$SRC/FilmLab.apk" | grep -E "^package|sdkVersion|native-code"
echo "BUILD OK: FilmLab.apk ($(wc -c < "$SRC/FilmLab.apk" | tr -d ' ') bytes)"
