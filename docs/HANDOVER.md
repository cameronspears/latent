# Latent: notes for the next session

Written 2026-10-17 at **v0.45.0**, built and tested on the Mac but not yet installed. The camera has
**v0.44.1** (the viewfinder shoots with the look at ~11 frames a second, and the next open develops).
`README.md` is the user guide and explains the develop pipeline. This file is the working context.

## The project in one paragraph

Latent is a PlayMemories Camera App for the user's Sony A5100 (Android 2.3.7, API 10). You load a
film look, shoot a roll, and Latent develops it in the camera. Each frame gets a 3D LUT, a tone curve
and grain, and lands in the camera's playback. The original is kept as a negative in
`LATENT/ROLLnnn/NEG/`. Latent's Photos shows every developed frame without the camera's Playback.
The user wants Apple and Fujifilm design: simple, beautiful, one obvious action per screen. They like
the current designs, and they asked for less: v0.45.0 took out the Lab, every setting and the
on-camera tests.

## Working with the user

- They install builds themselves: `sudo tools/pmca/pmca-console-v0.18-osx install -f Latent.apk`,
  run from the project folder. Start it in a Terminal tab when they ask, and they type the password.
  A hook allows at most 6 tabs, and tabs they typed in can't be closed for them, so ask them to close
  old ones.
- Always ask before an install.
- When the camera is plugged in as Mass Storage, the card is at `/Volumes/Untitled`, and the logs are
  in `LATENT/`.
- For anything that touches camera settings or new camera functions: investigate first, explain the
  risks, then build. The user doesn't want the camera harmed.
- A darkroom change must keep every developed picture bit-identical (`tools/sony-test.sh`,
  `tools/compare-builds.sh`). A live-view change may move pixels by one 4-bit level, on a small share
  of them, and no more unless the user agrees.
- No settings, no test screens. What a test proves becomes the default in code (prefs set by a test
  were lost when the camera crashed, v0.41.0). A one-off camera check, if one is ever needed again,
  is the user's call: each costs an install, and some save test photos.
- `latent.keystore` signs every build. It's gitignored and must be kept, or new builds won't install
  over old ones.
- In a git worktree, `latent.keystore` and `tools/pmca/` are missing (both gitignored). Link the key
  from the main checkout first (`ln -s ~/Projects/latent/latent.keystore latent.keystore`),
  or `./build.sh` makes a new key and the APK won't install. Run the installer by its full path,
  `~/Projects/latent/tools/pmca/pmca-console-v0.18-osx`, from the worktree.

## Build and test

