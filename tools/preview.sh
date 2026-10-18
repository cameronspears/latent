#!/usr/bin/env bash
# Renders every screen of the app to out/preview/*.png on this Mac, through the same Ui.java the
# camera runs (Gfx on Java2D instead of Android's Canvas, same fonts, same 640x480 layout), and as the
# camera really shows them in out/preview/camera/ (640 dots across, 4 bits a channel, the viewfinder
# dithered as the composer does). Then checks them (layers, overlay bands, window key, text sizes,
# contrast, overlaps); any failure fails the run.
#
#   tools/preview.sh [-sun]
#
# -sun also writes out/preview/camera-sun/ (a daylight veil over the camera renders). With an earlier
# version's renders in out/preview-base/ (its camera/ inside), out/preview/compare/ pairs them with these.
set -euo pipefail
cd "$(dirname "$0")/.."
JH="${LATENT_JAVA_HOME:-$(/usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home)}"
SUN=""; [ "${1:-}" = "-sun" ] && SUN="-Dlatent.sun=1"
O=out/preview
mkdir -p "$O/classes" "$O/samples"
tools/build-host.sh >/dev/null
# sample photos in each look, as the camera would show them
for img in STREET HOUSE PEOPLE; do
  for lut in CUBANNEG PORTRA4W CLASSNEG TRIX400 PARTY800 NATURAL; do
    s="$O/samples/$img-$lut"
    [ -e "$s.png" ] || { out/host/fltool preview "testdata/$img.JPG" "$s.ppm" --lut "assets/luts/$lut.CUB" 2>/dev/null \
      && sips -s format png "$s.ppm" --out "$s.png" >/dev/null; }
  done
done
# the viewer's screen preview, as developing makes it, and the middle of the developed photo at 100% (854 x 480)
[ -e "$O/samples/HOUSE-PRV.png" ] && [ -e "$O/samples/HOUSE-DEV.JPG" ] || { out/host/fltool process testdata/HOUSE.JPG "$O/samples/HOUSE-DEV.JPG" \
  --lut assets/luts/CUBANNEG.CUB --grain 5 --preview "$O/samples/HOUSE-PRV.JPG" 2>/dev/null \
  && sips -s format png "$O/samples/HOUSE-PRV.JPG" --out "$O/samples/HOUSE-PRV.png" >/dev/null; }
[ -e "$O/samples/HOUSE-100.png" ] || sips -s format png -c 480 854 "$O/samples/HOUSE-DEV.JPG" --out "$O/samples/HOUSE-100.png" >/dev/null
rm -f "$O"/*.png "$O"/camera/*.png "$O"/camera-sun/*.png "$O"/compare/*.png
"$JH/bin/javac" -encoding UTF-8 -d "$O/classes" src/com/latent/camera/Gfx.java src/com/latent/camera/UiState.java \
  src/com/latent/camera/Ui.java src/com/latent/camera/Photos.java src/com/latent/camera/Stock.java src/com/latent/camera/Library.java src/com/latent/camera/Roll.java \
  -cp "${LATENT_TOOLCHAIN:-$HOME/.latent-toolchain}/sdk/platforms/android-28/android.jar" \
  tools/preview/HostGfx.java tools/preview/CameraTrue.java tools/preview/RenderScreens.java
BASE=""; [ -d out/preview-base/camera ] && BASE=out/preview-base
"$JH/bin/java" -Djava.awt.headless=true $SUN -cp "$O/classes:${LATENT_TOOLCHAIN:-$HOME/.latent-toolchain}/sdk/platforms/android-28/android.jar" \
  com.latent.camera.RenderScreens assets/fonts "$O/samples" "$O" $BASE
