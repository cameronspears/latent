# Latent for the Sony A5100

*Shoot the roll. Develop it later.*

An in-camera app that develops your photos like a roll of film. On film, a photo you've taken is a
*latent image* until it's developed; this works the same way. Load a film stock, shoot the roll,
then develop it. Each frame gets a **3D LUT** (the colour look), a **highlight / shadow tone curve**,
and **film grain**, and lands in the camera's own playback. Latent shows your developed photos too,
without a trip to the camera's Playback.

Status: **v0.45.0**. v0.44.1 is the last version run on the A5100: rolls (12, 24, 36 or ∞), Latent's
own viewfinder with the film look live, the darkroom into the camera roll on three cores, negatives
and the six looks all work there. v0.45.0 is lighter (no Lab, no settings, no camera tests) and adds
Photos, a faster viewfinder and a screen that saves the battery. It's tested on a Mac, not yet on
the camera, so its speed and battery figures below are estimates until the camera's logs confirm
them. See `docs/HANDOVER.md` for where things stand.

## Install (about 2 minutes)

1. Charge the battery and set **Setup → USB Connection → Mass Storage**. Connect the camera.
2. In Terminal, from this project's folder (the installer is kept in `tools/pmca`), run:

   ```bash
   sudo tools/pmca/pmca-console-v0.18-osx install -f Latent.apk
   ```

   Wait for `Task completed successfully` and don't unplug before then.
3. Turn the camera off and on. Open **MENU → Application → Application List → Latent**.

To remove it later: **MENU → Application → Application Management → Manage and Remove**.

## Use

**Choose film.** With the camera empty, Latent opens on your last photo in a film look.
- Turn the wheel to change film. The row of canisters shows where you are among them.
- UP / DOWN sets the roll: 12, 24, 36 or ∞.
- Press the centre button to load the film, or half-press to load it and go straight to shooting.
- MENU goes back to the roll in the camera, or to the menu when there isn't one.

**The camera back.** With a roll loaded, it shows the film and the frame counter ("09 of 24", the
roll's last three frames in the film's colour).
- The centre button or a half-press opens Latent's viewfinder.
- DOWN shows your photos.
- MENU opens the menu.
- Right after you load film, the camera needs about 6 seconds before it can shoot: Latent has just
  set it to neutral, and shooting sooner restarts the A5100. The camera back counts it down
  ("Shooting in 4 s") and then opens the viewfinder.

Every photo you take is an exposure on the roll. Rolls count by the camera's photo numbers
(DSC04592, DSC04593, …), because file times can shift by hours between power-ons.

