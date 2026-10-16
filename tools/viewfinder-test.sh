#!/usr/bin/env bash
# The viewfinder's logic on this Mac: the shutter button, the level's steadying, pictures the card's
# listing doesn't show yet, the exposure readout (tests/ViewfinderTest), then the level itself on the
# test photos (tools/level-test.sh).
set -eo pipefail
cd "$(dirname "$0")/.."
JH="${LATENT_JAVA_HOME:-$(/usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home)}"
AJ="${LATENT_TOOLCHAIN:-$HOME/.latent-toolchain}/sdk/platforms/android-28/android.jar"
O=out/vftest
rm -rf "$O"; mkdir -p "$O/classes" "$O/card"
"$JH/bin/javac" -encoding UTF-8 -nowarn -cp "$AJ" -d "$O/classes" src/com/latent/camera/{Release,Level,Library,Roll,Stock,Probe,FilmCore,Viewfinder,HardwareDevelop,SonyMemory,CpuGate,UiState,Ui,Gfx,NeutralSettings,CameraSettings,Display,AndroidGfx}.java \
  tests/com/latent/camera/ViewfinderTest.java 2>&1 | { grep -v "^Note:" || true; }
"$JH/bin/java" -cp "$O/classes:$AJ" com.latent.camera.ViewfinderTest "$O/card"
tools/level-test.sh
