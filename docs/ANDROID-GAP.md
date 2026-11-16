# Android app layer: missing

## Status

This overlay does **not** include the Android app layer, and it cannot
be produced as part of this work. There is no activity, no manifest, no
Gradle setup, no JNI glue, and no surface provider in this repo. Do not
expect a working APK until the Android layer exists.

What follows is the **interface the Android layer must satisfy**, so
that whoever writes it can build against the native flat host without
guessing. Every requirement below is traceable to the pinned upstream
tree (`def8d32`) or to the flat host's C API in `FLAT-PORT.md` §5.
Verify each item against the pinned tree before implementing; nothing
here was verified by building or running anything.

## What is missing

- A flat game activity (the Quest tree has `GameActivity` extending
  `SDLActivity`; the flat equivalent does not exist here).
- A flat `AndroidManifest.xml` (no VR declarations, normal phone
  launcher, landscape).
- Gradle files with the OpenXR loader removed.
- JNI glue calling the flat host's C API.
- A surface provider handing an `ANativeWindow` to the host.
- Settings UI (touch mode, reload mode, presentation toggles) writing
  the shared settings file.

## Interface requirements

### 1. MAME launch (replicate the Quest contract)

- Load libraries `{"SDL2", "main"}` (upstream
  `GameActivity.java:15`).
- Launch MAME with args `vcop2 -rompath <Downloads> -nvram_directory …
  -cfg_directory … -state_directory … -snapshot_directory … -video none
  -sound sdl -skip_gameinfo -noreadconfig -nomouse -numprocessors 2
  -throttle` (upstream `GameActivity.java:25-28`). `-video none` is
  load-bearing: all presentation is the host's job.
- Whether the vendored SDL Java layer
  (`org.libsdl.app.SDLActivity`) is reused as-is is undecided; the
  MAME `android-project` template mechanics were not verified. Confirm
  before assuming.

### 2. Environment (set before the engine starts)

Upstream `GameActivity.java:19-24` sets these; the flat activity must
set the same names (see `FLAT-PORT.md` §1 for why the names are kept):

| Variable | Flat value |
|---|---|
| `VCOP2_VR` | `1` (required: enables live capture + input injection; the name is vestigial) |
| `VCOP2_XR_LIBRARY` | Absolute path of the installed `libvcop2_xr.so` (or rely on the default filename `libvcop2_xr.so`, `host_bridge.h:32-33`) |
| `VCOP2_SETTINGS` | Writable path for `vcop2-settings.ini` |
| `VCOP2_XR_LOG` | Writable log base path (engine appends `.engine`/`.audio`/`.timing`; the flat host appends to the base path) |
| `VCOP2_ASSETS` | Optional; only read by dropped files, harmless to set |

### 3. Surface handoff

- Deliver an `ANativeWindow*` via `vcop2_flat_set_surface(window, w, h)`
  when the surface is created or changes size; call
  `vcop2_flat_clear_surface()` when it is destroyed. The host owns the
  EGL surface; the Java side owns the native window.
- Surface events must be delivered promptly and in order. Rotation,
  background/foreground, and lock/unlock behavior is UNKNOWN (U2 in
  `FLAT-PORT.md`): the host rebuilds GL on window change, but the full
  lifecycle has never been exercised.

### 4. Touch forwarding (exactly one consumer per touch)

- Forward every game-surface touch to `vcop2_flat_pointer(id, action,
  x, y, surface_w, surface_h)` with pointer id, action (0 down, 1 move,
  2 up, 3 cancel), surface-pixel coordinates (origin top-left), and the
  surface size. `ACTION_CANCEL` must release all pointers.
- **UI hit-test before game.** Android View buttons (FIRE, RELOAD,
  START, Pause, service/test) must consume their touches before the
  game surface sees them, so UI buttons never fire the gun. Exactly one
  consumer must own each touch; the host library has zero SDL
  visibility and cannot arbitrate.