**The viewfinder.** Live view in the roll's film look. Along the top: the film, the frame counter
("09 / 24", outlined in the film's colour when 3 or fewer are left) and the battery. Along the
foot: shutter, aperture and ISO, each in a fixed place so nothing jumps when one changes, and the
exposure scale when it isn't at 0. The picture is 3:2, so the 16:9 panel leaves a black bar each
side. The left bar shows the focus mode and area, the right one how many photos are left on the
card (and the AF light, when it's on). The foot of each bar names the key beside it.

- **Half-press** focuses. The bracket tightens while it searches, snaps green on a lock (with a
  green dot by the exposure), and turns amber when it can't lock.
- **Full press** takes the picture once focus has locked, as AF-S does. The print develops in the
  corner.
- **The wheel** (or LEFT / RIGHT) changes the film. On an ∞ roll it's from this frame on, and each
  frame keeps the film it was shot in. A roll of 12, 24 or 36 is one film: it can change only until
  the first frame, and after that the wheel shows the film's card.
- **UP** changes the display: Standard, Grid (thirds, and a histogram of the picture as it will
  develop in this film), Clean.
- **DOWN** opens the exposure scale in the right bar, beside the wheel that moves it: −3 to +3 in
  thirds, the value in the film's colour when it isn't 0. The picture stays clear. DOWN, centre, a
  half-press or MENU closes it, and it closes by itself after 5 seconds untouched. It's the camera's
  own exposure compensation, so shooting mode has it too.
- **Centre** ("Look") turns the film look off for this visit: the camera's own live view shows,
  marked LOOK OFF. Press it again to bring the look back.
- **MENU** ("Back") goes back to the camera back. Once you've shot this visit it says "Develop":
  Latent restarts and develops the new pictures in the darkroom. (Once Latent has opened the
  camera, the image memory the darkroom needs comes back only when Latent closes.)

The first time you open the viewfinder, a line at the foot shows these keys for a few seconds. If
the camera falls behind, Latent gives it back its own live view ("Film look paused: the camera is
busy"). If a picture ever won't save, the viewfinder says so and nothing else: turn the camera off
(take the battery out if it won't). Your other photos are safe.

Live view ran at about 11 frames a second in v0.44.1 (up to ~15). v0.45.0 makes each frame with an
estimated half to three quarters of the work, so expect more, up to the 19 a second the camera
sends; `LATENT/VIEWFIND.TXT` will say. When the camera is set down and nothing moves (9 seconds
untouched, 2 seconds still), the viewfinder only checks the scene about four times a second and
redraws it once a second. Any key, or anything moving, and it's back to full rate at once.

**Develop.**
- A finished roll develops by itself as soon as you open Latent again.
- The darkroom shows each frame, the time left and the battery.
- The screen goes down to a red safelight at about 5% brightness. Turn the wheel or press an arrow
  to brighten it for 5 seconds.
- MENU pauses after the frame in hand; a half-press pauses it and goes to shoot.
- If nobody touched the camera while it developed, the screen stays dim when the roll is done. The
  first press only wakes it.
- At 5% battery or less (and not on a charger) no new frame starts: "Battery low. This roll is safe;
  charge to carry on." Once it's charged, the centre button on the camera back carries on where it
  stopped.
- When a roll of 12, 24 or 36 is done, Photos opens on it.
- **An ∞ roll never ends:** each time you come back, the photos shot since develop in the
  background (once the buttons have been left alone for 4 seconds), and the newest ones show under
  the counter.

Looks are always previewed on a photo Latent shot neutral, never on one with your own style or a look
already on it.

**Photos.** Every developed frame, newest first, on one contact sheet grouped by roll: each roll
starts a row of its own, with its canister and number in the margin. Open it from the menu, with
DOWN on the camera back, or with the camera's **Playback** button if the camera passes that button to
apps (press it again to go back).
- The wheel, LEFT and RIGHT move a frame, from one roll into the next. UP and DOWN move a row.
- The title names the roll. At the top right: when the frame was taken, and which frame of the roll
  it is. "Filing 2 negatives…" means that roll's originals are still moving to its `NEG` folder.
- Centre shows the frame full screen. A caption says its film, roll and frame, when it was taken
  and its exposure, for 2 seconds; UP shows it again. The wheel, LEFT and RIGHT go to the next and
  previous frame.
- Centre again shows it at **100%**: the middle of the developed photo, a photo pixel to a screen
  pixel. The arrows move half a screen at a time, as far as the photo's edges, and a small map at the
  top right shows where you are; the wheel still steps frames. The first move on a photo takes a
  moment: the camera reads the whole photo through first. Centre goes back to the whole frame.
- MENU goes back: from a frame to the sheet, and from the sheet to wherever you opened it.

Nothing in Photos develops or ends a roll, and it doesn't open from the viewfinder or the darkroom.

**MENU** holds:
- **This roll:** Develop now (on a roll of 12, 24 or 36 part-way through: it ends the roll there
  and develops what's shot), Change film.
- **Photos.**

The line at the foot says what the chosen row does, or why it can't right now. On Photos it ends
with Latent's version.

**A screen left alone** (the camera back, Choose film, the menu, the contact sheet) dims to about
30% after 30 seconds untouched, as a phone dims before it locks. The first press only wakes it; a
half-press wakes it and shoots. The viewfinder and a photo shown full screen never dim, and the
camera's own power-saving setting isn't touched for this.

**Neutral while a roll is loaded.** Latent sets the camera to neutral (white balance fine-tune
0 / 0, Standard 0 / 0 / 0, DRO off) and puts your own settings back once the roll is developed. Your
values are kept in `LATENT/NEUTRAL.TXT` until then. Latent can only write those few settings, within
their menu ranges.

### Where developed frames go

Each frame is developed **into the camera roll**. It shows in Latent's Photos, the camera's own
playback, Wi-Fi transfer and Imaging Edge, as a new photo with the next DSC number and the
original's date, exposure and orientation. The original becomes the roll's **negative**: it moves to
`LATENT/ROLLnnn/NEG/` on the card, so the camera roll holds only the developed frame. Nothing is
ever overwritten or lost:

- An original leaves the camera roll only when three things are true: its developed photo is in the
  camera's catalogue, its negative has been copied and checked byte for byte, and that negative
  has had 30 seconds to reach the card and still checks out when read back.
- This happens in the background, alongside the next frame, so it doesn't slow the darkroom. The
  last frame's original moves about 30 seconds after the roll finishes, but only while Latent is
  on screen. If you leave sooner, it moves the next time you open Latent.
- The original is removed with Sony's own delete, the same one playback uses, so the camera's photo
  database stays right.
- If any step doesn't check out, the original simply stays in the camera roll, and the reason goes
  to `LATENT/DEVELOP.TXT`.

If the camera-roll develop isn't available or fails, that frame and the rest of the visit develop
in software instead. Software-developed frames are saved as `LATENT/ROLLnnn/01_04547.JPG` (frame
number, then the original's number). Latent's Photos shows those too, but the camera's playback
can't, and the original stays in the camera roll.

### Best results

- The looks are tuned for the camera's **Standard** creative style at 0/0/0 with auto white
  balance. In Recipe Lab, pick the **Factory** recipe before shooting photos you plan to put through
  Latent. A Recipe Lab look plus a Latent look stacks, and that's usually too much.
- Shoot **JPEG** (Fine or Extra Fine). Latent can't read RAW files, or progressive JPEGs from
  other software.

The icon is the camera back's frame counter: shot frames in white, the frame in hand in orange.
`tools/make_icon.py` draws it.

## Your own looks

Any `.cube` 3D LUT works (sizes 2–65). Rename it to 8 characters or fewer with a `.CUB` extension,
for example `KODAK.CUB`, because the camera's card system only accepts short names. Then copy it
into `LATENT/LUTS/` on the card. Your own looks develop with a little fine grain and no change to
the highlights or shadows.

The six bundled looks (Natural, Portra, Classic Neg, Cuban Neg, Tri-X, Afterparty) come from
`tools/make_luts.py`, a small film model with white balance,
curve, per-hue shifts and split toning. Each has its own tone and grain, set in `Stock.java`. Change
the numbers in `make_luts.py` and rebuild:

```bash
python3 tools/make_luts.py assets/luts
```

You can then copy the new `.CUB` files to the card. You only need to rebuild the app for new
looks to ship inside it.

To preview a look on your Mac without the camera:

```bash
tools/build-host.sh
```

```bash
out/host/fltool process IN.JPG OUT.JPG --lut assets/luts/CUBANNEG.CUB --hl -2 --sh 1 --grain 6 --size 2
```

## Build from source

```bash
tools/setup-toolchain.sh
```

```bash
./build.sh
```

`setup-toolchain.sh` downloads Android NDK r16b plus SDK build-tools 30.0.3 and android-28 into
`~/.latent-toolchain` (about 2.5 GB). `build.sh` produces `Latent.apk`. Requirements:

- a JDK 11 or newer (`build.sh` picks JDK 11, because `d8` 30.0.3 fails on JDK 21 class files)
- Rosetta on Apple Silicon

The build also runs these checks:

- **`tools/check_so.py`:** the native library must load on Android 2.3 (SysV hash table, no text
  relocations, only system libraries).
- **`tools/check_api.py`:** no Java API newer than API 10 may be used.

The Mac tests run separately; `docs/HANDOVER.md` lists them all. The main ones:

- **`tools/test-jni.sh testdata/STREET.JPG`:** exercises the real JNI bridge on a desktop JVM.
- **`tools/preview.sh`:** draws every screen to `out/preview/`, and as the camera's panel shows
  them in `out/preview/camera/`.
- **`tools/sony-test.sh`:** every darkroom develop must match the reference bit for bit.

## How it works

**Camera-roll develop** (`HardwareDevelop.java`, `jni/sony_bridge.c`):

1. Sony's hardware decoder opens the original from its catalogue entry, with its EXIF.
2. Sony's scaler makes a separate output image. It must not overlap the source in memory.
3. Latent reads the decoded pixels (YCbCr, as the camera holds them) in 2 MB blocks, applies the
   look, and writes them into the output image. The private read/write functions are first checked
   against Sony's public buffer API on a scratch buffer, and every transfer is bounds-checked.
4. Sony's JPEG encoder saves and catalogues the result as a new photo. Its orientation is matched
   to the original with Sony's own rotate, then read back.

Every Sony call has a time limit. If one never answers, Latent leaves its resources alone and
stops using the hardware until it is restarted.

**The look:**

- The tone curve and the LUT are combined into one 25-point table on the camera's own YCbCr values,
  once per roll. Each pixel then needs no conversion to RGB and back. Grain is a change to
  brightness alone.
- Up to eight parsed films are kept in memory, shared by every part of the app, so a film is read
  from the card once rather than once per frame or per preview.
- The LUT uses tetrahedral interpolation in integer maths.
- Grain comes from a precomputed, seamlessly tiling 1024×1024 noise texture. It is
  brightness-weighted: strongest in the midtones, lighter in the shadows, lightest in the
  highlights. It's monochrome, like real film grain.

**Software develop** (the fallback):

- Streams the JPEG through libjpeg-turbo a strip at a time, so a 24 MP frame fits in the 24 MB
  that Java apps get.
- Re-renders the EXIF thumbnail into its original slot.
- Writes `.TMP` and renames it, so a dead battery never leaves a half-written `.JPG`.

**Live view:** the camera sends small JPEG frames (320 px) alongside its own live view. Three
threads, on cores 1, 2 and 3, each turn one into the screen's picture in one pass: decoded, the
film's look, stretched to the screen, Latent's type laid in, and dithered to the 4 bits a channel the
camera's screen plane holds. Core 0 is the camera's own and is never used.

## Speed, battery and the logs

Saving a photo is CPU-heavy, so the app keeps the work, and the time the camera is awake, as short
as it can:

- **Native code for the A5100's chip:** `armeabi-v7a` with the hardware FPU, in ARM mode at `-O3`.
  `armeabi` is kept as a fallback for older bodies.
- **No division in any per-pixel loop:** this ARM core has no divide instruction, so a division
  becomes a slow library call. The JPEG encoder uses the float DCT, which avoids libjpeg's
  per-coefficient division.
- **Three cores for the look:** Latent's own thread on core 3, and two helpers on cores 1 and 2 at
  lower priority. Never core 0.
- **One decode, not two:** the EXIF thumbnail is built during the main pass and patched into the
  finished file.
- **256 KB reads and writes** instead of 4 KB. Each one is a round trip through Sony's card layer.
- **Grain texture cached** per grain size, so it's built once per session instead of once per save
  or preview.
- **The backlight** is the largest share of the camera's power while a screen is up (the panel is a
  backlit LCD, so Latent's black saves nothing by itself). The darkroom runs at about 5%, a screen
  left alone rests at about 30%, and an unattended roll stays dim when it's done. Whether the A5100
  honours an app's brightness at all is still to be seen on the camera.
- **Power-saving timer raised** to the camera's longest setting once for each develop, so the camera
  can't sleep halfway through. Your own setting is put back afterwards, even after a crash, and
  before the camera opens to shoot. If the camera doesn't offer this API, set **Setup → Power Saving
  Start Time** to its longest value yourself.
- **A still viewfinder slows down:** see the viewfinder above. Behind MENU, with the camera still
  open, the viewfinder makes nothing at all.

Every developed frame appends a line to **`LATENT/TIMING.TXT`**. A camera-roll develop records
decode, output image, develop, save, find, orientation and negative copy times, and ends with the
battery's percent, voltage and temperature. A software develop records read, look and write times
plus CPU details. The A5100 has no NEON.

**`LATENT/DEVELOP.TXT`** records the darkroom's decisions: when it fell back to software, and why
an original stayed in the camera roll. **`LATENT/VIEWFIND.TXT`** is the viewfinder's: a line every
10 seconds while live (frames, milliseconds per stage, each core's load, the battery) and a summary
when it closes.

## Known limits, and what the camera hasn't confirmed yet

- **Darkroom speed:** a frame develops into the camera roll in about 6–7 s (v0.44, measured), down
  from about 19 s in v0.11. v0.45.0 prefetches the look's rows and overlaps Sony's 300 ms settle
  after each save with finding the new photo: an estimated 0.2–0.5 s less a frame, to be measured.
- **Live view:** about 11 frames a second in v0.44.1 (measured, up to ~15). v0.45.0's frames take an
  estimated 70–100 ms of work each against 135–150 ms, so more frames a second, up to the camera's
  19; to be measured. The camera's own live view is faster because its image chip isn't open to apps.
- **Battery:** the darkroom's light, the dimmed idle screen and the still viewfinder should all
  save power, but by how much is unmeasured. `TIMING.TXT` and `VIEWFIND.TXT` now log the battery
  so the next rolls can tell.
- **Portrait photos:** the orientation fix is confirmed on the A5100 (v0.12).
- **Power cut during a save:** if the battery dies in the second between Sony's save and Latent
  recording it, that frame may develop again next time, leaving one extra photo in the camera roll.
  The original is safe either way. The 5% battery guard is there so this shouldn't happen.
- **The Playback button** opens Photos only if the camera passes it to apps; `DEVELOP.TXT` notes
  whether it does.
- **Wheel direction:** if turning the wheel feels backwards, that's a one-line change in
  `MainActivity.java`.

## Credits and licences

Latent itself is MIT-licensed (`LICENSE`). The parts below keep their own licences.

- Test photos (`testdata/`): Wikimedia Commons, CC BY-SA, by Michael Barera and Ted Eytan; see
  `testdata/SOURCES.txt`. They're used only by the tests on a Mac.

- JPEG coding: libjpeg-turbo 2.1.5.1, plain C build (`vendor/libjpeg-turbo-2.1.5.1`, IJG and BSD
  licences, see its LICENSE.md). This software is based in part on the work of the Independent JPEG
  Group.
- Fonts: Inter and IBM Plex Mono (SIL Open Font License, `assets/fonts/OFL-*.txt`), subset by
  `tools/make_fonts.py`.
- The looks are original values. They're inspired by Osan Bilgi's "Classic Cuban Neg" Fujifilm
  recipe and Fuji X Weekly's "Kodak Portra 400" recipe.
- The camera-app approach (Android 2.3 / API 10, armeabi, v1 signing, scan codes, 8.3 card names)
  follows what the OpenMemories, Recipe Lab and Grain Lab projects documented. No code was copied
  from them.
