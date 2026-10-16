#!/usr/bin/env bash
# Builds out/host/fltool: the camera's image core compiled for this Mac, for testing looks.
set -euo pipefail
cd "$(dirname "$0")/.."
. tools/libjpeg-sources.sh
J=$JPEG_DIR
mkdir -p out/host/obj
for s in $LIBJPEG_SOURCES; do
  [ out/host/obj/$s.o -nt $J/$s.c ] || clang -O2 -w -Ijni/turbo -I$J -c $J/$s.c -o out/host/obj/$s.o
done
clang -O2 -Wall -Wextra -Wno-unused-parameter -Ijni -Ijni/turbo -I$J -o out/host/fltool \
  tools/fltool.c jni/filmcore.c out/host/obj/*.o
echo "built out/host/fltool"
