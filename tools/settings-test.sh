#!/usr/bin/env bash
# Builds the settings bridge for this Mac against a stand-in settings service (tools/fakeosal.c)
# and runs tests/SettingsTest: the write fence, and NeutralSettings' apply / restore.
set -euo pipefail
cd "$(dirname "$0")/.."
JH="${LATENT_JAVA_HOME:-$(/usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home)}"
AJ="${LATENT_TOOLCHAIN:-$HOME/.latent-toolchain}/sdk/platforms/android-28/android.jar"
O=out/host/settings; rm -rf "$O"; mkdir -p "$O/classes" "$O/card"
clang -O1 -g -Wall -dynamiclib -o "$O/libosal_uipc.so" tools/fakeosal.c
clang -O1 -g -Wall -Wno-unused-parameter -dynamiclib -DSETTINGS_HOST_TEST -I"$JH/include" -I"$JH/include/darwin" \
  -o "$O/liblatent.dylib" jni/settings_bridge.c
"$JH/bin/javac" -nowarn -cp "$AJ" -d "$O/classes" src/com/latent/camera/CameraSettings.java src/com/latent/camera/NeutralSettings.java \
  src/com/latent/camera/HardwareDevelop.java src/com/latent/camera/CpuGate.java src/com/latent/camera/Probe.java src/com/latent/camera/Library.java \
  src/com/latent/camera/SonyMemory.java src/com/latent/camera/FilmCore.java src/com/latent/camera/Stock.java \
  tests/com/latent/camera/SettingsTest.java 2>&1 | grep -v "^Note" || true
cd "$O" && DYLD_LIBRARY_PATH="$PWD" "$JH/bin/java" -Djava.library.path="$PWD" -cp "classes:$AJ" com.latent.camera.SettingsTest "$PWD/card"
