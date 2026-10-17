#!/usr/bin/env bash
# The hot loops' instruction budget, read from the camera's own compiler: NDK r16b gcc 4.9 with exactly the
# flags ndk-build gives jni/filmcore.c for armeabi-v7a (and LOCAL_CFLAGS from jni/Android.mk), -S. Mac timings
# run ~70x faster than the camera and don't rank ARM code; instruction counts do (~250 M simple instructions
# a second on the camera, plus ~430 ns for each memory line it misses).
#
# A loop is marked in jni/filmcore.c by a comment on a line of its body (-g's line notes find it, even inlined):
#     /* hot: dither per=2px max=60 sp=0 */
# name, what one trip of the loop does, and its budget: instructions on the longest path through one trip,
# and stack references on that path. tools/asm_budget.py prints each marked loop's shortest and longest trip
# and its stack references, and fails when one is over budget.
#
#   tools/asm-budget.sh                         every marked loop
#   tools/asm-budget.sh -mtune=cortex-a5        the same with extra compiler flags (to compare)
#   ASM_FUNCS="ycc_pairs" tools/asm-budget.sh   also every innermost loop of these functions (unmarked)
#   ASM_MARKS="pairs=d = y0 - y1" tools/asm-budget.sh
#                                               also the loop around the first line holding that text (';' between)
#   ASM_SHOW="look dither" tools/asm-budget.sh   and prints those loops' instructions (each with its source line)
#   ASM_KEEP=dir tools/asm-budget.sh            keeps filmcore.s in dir
set -euo pipefail
cd "$(dirname "$0")/.."
TC="${LATENT_TOOLCHAIN:-$HOME/.latent-toolchain}"
NDK="$TC/android-ndk-r16b"
GCC="$NDK/toolchains/arm-linux-androideabi-4.9/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-gcc"
[ -x "$GCC" ] || { echo "missing: $GCC (run tools/setup-toolchain.sh)" >&2; exit 1; }
# NDK r16b only knows Intel Macs; on Apple Silicon it runs under Rosetta.
ROSETTA=""; [ "$(uname -m)" = arm64 ] && ROSETTA="arch -x86_64"
LOCAL_CFLAGS=$(sed -n 's/^LOCAL_CFLAGS := //p' jni/Android.mk)
O="${ASM_KEEP:-${TMPDIR:-/tmp}/latent-asm}"
mkdir -p "$O"
# ndk-build r16b's own line for filmcore.c (armeabi-v7a, LOCAL_ARM_MODE arm), as `ndk-build -n V=1` prints it
# shellcheck disable=SC2086
$ROSETTA "$GCC" -fpic -ffunction-sections -funwind-tables -fstack-protector-strong -no-canonical-prefixes -g \
  -march=armv7-a -mfpu=vfpv3-d16 -mfloat-abi=softfp -marm -O2 -DNDEBUG -Ijni -Ijni/turbo -Ivendor/libjpeg-turbo-2.1.5.1 \
  -DANDROID $LOCAL_CFLAGS -D__ANDROID_API__=14 -Wformat -Werror=format-security \
  --sysroot "$NDK/sysroot" -isystem "$NDK/sysroot/usr/include/arm-linux-androideabi" "$@" \
  -S jni/filmcore.c -o "$O/filmcore.s"
MARKS=()
if [ -n "${ASM_MARKS:-}" ]; then IFS=';' read -ra M <<<"$ASM_MARKS"; for m in "${M[@]}"; do MARKS+=(--mark "$m"); done; fi
for m in ${ASM_SHOW:-}; do MARKS+=(--show "$m"); done
# shellcheck disable=SC2086
python3 tools/asm_budget.py jni/filmcore.c "$O/filmcore.s" ${MARKS[@]+"${MARKS[@]}"} ${ASM_FUNCS:+--funcs $ASM_FUNCS}
