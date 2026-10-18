#!/usr/bin/env bash
# The viewfinder's frame composer on this Mac, against v0.44.1's (tools/frame_ref.h): every test photo made
# into a frame like the camera's, through every film; the kernels' own checks; then the same under UBSan.
#   tools/frame-test.sh testdata/*.JPG               the checks
#   tools/frame-test.sh --bench testdata/*.JPG       and Mac timings, today against v0.44.1 (ratios only)
#   tools/frame-test.sh --render DIR testdata/*.JPG  and composed frames as PPMs in DIR
set -euo pipefail
cd "$(dirname "$0")/.."
tools/build-host.sh >/dev/null
J=vendor/libjpeg-turbo-2.1.5.1
clang -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Ijni -Ijni/turbo -I$J -o out/host/frametest tools/frametest.c out/host/obj/*.o
clang -g -O1 -fsanitize=undefined -fno-sanitize-recover=undefined -Wall -Wno-unused-function -Ijni -Ijni/turbo -I$J \
  -o out/host/frametest-ubsan tools/frametest.c out/host/obj/*.o
out/host/frametest "$@"
for a in "$@"; do if [ "$a" = --render ]; then   # and the other dither (FL_DITHER_GREEN), drawn beside
  clang -O2 -w -DFL_DITHER_GREEN=1 -Ijni -Ijni/turbo -I$J -o out/host/frametest-green tools/frametest.c out/host/obj/*.o
  out/host/frametest-green --render-only "$@" | grep dither
fi; done
ARGS=(); for a in "$@"; do [ "$a" = --bench ] || ARGS+=("$a"); done
echo "under UBSan:"
out/host/frametest-ubsan "${ARGS[@]}" | tail -1
