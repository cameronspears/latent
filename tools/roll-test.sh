#!/usr/bin/env bash
# The roll model on this Mac against a fake memory card (tests/RollTest): exposures by photo number,
# ROLL.TXT round trips, endless rolls and each frame's film, the card's photo order, every developed frame
# for Photos. Then the app's small rules (tests/LogicTest): the backlight, the save's settle, the viewer's
# slots, the thumbnail cache, Photos' contact sheet and caption.
set -eo pipefail
cd "$(dirname "$0")/.."
JH="${LATENT_JAVA_HOME:-$(/usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home)}"
AJ="${LATENT_TOOLCHAIN:-$HOME/.latent-toolchain}/sdk/platforms/android-28/android.jar"
O=out/rolltest
rm -rf "$O"; mkdir -p "$O/classes" "$O/card"
"$JH/bin/javac" -encoding UTF-8 -nowarn -cp "$AJ" -d "$O/classes" src/com/latent/camera/{Roll,Library,Stock,Probe,FilmCore,Backlight,Slots,HardwareDevelop,SonyMemory,CpuGate,Photos,UiState}.java \
  tests/com/latent/camera/{RollTest,LogicTest}.java 2>&1 | { grep -v "^Note:" || true; }
"$JH/bin/java" -cp "$O/classes:$AJ" com.latent.camera.RollTest "$O/card"
"$JH/bin/java" -cp "$O/classes:$AJ" com.latent.camera.LogicTest