- Buttons forward boolean states through `vcop2_flat_button(button,
  pressed)` with BOTH press and release edges: 0 FIRE, 1 RELOAD,
  2 START, 3 TEST, 4 SERVICE.
- The two-finger tap reload gesture is recognized in Java and delivered
  as a RELOAD button press. The C API exposes one pointer stream plus
  the reload button; it does not recognize gestures.
- Focus and menu state via `vcop2_flat_set_focused` /
  `vcop2_flat_set_menu_open`. Focus loss suspends input and requests
  pause.
- Touch mode (0 tap-to-shoot, 1 aim+fire) via
  `vcop2_flat_set_touch_mode`, switchable in settings, persisted
  alongside the shared settings file.

### 5. Manifest (remove all VR declarations)

Against upstream `android/app/src/main/AndroidManifest.xml`:

- REMOVE the OpenXR permission (line 5), the VR headtracking feature
  (line 6, required), the Quest device metadata (lines 14-15:
  `com.oculus.supportedDevices=quest3|quest3s`, `focusaware`), the 2D
  category on SetupActivity (line 21), and the VR + `IMMERSIVE_HMD`
  categories on GameActivity (lines 28-29).
- KEEP `glEsVersion 0x00030000` (line 7) — the GLES renderer needs
  ES 3.0.
- Normal phone launcher intent, landscape orientation. Package /
  application ID is TBD — if it changes from
  `com.vrlightgunhub.vcop2`, the `aapt2` check, manifest, and keystore
  alias/DN must all follow.
- The Quest return path to SetupActivity with the Oculus 2D category
  (`GameActivity.java:9-14`) must be adapted to target the flat
  launcher activity without Oculus categories.

### 6. Gradle (remove the OpenXR loader)

- Stage FOUR native libraries, not five: `libmain.so`, `libSDL2.so`,
  `libc++_shared.so`, `libvcop2_xr.so`. No `libopenxr_loader.so`
  build, no `OpenXRSource`/`OpenXRLoader` parameters.
- Keep the NDK/SDK/build-tools/CMake/JDK/Gradle pins consistent across
  the build script, Gradle files, and CMake toolchain flags (upstream
  pins: NDK `29.0.14206865`, compileSdk 36.1, minSdk 29, targetSdk 36,
  Gradle 9.4.1, AGP 9.2.1 — whether flat keeps them is undecided).
- Release signing flow follows upstream (`../private/vcop2.keystore`);
  signing identity is TBD.

### 7. ROM validation (unchanged)

Keep `GameFiles.java` ROM validation behavior unchanged: validate the
31 ROMs across `vcop2.zip` + `segabill.zip` by size/CRC32/SHA-1
against `game-set.json`, read from the Downloads folder at runtime,
never unpacked into app state. Patch it only if the flat flow proves a
need, with evidence.

### 8. JNI glue (names intentionally unspecified)

The JNI class/method names are NOT specified in this overlay: they
cannot be verified without the Android work, and inventing them would
create a false contract. The glue must call the C API documented in
`FLAT-PORT.md` §5 (`vcop2_flat_pointer`, `vcop2_flat_button`,
`vcop2_flat_set_touch_mode`, `vcop2_flat_set_surface`,
`vcop2_flat_clear_surface`, `vcop2_flat_set_focused`,
`vcop2_flat_set_menu_open`, `vcop2_flat_request_quit`). The Android
implementer defines the JNI signatures, verifies them against the
built `libvcop2_xr.so` export table, and device-tests every path.

## Done looks like

- [ ] Flat activity launches MAME headless with the §1 contract.
- [ ] Env vars set before engine start; `VCOP2_VR=1` present.
- [ ] Surface handoff works through rotation + background/foreground
      without crashing (U2 check).
- [ ] First tap after launch fires at the tapped point (U5 check).
- [ ] UI buttons never fire the gun; reload works while aiming.
- [ ] APK contains exactly the four ARM64 libs; `aapt2 dump badging`
      shows the flat package name.
- [ ] ROM validation rejects bad/missing ROMs with a clear message and
      never unpacks them into app state.
