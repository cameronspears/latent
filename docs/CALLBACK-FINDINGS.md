# Why the shutter-sequence capture hook is refused (RESULT ERROR(2))

Written 2026-10-16. Read-only investigation on the Mac, from the camera's own libraries copied to
the card under `LATENT/FW/` (`FW/INDEX.TXT` maps each `Fnnnn.BIN` to its real path). No firmware was
modified; nothing was written to the camera.

## The one-line answer

**Final answer (after v0.27.0–v0.30.0, see "Conclusion" near the end):** the hook needs working memory
the camera's default layout doesn't give it. Latent never loaded a memory map
(`MemoryMapConfig.loadExtraMap`, message `0x000E`). But the only map this body ships is Portrait
Lighting's, and with it loaded the camera hardware won't open in any way a third-party app can ask
for. Capture-time processing is closed on this camera; Latent develops on the next open.

The develop-program hypothesis below was the first guess. It was tested in v0.27.0 and not confirmed.

The original hypothesis: the firmware accepts the shutter-sequence develop callback only when a **develop _program_ is loaded
on the imaging DSP** (`sony-di-dsp`). Latent never loads one, so when it registers the callback the
DSP has `"no program is set."` and the main processor rejects the registration with
`SDComMsg(MsgId=0x0120): RESULT ERROR(2)`. Sony's Picture Effect apps load the effect's develop
program first, then register the callback — that is the step Latent is missing.

## What the evidence shows

### 1. The refusal is firmware-side; the library imposes no local gate (proven by disassembly)

`libcameraseq.so` (F0090) `scalar::CameraSequenceDiadem::setShutterSequenceCallbackMode(bool,
Options&)` @ `0x83c4`:

- It packs the options into a `Vector<SDComMsgParam*>` (an `IntParam` per int option, one
  `StringParam` for `MEMORY_MAP_FILE`) and calls
  `CameraSeqUtil::sendSDComMsg(MESSAGE_ID=0x120, ...)` — **unconditionally**. There is no mode check,
  capability check, or caller check before the send. (Two send sites, both `mov r1, 0x120`, at
  `0x87f8` and `0x8ab8`.)
- `sendSDComMsg` (6-arg, @ `0xec00`) sends the message synchronously and, on a non-OK reply, prints
  `"SDComMsg(MsgId=0x%04x): RESULT ERROR(%d)"` (offset `0x11000`). The `%d` is the `result` param the
  main processor returned; here it is `2`. The reply also carries a separate `errorCode` param
  (string `invalid SDComParam:"errorCode"` @ `0xfe24`) which the library reads but does **not** log at
  this level.

So the library is byte-identical whether Sony's app or Latent calls it. The rejection depends
entirely on firmware **state** when the message arrives — not on anything in this `.so`.

### 2. It is not a missing/extra option and not a permission wall

- The full shutter-sequence option-key set in `libcameraseq.so`: `AUTO_RELEASE_LOCK_ENABLED`,
  `EXPOSURE_COUNT`, `RECORD_COUNT`, `MEMORY_MAP_FILE`, `SPLIT_EXPOSURE`, `SPLIT_EXPOSURE_INTERVAL`,
  `SPLIT_EXPOSURE_COUNT_LIMIT`. Latent sends the core four plus `SPLIT_EXPOSURE` (appended by the
  library). Nothing valid is missing.
- `sony.cameraex.xml` (F0147) declares `sony.cameraex` only as a shared **library** — no
  `<permission>`, no signature/privapp gate. The only camera permission is the standard
  `android.permission.CAMERA` (`platform.xml`). So there is no Android-framework package/signature
  check on `CameraSequence`; any app with `CAMERA` can call it.

### 3. The imaging DSP runs a loadable "program"; the develop filters are programs

Also in `libcameraseq.so`:

```
no program is set.
program created.(programDescriptor=0x%x,URI[%s])
program replaced.(programDescriptor=0x%x,oldURI[%s],newURI[%s])
release program(programDescriptor=0x%x)
scalar::DSPDiademSA::setProgram(const char* URI)
scalar::DSPDiademSA::replaceProgram(const char* oldURI, const char* newURI)
```

`sony.cameraex.odex` (F0075) exposes this to apps: `setProgram` / `nativeSetProgram`,
`replaceProgram` / `nativeReplaceProgram` / `replaceInstalledProgram`, `PROGRAM_DESCRIPTOR`, plus
`setPictureEffect` and `setRecordingMode`. The develop filters named in both the library and the Java
API — `DEFAULT_DEVELOP`, `HDR_DEVELOP`, `ART_EFFECT_DEVELOP`, `RICHTONE_MONOCHROME_DEVELOP`,
`SIMPLE_DEVELOP`, `RAW*_ADDITION_NR_DEVELOP` — are these DSP programs. `MEMORY_MAP_FILE` is only the
shared-memory **map** for a program; it does not *load* a program (hence adding/removing the memmap
changed nothing).

