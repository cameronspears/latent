#!/usr/bin/env bash
# The image core's shared caches under contention (tools/cachetest.c): three frame composers changing films
# while a fourth thread previews, reads films ahead, prewarms and forgets, with more film files than the
# cache holds. Plain, under UBSan and under Guard Malloc (a freed film still in use faults at once); every
# picture must be exactly what one thread alone made. (ThreadSanitizer's and AddressSanitizer's runtimes
# hang at start on this Mac, even for an empty program, so they aren't run.)
#   tools/cache-test.sh [PHOTO.JPG]   (default testdata/STREET.JPG)
set -uo pipefail
cd "$(dirname "$0")/.."
tools/build-host.sh >/dev/null
F="-Wall -Wextra -Wno-unused-parameter -Ijni -Ijni/turbo -Ivendor/libjpeg-turbo-2.1.5.1"
SRC="tools/cachetest.c jni/filmcore.c out/host/obj/*.o"
clang -O2 $F -o out/host/cachetest $SRC || exit 1
clang -g -O1 -fsanitize=undefined -fno-sanitize-recover=undefined $F -o out/host/cachetest-ubsan $SRC || exit 1
PHOTO="${1:-testdata/STREET.JPG}"
T=out/host/cachetest-tmp; rm -rf "$T"; mkdir -p "$T"
fail=0
check() {  # label, command...
  local label="$1"; shift
  local out
  if ! out=$("$@" 2>&1) || ! grep -q "CACHE STRESS OK" <<<"$out" || grep -q "runtime error\|GuardMalloc.*error" <<<"$out"; then
    echo "FAIL $label"; grep -i "FAIL\|runtime error\|error" <<<"$out" | head -5; fail=1
  else
    echo "  pass  $label: $(grep "CACHE STRESS OK" <<<"$out" | sed 's/CACHE STRESS OK: //')"
  fi
}
check "plain" out/host/cachetest "$PHOTO" assets/luts "$T" 60
check "ubsan" out/host/cachetest-ubsan "$PHOTO" assets/luts "$T" 30
check "gmalloc" env DYLD_INSERT_LIBRARIES=/usr/lib/libgmalloc.dylib MALLOC_PROTECT_BEFORE=1 out/host/cachetest "$PHOTO" assets/luts "$T" 20
check "gmalloc, strict sizes" env DYLD_INSERT_LIBRARIES=/usr/lib/libgmalloc.dylib MALLOC_STRICT_SIZE=1 out/host/cachetest "$PHOTO" assets/luts "$T" 20
rm -rf "$T"
[ $fail = 0 ] && echo "CACHE TESTS OK: plain, UBSan, Guard Malloc"
exit $fail
