#!/usr/bin/env bash
# The README's pictures of the screens (docs/images/*.jpg), from the Mac previews of the same Ui.java the
# camera runs (tools/preview.sh): rendered at the panel's 853x480 design size, saved as JPEG so the
# repository stays small. Run it again after changing a screen.
#   tools/readme-images.sh
set -euo pipefail
cd "$(dirname "$0")/.."
tools/preview.sh >/dev/null
mkdir -p docs/images
# preview scene -> README name
while read -r scene name; do
  sips -s format jpeg -s formatOptions 92 "out/preview/$scene.png" --out "docs/images/$name.jpg" >/dev/null
  echo "docs/images/$name.jpg ($(wc -c < "docs/images/$name.jpg" | tr -d ' ') bytes)"
done <<'EOF'
1-home-choose-classic choose-film
3-home-roll camera-back
8c-vf-locked viewfinder
8e-vf-look-trix viewfinder-film
8s-vf-exposure-plus-third viewfinder-exposure
8-darkroom darkroom
9-photos photos
10-view-fit photos-viewer
6-menu menu
EOF