The single `/etc/memmap` file on this body,
`libILDCSC_MUSASHI8Gb24m_PortraitLighting_00_00.so`, is a *PortraitLighting* program map, and
"PortraitLighting" is **not** in this body's supported `picture-effect-values`
(`off, toy-camera, pop-color, posterization, retro-photo, soft-high-key, part-color, rough-mono,
soft-focus, hdr-art, richtone-mono, miniature, illust, watercolor`). It is effectively a leftover map,
not a develop program this body runs.

### 4. Latent skips the program-load step (confirmed in Latent's own source)

`ShootingTest.java:605–633` opens a `CameraSequence`, sets the options, sets allocation policy, and
calls `setShutterSequenceCallback`. A repo-wide grep finds **no** call to `setProgram`,
`replaceProgram`, or `setPictureEffect` anywhere in Latent. The only `setRecordingMode` is on
`CameraEx$OpenOptions` at open time, value `0` = plain stills (`ShootingTest.java:179`) — not a
develop mode. So Latent registers the callback with no develop program on the DSP → `"no program is
set."` → `RESULT ERROR(2)`.

This explains every previously ruled-out attempt (options vs. plain call, allocation policy 0/1, with
and without the memmap, live-view-first orderings): none of them loaded a develop program, so all got
the same `ERROR(2)`.

## The honest limit of this analysis

The code that actually returns `result = 2` lives in the camera-subsystem **main-processor firmware /
ScalarDaemon**, which is *not* in `/system` and is not on the card. So the exact line that maps the
"no program" state to code `2` can't be read, and "`2` == no program loaded" is a strong **inference**
— from (a) the library having no local gate, (b) the DSP's own `"no program is set."` error and the
`setProgram(URI)` load surface, (c) Latent demonstrably skipping that step, and (d) it fitting all
prior negative results — not a firmware-level proof.

## Options and risks (no change made; for the user to decide)

1. **Select a supported Picture Effect before registering the hook** (`setPictureEffect` /
   `createParametersModifier`, a documented, reversible camera setting in the same class as Latent's
   11 allowlisted neutral settings). Sony's own code would then load the matching develop program.
   *Risk: low* — a reversible setting; worst case a session-scoped capture hiccup cleared by a
   power-cycle. *Unknown:* whether, once a program is loaded, Latent's own `DefaultDevelopFilter` +
   `developInPlace` can run on the callback buffer, or whether the look ends up Sony's effect instead
   of Latent's. This is the first thing worth trying on-camera (needs a build + install).

2. **Call `setProgram(URI)` / `replaceProgram` directly** to load a develop program. *Risk: medium* —
   it is a session-scoped DSP load (mmap, with a matching `release`), not a flash write, so it's
   reversible by power-cycle; but pointing the DSP at a wrong/absent URI could wedge capture for the
   session. *Likely blocker:* there may be **no develop-program file on this body to point at** — the
   only memmap present is PortraitLighting (not a develop program), and Sony's develop programs may
   live only inside the main-processor firmware image we won't extract. If so, this path is a dead end
   without firmware extraction, which is off the table.

3. **Writing `/system` or flashing firmware** — off the table (user rule: the camera must not be
   harmed). Not needed for option 1, and not justified by anything found here.

## Result of option 1 (v0.27.0, tested 2026-10-16): refused

Shooting test selected Pop Color, then Toy Camera, through Sony's parameters, waited 1.5 s each,
and tried the hook twice under each. The camera took every effect change (`CameraHardwareDiadem:
setParameters … SDComMsg (MsgId=0x1003) OK`, three times, the last one putting the effect back to
Off), and live view showed the effects. The hook was refused every time with the same
`SDComMsg(MsgId=0x0120): RESULT ERROR(2)`. No program-load or DSP lines appeared in the log.

So on this body a Picture Effect is applied inside the main processor's own pipeline. Selecting one
loads nothing the shutter-sequence hook can use, and the "develop program" explanation above is **not
confirmed**. The precondition behind `ERROR(2)` is still unknown.

One premise also remains unverified: no log so far shows a Sony app getting `0x0120` accepted on this
body. The only memory map in `/etc/memmap` is named for Portrait Lighting, which was a PlayMemories
app. If a Sony app that processes at capture is installed, running it and then Latent's Shooting test
could put Sony's own `0x0120` exchange into `LATENT/LOGCAT.TXT`, since the kernel log keeps earlier
sessions' entries. That is the only remaining read-only comparison. Without it, the hook should be
treated as closed.

## What we were missing: the map is never loaded (found after the v0.27.0 test)

`libcameraseq.so` also holds `scalar::MemoryMapConfigIF`, which Java exposes as
`com.sony.scalar.hardware.MemoryMapConfig` (parsed from the odex with a small dex lister):

