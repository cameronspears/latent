# Latent for the Sony A5100

*Shoot the roll. Develop it later.*

An in-camera app that develops your photos like a roll of film. On film, a photo you've taken is a
*latent image* until it's developed; this works the same way. Load a film stock, shoot the roll,
then develop it. Each frame gets a **3D LUT** (the colour look), a **highlight / shadow tone curve**,
and **film grain**, and lands in the camera's own playback.

Status: **v0.30.1**, running on the A5100. Rolls (12, 24, 36 or ∞), the darkroom into the camera
roll on three cores, negatives, contact sheets and the six looks all work on the camera. Next up is
Latent's own viewfinder; see `docs/HANDOVER.md` for where things stand.

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
- Turn the wheel to change film.
- UP / DOWN sets the roll: 12, 24, 36 or ∞.
- Press the centre button to load the film, or half-press to load it and go straight to shooting.

**Shoot.** The camera back shows the frame counter. Half-press, or press the centre button, to open
Latent's viewfinder: live view in the roll's film look (about 11 frames a second), the frame counter,
exposure compensation (DOWN, then the wheel), and the film changed with the wheel (frame by frame on
an ∞ roll). MENU in the viewfinder goes straight to the red darkroom. Every photo you take is an exposure on the roll. Rolls count by the camera's photo
numbers (DSC04592, DSC04593, …), because file times can shift by hours between power-ons.

**Develop.**
- A finished roll develops by itself as soon as you open Latent again.
- The darkroom shows each frame, the time left and the battery.
- MENU pauses after the frame in hand; a half-press pauses it and goes to shoot.
- **An ∞ roll never ends:** each time you come back, the photos shot since develop in the
  background, and the newest ones show under the counter.

Looks are always previewed on a photo Latent shot neutral, never on one with your own style or a look
already on it.

**MENU** holds:
- **This roll:** Develop now, Change film.
- **Rolls:** contact sheets. LEFT / RIGHT choose a frame, UP / DOWN change roll, and the centre button
  opens the viewer.
- **Developing:** Use three cores, Shoot neutral.
- **Looks:** Try looks on a photo, the Lab with highlight, shadow and grain controls.
- **Camera:** the checks below.

**Speed test** times the film look's interchangeable inner loops on this camera's own
chip, on your newest photo decoded into memory, checks each against a full-precision reference, and
keeps the fastest one that is as accurate as today's. Results go to `LATENT/SPEED.TXT`.

**Settings check** reads your white balance, its fine-tune grid and your Creative Style,
and shows them as the camera's menus do. It only reads, and every raw value goes to
`LATENT/SETTINGS.TXT`. While a roll is loaded, Latent sets the camera to neutral (white balance fine-tune 0 / 0, Standard
0 / 0 / 0, DRO off) and puts your own settings back once the roll is developed. Your values are kept
in `LATENT/NEUTRAL.TXT` until then. Latent can only write those few settings, within their menu ranges.

### Where developed frames go

Each frame is developed **into the camera roll**. It shows in the camera's own playback, in Wi-Fi
transfer and in Imaging Edge, as a new photo with the next DSC number and the original's date,
exposure and orientation. The original becomes the roll's **negative**: it moves to
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
number, then the original's number). The camera's playback can't show those, and the original
stays in the camera roll.

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
into `LATENT/LUTS/` on the card.

The six bundled looks (Natural, Portra, Classic Neg, Cuban Neg, Tri-X, Afterparty) come from
`tools/make_luts.py`, a small film model with white balance,
curve, per-hue shifts and split toning. Change the numbers there and rebuild:

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
- **`tools/test-jni.sh testdata/STREET.JPG`** (run separately): exercises the real JNI bridge on a
  desktop JVM.

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
- The parsed LUT is cached, so it is read once per roll rather than once per frame.
- The LUT uses tetrahedral interpolation in integer maths.
- Grain comes from a precomputed, seamlessly tiling 1024×1024 noise texture. It is
  brightness-weighted: strongest in the midtones, lighter in the shadows, lightest in the
  highlights. It's monochrome, like real film grain.

**Software develop** (the fallback, and Lab):

- Streams the JPEG through libjpeg-turbo a strip at a time, so a 24 MP frame fits in the 24 MB
  that Java apps get.
- Re-renders the EXIF thumbnail into its original slot.
- Writes `.TMP` and renames it, so a dead battery never leaves a half-written `.JPG`.

## Speed, battery and the timing log

Saving a photo is CPU-heavy, so the app keeps the work, and the time the camera is awake, as short
as it can:

- **Native code for the A5100's chip:** `armeabi-v7a` with the hardware FPU, in ARM mode at `-O3`.
  `armeabi` is kept as a fallback for older bodies.
- **No division in any per-pixel loop:** this ARM core has no divide instruction, so a division
  becomes a slow library call. The JPEG encoder uses the float DCT, which avoids libjpeg's
  per-coefficient division.
- **One decode, not two:** the EXIF thumbnail is built during the main pass and patched into the
  finished file.
- **256 KB reads and writes** instead of 4 KB. Each one is a round trip through Sony's card layer.
- **Grain texture cached** per grain size, so it's built once per session instead of once per save
  or preview.
- **Screen dimmed** to 5% while a save runs, then restored.
- **Power-saving timer raised** to the camera's longest setting for the length of a save, so the
  camera can't sleep halfway through. Your own setting is put back afterwards, even after a crash.
  If the camera doesn't offer this API, set **Setup → Power Saving Start Time** to its longest
  value yourself.

Every developed frame appends a line to **`LATENT/TIMING.TXT`**. A camera-roll develop records
decode, output image, develop, save, find, orientation and negative copy times. A software develop
records read, look and write times plus CPU details. The A5100 has no NEON. Latent's own thread runs
on one core, and once the Speed test has checked them, two helper threads run the look on cores 1
and 2 at lower priority (MENU → Developing → Use three cores).

**`LATENT/DEVELOP.TXT`** records the darkroom's decisions: when it fell back to software, and why
an original stayed in the camera roll. **`LATENT/HWTEST.TXT`** is the log of the last Hardware
test.

## Known limits / not yet verified on the camera

- **Speed:** a frame develops into the camera roll in about 6–7 s (v0.44), down from about 19 s
  in v0.11. Live view with the look runs at about 11 frames a second (up to ~15) on this camera's
  cores; the camera's own live view is faster because its image chip isn't open to apps.
- **Portrait photos:** the orientation fix is confirmed on the A5100 (v0.12).
- **Power cut during a save:** if the battery dies in the second between Sony's save and Latent
  recording it, that frame may develop again next time, leaving one extra photo in the camera roll.
  The original is safe either way.
- **Wheel direction:** if turning the wheel feels backwards, that's a one-line change in
  `MainActivity.java`.

## Credits and licences

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
