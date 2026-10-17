# Darkroom speed: what was tried, what the camera showed

Each idea was tested on its own, under MENU > Camera > Develop test or in the darkroom's own
`LATENT/TIMING.TXT` lines. An idea was kept only if every develop's output hashed the same as before.
The Develop test's log is `LATENT/DEVTEST.TXT`.

## Where a 24 MP frame's time goes

| | v0.37.0 | v0.43.0 (after the Develop test) |
|---|---|---|
| Hardware decode | ~0.55 s | ~0.57 s |
| Output image (Sony's scaler, via half size) | ~0.45 s | ~0.44 s |
| Develop (setup, read, look, write) | 4.2-4.9 s | ~2.2 s expected (2.9-3.0 s before the lookup memory) |
| Save (Sony's encoder, Extra Fine) | ~1.75 s | ~1.75 s |
| Total | 7.3-8.6 s | ~5.6 s expected (6.3-6.8 s measured before the lookup memory) |

## Ideas, in order

| Version | Idea | Result on the camera | Kept |
|---|---|---|---|
| v0.38.1 | Transfer blocks of 4 MB or 1 MB instead of 2 MB | -3% / +5%; reads cost 20 ms a MB plus 15 ms a call | No (2 MB stays) |
| v0.39.1 | Read film files (.cube) with Latent's own exact decimal reader | Every row of all 6 films identical; 537 -> 204 ms a film; -324 ms a develop | Yes (default since v0.42.0) |
| v0.40.1 | The darkroom works out the roll once a frame, not on every redraw | Redraws 5.3-5.9 s -> 1.6-1.9 s a frame | Yes |
| v0.41.0 | A third develop helper on core 0 | Not run; core 0 is the camera's (v0.42.0's live view tore and restarted the camera) | No, never |
| v0.42.0 | The darkroom redraws only the progress band and the tray, at most once a second | Redraws ~1.7 s -> 0.3-0.4 s a frame; develop 3.7-4.5 -> 2.9-3.6 s | Yes |
| v0.43.0 | The next frame's film made ready while Sony saves this one (`fl_prewarm`) | Setup on a film change 675 -> 8-26 ms | Yes |
| v0.43.0 | Each look thread remembers recent per-pair lookups (1K / 4K / 16K entries) | 2863 -> 2225 / 2246 / 2255 ms; every develop identical | Yes (1K, pref `pairMemo`) |

## Facts worth keeping

- Image layout: the decoded source is 6000x4000 on a 6080x4048 canvas, and the output from the scaler is
  unpadded 6000x4000. Writes are gathered into ~2 MB calls, not one per row.
- Raw transfers: source reads cost about 15 ms a call plus 20 ms a MB (~42 MB/s); output writes cost
  about 11 ms a call plus 3.8 ms a MB.
- The cores run at an effective ~200-300 MHz, and DRAM reads take ~430 ns a cache line (the live view's
  speed probe), so the look is limited by memory reads and arithmetic on cores 1-3.
- Latent's thread runs on core 3 together with the UI thread, so anything the screen draws during a
  develop slows the develop's reads and writes.
- Prefs set by a test can be lost if the camera crashes and restarts (v0.41.0 lost `fastCube`).
  Anything proven should become a default in code.
- `build.sh` builds in `$TMPDIR/latent-build`, which every session shares. Set `TMPDIR` to a folder of
  your own when another session may be building.

## Not tried yet

- Save (~1.75 s, now the largest piece): overlapping Sony's encode of frame N with the decode and
  develop of frame N+1 needs three full images in Sony's memory, plus decoder and encoder running at the
  same time. Neither is known to be safe. It needs a careful check of its own first.
- Extra Fine -> Fine would likely save faster, but it changes the file. That is the user's call, not a
  speed fix.
- Output image straight from the source (one scaler pass instead of two): the pixels are all
  overwritten anyway, but whether the photo's EXIF survives the same way would need checking.