| Task | Command |
|---|---|
| Toolchain (once) | `tools/setup-toolchain.sh` (installs to `~/.latent-toolchain`) |
| Build the APK | `./build.sh` (JDK 11; runs the API-10 and native-library checks; must end `BUILD OK`) |
| Screen previews | `tools/preview.sh [-sun]`: every screen to `out/preview/`, and as the panel shows it in `out/preview/camera/` (640 dots, 4 bits a channel, the viewfinder in its parts with the composer's dither); `-sun` adds a daylight veil; with an earlier version's renders in `out/preview-base/`, `compare/` pairs them. Fails on any check: text 13 px or more (sentences 15), contrast 4.5:1 (3:1 from 18 px), no text over text, the palette on the 4-bit grid, the overlay inside its bands, `Ui.windowKey` complete, partial redraws inside their rects, the viewfinder's layers adding up |
| Roll model and the app's small rules | `tools/roll-test.sh` (RollTest: rolls on a fake card, every developed frame for Photos; LogicTest: Backlight, the save's settle, Slots, the thumbnail cache, Photos' sheet and caption) |
| Viewfinder logic | `tools/viewfinder-test.sh` (ViewfinderTest: Release, FrameScheduler, Pacer, the store quiet, `/proc` readings, the overlay's bands, when a presented state runs out) |
| JNI and the live composer | `tools/test-jni.sh testdata/STREET.JPG` (91 checks; also PEOPLE, HOUSE, CHART; the camera's build, then one dither threshold against v0.44.1's composer pixel for pixel) |
| The composer against v0.44.1's | `tools/frame-test.sh testdata/*.JPG` (`--bench` Mac ratios, `--render DIR` frames as PPMs to judge by eye; UBSan) |
| Hot loops' instruction budget | `tools/asm-budget.sh` (the camera's own compiler and flags; each loop marked `hot:` in `jni/filmcore.c`; fails when one is over budget) |
| Shared caches under load | `tools/cache-test.sh` (3 frame composers changing films, 2 threads previewing, reading ahead, prewarming and forgetting; UBSan, Guard Malloc) |
| Sony memory bridge | `tools/sony-test.sh` (every develop bit for bit against the reference; 1 and 3 threads, the lookup memory, prewarm; UBSan, Guard Malloc) |
| Darkroom outputs unchanged | `tools/compare-builds.sh OLD_FLTOOL NEW_FLTOOL testdata/*.JPG` |
| Sanitizer | `tools/sanitize-test.sh testdata/*.JPG` |
| Neutral settings | `tools/settings-test.sh` (the write fence, apply and restore, against a stand-in service) |
| The .cube reader | `tools/cube-test.sh` |
| Image core on the Mac | `tools/build-host.sh`, then `out/host/fltool` |

Build with your own `TMPDIR` when another session may build too: `build.sh` works in `$TMPDIR/latent-build`
(and `asm-budget.sh` in `$TMPDIR/latent-asm`), and two builds there at once mix their objects.

New in v0.45.0: `asm-budget.sh` (with `tools/asm_budget.py`), `frame-test.sh` (with `tools/frame_ref.h`,
v0.44.1's whole live frame path, built into host tests under `FL_FRAME_REFERENCE` so the composer can be
compared with it), `cache-test.sh`, and the camera-true previews (`tools/preview/CameraTrue.java`).

Mac timings run ~70x faster than the camera and don't rank ARM code. Instruction counts from
`asm-budget.sh` do: the camera runs ~250 M simple instructions a second, plus ~430 ns for each memory
line it misses.

## Where things are

- `MainActivity.java`: screens, keys, rolls, the darkroom and the background develop, Photos, the
  light, the battery guard.
- `Ui.java` / `UiState.java`: all drawing, design system v2 (one palette on the 4-bit grid, one type
  scale, one hint bar with MENU last). `Ui.menuRows` (the menu, shared with the previews),
  `Ui.windowKey` and `Ui.overlayKey` (when the viewfinder's window and overlay need drawing again),
  `Ui.dirty` (partial redraws after a key). The Mac preview tool renders the same code.
- `Roll.java`, `Library.java`: rolls on the card, counted by the camera's photo numbers. The card is
  the record of the loaded roll; prefs are only a hint. `Roll.developedAll` is Photos' list.
- `Photos.java`: the contact sheet's rows, keys and pages, 100%'s edges, the caption's words from EXIF.
  `Loupe.java`: Android's region decoder, for 100% away from the centre.
- `Backlight.java`: the screen's light (the darkroom's, the rest, the peek). `Slots.java`: what the
  viewer keeps decoded, and the bounded caches.
- `HardwareDevelop.java`, `SonyMemory.java`, `jni/sony_bridge.c`, `jni/filmcore.c`: the develop into
  the camera roll. The look runs in YCbCr, on three cores, with pipelined blocks.
- `NeutralSettings.java`, `jni/settings_bridge.c`: neutral camera settings while a roll is loaded,
  with your own settings put back afterwards. Writes are limited to 11 allowlisted settings.
- `Viewfinder.java`, with `FrameScheduler.java`, `Pacer.java` and `Release.java` (all plain logic,
  tested on the Mac): the live view with the film look (see below).
- `FilmCore.java`, `jni/filmcore.h`, `jni/jni_bridge.c`: the native image core's interface.
- `Relaunch.java`: restarts Latent (its own process) to develop after the viewfinder, drawing the darkroom.
- `PowerSave.java`: the camera's power-save timer, raised for a develop and put back.
- `Probe.java`: Sony's catalogue, read only (all that's left of the first camera-roll test).
- `tools/preview/RenderScreens.java`, `CameraTrue.java`: the previews and their checks.
- `docs/DEVELOP-SPEED.md`: every darkroom speed idea since v0.38, with the camera's numbers.
- `docs/CALLBACK-FINDINGS.md`: why capture-time processing is closed.
- `tools/make_luts.py`: the six looks (Natural, Portra, Classic Neg, Cuban Neg, Tri-X, Afterparty).

## What works on the camera today (v0.44.1)

- Home screen:
  - Choose film full screen.
  - The roll length is 12, 24, 36 or ∞.
  - A half-press loads the film and shoots.
