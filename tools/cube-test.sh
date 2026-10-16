#!/usr/bin/env bash
# The fast .cube reader (filmcore.c fast_triple) against sscanf: every shipped look, and a stress file of
# awkward rows (many digit counts, signs, out of range, exponents, odd separators, and numbers whose
# correctly rounded double lies exactly halfway between two floats, which must go to sscanf).
#   tools/cube-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
tools/build-host.sh >/dev/null
mkdir -p out/test
python3 - out/test/STRESS.CUB <<'PY'
import random, struct, sys
random.seed(38)
def f32(x): return struct.unpack('f', struct.pack('f', x))[0]
rows = []
# halfway cases: integers 2^24 + odd are exactly between two floats; so are some short decimals' doubles
ties = [str(2**24 + 2 * k + 1) for k in range(40)] + [str(2**25 + 4 * k + 2) for k in range(40)]
def num():
    r = random.random()
    if r < 0.05: return random.choice(ties)
    v = random.uniform(-0.2, 1.3) * 10 ** random.choice([0, 0, 0, -3, 2])
    s = '%.*f' % (random.randint(0, 12), v)
    if r < 0.10: s = s.lstrip('0') or '0'          # ".5"
    if r < 0.12: s = '+' + s
    if r < 0.13: s = '%.6e' % v                    # exponent: sscanf's
    return s
n = 33
rows = []
for i in range(n ** 3):
    sep = random.choice([' ', ' ', '\t', '  '])
    end = random.choice(['\n', '\n', '\r\n', ' \n', 'x\n'])   # 'x': trailing text, sscanf ignores it
    rows.append(sep.join(num() for _ in range(3)) + end)
open(sys.argv[1], 'w').write('TITLE "stress"\nLUT_3D_SIZE 33\n' + ''.join(rows))
PY
out/host/fltool cubecheck assets/luts/*.CUB out/test/STRESS.CUB
