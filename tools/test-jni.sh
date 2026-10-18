#!/usr/bin/env bash
# Builds the JNI bridge for this Mac and runs tests/JniTest against a real camera JPEG: the camera's build
# (green's opposite dither threshold, FL_DITHER_GREEN, as filmcore.c defines it), then one built with a single
# threshold, as v0.44.1's, for the composer against v0.44.1's pixel for pixel.
#   tools/test-jni.sh testdata/STREET.JPG
set -euo pipefail
cd "$(dirname "$0")/.."
tools/build-host.sh >/dev/null
JH="${LATENT_JAVA_HOME:-$(/usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home)}"
O=out/host/jni; rm -rf "$O"; mkdir -p "$O/classes" "$O/out" "$O/one"
# FL_FRAME_REFERENCE: v0.44.1's composer built in too (tools/frame_ref.h), for JniTest to compare the composer with
lib() {
  clang -O2 -Wall -Wextra -Wno-unused-parameter -DFL_FRAME_REFERENCE "$@" -dynamiclib -Ijni -Ijni/turbo -Ivendor/libjpeg-turbo-2.1.5.1 \
    -I"$JH/include" -I"$JH/include/darwin" jni/jni_bridge.c jni/filmcore.c out/host/obj/*.o
}
lib -o "$O/liblatent.dylib"
lib -DFL_DITHER_GREEN=0 -o "$O/one/liblatent.dylib"
"$JH/bin/javac" -d "$O/classes" src/com/latent/camera/FilmCore.java tests/com/latent/camera/JniTest.java
"$JH/bin/java" -Djava.library.path="$O" -cp "$O/classes" com.latent.camera.JniTest \
  "$1" "$PWD/$O/out" "$PWD/assets/luts/CUBANNEG.CUB"
"$JH/bin/java" -Djava.library.path="$O/one" -cp "$O/classes" com.latent.camera.JniTest \
  "$1" "$PWD/$O/out" "$PWD/assets/luts/CUBANNEG.CUB" one-threshold
