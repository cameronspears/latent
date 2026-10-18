# Latent: notes for the next session

Written 2026-10-16 at **v0.32.1**, the version installed on the camera (the viewfinder shoots and
the next open develops; see "The viewfinder" below).
`README.md` is the user guide and explains the develop pipeline. This file is the working context.

## The project in one paragraph

Latent is a PlayMemories Camera App for the user's Sony A5100 (Android 2.3.7, API 10). You load a
film look, shoot a roll, and Latent develops it in the camera. Each frame gets a 3D LUT, a tone curve
and grain, and lands in the camera's playback. The original is kept as a negative in
`LATENT/ROLLnnn/NEG/`. The user wants Apple and Fujifilm design: simple, beautiful, one obvious action
per screen. They like the current designs.

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
| Build the APK | `./build.sh` (JDK 11; runs the API-10 and native-library checks) |
| Screen previews on the Mac | `tools/preview.sh`, renders to `out/preview/` |
| Roll model tests | compile `src/.../Roll,Library,Stock,Probe,FilmCore` and `tests/.../RollTest` with the android.jar from the toolchain, then run `RollTest <empty dir>` |
| Image core on the Mac | `tools/build-host.sh`, then `out/host/fltool` |
| Sony memory bridge test | `tools/sony-test.sh` (4 photos, 1 and 3 threads, the lookup memory, prewarm, under UBSan and Guard Malloc) |
| Shared caches under load | `tools/cache-test.sh` (3 frame composers changing films, 2 threads previewing, reading ahead, prewarming and forgetting; UBSan, Guard Malloc) |
| JNI and the live frame composer | `tools/test-jni.sh testdata/STREET.JPG` (37+ checks: the composer, overlay maths, two threads) |
| Viewfinder logic | `tools/viewfinder-test.sh`; roll model `tools/roll-test.sh`; sanitizer `tools/sanitize-test.sh testdata/X.JPG` |
| Previews check the viewfinder's layers | `tools/preview.sh` fails if the overlay / screen / surface parts don't add up to the whole (scenes 8w, 8x) |

Build with your own `TMPDIR` when another session may build too: `build.sh` works in `$TMPDIR/latent-build`,
and two builds there at once mix their objects.

## Where things are

- `MainActivity.java`: screens, keys, rolls, the darkroom and the background develop.
- `Ui.java` / `UiState.java`: all drawing. The Mac preview tool renders the same code.
- `Roll.java`: rolls on the card, counted by the camera's photo numbers. The card is the record of
  the loaded roll; prefs are only a hint.
- `HardwareDevelop.java`, `jni/sony_bridge.c`, `jni/filmcore.c`: the develop into the camera roll.
  The look runs in YCbCr, on three cores, with pipelined blocks.
- `NeutralSettings.java`, `jni/settings_bridge.c`: neutral camera settings while a roll is loaded,
  with your own settings put back afterwards. Writes are limited to 11 allowlisted settings.
- `Viewfinder.java`: the live view with the film look (see below). `ScreenTest.java`, `ShootingTest.java`,
  `LiveLookTest.java`, `RestartTest.java`, `DevelopTest.java`: on-camera checks (MENU, Camera).
- `Relaunch.java`: restarts Latent (its own process) to develop after the viewfinder, drawing the darkroom.
- `docs/DEVELOP-SPEED.md`: every darkroom speed idea since v0.38, with the camera's numbers.
- `tools/make_luts.py`: the six looks (Natural, Portra, Classic Neg, Cuban Neg, Tri-X, Afterparty).

## What works on the camera today

- Home screen:
  - Choose film full screen.
  - The roll length is 12, 24, 36 or ∞.
  - A half-press loads the film and shoots.
