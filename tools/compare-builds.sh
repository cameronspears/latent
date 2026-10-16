#!/usr/bin/env bash
# Checks two builds of fltool produce byte-identical output over photos x settings.
#   tools/compare-builds.sh OLD_FLTOOL NEW_FLTOOL testdata/*.JPG
set -uo pipefail
cd "$(dirname "$0")/.."
old="$1" new="$2"; shift 2
SETTINGS=(
  "--lut assets/luts/CUBANNEG.CUB --grain 6 --size 2 --hl -2 --sh 1"
  "--lut assets/luts/PORTRA4W.CUB --grain 0"
  "--grain 4 --hl 3 --size 3"
)
same=0 diff=0
for img in "$@"; do
  for s in "${SETTINGS[@]}"; do
    for th in 1 3; do
      "$old" process "$img" out/test/CMP_A.JPG $s --threads $th 2>/dev/null || { echo "old failed: $img $s"; diff=$((diff+1)); continue; }
      "$new" process "$img" out/test/CMP_B.JPG $s --threads $th 2>/dev/null || { echo "new failed: $img $s"; diff=$((diff+1)); continue; }
      if cmp -s out/test/CMP_A.JPG out/test/CMP_B.JPG; then same=$((same+1)); else diff=$((diff+1)); echo "DIFFERENT: $img $s threads=$th"; fi
    done
  done
done
echo "identical: $same  different: $diff"
[ $diff = 0 ]
