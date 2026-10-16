#!/usr/bin/env bash
# Builds the JNI bridge for this Mac and runs tests/JniTest against a real camera JPEG.
#   tools/test-jni.sh testdata/STREET.JPG
set -euo pipefail
cd "$(dirname "$0")/.."
tools/build-host.sh >/dev/null
JH="${FILMLAB_JAVA_HOME:-$(/usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home)}"
O=out/host/jni; rm -rf "$O"; mkdir -p "$O/classes" "$O/out"
clang -O2 -Wall -Wextra -Wno-unused-parameter -dynamiclib -Ijni -Ijni/turbo -Ivendor/libjpeg-turbo-2.1.5.1 \
  -I"$JH/include" -I"$JH/include/darwin" -o "$O/libfilmlab.dylib" \
  jni/jni_bridge.c jni/filmcore.c out/host/obj/*.o
"$JH/bin/javac" -d "$O/classes" src/com/filmlab/camera/FilmCore.java tests/com/filmlab/camera/JniTest.java
"$JH/bin/java" -Djava.library.path="$O" -cp "$O/classes" com.filmlab.camera.JniTest \
  "$1" "$PWD/$O/out" "$PWD/assets/luts/CUBANNEG.CUB"