| Java (static) | Native | Firmware message |
|---|---|---|
| `loadExtraMap(String path)` | `MemoryMapConfigIF::loadExtraMap` @ `0xd9d0` | **`0x000E`** with the path |
| `unloadExtraMap()` | `MemoryMapConfigIF::unloadExtraMap` @ `0xdcfc` | (undoes it) |
| `setAllocationPolicy(int)` | `MemoryMapConfigIF::setAllocationPolicy` @ `0xddf0` | `0x0010` |

`ALLOCATION_POLICY_DEFAULT = 0`, `ALLOCATION_POLICY_VERSION_1 = 1`, `ALLOCATION_POLICY_VERSION_2 = 2`.

- `loadExtraMap` rejects a path that is null or not absolute. If the path doesn't already start with
  `/android/`, it adds `/android` in front (the main processor sees Android's files there), then sends
  `0x000E` and logs `loadExtraMap() path=%s ret=%d`. So our `/android/system/etc/memmap/...` form is
  right.
- Neither Java `CameraSequence.setShutterSequenceCallback` nor native
  `setShutterSequenceCallbackMode` calls `loadExtraMap`. The Java method only checks that no split
  callback is active, adds `SPLIT_EXPOSURE=0`, and calls the native code, which passes
  `MEMORY_MAP_FILE` through verbatim in `0x0120`.
- `CameraEx$OpenOptions` holds only `inherit_setting`, `movie_mode`, `preview`, `recoding_mode` and
  `target_media`. Nothing about memory is set when the camera opens.
- **Latent has never called `loadExtraMap`.** The log line `loadExtraMap() path=` appears in no
  system log we've saved. Every attempt named a map in `0x0120` that the firmware had never been told
  to load, which would explain an `ERROR(2)` for every variant, with or without the map option.

The next test should therefore be: `MemoryMapConfig.loadExtraMap(map)` (ideally before the camera is
opened, as an app would at start-up), `setAllocationPolicy(1)`, then the hook with the same
`MEMORY_MAP_FILE`; then `setAllocationPolicy(2)` if still refused. Always `unloadExtraMap()` and
policy 0 at close. Risk: this reconfigures the main processor's working memory with Sony's own map for
this exact body (`ILDCSC_MUSASHI8Gb`, 24 MP). It is a run-time state with its own undo, not a flash
write. The worst plausible case is the camera refusing or restarting, cleared by a power cycle.

## Result of the v0.28.0 test (2026-10-16): the map loads, then live view fails

```
loading the memory map /android/system/etc/memmap/libILDCSC_MUSASHI8Gb24m_PortraitLighting_00_00.so
memory map loaded                                  (84 ms; no error)
Open the camera: ok - opened in 84 ms              (usually ~650 ms)
Live view: FAILED - RuntimeException: startPreview failed
memory map unloaded
Camera closed: ok - live view stopped, camera released
```

- **`loadExtraMap` works, and it really changes the camera's memory.** It was the first call in this
  investigation to change how the camera behaves: the camera opened much faster, and normal live view
  could not start.
- The hook was never reached (it waits for live view), so this run has no `0x0120` answer. The system
  log was not saved either, because it is only saved after the hook attempts. `LOGCAT.TXT` is still
  the 10:36 one.
- Latent closed cleanly and unloaded the map.

**The map file** (`LATENT/FW/M0001.BIN`, 3,040 bytes, copied from `/system/etc/memmap/`) is data, not
code. It is a table of memory regions: region numbers (`0x337`–`0x33b`, `0x3b6`–`0x3be`, …) and sizes
from ~4 MB (`0x003fc000`) to ~77 MB (`0x049aad40`), including 12, 24, 47 and 48 MB blocks. It is
Portrait Lighting's own working-memory layout for this body.

**What the order suggests:** in v0.28.0 the allocation policy was still `0` (default) when the camera
opened, because policy 1/2 is only set just before the hook. The policy (`VERSION_1` / `VERSION_2`)
most likely tells the firmware how to combine the extra map with its base layout, so with a map loaded
under policy 0 the layout may simply be inconsistent. The next test is therefore: `loadExtraMap`
**and** `setAllocationPolicy(1)` before `CameraEx.open`, then live view, then the hook. If live view
fails, release the camera and retry the open under policy 2. The system log should be saved on every
close.

## Result of the v0.29.0 test (2026-10-16): with the map loaded, the camera hardware won't open

Map loaded and policy set before `CameraEx.open`, under policy 1 and then policy 2. System log:

```
MemMapConfigHAL: sendSDComMsg() msgId=0x000e ret=0
MemMapConfigHAL: loadExtraMap() path=/android/system/etc/memmap/libILDCSC_MUSASHI8Gb24m_PortraitLighting_00_00.so ret=0
MemMapConfigHAL: setAllocationPolicy() policy=1 ret=0        (policy=2 on the second open)
_jniOsalUtil_: SendObjMsg ... msgId(android) = 0x11007 / RecvObjMsg messageId = 0x11008, osalErr = OK
E/CameraHardwareDiadem: CameraHardwareDiadem: open failed
...
MemMapConfigHAL: setAllocationPolicy() policy=0 ret=0
MemMapConfigHAL: sendSDComMsg() msgId=0x000f ret=0
MemMapConfigHAL: unloadExtraMap() ret=0
```

- The firmware **accepts** the map (`0x000E`, `ret=0`) and both policies (`0x0010`, `ret=0`), and the
  undo is clean (`0x000F` unloads, `ret=0`).
- With the map loaded, **the camera hardware refuses to open** under policy 1 and under policy 2.
  Java's `CameraEx.open` returned anyway (81 ms and 22 ms instead of ~650 ms), so the visible failures
  ("startPreview failed", then "no live view surface") were consequences, not causes. (The policy 2
  retry also had a bug that kept live view from starting, but the open had already failed.)
- So the map that would let `0x0120` through is Portrait Lighting's memory layout, and under that
  layout the camera cannot be opened the way Latent opens it (stills, normal preview). Without the map,
  `0x0120` is refused. The firmware ships no other map for this body.

**One documented option is still untried:** `CameraEx$OpenOptions.setPreview(false)`. Portrait
Lighting's layout may give normal live view's buffers to its own processing, with the app showing
live view through `CameraSequence`'s preview sequence instead (which Latent already knows works:
`LiveViewTest`, up to 19 frames a second). Opening without normal preview under the map is the last
cheap, reversible test. Writing our own memory map is not on the table: the format is undocumented,
and a wrong layout would be handed straight to the main processor.

