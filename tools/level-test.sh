#!/usr/bin/env bash
# The viewfinder's level (fl_level), checked on the test photos turned by known angles, as the
# camera's 320 px live frames would show them. Each photo's reading at 0 degrees is its own horizon;
# turned by D, the reading must move by D: within 0.3 degrees on the test chart (straight lines,
# square on), within 1.25 on real scenes (whose edges lean with perspective). Readings the level
# isn't sure of (confidence under 30) are hidden on screen, so they can't be wrong there.
set -euo pipefail
cd "$(dirname "$0")/.."
tools/build-host.sh >/dev/null
fail=0; checked=0; worst=0
for img in HOUSE PEOPLE LAKE CHART STREET; do
  read -r base conf _ <<<"$(out/host/fltool level "testdata/$img.JPG" 0)"
  limit=1.25; [ "$img" = CHART ] && limit=0.3
  for d in -8 -5 -3 -2 -1 -0.5 0.5 1 2 3 5 8; do
    read -r a c n <<<"$(out/host/fltool level "testdata/$img.JPG" "$d")"
    if [ "$c" -lt 30 ] || [ "$conf" -lt 30 ]; then continue; fi   # hidden on screen: nothing to get wrong
    err=$(python3 -c "print(round(abs(($a) - ($base) - ($d)), 2))")
    checked=$((checked + 1))
    worst=$(python3 -c "print(max($worst, $err))")
    if python3 -c "import sys; sys.exit(0 if $err <= $limit else 1)"; then :; else
      echo "FAIL $img turned $d: read $a (level at $base), off by $err (limit $limit)"; fail=$((fail + 1)); fi
  done
done
echo "level: $checked readings checked, worst error $worst degrees, $fail over the limit"
[ "$fail" -eq 0 ]
