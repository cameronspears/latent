#!/usr/bin/env bash
# Renders every screen of the app to out/preview/*.png on this Mac, through the same Ui.java the
# camera runs (Gfx on Java2D instead of Android's Canvas, same fonts, same 640x480 layout).
set -euo pipefail
cd "$(dirname "$0")/.."
JH="${FILMLAB_JAVA_HOME:-$(/usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home)}"
O=out/preview
mkdir -p "$O/classes" "$O/samples"
tools/build-host.sh >/dev/null
# sample photos in each look, as the camera would show them
for img in STREET HOUSE PEOPLE; do
  for lut in CUBANNEG PORTRA4W; do
    s="$O/samples/$img-$lut"
    [ -e "$s.png" ] || { out/host/fltool preview "testdata/$img.JPG" "$s.ppm" --lut "assets/luts/$lut.CUB" 2>/dev/null \
      && sips -s format png "$s.ppm" --out "$s.png" >/dev/null; }
  done
done
# the viewer's screen preview, as developing makes it
[ -e "$O/samples/HOUSE-PRV.png" ] || { out/host/fltool process testdata/HOUSE.JPG "$O/samples/HOUSE-DEV.JPG" \
  --lut assets/luts/CUBANNEG.CUB --grain 5 --preview "$O/samples/HOUSE-PRV.JPG" 2>/dev/null \
  && sips -s format png "$O/samples/HOUSE-PRV.JPG" --out "$O/samples/HOUSE-PRV.png" >/dev/null; }
rm -f "$O"/*.png
"$JH/bin/javac" -d "$O/classes" src/com/filmlab/camera/Gfx.java src/com/filmlab/camera/UiState.java \
  src/com/filmlab/camera/Ui.java src/com/filmlab/camera/Probe.java src/com/filmlab/camera/HardwareTest.java src/com/filmlab/camera/HardwareDevelop.java src/com/filmlab/camera/CpuGate.java src/com/filmlab/camera/SettingsCheck.java src/com/filmlab/camera/SpeedTest.java src/com/filmlab/camera/CameraSettings.java src/com/filmlab/camera/Library.java src/com/filmlab/camera/Stock.java src/com/filmlab/camera/FilmCore.java src/com/filmlab/camera/SonyMemory.java \
  -cp "${FILMLAB_TOOLCHAIN:-$HOME/.filmlab-toolchain}/sdk/platforms/android-28/android.jar" \
  tools/preview/HostGfx.java tools/preview/RenderScreens.java
"$JH/bin/java" -Djava.awt.headless=true -cp "$O/classes:${FILMLAB_TOOLCHAIN:-$HOME/.filmlab-toolchain}/sdk/platforms/android-28/android.jar" com.filmlab.camera.RenderScreens assets/fonts "$O/samples" "$O"
ls "$O"/*.png