## Result of the v0.30.0 test (2026-10-16): no way to open the camera under the map. Topic closed.

Map loaded and policy set before `CameraEx.open`, camera opened with `OpenOptions.setPreview(false)`,
live view asked for as the preview sequence; under policy 1, then (after a clean release and reopen)
policy 2:

```
loadExtraMap() path=/android/system/etc/memmap/libILDCSC_MUSASHI8Gb24m_PortraitLighting_00_00.so ret=0
setAllocationPolicy() policy=1 ret=0                      (policy=2 on the second open)
E/CameraHardwareDiadem: CameraHardwareDiadem: open failed
CameraSeqHAL: startPreviewSequence(SAPD=0x0,bOutput=1)
E/CameraSeqUtilHAL: SDComMsg(MsgId=0x0124): RESULT ERROR(2)
E/CameraSeqUtilHAL: SDComMsg(MsgId=0x0125): RESULT ERROR(2)      (stopPreviewSequence)
setAllocationPolicy() policy=0 ret=0 / unloadExtraMap() ret=0
```

Turning normal preview off makes no difference: the camera hardware still won't open with the map
loaded. Then the preview sequence (`0x0124`), which works without the map (`LiveViewTest`, v0.21–0.23),
gets the same `RESULT ERROR(2)` as the hook. `ERROR(2)` is the main processor's general answer for a
sequence request it can't serve in the camera's current state. It does not single out the hook.

### Conclusion

| Memory | Camera opens | Live view | Hook (`0x0120`) |
|---|---|---|---|
| Default (no map) | yes | yes (normal and preview sequence) | **refused**, `ERROR(2)` |
| Portrait Lighting map, policy 0, 1 or 2 | **no** (`CameraHardwareDiadem: open failed`) | no | not reachable |

The capture hook needs working memory that the default layout doesn't give it. The only extra layout
this body's firmware ships belongs to Portrait Lighting, and under it the camera can't be opened in any
way a third-party app can ask for (stills, preview on or off). Portrait Lighting presumably opens the
camera in its own way, which isn't visible from the libraries on this body. Making the hook work would
mean writing our own memory map, which is an undocumented format handed straight to the main
processor. That is the kind of change the user ruled out, so **capture-time processing is closed on
this camera**. Latent keeps the look live on screen and develops on the next open.

Every step was reversible and was undone each time: `0x000F` unloads the map (`ret=0`), and the policy
goes back to `0`.

## Recommendation (before the v0.27.0 test)

The precondition is now understood: a develop program must be loaded before the callback is accepted.
The only *safe* experiment is option 1 — set a supported picture effect first, on-camera, and watch
whether `ERROR(2)` clears in `LATENT/LOGCAT.TXT`. If this body exposes no develop program usable by a
third-party app (quite possible, given its effect set and the single leftover memmap), the hook stays
closed — and that is an acceptable outcome: Latent already shows the look live and develops on the
next open, exactly the fallback the handover anticipated.
