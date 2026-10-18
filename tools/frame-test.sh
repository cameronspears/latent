#!/usr/bin/env bash
# The viewfinder's frame composer on this Mac, against v0.44.1's (tools/frame_ref.h): every test photo made
# into a frame like the camera's, through every film; the kernels' own checks; then the same under UBSan.
# The camera's build first (green's opposite dither threshold, FL_DITHER_GREEN), then one with a single
# threshold, as v0.44.1's, which compares the composer with v0.44.1's pixel for pixel.
#   tools/frame-test.sh testdata/*.JPG               the checks
#   tools/frame-test.sh --bench testdata/*.JPG       and Mac timings, today against v0.44.1 (ratios only)
#   tools/frame-test.sh --render DIR testdata/*.JPG  and composed frames as PPMs in DIR (one threshold's beside)
set -euo pipefail
cd "$(dirname "$0")/.."
tools/build-host.sh >/dev/null
J=vendor/libjpeg-turbo-2.1.5.1
clang -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Ijni -Ijni/turbo -I$J -o out/host/frametest tools/frametest.c out/host/obj/*.o
clang -O2 -w -DFL_DITHER_GREEN=0 -Ijni -Ijni/turbo -I$J -o out/host/frametest-one tools/frametest.c out/host/obj/*.o
clang -g -O1 -fsanitize=undefined -fno-sanitize-recover=undefined -Wall -Wno-unused-function -Ijni -Ijni/turbo -I$J \
  -o out/host/frametest-ubsan tools/frametest.c out/host/obj/*.o
out/host/frametest "$@"
CHECKS=(); skip=0
for a in "$@"; do
  if [ $skip = 1 ]; then skip=0; continue; fi
  case "$a" in --bench) ;; --render) skip=1 ;; *) CHECKS+=("$a") ;; esac
done
echo "one threshold (FL_DITHER_GREEN 0), as v0.44.1's:"
out/host/frametest-one "${CHECKS[@]}" | grep -E "FAIL|v0.44.1's \(one|FRAME"
for a in "$@"; do if [ "$a" = --render ]; then   # and one threshold's frames, drawn beside
  out/host/frametest-one --render-only "$@" | grep dither
fi; done
ARGS=(); for a in "$@"; do [ "$a" = --bench ] || ARGS+=("$a"); done
echo "under UBSan:"
out/host/frametest-ubsan "${ARGS[@]}" | tail -1
