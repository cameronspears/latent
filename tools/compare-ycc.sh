#!/usr/bin/env bash
# Builds the camera-format develop three ways (through RGB, per pixel, per pair) and compares each
# against the RGB reference on every test photo, both looks, with and without grain.
# Usage: tools/compare-ycc.sh
set -euo pipefail
cd "$(dirname "$0")/.."
tools/build-host.sh >/dev/null
J=vendor/libjpeg-turbo-2.1.5.1
for v in "0 1" "25 0" "25 1"; do
  set -- $v
  clang -O2 -Wall -DFL_YCC_GRID=$1 -DFL_YCC_PAIRS=$2 -Ijni -Ijni/turbo -I$J -o out/host/uyvytest-g$1-p$2 \
    tools/uyvytest.c jni/filmcore.c out/host/obj/*.o
done
mkdir -p out/test
for gr in 0 6; do for lut in CUBANNEG PORTRA4W; do for img in HOUSE STREET PEOPLE; do
  for b in g0-p1 g25-p0 g25-p1; do out/host/uyvytest-$b testdata/$img.JPG out/test/U_$b.JPG assets/luts/$lut.CUB $gr >/dev/null 2>&1; done
  python3 -c "
import numpy as np
from PIL import Image
d='out/test/'
a=np.asarray(Image.open(d+'U_g0-p1.JPG'),dtype=float)
r=[]
for b in ('g25-p0','g25-p1'):
    x=abs(a-np.asarray(Image.open(d+'U_%s.JPG'%b),dtype=float))
    r.append('%s mean %.2f >4 %.2f%% max %d'%(b,x.mean(),100*(x.max(2)>4).mean(),x.max()))
print('grain $gr $lut $img |',' | '.join(r))
"
done; done; done
for b in g25-p0 g25-p1; do
  t=""; for i in 1 2 3; do t="$t $(out/host/uyvytest-$b testdata/HOUSE.JPG out/test/U_t.JPG assets/luts/CUBANNEG.CUB 6 2>&1 | grep -o 'look [0-9]*' | cut -d' ' -f2)"; done
  echo "$b look ms:$t"
done
