#!/usr/bin/env bash
# Runs the image core under UBSan and Apple's Guard Malloc over every test photo and the extremes of
# every setting. libjpeg itself is built without sanitizers (its bit reader does a signed shift UBSan
# reports by design). Usage: tools/sanitize-test.sh testdata/*.JPG
set -uo pipefail
cd "$(dirname "$0")/.."
tools/build-host.sh >/dev/null
clang -g -O1 -fsanitize=undefined -fno-sanitize-recover=undefined -Wall -Ijni -Ijni/turbo -Ivendor/libjpeg-turbo-2.1.5.1 \
  -o out/host/fltool-ubsan tools/fltool.c jni/filmcore.c out/host/obj/*.o || exit 1
mkdir -p out/test
SETTINGS=(
  "--lut assets/luts/CUBANNEG.CUB --grain 10 --size 3 --hl 4 --sh -4"
  "--lut assets/luts/PORTRA4W.CUB --grain 1 --size 1 --hl -4 --sh 4"
  "--grain 5 --size 2"
)
fail=0
run() {  # label, command...
  local label="$1"; shift
  local out
  if ! out=$("$@" 2>&1) || grep -qi "runtime error\|GuardMalloc.*error" <<<"$out"; then
    echo "FAIL $label"; grep -i "error" <<<"$out" | head -3; fail=1
  fi
}
for img in "$@"; do
  for s in "${SETTINGS[@]}"; do
    run "ubsan $img $s" out/host/fltool-ubsan process "$img" out/test/SANITIZE.JPG $s
    run "gmalloc $img $s" env DYLD_INSERT_LIBRARIES=/usr/lib/libgmalloc.dylib MALLOC_PROTECT_BEFORE=1 \
      out/host/fltool process "$img" out/test/SANITIZE.JPG $s
  done
  run "preview crop $img" out/host/fltool-ubsan preview "$img" out/test/sanitize.ppm --crop --grain 7 --size 2
  run "preview fit $img" out/host/fltool-ubsan preview "$img" out/test/sanitize.ppm --lut assets/luts/PORTRA4W.CUB
  # the viewfinder: a live frame from memory, and the level at a few sizes (down to too small to read)
  run "live $img" out/host/fltool-ubsan live "$img" out/test/sanitize.ppm --lut assets/luts/CLASSNEG.CUB
  run "live gmalloc $img" env DYLD_INSERT_LIBRARIES=/usr/lib/libgmalloc.dylib MALLOC_PROTECT_BEFORE=1 \
    out/host/fltool live "$img" out/test/sanitize.ppm --lut assets/luts/TRIX400.CUB
  for w in 320 97 30 12; do run "level $img at $w px" out/host/fltool-ubsan level "$img" 3 "$w"; done
  run "level gmalloc $img" env DYLD_INSERT_LIBRARIES=/usr/lib/libgmalloc.dylib MALLOC_PROTECT_BEFORE=1 out/host/fltool level "$img" -2 160
done
# the fast .cube reader against sscanf, every shipped film, under both
run "cubecheck ubsan" out/host/fltool-ubsan cubecheck assets/luts/*.CUB
run "cubecheck gmalloc" env DYLD_INSERT_LIBRARIES=/usr/lib/libgmalloc.dylib MALLOC_PROTECT_BEFORE=1 out/host/fltool cubecheck assets/luts/*.CUB
[ $fail = 0 ] && echo "clean: $# photos x ${#SETTINGS[@]} settings under UBSan and Guard Malloc, plus previews, live frames and the level"
exit $fail
