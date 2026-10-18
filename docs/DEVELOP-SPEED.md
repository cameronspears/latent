# Darkroom speed: what was tried, what the camera showed

Each idea was tested on its own, in the darkroom's own `LATENT/TIMING.TXT` lines or, until v0.45.0,
under MENU > Camera > Develop test (its log was `LATENT/DEVTEST.TXT`). An idea was kept only if every
develop's output hashed the same as before. v0.45.0 has no tests on the camera: an idea is proven
bit-identical on the Mac (`tools/sony-test.sh`, `tools/compare-builds.sh`) and then measured in
TIMING.TXT on the next roll. Rows marked "to be measured on the camera" are waiting for that roll.

## Where a 24 MP frame's time goes

| | v0.37.0 | v0.44.0 | v0.45.0 |
|---|---|---|---|
| Hardware decode | ~0.55 s | ~0.57 s | to be measured on the camera (no change made) |
| Output image (Sony's scaler, via half size) | ~0.45 s | ~0.44 s | to be measured on the camera (no change made) |
| Develop (setup, read, look, write) | 4.2-4.9 s | 2.6-3.3 s in the darkroom (v0.44.0, five frames) | to be measured on the camera; the look fetches its rows ahead, an estimated 0.15-0.25 s less |
| Save (Sony's encoder, Extra Fine) | ~1.75 s | ~1.75 s | to be measured on the camera; its 300 ms settle now overlaps finding the photo (see below) |
| Total | 7.3-8.6 s | 5.9-6.9 s (v0.44.0, five frames) | to be measured on the camera; an estimated 0.2-0.5 s less |

Since v0.45.0, `save=` in TIMING.TXT runs to the encoder's answer. Sony's 300 ms settle after it is
counted in `find=`, shown as `(settle M)`: the part of the 300 ms that finding the new photo on the
card didn't already cover. So `save=` drops by about 300 ms by itself; compare `total=` with earlier
rolls, not `save=` alone.

## Ideas, in order

| Version | Idea | Result on the camera | Kept |
|---|---|---|---|
| v0.38.1 | Transfer blocks of 4 MB or 1 MB instead of 2 MB | -3% / +5%; reads cost 20 ms a MB plus 15 ms a call | No (2 MB stays) |
| v0.39.1 | Read film files (.cube) with Latent's own exact decimal reader | Every row of all 6 films identical; 537 -> 204 ms a film; -324 ms a develop | Yes (default since v0.42.0) |
| v0.40.1 | The darkroom works out the roll once a frame, not on every redraw | Redraws 5.3-5.9 s -> 1.6-1.9 s a frame | Yes |
| v0.41.0 | A third develop helper on core 0 | Not run; core 0 is the camera's (v0.42.0's live view tore and restarted the camera) | No, never |
| v0.42.0 | The darkroom redraws only the progress band and the tray, at most once a second | Redraws ~1.7 s -> 0.3-0.4 s a frame; develop 3.7-4.5 -> 2.9-3.6 s | Yes |
| v0.43.0 | The next frame's film made ready while Sony saves this one (`fl_prewarm`) | Setup on a film change 675 -> 8-26 ms | Yes |
| v0.43.0 | Each look thread remembers recent per-pair lookups (1K / 4K / 16K entries) | 2863 -> 2225 / 2246 / 2255 ms; every develop identical | Yes (1K, the default in code since v0.43.2) |
| v0.44.0 | Can the camera save one photo while it decodes the next? (a check only) | Yes, no hang: room for a second photo; save 1709 ms alone, 1716 ms with the decode beside it, but the decode slowed 600 -> 1915 ms, so both took 2446 ms against 2764 one after the other: ~318 ms a frame. The two saved files differ only in three copies of the save time (EXIF) | Not built (see below) |
| v0.45.0 | The look kernel fetches each row ahead (a PLD two cache lines on, once a line: this core doesn't prefetch for itself) and reads and writes a pixel pair as one word (K1) | To be measured on the camera: an estimated 0.15-0.25 s less look a frame. On the Mac every develop is bit-identical (432 at each size: 6 photos x 3 films x 3 settings x the develop's variants and threads, at half and full size), and the camera's compiler gives the repeat-pair path with grain 62 instructions instead of 80 | Yes |
| v0.45.0 | Sony's 300 ms settle after each save overlapped with finding the new photo on the card. Sony sees the same calls in the same order with the same gap; only Latent's own wait moved | To be measured on the camera: up to 300 ms a frame, less whatever the card listing already took (`find=`'s `settle M`) | Yes |
| v0.45.0 | Choose film's previews decoded once: the full-size picture and the samples from two decodes, not seven, and a wheel turn from none. Nothing of Choose film's is queued ahead of a develop, and the decoded previews (up to 1.4 MB) are let go before one | To be measured on the camera: the first frame after the restart to the darkroom should start sooner (DEVELOP.TXT's `start:` line against the first TIMING line). Every preview byte-identical to v0.44.1's on the Mac | Yes |
| v0.45.0 | One power-save hold for the whole develop instead of a write for every frame; the roll asked about once per develop, not twice for each frame; progress posts at most four a second; no redraws that change nothing | To be measured on the camera: small, but it all runs on core 3, which the develop's reads and writes share | Yes |
| v0.45.0 | `-mtune` for the camera's core (cortex-a5, a7, a9, a15) | Measured with the camera's own compiler (`tools/asm-budget.sh`): cortex-a5 saved 1-3 instructions in the live loops but cost the darkroom's pair loop 4-5 a pair; the others traded likewise | No |
| v0.45.0 | The Lab and the camera tests removed (the Lab's 100% view, which decodes only its crop, now serves Photos' 100% view) | Nothing to measure in the darkroom; every develop identical (48 camera-format, 24 software); the APK 2.59 -> 2.48 MB | Gone |

## Facts worth keeping

- Image layout: the decoded source is 6000x4000 on a 6080x4048 canvas, and the output from the scaler is
  unpadded 6000x4000. Writes are gathered into ~2 MB calls, not one per row.
- Raw transfers: source reads cost about 15 ms a call plus 20 ms a MB (~42 MB/s); output writes cost
  about 11 ms a call plus 3.8 ms a MB.
- The cores run at an effective ~200-300 MHz, and DRAM reads take ~430 ns a cache line (the live view's
  speed probe), so the look is limited by memory reads and arithmetic on cores 1-3. Sequential reads
  run at only 58-71 MB/s: the core doesn't fetch ahead by itself. v0.45.0's probe also chases a load
  through 8 to 256 KB (`VIEWFIND.TXT`'s `speed:` line), which will show how big the caches are.
- Latent's thread runs on core 3 together with the UI thread, so anything the screen draws during a
  develop slows the develop's reads and writes.
- Prefs set by a test can be lost if the camera crashes and restarts (v0.41.0 lost `fastCube`).
  Anything proven becomes a default in code; since v0.45.0 there are no settings at all.
- On the Mac, instruction counts from `tools/asm-budget.sh` (the camera's own compiler and flags) rank
  ARM code; Mac timings don't.
- `build.sh` builds in `$TMPDIR/latent-build`, which every session shares. Set `TMPDIR` to a folder of
  your own when another session may be building.

## Not tried yet

- **Saving frame N while frame N+1's look runs (D6).** v0.44.0's check overlapped the save with the
  next decode and was worth only ~318 ms a frame (~5%), because Sony's decoder slows to a third of its
  speed while the encoder runs: they share hardware. Another order would overlap the save with the
  look instead, which is CPU and memory work: decode N+1 and make its output image while the encoder
  is idle, then save N beside N+1's look. Estimated ~6.4 s -> ~3.8-4.2 s a frame. It means two frames
  in flight in the darkroom (failures, pauses and the negative filing all have to handle that), and a
  guarded camera check first that Sony's memory reads and writes on one image can run while the
  encoder works on another. High risk; left for now.
- **Output image straight from the source (D5).** Develop the decoded source in place and save it
  directly: no scaler pass (~0.44 s now) and half the image memory per frame, which would make D6
  cheaper. The pixels are all overwritten anyway, but whether Sony's encoder takes the padded canvas
  and keeps the photo's EXIF the same way needs a camera check. The Develop test is gone, so a one-off
  check is the user's call (each run saves test photos).
- **The look straight into packed output blocks (D4).** Today each of the 4000 rows is copied into a
  gather buffer before the write (~0.3 s of core 3 a frame, ~0.1 s of wall time). Not needed if D5
  works; worth a step only if TIMING.TXT still shows the copy's cost after the prefetch.
- **Finding the new print by its name, and one EXIF read a photo (D2, D3).** Each frame walks the
  card's photo folders at least twice and reads the EXIF five to seven times. TIMING.TXT's `find=` and
  `orient=` will size them.
- Extra Fine -> Fine would likely save faster, but it changes the file. That is the user's call, not a
  speed fix.