- The frame counter.
- Finished rolls develop as soon as Latent opens.
- ∞ rolls develop in the background each time you come back.
- A darkroom frame takes about 6–7 s (develop ~2.7 s, Sony's save ~1.75 s). Startup takes about
  0.2–0.4 s to the first frame.
- Latent's own viewfinder with the film look live: ~11 frames a second (up to ~15).
- Test shots are kept off rolls through `LATENT/IGNORE.TXT`.

## What v0.45.0 changes (Mac-tested only)

- **Debloat.** Gone with their code: the Lab; the Settings check, Speed, Screen, Shooting, Restart,
  Live look, Live view, Hardware and Develop tests and their PROBE screen; the menu's switches; the
  level. What they proved is fixed in code: Latent's viewfinder, focus first (a full press shoots once
  focus locks), the film look on live view (centre turns it off per visit), shooting neutral while a
  roll is loaded, three cores for the darkroom (`MainActivity.DEVELOP_THREADS`; helpers on cores 1
  and 2), the per-pair develop with its 1K lookup memory, the fast .cube reader, 2 MB transfers, the
  picture on its own 16-bit surface. The menu is This roll (Develop now when it applies, Change film)
  and Photos. The APK is ~2.48 MB, down from 2.59.
- **Photos.** One screen for every developed frame (see README). Opened from the menu, DOWN on the
  camera back, the Playback button (if the camera passes it on), and after a roll of 12, 24 or 36
  develops. Never from the viewfinder or the darkroom. Nothing in it develops or ends a roll.
- **The viewfinder:** the composer in one pass, Y'CbCr frames, a leaner look kernel, still-scene
  pacing, nothing made while hidden, the surface kept across MENU, the keys and the exposure scale in
  the side bars, notes and the film card on opaque plates.
