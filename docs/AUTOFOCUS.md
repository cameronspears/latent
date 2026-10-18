# Autofocus: what the camera does, and the plan to do better

Written 2026-10-18 against **v0.45.0** (built, not yet installed). This is the plan for making
Latent's focus better than the camera's own, from what the camera and the photos on the card already
show. **Phase 1 is built in v0.47.0** (Mac-tested, not yet on the camera), with one change to the
plan: the check after each shot runs after Latent's restart, not in the viewfinder (see 1b).

## The plan in short

The soft photos on the card have three causes, and each part answers one:

1. **See and check focus.** The viewfinder shows where the camera actually focused (it never has).
   Each photo's own preview inside its JPEG is checked where the camera focused, once Latent can read
   it: after you leave the viewfinder, the camera back says "Frame 9: missed focus" or "Frame 9:
   blurred, the camera moved". Answers the false lock: the camera says it's focused and it isn't.
2. **A steady shutter.** Slower than 1/30, a full press waits (at most 0.6 s) for the moment the live
   view shows the camera holding still, then takes the picture. Answers shake.
3. **A precise focus point.** While Latent's viewfinder is open, focus on a small spot under the
   bracket instead of the camera's Center zone, which covers the middle 60% of the picture, and put
   your own setting back on the way out. This is a new kind of camera write, so a probe build checks
   it on the camera first. Answers "it focused on something else".

Parts 1 and 2 need no new camera call or setting: one install. Part 3 needs a probe install, then its
own.

## What the camera and the card showed