- The frame counter.
- Finished rolls develop as soon as Latent opens.
- ∞ rolls develop in the background each time you come back.
- MENU is one scrolling list.
- A darkroom frame takes about 6–7 s (develop ~2.7 s, Sony's save ~1.75 s). Startup takes about
  0.2–0.4 s to the first frame.
- Latent's own viewfinder with the film look live: ~11 frames a second (up to ~15), v0.44.1.
- Test shots are kept off rolls through `LATENT/IGNORE.TXT`.

## Camera facts learned the hard way

- **Settings writes:** shooting mode must not start within about 6 s of a settings write, or the
  camera restarts. `goShoot` waits for this.
- **Handing over to the camera:** do it on shutter key release, or the camera won't focus.
- **File names:** the card only accepts 8.3 names.
- **No shelling out:** apps can't start other programs (`sync` and `logcat` both hung).
- **Photo times:** the camera reports file times that shift between power-ons, so rolls count by
  photo number.
- **Live view and capture inside Latent:** these work through Sony's camera API, from the UI thread:
  - focus with retries;
  - one photo, in Sony's catalogue within about 0.1 s;
  - live frames as small JPEGs, up to 19 a second;
  - a look on a 320 px frame takes about 140 ms.
- **Image memory:** once the app has opened the camera, Sony's image memory isn't available until the
  app closes. Photos shot inside Latent can only develop on the next open.
- **No tilt sensor:** the motion sensors give no readings on this body, so a level has to come from
  the picture (a horizon from live frames).
- **In-camera capture processing:** this isn't available to Latent. It was investigated to the end
  in v0.25.0–v0.30.0; `docs/CALLBACK-FINDINGS.md` has the evidence. The hook (`0x0120`) needs a memory
  map loaded first (`MemoryMapConfig.loadExtraMap`), and the only map this body ships (Portrait
  Lighting's) stops the camera hardware from opening. The experiment is still in `ShootingTest`
  behind `CAPTURE_HOOK = false`. It's a closed topic; don't revisit it.

## The viewfinder (v0.44.1, on the camera)

Shoot or a half-press opens Latent's viewfinder: live frames from the camera with the roll's film
look on them, ~11 frames a second (up to ~15), Latent's HUD, the print developing in the corner,
exposure compensation (DOWN, the wheel), the film changed with the wheel (per frame on ∞ rolls).
MENU restarts Latent (Relaunch) to develop the new pictures in the red darkroom. Log:
`LATENT/VIEWFIND.TXT` (a stats line every 10 s: ms per stage, frames per core, each core's load).

**How a frame is made (read before changing it):**
- The camera's preview sequence gives 320 px JPEG frames (compression 1/8). The UI thread grabs
  them (~10 ms) and hands each to a look thread: cores 2 and 1, and 3 at background priority.
- Each look thread runs `fl_frame` (`jni/filmcore.c`), a composer per thread: decode straight into a
  kept buffer, the film's look at 17 points (a 29 KB cube the cache holds), packed two channels a
  word, then per screen pixel (540x480) a bilinear stretch, Latent's static type and fades laid in
  (the overlay: drawn by Skia only when it changes, as runs), blue-noise dither to 4 bits a channel,
  written straight into an RGB565 bitmap (libjnigraphics, opened at run time).
- A presenter thread puts the newest bitmap up on a 16-bit SurfaceView over the picture's area and
  draws what moves over it (focus, the print, notes): `UiState.PART_PICTURE`. Latent's screen draws
  only the side bars (`PART_WINDOW`). The Screen test chose this (31.5 frames a second vs 20.9).

**Camera facts from this work (each cost an install or a battery pull):**
- **The screen plane must be 16-bit (RGBA4444) while the camera is open:** with Latent's 32-bit OSD
  the camera stores only the first picture (`Display.fullColour(false)` before opening).
- **Never use core 0:** it is the camera's own. A look thread and the overlay thread there (v0.41-0.42)
  starved its video path: live view tore into green and pink lines and the camera restarted itself.
- **These cores are slow:** ~54 M dependent multiply-adds a second; memory reads ~60-90 MB/s. The
  look is limited by memory traffic more than by arithmetic.
- **Asking for a 16-bit window does nothing here** (setFormat); a SurfaceView with its own format works.
- **Never free a bitmap a look thread may be writing:** a reaper waits for the threads (v0.41.0's crash).
- **Never flush the catalogue while a picture may be storing.** The camera catalogues its pictures
  itself within ~0.5-2 s.
- **Image memory belongs to the camera while it is open,** so developing needs Latent to restart.
- Sony's DSP preview plugin exists but needs Sony's DSP programs; none ship on this body. The
  camera's own rendering (Creative Style, WB shift) could do looks natively, but the user ruled out
  camera recipes.

**Off by choice:** the level (`Viewfinder.LEVEL`; the user didn't want the line), moving grain,
the shutter blink, the exposure numbers brightening on a half-press.

## Card housekeeping

`LATENT/FW`, `LOGCAT.TXT`, `DARKPROC.TXT`, `VFLOG.TXT`, `LIVEVIEW.TXT`, `LIVELOOK.TXT` and `LIVEFRM.JPG` are
diagnostic leftovers the user can delete. `LIVE.JPG` is one live frame kept each visit. The Develop
test (off the menu since v0.44.1) left test photos listed in `LATENT/IGNORE.TXT`. If playback ever says
"unable to display" on older photos, restarting the camera has fixed it (the files were intact).