- **The app:** the backlight (the darkroom's light, a 5 s peek, an idle rest), the battery guard,
  one power-save hold per develop, the save's settle overlapped, previews decoded once, the darkroom
  started with nothing queued ahead of it.
- Darkroom outputs are bit-identical to v0.44.1's (`tools/sony-test.sh`, and the develops compared at
  each step). Live view differs from v0.44.1's by about one 4-bit level on a few percent of pixels
  (the Y'CbCr film and the one-pass dither).

## Camera facts learned the hard way

- **Settings writes:** shooting mode must not start within about 6 s of a settings write, or the
  camera restarts. `goShoot` waits for this, and the camera back counts it down.
- **Handing over to the camera:** do it on shutter key release, or the camera won't focus.
- **File names:** the card only accepts 8.3 names.
- **No shelling out:** apps can't start other programs (`sync` and `logcat` both hung).
- **Photo times:** the camera reports file times that shift between power-ons, so rolls count by
  photo number.
- **Live view and capture inside Latent:** these work through Sony's camera API, from the UI thread:
  - focus with retries;
  - one photo, in Sony's catalogue within about 0.1 s;
  - live frames as small JPEGs, up to 19 a second;
  - a look on a 320 px frame took about 140 ms in v0.44.1.
- **Image memory:** once the app has opened the camera, Sony's image memory isn't available until the
  app closes. Photos shot inside Latent can only develop on the next open.
- **No tilt sensor:** the motion sensors give no readings on this body. (The level, made from live
  frames instead, is gone since v0.45.0.)
- **In-camera capture processing:** this isn't available to Latent. It was investigated to the end
  in v0.25.0–v0.30.0; `docs/CALLBACK-FINDINGS.md` has the evidence. The hook (`0x0120`) needs a memory
  map loaded first (`MemoryMapConfig.loadExtraMap`), and the only map this body ships (Portrait
  Lighting's) stops the camera hardware from opening. The experiment's code left with `ShootingTest`
  in v0.45.0 (it's in git before that). It's a closed topic; don't revisit it.

From the viewfinder work (each cost an install or a battery pull):

- **The screen plane must be 16-bit (RGBA4444) while the camera is open:** with Latent's 32-bit OSD
  the camera stores only the first picture (`Display.fullColour(false)` before opening, true only
  after giving it back).
- **Never use core 0:** it is the camera's own. A look thread and the overlay thread there (v0.41-0.42)
  starved its video path: live view tore into green and pink lines and the camera restarted itself.
- **These cores are slow:** ~54 M dependent multiply-adds a second; memory reads ~60-90 MB/s, ~430 ns
  a missed line. The look is limited by memory traffic more than by arithmetic.
- **Asking for a 16-bit window does nothing here** (setFormat); a SurfaceView with its own format works.
- **Never free a bitmap a look thread or the presenter may still be using:** give it back, don't
  recycle it, on the live path. A reaper waits for the threads (v0.41.0's crash).
- **Never flush the catalogue while a picture may be storing** (`AvindexStore.loadMedia`,
  `waitAndUpdateDatabase`). The camera catalogues its pictures itself within ~0.5-2 s. Queries are
  fine.
- **No new camera calls, settings writes or camera-option values** (the preview sequence's options,
  picture sizes, compression) without a guarded check first. v0.45.0 adds none; it only makes fewer
  calls of the same kinds.
- Sony's DSP preview plugin exists but needs Sony's DSP programs; none ship on this body. The
  camera's own rendering (Creative Style, WB shift) could do looks natively, but the user ruled out
  camera recipes.

## The viewfinder (v0.45.0)

Shoot or a half-press opens Latent's viewfinder: live frames from the camera with the roll's film
look on them, Latent's type over them, the print developing in the corner, exposure compensation
(DOWN, the wheel), the film changed with the wheel (per frame on ∞ rolls). MENU with pictures shot
restarts Latent (Relaunch) to develop them in the red darkroom; with none, it hides the viewfinder
and keeps the camera open. Log: `LATENT/VIEWFIND.TXT`.

**How a frame is made (read before changing it):**

- **Asking for frames** (`FrameScheduler`, UI thread): the camera's preview sequence gives 320 px
  JPEG frames (compression 1/8, unchanged). A frame is asked for as soon as a look thread is free, in
  core order 2, then 1, then 3 (at background priority, only when the other two are busy), never
  closer than `MIN_FRAME_MS` (45 ms) after the last. None during the store quiet: at least
  `QUIET_FLOOR_MS` (1.5 s) after the shutter's answer, and on until the picture is in the catalogue,
  at most 3 s. Hidden behind MENU, or with the look off, one frame is taken and dropped every 400 ms,
  so the camera's frames are drained as they always were and nothing is made. A 100 ms watchdog keeps
  the stats and the quiet's end going (it was a 10-20 ms poll).
- **The pace** (`Pacer`, `PACE`): after 9 s untouched and 2 s still, a frame is only checked about
  every 250 ms, with a whole frame each second. A check (`FilmCore.F_STILL`) decodes the frame, takes
  its signature (mean brightness of 8 x 6 cells) and stops if no cell moved more than 4 from the still
  reference (`setStillRef`). Any key, the half-press, the scale, the film card or a picture storing,
  or a moved cell, and it's full rate at once. Off for the visit if the image core gives no signature.
  The constants are first guesses.
- **The composer** (`fl_frame` in `jni/filmcore.c`, one per look thread): the JPEG decoded as Y'CbCr
  as libjpeg holds it (no colour conversion; RGB only with no film, or for the host tests' `FL_F_RGB`);
  its signature; the look through a table indexed by (Y, Cb, Cr) holding the look's R'G'B', built per
  thread from the cached film as the darkroom builds its own (25 points along Y where the tone curve
  bends, 17 across colour, 28.9 KB); the histogram of the looked picture for Grid (every other pixel
  of every other row); a stretch to the screen's 540 x 480 (one word a source pixel). Then each screen
  row is **one pass**: the two stretched rows, the row's fade and the blue-noise threshold in one chain
  of multiply-adds that lands on 4 bits in each 16-bit lane, two pixels a store (37 instructions a
  pair), packed straight into an RGB565 bitmap that holds the 4-bit values exactly. Green takes the
  opposite threshold to red's and blue's (`FL_DITHER_GREEN` 1: faint colour speckle instead of
  brightness noise, smoother skies). Latent's type follows per run; opaque type is moved to the nearest
  4-bit colour, so it's solid instead of fizzing with the dither. The packing tables and thresholds
  live with the composer, made once for a screen format.
- **The overlay** (Latent's type and fades over the picture): drawn by Skia on the overlay thread
  (core 1, the look threads' priority) only when `Ui.overlayKey` changes, and laid into every frame
  by the composer. Unless Grid is on, the type keeps to two bands, rows 0-64 and 384-480
  (`Ui.OVL_TOP_END`, `Ui.OVL_BOTTOM_START`), and once a whole render has shown that, only the bands
  are cleared, drawn and read back.
- **The presenter** (core 3): puts the newest bitmap up on a 16-bit SurfaceView over the picture's
  area (the Screen test, v0.43: 31.5 frames a second there against 20.9 through Latent's 32-bit window)
  and draws what moves over it (`UiState.PART_PICTURE`): focus, the print, the histogram, toasts,
  and on opaque near-black plates (`0xFF111111`: the 565 surface bands anything translucent) the notes,
  the film card and the first visit's keys. The surface is made once, before the camera opens,
  and kept for the visit: behind MENU or with the look off it's moved off the screen, never made again
  with the camera open. A presented state carries when it runs out (`outOfDateAt`), so a card or a
  note leaves on time with frames or without.
- **Latent's window** draws only the side bars (`PART_WINDOW`), and only when `Ui.windowKey`
  changes: focus steps without the AF light, or what the exposure scale covers, don't redraw it.
- **The shutter:** focus first; the picture asked for before the print is kept; two print bitmaps
  drawn in turn, never freed while the presenter may draw them. A picture that won't save is
  `UiState.fatal`, full screen in every part, and the camera isn't given back until it stores (or the
  camera is switched off).
- **Logs** go out on a thread of their own (core 3, background priority), never the UI thread's.

**The shared caches** (`jni/filmcore.c`, any thread): up to 8 parsed films (least recently used
dropped) under a mutex, each look holding a reference, so a film dropped meanwhile is freed only when
the last look lets go. `FilmCore.cacheLut` reads a film ahead: the viewfinder warms the roll's film at
open and the films either side of each wheel turn, never while a picture may be storing. Grain tiles
are built under a lock and never freed; the darkroom's Y'CbCr table is reference-counted; the fixed
tables are made once (`pthread_once`). `fl_preview` keeps the last two neutral decodes, so Choose
film's full-size picture and its samples cost two decodes, not seven, and a wheel turn none. A block
develop drops them first (up to 1.4 MB). `forgetLut` forgets everything (the host tests start cold).
Each thread has its own last error (a pthread key: Android 2.3 has no `__thread`).

## The rest of the app (v0.45.0)

- **The light** (`Backlight`, through `MainActivity.applyLight` and `work`; nothing else in
  MainActivity sets `screenBrightness`, and Relaunch, its own process, sets only the darkroom's): the
  darkroom at 0.05; the wheel or an arrow there peeks at full for 5 s; a roll that developed with
  nobody pressing anything stays at 0.05 until a press. Still screens (the
  camera back, Choose film, the menu, the contact sheet) rest at 0.3 after 30 s. On a resting or
  rested screen the first press only wakes it, except the shutter, which wakes and acts. Never the
  viewfinder, the viewer, or anything while the camera is Latent's. Relaunch keeps the darkroom's
  light and has no peek. The camera's power-save timer is never touched for any of it.
- **Power:** one power-save hold per develop (no write per frame); an unwatched roll keeps the hold
  until its last originals are filed (at most 60 s); given back before the camera opens, with
  `POWER_SETTLE_MS` (1 s) after it before shooting.
- **The battery guard:** at 5% or less, not on a charger, no darkroom frame starts. A roll whose
  frames are all developed still finishes. Each TIMING.TXT line carries the battery's %, mV and
  temperature.
- **The darkroom:** Sony's 300 ms settle after each save now overlaps looking for the new photo on
  the card (`HardwareDevelop.Settle`): Sony sees the same calls in the same order with the same gap.
  `save=` in TIMING.TXT is now to the encoder's answer, and `find=` holds the settle as `(settle M)`.
  The look kernel prefetches its rows. The roll is asked about once per develop; progress posts at
  most four a second; nothing of Choose film's is queued ahead of a develop.
- **Photos:** `Roll.developedAll` lists every roll's folder once and finds the prints in the last card
  read's listing. The viewer keeps the frame on show and its neighbours decoded (three previews of
  ~1.4 MB, `Slots`), the thumbnail standing in, never a black screen. 100% at the centre is
  `FilmCore.preview`'s crop (it decodes only those rows and columns; no film, no grain); a move goes
  through `Loupe`, opened on the first move (which reads the photo through) and closed on the worker.
  Every way out of Photos lets go of all of it (`closePhotos`, `photosLeft`); late decodes are dropped
  by generation. The thumbnail cache holds at most 64.
- **Playback:** `ISV_KEY_PLAY`, read once by reflection from `ScalarInput`'s static fields (nothing is
  called on the camera). Buttons Latent doesn't use are noted, one line a visit in DEVELOP.TXT.

## The next camera visit: what to check, and the lines that answer it

Look first, before anything else:

- **MENU from a viewfinder with nothing shot**, then about 20 s on the camera back and the menu. No
  frozen picture may sit over the camera back (the surface is moved off the screen, not removed;
  whether Android 2.3 moves it is the one thing the Mac can't show). Then half-press back in: the look
  should return on its surface.
- **No green or pink tearing in live view, and every picture stores.** None is expected: core 0 is
  untouched, the OSD stays 16-bit, and there's no new camera call.
- **Is `screenBrightness` honoured?** Is the darkroom visibly dimmer than the camera back, and does a
  still screen drop after 30 s? If neither, Sony ignores it and the light saves nothing (it costs
  nothing either). A names-only listing of Sony's `DisplayManager` methods might then find its own
  backlight control (nothing invoked).

`VIEWFIND.TXT`:

- `speed: …; a load chased through 8/16/32/64/128/256 KB: a/b/c/d/e/f ns` (once a visit, at frame
  40): where the time jumps is the end of the level-1 cache, and a second step shows a level 2. It
  decides how big the live film may be (the Y'CbCr film is 28.9 KB; 19.6 KB as single words).
- `cpu: … part 0x…`: Cortex-A5 (0xc05) or A9 (0xc09).
- `live: N frames in 10 s, M ms each (decode, sig+sharpen, look, compose, of it one pass, type)`:
  look and compose against v0.44.1's (decode 23-50, look 23-25, compose 80-86 ms; ~11.1 frames a
  second). The estimate is 70-100 ms a frame. The same line has `late a+b+c`, `N ms from the camera to
  the screen`, `N without a bitmap`, `still pace N s`, `N drained` and the battery.
- `pace: still, nothing touched for 9 s …` and `pace: full rate (why) after N s slow`: whether the
  signature jitters on a still scene. Set the camera on a table for 15 s; any key or a pan should go
  back to full rate at once.
- `live again N ms after the shutter`: about 1.5-3 s with today's floor. Whether to lower
  `QUIET_FLOOR_MS` is the user's call, once these are in.
- `the picture's surface off the screen (kept for the return)`, `… on the screen again, N ms after
  the return`, `hidden (MENU): …`, `shown again after N s hidden (M frames drained)`; no `live:` line
  while hidden.
- `overlay: the type keeps to rows 0-64 and 384-480, only those drawn from now on`, and `overlays of
  N ms` lower than v0.44.1's.
- `film cached: X in N ms` at open; the wheel's film on the next frame.
- `look off by you: the camera's own view` after centre: the camera's own live view should move, not
  freeze, and the next `live:` line shows no frames made and `N drained`.
- `catalogue: N photos in M ms (folder and number only)`, and no `answered differently` line.
- `visit: N min, battery A -> B, N s of it at the still pace`: with the stats lines' battery, the mV
  slope per minute, still against moving.

`TIMING.TXT`, each hardware line:

- `develop=N (read … look … write …)` against a v0.44.1 roll in the same film: the prefetch should
  take an estimated 0.15-0.25 s off the look. `save=` about 300 ms lower than v0.44.1's (the settle
  moved); `find=` ends `(settle M)`, the part of the 300 ms the card listing didn't cover; `total=`
  lower by up to 300 ms. Every print still in the catalogue and the right way up.
- `battery=N% NmV T C` at the end: the mV slope per frame.

`DEVELOP.TXT`:

- `ROLL n: developing N frame(s); power-save A->B` (or `already longest`, `unchanged (…)`): whether
  Sony's power-save API works on the A5100, and its units.
- `leaving: … N s since the last press`: after the camera's own power-save sends Latent away from the
  viewfinder, N should be near the Power Saving Start Time.
- `keys: not used here […]; Playback is scan code N` (or `not named by the camera`): the Playback key
  census. If it isn't `ISV_KEY_PLAY`, map the scan code it reports.
- `Photos: X opened for 100% in N ms`: what the region decoder's first move costs here; `Photos: X at
  100%: …` when a view couldn't be made.
- No `kept the original` lines after an unattended roll; the sheet shows `Filing N negatives…`, then
  they're filed in the same visit.

Also by eye: a half-press right after a roll ends may show "Getting ready…" on the camera back for up
to 1 s (the power-save settle), then the picture stores as usual; the battery guard's message at 5%;
the darkroom's text readable at 5% light; the side bars' labels and `⦿ Look` / `MENU Back` turning
`MENU Develop` after a shot; DOWN's exposure track keeping up with the wheel; notes and the card on
solid plates with no ghost behind them; the first visit's keys, once ever; Photos through a long roll
with no out-of-memory; green's opposite dither threshold in skies (the user may judge it against
`tools/frame-test.sh --render`).

## Left on purpose (v0.45.0)

- **D4**, the look writing straight into packed output blocks (no per-row gather copy: ~0.3 s of
  core 3 a frame, ~0.1 s of wall time). Worth a camera-measured step only if TIMING.TXT still shows
  the copy's cost after the prefetch.
- **D5**, developing the decoded source in place and saving it directly (no output image: -0.44 s a
  frame). Needs a camera check that Sony's encoder takes the padded 6080 x 4048 canvas and keeps the
  EXIF. The Develop test is gone, so a one-off check is the user's call.
- **D6**, saving frame N while frame N+1's look runs (an estimated ~35% off a frame, two frames in
  flight). High risk; needs a guarded camera check first. See `docs/DEVELOP-SPEED.md`.
- **LV4**, streaming each live frame a scanline at a time (an estimated 5-7 ms a frame and less bus
  traffic beside the camera's video path). A larger restructure of `fl_frame`; worth it once the
  camera's `live:` numbers are in.
- **Sharpening** (`FL_SHARPEN`, built, off): 18-25 instructions a source pixel (~5-7 ms a frame),
  over the ~3 ms allowed, and edges only a little crisper under the 4-bit dither.
- **640 px frames:** 1.5x the detail, an estimated 25-40% fewer frames a second, and another value of
  the frames' camera option (640 is asked for today only when 320 gives no frames).
- **Frame compression:** the preview sequence's compression is a camera option; no new value in this
  version.
- **-mtune:** measured with `asm-budget.sh`: every candidate made some hot loop worse (cortex-a5 cost
  the darkroom's pair loop 4-5 instructions a pair).
- **A smaller live film** waits for the probe's cache line.

## Off by choice

- The Lab, the settings and the on-camera tests (removed in v0.45.0 at the user's wish; v0.44.1 in
  git has them all).
- The level (removed with its code in v0.45.0; the user didn't want the line).
- Moving grain (`MOVING_GRAIN`, "noise"), the shutter blink, the exposure numbers brightening on a
  half-press, the LIVE pill (only LOOK OFF shows), the exposure meter at 0.
- Direct shutter (`DIRECT_SHUTTER`) and the exposure listeners (`EXPOSURE_LISTENERS`): no picture
  was ever stored with them on.
- `DIAG`: `LIVE.JPG`, the 16/32-bit copy probe and each core's clock, once a visit, are in the
  history already; off, so they cost live view nothing.

## Card housekeeping

`LATENT/` holds what Latent needs: `LUTS/`, the `ROLLnnn/` folders, `NEUTRAL.TXT` (the user's own
settings while a roll is loaded: never delete it then), `SHOTS.TXT`, `IGNORE.TXT` (test photos kept
off rolls; still read), and the logs `VIEWFIND.TXT`, `TIMING.TXT` and `DEVELOP.TXT`.

The rest are leftovers the user can delete: `FW/`, `LOGCAT.TXT`, `DARKPROC.TXT`, `LIVEVIEW.TXT`,
`LIVELOOK.TXT`, `LIVEFRM.JPG`, `LIVE.JPG` (no longer kept each visit), and the removed tests' logs:
`SPEED.TXT`, `SETTINGS.TXT`, `HWTEST.TXT`, `DEVTEST.TXT`, `SCREEN.TXT`, `SHOOTING.TXT`, `RESTART.TXT`,
`MEMMAP.TXT`. `VFLOG.TXT` is the camera's own log, saved only when a picture hasn't stored 4 s after
live view restarts; one there is worth reading. If playback ever says "unable to display" on older
photos, restarting the camera has fixed it (the files were intact).