Sources: `SHOOTING.TXT` (v0.22.0's read-only focus probe), `VIEWFIND.TXT` and the 61 original photos
on the card backup of 2026-10-16 (`~/Projects/latent-card-backup-2026-10-16`, outside the repo).

### The camera's focus options (v0.22.0, read only)

| | Now | Offered |
|---|---|---|
| Focus mode | `auto` (AF) | `manual`, `auto`, `dmf` |
| AF mode | `af-s` | `af-a`, `af-s`, `af-c` |
| Focus area | `center` | `multi`, `center`, `flex-spot`, `local`, `spot`, `wide` |
| Flexible Spot size | `midium` (Sony's spelling) | `small`, `midium`, `large` |
| Flexible Spot position | `focus-point-x=-752`, `focus-point-y=361` | units unknown |
| Face detection | `auto` | `off`, `auto`; up to 8 faces |
| Tracking (Lock-on AF) | `off` | none offered to apps |
| Focus drive | not supported | |
| Focus shift | supported, -16 to 16 | meaning unknown |

Two limits follow. **Latent can't move the lens itself** (`focus-drive-supported=false`), so it can't
run its own contrast sweep or focus bracket: the camera always does the focusing. And **tracking isn't
offered**. What Latent can choose is where the camera focuses, whether to trust the result, and when
the shutter fires.

### The lens

Every photo's EXIF names a **TTArtisan AF 24mm f/3.8 FE**, and the camera reports its aperture as
3.8 to 3.8: it's fixed. All 61 were shot in Aperture priority with Auto ISO. The camera holds 1/60 s
until ISO reaches 6400, then lowers the shutter speed: 1/40, 1/30, 1/25, 1/20, 1/15, 1/13, 1/8, 1/3,
1/2 on this card. Ten of the 61 are slower than 1/30.

A third-party AF lens matters here. The camera's lock means the lens reported reaching the position
it was asked for, and cheap AF lenses on older bodies are known to confirm focus and land a little
off, more so in dim light.

### Where the camera says it focused

- The AF report (`CameraEx.AutoFocusDoneListener`, logged as `camera's focus: status 1, areas [...]`)
  numbers the areas 1 to 25 on a 5 × 5 grid.
- Each photo's Sony makernote, tag `0x201F`, holds a 32-bit mask of the areas used, numbered 0 to 24.
- In Center they only ever name six cells (0-based 7, 8, 11, 12, 13 and 17; the report's 8, 9, 12, 13,
  14 and 18), so the two numberings are one apart.
- The six cells fall within the middle 3 × 3 cells, about 60% × 60% of the picture.
- Drawn over the photos, the cells sit on plausible subjects.

That the grid tiles the whole picture is **inferred, not documented**; the first install confirms it
(below).

### The causes, frame by frame

Each frame was judged by eye at 100% in the cells the camera used, and by the check in Part 1.

| Cause | Frames | What shows |
|---|---|---|
| **False lock**: the focused cells are soft | 04627, 04664, 04617, 04653 | The camera reported focus; at 100% the cells it named are out of focus while other parts of the frame are sharp |
| **The zone picked something else** | 04618 | Two cells reported: the cable at the top is sharp, the one at the centre is soft |
| **No lock, most likely** | 04671, 04673 | No cells recorded and the whole frame soft. Probably taken before a lock, which older builds allowed (the Shooting test shot anyway) and v0.45.0's focus first doesn't. Eight more frames have no cells recorded and four of those are sharp, so a missing record alone doesn't mean a miss |
| **Shake** | 04683 (1/15), 04684 (1/20), 04678 (1/3) | Text doubled sideways; the whole frame soft |
| **Subject motion** | 04611 | A 3D printer moving at 1/60 |

Shake isn't a matter of speed alone: 04685 at 1/2 s and 04667 at 1/13 are sharp. The moment decides,
which is what Part 2 is for.

### Every JPEG carries a usable preview

Each A5100 JPEG ends with a **1616 × 1080 preview** (about 340 KB), listed in its MPF segment (APP2).
At half size (808 × 540, which libjpeg's DCT scaling gives almost free), a blur of 20 pixels in the full
photo is still 2-3 pixels: easy to see. Reading it means a seek and ~340 KB, never the 5-6 MB
picture.

### The check, tried on the card

The measure:

- At strong edges (local range over 12 levels in a 5 × 5 window), take the gradient divided by the
  local range. This is high for a crisp edge, low for a soft one, and nearly independent of contrast.
- Score each of the 25 cells by the 98th percentile of that value. A cell with too few edges gets no
  score.
- `locked` is the best score among the cells the camera used; `best` is the best score of all.

On the 61 originals at 808 × 540, **soft when `locked` < 0.52, or `locked` < 0.70 × `best`**:

| Flagged soft | locked | best | By eye at 100% |
|---|---|---|---|
| 04627 | 0.34 | 0.73 | soft (focused cells defocused) |
| 04664 | 0.39 | 0.72 | soft |
| 04617 | 0.43 | 0.72 | soft |
| 04653 | 0.51 | 0.76 | soft (1/20) |
| 04678 | 0.38 | 0.65 | soft (1/3) |
| 04608 | 0.38 | 0.42 | soft overall (a close-up) |

Every frame checked as sharp passed: 04702 (0.92), 04629 (0.76), 04694 (0.63), 04620 (0.81). The
nearest pass is 04626 (0.55; 0.74 of best), whose cells were a plain desk. Missed: 04611's moving
printer (0.59), since part of its cell is sharp. Only ten frames were checked by eye. The
thresholds are first guesses from one lens and one evening of indoor light; Part 1 logs every score
so the next rolls can set them.

## Part 1: See and check focus

### 1a. The areas it focused on, on screen

`UiState.afAreas` and `Ui.afBrackets` already draw green boxes on the focused areas, but nothing
sets `afAreas` outside `RenderScreens`. The AF report's areas (1-based, 5 × 5) become boxes on the
3:2 picture. A whole cell is a fifth of the picture, so each box is drawn inset to about 60% of its
cell, so the subject still shows. The centre bracket stays as it is while searching.

- No new camera call: the areas are already in every report Latent receives.
- First thing to check on the camera: point the bracket at a small object right of centre (within
  the zone) and see the box land on it. If the boxes are offset, the grid isn't the whole picture
  and `afAreas` takes the real geometry from that test.

### 1b. Every frame checked after it's taken

**As built (v0.47.0):** the camera hides a photo it has just taken from Linux until the card is read
afresh (v0.22.1: catalogued in 79 ms, the file not seen in 30 s; the plan below missed this). So the
viewfinder tries each photo once, as below, and logs `not readable here`; at close the photos go to
`LATENT/TOCHECK.TXT`, and after the restart Latent checks them on the filer's thread (between the
darkroom's looks) and toasts on the camera back: "Frame 9: missed focus", "Frames 7 and 9: missed
focus", "Frame 12: blurred, the camera moved", "Frame 4: not sharp". If the viewfinder's try ever
succeeds, the print turns amber with the note, as planned. The check takes ~13 ms on the Mac, so
about a second a photo on the camera (estimated), not 0.1-0.4 s.

The plan as written, for the record:

When: once a picture is in the catalogue (`host.shot(f, film)`) and the store quiet is over
(`quietNow` false, `stored >= shots`), so Latent's reading of the card never overlaps the camera's
writing of it. That's about 1.5-2 s after the shutter, while the print is still developing in the
corner (`PRINT_MS`, 4 s). If another picture is taken first, the check waits for that store too,
and is dropped when the viewfinder closes or hides. It's a read of a file the camera has finished;
the catalogue is only queried, as now.

What runs:

- **The file.** Read the start of the JPEG (64 KB: EXIF, the makernote, MPF), find the preview's
  offset and size in the MPF index, seek and read it. Missing or odd (another body, a file not yet
  whole): no verdict, a log line.
- **The areas.** The makernote's `0x201F` mask for this frame. If it can't be read, the last
  AF report's areas before the shutter, recorded per shot like `shotFilms`.
- **The decode.** Native, in `jni/filmcore.c`: libjpeg-turbo at scale 1/2, `JCS_GRAYSCALE` (only
  Y; chroma's IDCT is skipped), then the measure above in one pass. The local range uses a separable
  running min / max, not a 25-pixel window, and the percentile a 64-bin histogram per cell.
  `FilmCore.focusCheck(path, mask, scores[25])` returns the verdict.
- **The thread.** One job on a look thread (cores 2 / 1) in place of a frame, given out by
  `FrameScheduler` like a frame. Never core 0, and not on core 3 (the screen's).
- **The cost.** An estimate of 0.1-0.4 s once a shot on a core that would otherwise make one or two
  frames. To be measured on the camera (logged).

What you see, only when it's soft (a sharp frame says nothing):

- The print gets an amber outline and a note on a plate under it, as "Not in focus" is shown today:
  - **"Missed focus"** when `best` is sharp (focus landed elsewhere);
  - **"Blurred: the camera moved"** when the whole frame is soft and the shutter was slower than
    1/30;
  - **"Not sharp"** otherwise.
- The words are drafts. The frame stays on the roll: like film, nothing is thrown away.
- Logged per shot in `VIEWFIND.TXT`: the 25 scores, the mask, locked, best, the verdict, the ms
  for the read and the decode.

Photos isn't touched in this part (open question 2).

## Part 2: A steady shutter at slow speeds

Latent takes every picture itself (`Release.always`), so it chooses the moment. A full press already
waits for focus; slower than 1/30, it also waits for the camera to be still.

- **The speed.** `readExposure` already runs at each lock; keep the speed as a number as well as
  text. A press held through focus shoots inside `focused()` without reading it (v0.44's 10-40 ms of
  lag, removed in v0.45.0), so the last known speed decides. Only if that was slower than 1/30, or
  unknown, is the exposure read before the shot.
- **The movement.** `fl_frame` adds a global shift between consecutive live frames to `info[]`
  (`FI_SHIFT_X`, `FI_SHIFT_Y`, in 1/16 px of the 320-px frame):
  - from a small Y thumbnail of the picture's centre, a ±3 px search by sum of differences, and a
    parabola through the minimum for the sub-pixel part;
  - a few ms at most, to be measured.
  - Pacer's 8 × 6 signature is too coarse: the shake that spoils 1/8 s moves a frame by well under a
    pixel at 320.
- **The rule.** After the full press with focus locked, take the picture on the first frame whose
  shift predicts blur under `STEADY_PX` full-size pixels at this shutter speed:
  `shift × (6000 / 320) × exposure / frame interval`. If no frame qualifies within `STEADY_MAX_MS`,
  take it anyway. At 11-19 frames a second there are 6-11 chances in that time.
- **As built:** a 320 px frame can't tell a hand's finest tremor (8-12 Hz, faster than the frames),
  so what the wait really catches is the jolt of the press settling. The aim is `STEADY_PX` (4) or
  the least a frame can tell (`FLOOR_PX`, 0.25 px a 70 ms frame), whichever is more: at 1/25 that's
  4 px, at 1/8 about 8 px. A scene too plain or dark to match (`DETAIL_MIN`) gets `BLIND_MS`
  (250 ms) for the jolt, then the picture. On the Mac the measure finds known half-pixel moves to
  within 0.06 px.
- **A gap, kept on purpose:** a focused cell blurred so far that no edge is left in it has no score,
  and gets no verdict, as a plain wall doesn't. On the card every soft frame still had edges in its
  focused cells; the logs show such a cell as `--` if it happens.
- **Letting go keeps the promise.** Let go during the wait and the picture is still taken at the
  steadiest moment within the cap. Firing at the release would add the button's own jolt, and
  cancelling would lose the shot. (Let go before focus locks, and nothing is taken, as now.)
- **What you see.** At the lock, the shutter speed in the foot turns amber when slower than 1/30,
  a heads-up before you press. During the wait, "Hold still" under the bracket.
- **Where it lives.** A steady wait in `Release` between LOCKED and shooting (plain logic, tested on
  the Mac); the viewfinder feeds it each frame's shift and the timeout.
- **With a picture still saving** (`SAVE_FIRST`): frames are quiet then, so the wait starts once
  frames come again.

| Constant | First guess | Why |
|---|---|---|
| `STEADY_BELOW` | slower than 1/30 | The camera's own floor is 1/60, and at 1/40-1/30 nothing on the card is shaken. Waiting at 1/60 would add lag to almost every indoor shot |
| `STEADY_PX` | 4 full-size pixels | Invisible at any print size; tune from the logs |
| `STEADY_MAX_MS` | 600 ms | Longer feels like a fault |

Logged per shot: the speed, the wait in ms, each frame's predicted blur, fired steady or at the cap.

## Part 3: A precise focus point

The camera is on **Center**: it picks among six of its 25 areas, the middle 60% of the picture,
usually the nearest or most contrasty thing there. 04618 shows the cost: it focused on the cable
near the top, while the cable under the bracket is soft. A small spot under the bracket is what a
film camera's centre point is: what's under the mark is what's sharp. Latent's opinion is that
predictable beats clever on a 24 mm lens, where most frames have several possible subjects.

### 3a. First, with no write: draw the camera's own area exactly

Latent already reads `focus-area-type` (the left bar says "Centre"). It also reads the spot's size
and `focus-point-x/y`, and draws the bracket where the focus area really is: the Center zone's
outline, or the Flexible Spot at its place and size. The README then recommends
**Focus Area: Flexible Spot M** in the camera's menu. This needs only the coordinate mapping from 3b,
and it's the fallback if 3b finds that writes aren't safe.

### 3b. The on-camera check (a probe build)

A build with `FOCUS_PROBE = true`, as `DIAG` is used: not a release. On the first viewfinder open
it runs once, with notes on screen saying what to do, and writes `LATENT/FOCUS.TXT`. About two
minutes.

1. **Reads only.**
   - Every method name on `CameraEx` and `ParametersModifier` containing Focus, Face, Spot, Area or
     Point (names by reflection, nothing called).
   - The getters v0.22.0 already called without trouble.
   - The flattened parameters' focus keys.
   - Whether a face listener exists (`setFaceDetectionListener` or the like).
2. **Your setting kept.** The current area, size and point to `FOCUS.TXT` on the card before any
   write (NeutralSettings' rule).
3. **Flexible Spot at the centre.**
   - Set `focus-area-type=flex-spot`, size `midium`, through `ParametersModifier` if it has setters,
     else `Camera.setParameters`: the call exposure compensation already uses live.
   - Read back, and log the ms.
   - "Half-press on something small in the middle": log the AF report's areas.
4. **The coordinates.**
   - Move the point to the left third, then the right, then up, then down; a half-press each.
   - The areas reported give the mapping from `focus-point-x/y` to the picture (perhaps -1000..1000
     about the centre, as Android's focus areas; -752 / 361 now).
5. **Small, and low light.** Size `small`, a half-press in normal light and one in a dim corner.
   Lock times and failures logged.
6. **Faces.**
   - With a face on a phone screen off to one side and the spot on something else, half-press: does
     the camera go to the face in Flexible Spot?
   - If a face listener exists, log what it reports.
7. **A picture.** One shot after the writes: stored and catalogued as usual. It goes in
   `IGNORE.TXT`, off the roll.
8. **Your setting back.** Restore area, size and point; read back. Close. Then check the camera's
   own MENU → Focus Area shows Center, where it was.

The risks, said plainly:

- **A restart.** The A5100 restarts when shooting mode starts within a few seconds of a *stored
  settings* write (v0.14.4). Exposure compensation, written through the open camera, hasn't done that
  in any log, and this goes the same way. But it's a new value, so it's tried with the camera already in
  live view, never on the way into shooting mode. If the camera restarts, nothing is lost but that
  visit.
- **A setting left changed** if the battery comes out mid-probe: the camera stays on Flexible Spot
  until set back. Latent puts it back at its next camera open from `FOCUS.TXT`, or set it yourself:
  MENU → Camera Settings → Focus Area → Center.
- **One or two test photos** on the card, kept off rolls.

`focus-shift` (-16 to 16) stays read only. If its setter's name says it's an AF fine adjustment, it
could one day fix a lens that always misses the same way. Until then it's unknown, and isn't
written.

### 3c. The behaviour, if the probe passes

- **On viewfinder open**, once live view is on: keep your area, size and point (`FOCUS.TXT`, before
  the write), then set Flexible Spot at the centre, size from step 5 (M unless S locks as well in
  low light).
- **The bracket is the spot**: drawn at its true size and place.
- **Faces.** If the camera reports faces, the spot moves to the largest one, with a box on it, and
  back when it's gone. If it doesn't report them but focuses faces in Flexible Spot anyway (step 6),
  leave it to the camera.
- **Low light, low contrast.** If the spot can't lock twice in one half-press, the third try widens
  to your own area (Center), then back to the spot at the next half-press. Precise first, generous
  when it must be. Only if step 3's write is quick (logged ms) and focus works straight after it.
- **On the way out**, put your setting back before the camera is released:
  - every close: MENU with pictures, leaving, `close(why)`, the crash handler;
  - never once Latent has left the front ("no camera write with Latent away");
  - a restore still pending runs at the next camera open, before live view.
  - Only a setting still at Latent's value is put back, as NeutralSettings does.
- **Part 1's check** then knows where focus was meant to be: the spot's own cells.

Latent doesn't guess subjects from the 320 px frames (saliency, "the interesting thing"). The camera
knows depth and Latent doesn't, and a wrong guess is worse than a predictable centre.

## Phases and installs

| Phase | What | Camera writes | Install |
|---|---|---|---|
| 1 | Part 1 (areas on screen, the check after each shot) and Part 2 (steady shutter), plus 3a (draw the camera's own area). **Built: v0.47.0**, going to the camera with v0.46.1 (the neutral settings read back) | None new | One, after your OK |
| 2 | The probe build (3b) | Focus area, size, point, put back | One, after your OK; its log decides phase 3 |
| 3 | Part 3c | Focus area, size, point while the viewfinder is open, always put back | One, after your OK |

v0.45.0 reached the camera on 2026-10-18 inside v0.46.0 (the rebuilt looks); Phase 1 is the focus work's
first run there (open question 1).

## Tests on the Mac

- **`fltool focus FILE.JPG [mask]`.** The cell scores, locked, best, the verdict and the ms, from
  the same native code: calibrate on the card backup's originals, which stay outside the repo.
- **A fixture.** A JPEG built from `testdata/` with an MPF preview appended: one sharp, one blurred
  by a known amount. Checks the MPF parse, both verdicts, and "no verdict" for a file without MPF or
  cut short.
- **JniTest.** `fl_frame`'s shift on a testdata frame and copies shifted by known sub-pixel amounts
  (within 1/4 px). Live view's pixels unchanged (`tools/frame-test.sh`), since the shift only reads.
- **ViewfinderTest.**
  - Release's steady wait: fires on a steady frame, fires at the cap, let go mid-wait still fires,
    let go before the lock takes nothing, at 1/60 no wait at all.
  - The check's scheduling: never during the quiet or a store, after a second shot's store, dropped
    on close.
- **RenderScreens.** The locked boxes, the amber print with each note, "Hold still", the amber
  shutter, the Flexible Spot bracket at a few places. `tools/preview.sh` keeps every check passing
  (text sizes, contrast, the 4-bit palette).

## On the camera: what to look for

Phase 1:

- **The boxes.** A small object right of centre gets the box. 1a's geometry, confirmed or
  corrected.
- **Ten or more shots in ordinary light.** Every picture stored and catalogued as before. A focus
  check line for each. No "Missed focus" on a frame that's sharp in Photos at 100%.
- **A miss made on purpose.** Half-press on something near, keep it held, turn to a far scene,
  shoot: "Missed focus". This is focus and recompose, which AF-S allows.
- **A dim room.** Shots slower than 1/30 log the wait and the predicted blur. Compare how many are
  soft with the card's (half of its ten slow frames).
- **Shutter lag at 1/60 and faster.** `picture asked for N ms after the full press`, as v0.45.0's.

Phase 2: `FOCUS.TXT`, and the camera's menu showing your own setting afterwards.

## Not doing, and why

- **Latent's own contrast AF, focus stacking, zone focusing**: the camera doesn't let apps drive the
  lens.
- **Lock-on AF**: not offered to apps.
- **AF-C**: the camera offers it, but with this lens in dim light it would hunt, and focus first
  means little when focus never settles.
- **Guessing subjects from the live frames**: see 3c.
- **Raising the shutter speed in dim light** (a higher Auto ISO ceiling, or a minimum shutter speed):
  the biggest lever against shake, but it changes your exposure and the grain the films assume. It's
  a separate conversation.
- **Checking focus before the shot from the live frames**: at 320 px a 20-pixel miss is about one
  pixel. The preview after the shot is the first picture sharp enough to judge.

## Open questions for you

1. **Install v0.45.0 alone first, or with Phase 1?** Settled: you chose together, and then v0.45.0
   went to the camera on 2026-10-18 inside v0.46.0 (the rebuilt looks). Phase 1 follows as v0.47.0.
2. **Soft frames in Photos.** Should Photos mark them (a small amber dot on the contact sheet), or
   only the viewfinder say so at the time? The plan says only the viewfinder.
3. **Real photos in the tests.** May two of your originals (one sharp, one soft, e.g. 04702 and
   04627, both of things on desks) go into `testdata/`? Otherwise the tests use a synthetic file and
   calibration stays on your Mac.
4. **The words.** "Missed focus", "Blurred: the camera moved", "Not sharp", "Hold still": or your
   own.
