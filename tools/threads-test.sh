#!/usr/bin/env bash
# The software develop's threads (fl_process_file): the writer on core 3, a look worker on each of cores 1
# and 2, every pin checked. On the Mac FL_REFUSE_CORES (a mask) makes those cores refuse, as the camera's
# kernel might: a worker that can't have its core doesn't join, and with no writer on core 3 or no worker
# at all the develop runs on its calling thread alone. Each develop must be byte-identical to one thread's,
# and report the threads expected (fltool's "threads N"). Plain, and under UBSan for the photo first named.
#   tools/threads-test.sh [PHOTO.JPG...]   (default: STREET and PEOPLE)
set -uo pipefail
cd "$(dirname "$0")/.."
tools/build-host.sh >/dev/null
clang -g -O1 -fsanitize=undefined -fno-sanitize-recover=undefined -Wall -Ijni -Ijni/turbo -Ivendor/libjpeg-turbo-2.1.5.1 \
  -o out/host/fltool-ubsan tools/fltool.c jni/filmcore.c out/host/obj/*.o || exit 1
[ $# -gt 0 ] || set -- testdata/STREET.JPG testdata/PEOPLE.JPG
mkdir -p out/test
S="--lut assets/luts/CUBANNEG.CUB --grain 6 --size 2 --hl -2 --sh 1"
# threads asked, cores refused, threads that should run: this one, the writer, the workers that joined
CASES=(
  "4 0 4"       # cores 3, 1 and 2 (the camera's default: availableProcessors)
  "4 0x1 4"     # core 0 is never asked for
  "4 0x2 3"     # core 1 refused: its worker stays out, core 2's looks every strip
  "4 0x4 3"     # core 2 refused
  "4 0x6 1"     # no worker: no pipeline, this thread does it all
  "4 0x8 1"     # no writer on core 3: the same
  "4 0xe 1"
  "3 0 3"       # one worker, on core 1
  "3 0x2 1"
  "3 0x4 3"     # core 2 isn't asked for at 3
  "2 0 3"       # 2 is the pipeline with one worker, as before
  "9 0 4"       # at most a worker for each helper core
  "1 0xe 1"     # one thread asks for no core
)
fail=0 runs=0 first=$1
for img in "$@"; do
  ref=out/test/THREADS_REF.JPG
  out/host/fltool process "$img" $ref $S --threads 1 2>/dev/null || { echo "FAIL reference: $img"; fail=1; continue; }
  tools=(out/host/fltool)
  [ "$img" = "$first" ] && tools+=(out/host/fltool-ubsan)
  for tool in "${tools[@]}"; do
    for c in "${CASES[@]}"; do
      read -r th refuse want <<<"$c"
      log=$(FL_REFUSE_CORES=$refuse "$tool" process "$img" out/test/THREADS_OUT.JPG $S --threads $th 2>&1)
      ran=$(sed -n 's/.*  threads \([0-9]*\).*/\1/p' <<<"$log")
      runs=$((runs+1))
      if grep -q "runtime error\|^error" <<<"$log"; then
        echo "FAIL $(basename "$tool") $img threads $th refused $refuse: $(grep -m1 "runtime error\|^error" <<<"$log")"; fail=1
      elif ! cmp -s $ref out/test/THREADS_OUT.JPG; then
        echo "FAIL $(basename "$tool") $img threads $th refused $refuse: the picture differs from one thread's"; fail=1
      elif [ "$ran" != "$want" ]; then
        echo "FAIL $(basename "$tool") $img threads $th refused $refuse: ran on $ran threads, not $want"; fail=1
      fi
    done
  done
done
[ $fail = 0 ] && echo "THREADS TESTS OK: $runs develops, each identical to one thread's, on the threads expected"
exit $fail
