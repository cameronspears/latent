# Latent: notes for the next session

Written 2026-10-16 at **v0.30.1**, the version installed on the camera (its Shooting test checked
out: open, live view, focus and one shot, all normal).
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
  from the main checkout first (`ln -s /Users/cam/Projects/latent/latent.keystore latent.keystore`),
  or `./build.sh` makes a new key and the APK won't install. Run the installer by its full path,
  `/Users/cam/Projects/latent/tools/pmca/pmca-console-v0.18-osx`, from the worktree.

## Build and test

| Task | Command |
|---|---|
| Toolchain (once) | `tools/setup-toolchain.sh` (installs to `~/.latent-toolchain`) |
| Build the APK | `./build.sh` (JDK 11; runs the API-10 and native-library checks) |
| Screen previews on the Mac | `tools/preview.sh`, renders to `out/preview/` |
| Roll model tests | compile `src/.../Roll,Library,Stock,Probe,FilmCore` and `tests/.../RollTest` with the android.jar from the toolchain, then run `RollTest <empty dir>` |
| Image core on the Mac | `tools/build-host.sh`, then `out/host/fltool` |
| Sony memory bridge test | build `tools/sonytest.c` with `-DSB_HOST_TEST` (see `git log` for v0.25.0), then run it on a test JPEG and a LUT. Covers one and three threads, and in place |

## Where things are

- `MainActivity.java`: screens, keys, rolls, the darkroom and the background develop.
- `Ui.java` / `UiState.java`: all drawing. The Mac preview tool renders the same code.
- `Roll.java`: rolls on the card, counted by the camera's photo numbers. The card is the record of
  the loaded roll; prefs are only a hint.
- `HardwareDevelop.java`, `jni/sony_bridge.c`, `jni/filmcore.c`: the develop into the camera roll.
  The look runs in YCbCr, on three cores, with pipelined blocks.
- `NeutralSettings.java`, `jni/settings_bridge.c`: neutral camera settings while a roll is loaded,
  with your own settings put back afterwards. Writes are limited to 11 allowlisted settings.
- `ShootingTest.java`, `LiveViewTest.java`: on-camera diagnostics, under MENU, Camera.
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
- A darkroom frame takes about 7–8 s. Startup takes about 0.2–0.4 s to the first frame.
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

## Next: the Latent viewfinder (the user's choice)

The design is already drawn in `Ui.photo` (preview scenes `8-*`).

Remaining work:
1. Wire the viewfinder:
   - live view with the chosen look on the live frames;
   - shutter, focus and the HUD states.
2. Add the horizon level from the live frames.
3. Smarter focus:
   - retry while the shutter is half-pressed;
   - a "focus first" option;
   - the focus areas drawn on screen.
4. Develop viewfinder shots the next time Latent opens, reusing the ∞-roll path.
5. Tidy `ShootingTest`: keep only the focus, shot and live-frame checks. The capture-hook code
   (`CAPTURE_HOOK`, the memory map, `SEQUENCE_LIVE`) can go then.

## Card housekeeping

`LATENT/FW` (with `FW/M0001.BIN` and `FW/MEMMAP.TXT`, the copied memory map), `LOGCAT.TXT`,
`DARKPROC.TXT` and `LIVEFRM.JPG` are diagnostic leftovers, and the user can delete them. Roll 13 (Tri-X, 12 exposures) is loaded.
