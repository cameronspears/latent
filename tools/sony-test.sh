#!/usr/bin/env bash
# The Sony memory bridge (jni/sony_bridge.c + the block develop) on fake image memory: tools/sonytest.c,
# plain, under UBSan and under Guard Malloc, at 1 and 3 threads (each thread with its 1K lookup memory,
# which the row-by-row reference never has), and with the next film made ready first (fl_prewarm, also
# the wrong film). Every develop must match the reference bit for bit. (ThreadSanitizer's runtime crashes
# at start on this Mac, even on old code.)
#   tools/sony-test.sh [PHOTO.JPG...]   (default: the test photos that are baseline JPEGs)
set -uo pipefail
cd "$(dirname "$0")/.."
tools/build-host.sh >/dev/null
JH="${LATENT_JAVA_HOME:-$(/usr/libexec/java_home -v 11 2>/dev/null || /usr/libexec/java_home)}"
F="-Wall -Wextra -Wno-unused-parameter -DSB_HOST_TEST -Ijni -Ijni/turbo -Ivendor/libjpeg-turbo-2.1.5.1 -I$JH/include -I$JH/include/darwin"
clang -O2 $F -o out/host/sonytest tools/sonytest.c jni/sony_bridge.c jni/filmcore.c out/host/obj/*.o || exit 1
clang -g -O1 -fsanitize=undefined -fno-sanitize-recover=undefined $F -o out/host/sonytest-ubsan tools/sonytest.c jni/sony_bridge.c jni/filmcore.c out/host/obj/*.o || exit 1
[ $# -gt 0 ] || set -- testdata/STREET.JPG testdata/HOUSE.JPG testdata/PEOPLE.JPG testdata/CHART.JPG
fail=0
check() {  # label, command...
  local label="$1"; shift
  local out
  if ! out=$("$@" 2>&1) || ! grep -q "SONY BRIDGE OK" <<<"$out" || grep -q "runtime error" <<<"$out"; then
    echo "FAIL $label"; grep -i "FAIL\|runtime error" <<<"$out" | head -3; fail=1
  fi
}
for img in "$@"; do
  for th in 1 3; do
    check "$img threads $th" out/host/sonytest "$img" assets/luts/PORTRA4W.CUB $th
    check "$img threads $th prewarm" env SB_WARM=assets/luts/TRIX400.CUB out/host/sonytest "$img" assets/luts/PORTRA4W.CUB $th
  done
  check "ubsan $img prewarm" env SB_WARM=assets/luts/NATURAL.CUB out/host/sonytest-ubsan "$img" assets/luts/CUBANNEG.CUB 3
done
check "gmalloc prewarm" env SB_WARM=assets/luts/NATURAL.CUB DYLD_INSERT_LIBRARIES=/usr/lib/libgmalloc.dylib MALLOC_PROTECT_BEFORE=1 \
  out/host/sonytest "$1" assets/luts/TRIX400.CUB 3
[ $fail = 0 ] && echo "SONY TESTS OK: $# photos x 1/3 threads x prewarm (right and wrong film), UBSan, Guard Malloc"
exit $fail
