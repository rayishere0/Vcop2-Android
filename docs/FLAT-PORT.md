# Flat port design

How the vcop2-vr OpenXR host becomes a flat Android touch port, what is
reused, what is replaced, and what is still unknown. All upstream
file:line citations are against the pinned commit
`def8d32` (`def8d32914911d03c9c8d24680bf21b3f869524c`, tag `v0.2.15`).

Status: the native flat host is written but **not compile-checked and
not device-tested**. The Android app layer does not exist yet — see
`ANDROID-GAP.md`. Nothing in this document claims a working build.

## 1. Architecture

Two binaries share one `SharedRuntime` across a `dlopen` boundary:

- The **MAME engine** (`libmain.so`) owns emulation, Model 2 scene
  capture, game-state reads, and the cabinet-input read functions.
  Upstream patch 0005 is the bridge: it publishes live frames into the
  shared runtime, injects host input into the emulated lightgun reads,
  and polls the host every OSD update. MAME itself renders nothing:
  it is launched with `-video none`, so the capture-replay renderer is
  load-bearing for the flat port too.
- The **host library** (`libvcop2_xr.so`) owns presentation and input
  synthesis. The engine loads it dynamically and passes a pointer to
  its own `shared_runtime()` singleton; the host runs its frame loop on
  a worker thread it spawns.

`GLScene` — the capture-replay renderer — has no stereo loop and no
OpenXR dependency: `draw()` takes a single view-projection matrix
(`native/gl_scene.h`). Stereo was entirely the caller's loop in the
upstream host. Only `native/xr_host.cpp` includes OpenXR or calls `xr*`
functions (verified by grep). The flat host therefore keeps the
renderer and replaces only the loop around it, calling `prepare()` once
and `draw()` once per frame with a single flat view-projection matrix.

### Decisions (made once, applied everywhere)

- **Keep the names.** The env vars (`VCOP2_VR`, `VCOP2_XR_LIBRARY`,
  `VCOP2_SETTINGS`, `VCOP2_ASSETS`, `VCOP2_XR_LOG`), the target and
  filename (`vcop2_xr` / `libvcop2_xr.so`), and the three exported
  symbols are kept verbatim. Renaming any of them would require editing
  the engine-side bridge header plus every patch that reads the gate,
  for cosmetic benefit only. The flat activity MUST set `VCOP2_VR=1`:
  the name is vestigial, but the gate is what enables live capture
  (`live_active() = vr_requested()`, `native/model2_live.h:16-17`) and
  the input-injection reads (patch 0005).
- **Panel mode for first bring-up.** The flat host calls
  `set_immersive(false)` and drives the panel path. The immersive-mode
  flat view-projection is UNKNOWN (U3).
- **No camera widening for first bring-up.** The flat host writes
  `camera_fov = 0` at startup and forces `wider_view = false`. The
  110-degree widening is a VR presentation choice; see §3.
- **Full upstream patch stack for first bring-up.** The 22 engine
  patches are applied as-is (§6). Dropping middle patches requires
  rebasing later ones (they edit each other's lines) and re-verifying
  with `git apply --check` — work that cannot be validated without a
  MAME build. Diagnostic and VR-only patches are inert or env-gated
  when `immersive=false` and `camera_fov=0`. Slimming is a later step,
  guided by the verdicts in §6.

## 2. The host contract (must be preserved)

### Exported symbols

Bound by exact name in `native/host_bridge.h:35,36,38`, defined in
`native/xr_host.cpp:464,488,497`:

| Symbol | Signature | Contract |
|---|---|---|
| `vcop2_xr_start` | `bool (*)(SharedRuntime*)` | Reject null/ABI mismatch/already-running; spawn the worker; return immediately. Failures surface via `quit_requested`. |
| `vcop2_xr_stop` | `void (*)()` | Set stopping, join the worker, release the Android global activity ref. |
| `vcop2_xr_android_init` | `void (*)(void*, void*)` | Android only (null bind tolerated). Receives `JavaVM*` and the Activity; the only path to JVM access. |

`CV_EXPORT` is `extern "C"` with default visibility on non-Windows.
The flat host reimplements these three exports in
`flat/native/flat_host.cpp` with identical names and signatures.

### SharedRuntime layout

`SharedRuntime::Abi = 0x56433205` (`native/shared_runtime.h:13`), first
member. Layout order: `abi`, `scene` (snapshot slot of
`NativeFrame`), `mutex`, `state` (`GameState`), `weapons` (7
`shared_ptr<const NativeWeapon>`), `input`/`engine_input`
(`CabinetInput`), `input_latch`, `operator_latch`, 5 atomic bools
(`host_ready`, `pause_requested`, `quit_requested`,
`service_requested`, `service_active`), 2 atomic `uint16_t`
(`operator_pending`, `operator_latched`), `camera_fov` (atomic float,
default 110). The layout must stay byte-for-byte: the flat host
compiles against the unchanged upstream headers with the same
toolchain/STL (`c++_shared` on Android). The host must never call
`shared_runtime()` itself and never write `engine_input` directly —
the engine latches `input` → `engine_input` in `native_tick()`.

### The `camera_fov` reset trap

`reset()` sets `camera_fov = 110` and `start_host()` calls `reset()`
before building the bridge. A flat host that never writes it gets
110-degree camera widening on every camera write (patch 0009's taps).
The flat host MUST write `0.f` at startup, before the first frame.

### Environment variables

| Variable | Default / meaning |
|---|---|
| `VCOP2_VR` | Master gate; `vr_requested()` is `getenv != nullptr` (`host_bridge.h:18`). Flat activity sets it to `1`. |
| `VCOP2_XR_LIBRARY` | Host library path override; fallback `libvcop2_xr.so`. |
| `VCOP2_SETTINGS` | Settings file; default `vcop2-settings.ini`. |
| `VCOP2_ASSETS` | Only read by dropped files; the flat activity may still set it harmlessly. |
| `VCOP2_XR_LOG` | Log base; the engine appends `.engine`/`.audio`/`.timing`. The flat host appends its own messages to the same path. |

### MAME launch contract

The Quest activity launches MAME with libraries `{"SDL2","main"}` and
args `vcop2 -rompath <Downloads> -nvram_directory … -cfg_directory …
-state_directory … -snapshot_directory … -video none -sound sdl
-skip_gameinfo -noreadconfig -nomouse -numprocessors 2 -throttle`
(upstream `GameActivity.java:15,19-28`). The flat activity must
replicate this contract (see `ANDROID-GAP.md`). `-video none` and
`-sound sdl` are load-bearing: no MAME video output, SDL audio.

## 3. File classification

50 upstream `native/` files, classified for the flat target. PATCH and
REPLACE files are the only ones the overlay touches; KEEP files are
compiled unchanged from the fetched tree; DROP files stay out of the
flat target but remain in the fetched tree.

- **PATCH (2):** `native/gl_context.h`, `native/gl_context.cpp` — add
  the window-surface EGL path (see §4). Patch file:
  `patches/0001-flat-gl-window-surface.patch`.
- **REPLACE (3):** `native/xr_host.cpp` (flat host supplies the three
  exports, worker, EGL loop, touch synthesis, local frustum builder),
  `native/presentation.h` / `native/presentation.cpp` (no native
  equivalent; settings editing moves to Android UI).
- **KEEP (25):** `gl_scene.h/.cpp`, `model2_capture.h`,
  `model2_scene.h`, `model2_live.h`, `model2_trace.h`,
  `input_policy.h`, `shared_runtime.h`, `snapshot_slot.h`,
  `game_state.h`, `game_memory.h`, `native_weapon.h`, `native_zoom.h`,
  `native_clip.h`, `native_flash.h`, `native_timing.h`,
  `camera_state.h`, `realtime_pacer.h`, `projection.h`,
  `reticle_mode.h`, `settings_io.h`, `startup_policy.h`,
  `weapon_choice.h`, `button_latch.h`, `model2_shaders.h`,
  `host_bridge.h` (engine-side; kept because the names are kept).
- **DROP (20):** all of `native/shared/` (10 files; no KEEP file
  includes `shared/`), plus `cabinet_io.h` (test-only struct, no live
  use site — do not wire input to it), `controller_controls.h`
  (`ServiceGunPolicy`/`OperatorPolicy` semantics are reference only),
  `controller_labels.h`, `custom_reticle.h`,
  `custom_reticle_renderer.h`, `gun_effects.h`, `gun_pose.h`,
  `menu_pointer.h`, `reticle_size.h`.

The overlay CMake file (`flat/CMakeLists.txt`, overlaid onto the
upstream tree root) builds target `vcop2_xr` from `native/flat_host.cpp`,
`native/gl_scene.cpp`, patched `native/gl_context.cpp`, and
`vendor/glad/src/gl.c`, with includes `native vendor vendor/stb
vendor/glad/include vendor/glm`, C++20, linking `EGL GLESv3 android log
dl` (the upstream non-Windows link set, `CMakeLists.txt:92`). It is
Android-only and errors on any other platform.

## 4. The flat host

### EGL window surface (the patch)

Upstream `GLContext` exposes only `display()`/`context()`/`config()`;
`surface_` is private with no getter, the EGL branch requests
`EGL_PBUFFER_BIT` only, creates a 16×16 pbuffer, and no
`eglSwapBuffers`/`ANativeWindow` call site exists anywhere in the tree.
The patch adds, without touching the pbuffer constructor:

- a window-surface constructor taking an `ANativeWindow*` (caller-owned;
  only the EGL surface is created/destroyed here), with a config
  requesting `EGL_WINDOW_BIT`,
- a `surface()` accessor and `window_surface()` predicate,
- `make_current()` (rebind on the calling thread) and `swap_buffers()`.

A context must be current BEFORE any `GLScene` is constructed (shader
compilation happens in `gl_scene.cpp:92`); the flat host preserves
context-before-scene ordering on the rendering thread, and destroys the
scene before its context on teardown.

### Worker loop

`vcop2_xr_start` spawns the worker and returns. The worker:

1. Attaches to the JVM (Android) if a `JavaVM*` was supplied.
2. Loads settings via `load_settings()` (§7), forces
   `wider_view = false`, writes `camera_fov = 0`.
3. Each frame: snapshots touch/button/surface state (see §5), rebuilds
   GL objects on window change, synthesizes one persistent P1 hand
   through `InputPolicy`, publishes `CabinetInput` under the shared
   mutex, applies operator-button rising edges, sets
   `pause_requested`, then renders one flat view or a black frame.
4. On stop: tears down GL, clears `host_ready`, deletes the global
   activity ref, detaches from the JVM. Fatal errors set
   `quit_requested` (the upstream failure shape).

Pacing: the worker sets a swap interval of 1 and paces on vsync through
`eglSwapBuffers`. Whether that is sufficient (vs patch 0006's deadline
pacer or stock throttling) is UNKNOWN (U1).

### View projection and pillarboxing

Panel mode, single view. The frustum is built from the SAME lens
`draw()` uses: `presentation_lens(frame)` returns an override, else
`frame.lens`, else the fallback `{width, height, 1827.30969, 1827.30969,
248., 191.}` (`native/model2_scene.h`). No code ever assigns
`frame.lens` on live frames, so the fallback is what the panel path
actually uses unless the host supplies an override. The flat host
supplies no override for first bring-up.

The frustum is an infinite-far-plane asymmetric frustum from the lens
(`near = 0.02`), reimplementing the upstream `projection()` form
(file-local to `xr_host.cpp:77-81`), with `l = -cx/fx`,
`r = (W-cx)/fx`, `b = (cy-H)/fy`, `t = cy/fy`:

```
m[0][0] = 2/(r-l)      m[1][1] = 2/(t-b)
m[2][0] = (r+l)/(r-l)  m[2][1] = (t+b)/(t-b)
m[2][2] = -1           m[2][3] = -1
m[3][2] = -2*near
```

Verified algebra: a native pixel `(px, py)` unprojected through the
lens lands at `NDC_x = (2*px - W)/W`, `NDC_y = (H - 2*py)/H`,
independent of panel distance and world scale. Use live frame
`width`/`height`, never hardcoded dimensions.

Pillarboxing is a `glViewport` rect, not a VP change: preserve the
native aspect `W:H` from the live frame, centre the rect, clear the
full surface first so the bars show the clear colour. **496×384 has
aspect 1.2917, NOT 4:3 (1.3333)** — pillarboxing to literal 4:3 would
stretch the picture ~3.2%. Live dimensions come from MAME
`visible_area()` and are not proven to be 496×384 (U8); frames also
carry `xoffset`/`yoffset`, whose effect on the panel rect is UNKNOWN
(U8). Touch mapping uses the same rect. `GLScene::draw()` in panel mode
forces zoom and hud_size to 1, draws the background raster (not sky),
and uses the presentation lens; the flat host passes zoom/hud_size 1.

A loading state uses `native_picture_available(frame)`
(`native/startup_policy.h:7-11`): non-null, complete, dims 1..2048, and
the `native` or `background` raster exactly `width*height` pixels.
Visual readiness is independent of input-ready mode.

Shaders: all six live in `native/shaders/model2_shaders.h` as GLSL
`#version 330` literals, rewritten to `#version 300 es` on Android by
`gl_scene.cpp:16-18`; single-`vp` uniforms, no stereo content, reused
unchanged.

## 5. Touch input

### The existing path (reused, not reinvented)

`InputPolicy::update(now, hands, state, settings, focused, menu)`
(`native/input_policy.h`) converts two `HandInput`s into a
`CabinetInput`. Aim→gun formula (`input_policy.h:141-142`): if tracked,
valid, and finite, `gun.x = lround(clamp(u,0,1)*65535)`, same for `y`.
Trigger is gated on `inside` and not pulling outside; pump is held
0.12 s and suppresses trigger; coin/start pulse (`coin_mask` 0–0.1 s,
`start_mask` 0.14–0.26 s or while held). `CabinetLatch` inserts a
release frame on hand change and suppresses pump overlapping a held
trigger. `OperatorLatch` stretches operator bits `{4,8,0x10,0x20}` to
6 ticks.

The engine reads the latched input through active-low functions in
`native/host_bridge.h`: `lightgun_byte` (Y at offsets 0,1; X at 2,3;
via `calibrated_gun_position`, `game_memory.h:33-35`, inverting the
retail formula against per-player calibration from RAM at
`0x507278 + player*16`; native screen 496×384, ADC 0..1023),
`trigger_input`, `system_input` (coin bits 0/1, start bits 0x10/0x20,
service 150 ms pulse bit 0x04), `offscreen_input` (pump-driven only,
ignores coordinates). Only P1 is wired in Phase 1.

### Synthetic-hand discipline (mandatory)

- `tracked = true` ALWAYS once ready. Flapping it eats the first tap
  after every lift (`blocked_` in `InputPolicy`).
- `aim.valid = true` ALWAYS, with aim = last touch point (default
  0.5, 0.5). The aim stays at the last point when the finger lifts.
- Trigger/pump/start are false at rest. Never flap hand assignment.
- `policy.suspend()` on focus loss.
- `solo_hand_` starts at 1 and transfers on the first enabled frame
  (≤1-frame effect; verify on device, U5).

### Touch mapping

With pillarbox rect `(vx,vy,vw,vh)` and touch `(sx,sy)` origin
top-left, y-down: `u = (sx-vx)/vw`, `v = (sy-vy)/vh`, no axis flip;
`inside` = rect test. The edge convention differs by ≤1 native pixel
from `project_point`'s `(width-1)` divisor; do not "fix" without a
device check (U4).

| Touch spec item | Implementation |
|---|---|
| Tap-to-shoot (default) | Tap sets aim to the tap point and holds `trigger` while the aim pointer is down. |
| Aim+fire mode | Drag moves the crosshair (aim only); the FIRE button drives `trigger`. |
| Mode switch | Setting, delivered via `vcop2_flat_set_touch_mode` (0/1). |
| RELOAD | `pump` bit, driven by the RELOAD button (primary). `ShootOutside`/`PointOutside` reload modes are opt-in only. |
| Two-finger tap reload | Second pointer down while aiming also drives `pump` (Java-side gesture; the C API exposes one pointer stream + the reload button — the Java layer owns gesture recognition). |
| Start/Coin | START button drives `start`; `InputPolicy` produces the coin→start pulse. No separate COIN button. |
| Pause | Sets `pause_requested` (focus loss or menu open). |
| Quit | `vcop2_flat_request_quit()` sets `quit_requested`. |
| Service/Test | Rising edges OR `operator_pending` bits 4 (Test) / 8 (Service advance). Bits 0x10/0x20 (calibration Start) are deferred. |
| UI hit-test | The Java layer owns it: Android View buttons consume touches before the game surface sees them, so UI buttons never fire the gun. Exactly one consumer must own each touch. |
| Multi-touch | Pointer stream is keyed by pointer id; reload (button or second finger) works while the other thumb aims. |

Minimum trigger duration vs vblank latching is UNKNOWN (U6): a tap
shorter than one emulated frame may or may not register. The proving
check is a device test with short vs held taps.

### C API (what the Java/JNI glue calls)

Defined at the bottom of `flat/native/flat_host.cpp`. All functions are
thread-safe and never block on the worker. The JNI glue itself is part
of the blocked Android work (`ANDROID-GAP.md`); method names are NOT
specified here because they cannot be verified without that work.

| Function | Arguments | Meaning |
|---|---|---|
| `vcop2_flat_pointer` | `id, action, x, y, surface_w, surface_h` | Pointer event. `action`: 0 down, 1 move, 2 up, 3 cancel (releases all pointers). Coords in surface pixels, origin top-left. |
| `vcop2_flat_button` | `button, pressed` | Buttons: 0 FIRE, 1 RELOAD, 2 START, 3 TEST, 4 SERVICE. `pressed` 0/1; both edges must be delivered. |
| `vcop2_flat_set_touch_mode` | `mode` | 0 tap-to-shoot (default), 1 aim+fire. Other values ignored. |
| `vcop2_flat_set_surface` | `native_window, w, h` | `ANativeWindow*` owned by the caller. Triggers GL (re)build on the worker. |
| `vcop2_flat_clear_surface` | — | Surface destroyed; worker tears down GL. |
| `vcop2_flat_set_focused` | `focused` | Focus loss suspends input and requests pause. |
| `vcop2_flat_set_menu_open` | `open` | Menu open requests pause. |
| `vcop2_flat_request_quit` | — | Sets `quit_requested`. |

The host library has zero SDL visibility (no link, no headers, no event
calls) — touch must be owned by the Java side and forwarded through
this API. Whether the host could `dlsym` SDL events is UNKNOWN (U7)
and must not be relied on. `ACTION_CANCEL` must release all pointers.

## 6. Settings

`key=value` file, version 6 (`native/settings_io.h`):
`load_settings(const char*)`, `read_settings(istream, bool*)`,
`write_settings(ostream, Settings)`. The flat host reuses
`settings_io.h` unchanged and drops UI rows, not file keys.

Flat-relevant keys: `reload`, `p1_hand`, `wider_view`, `view_fov`
(only meaningful when wider), `world_scale`, `panel_distance`,
`hud_size` (immersive only). VR-only keys (`gun`, `gun_angle`,
`gun_scale`, `laser`, `laser_width`, reticle keys) are parsed and
ignored. `haptics` has no non-XR consumer. `background` has no reader
outside settings I/O/tests. `game_zoom` has no parse/write branch;
`wider_view_fade` is immersive/session-only. Note `Settings{}` defaults
to `wider_view=true` — the flat host explicitly forces `false` for
first bring-up.

## 7. Upstream MAME patch table (patches 0001–0022)

Verdicts from a full read of all 22 upstream patch files. Patch-line
citations are line numbers inside the patch file. **Phase 1 applies
the full stack as-is** (§1); this table records the verdicts for
future slimming. Dropping any patch except a clean tail/set requires
rebasing later patches (0005 edits 0004's lines; 0009 edits 0007's
line; 0011/0012 edit 0010's block; 0017's context includes 0016's
scopes; 0021 deletes 0020's lines; 0022's context includes 0015/0018
includes and 0011 calls) and re-verifying with `git apply --check`
against the pinned MAME commit. `sync_engine_patches.py` throws on
unrecognized edits, so a subset build must adapt that verification
step. Formats are mixed: 0001–0003 are `diff --git` style, 0004–0022
are bare unified diffs.

| # | Purpose | Verdict | Evidence |
|---|---|---|---|
| 0001 | MinGW WASAPI link fix (`ksuser`, `<initguid.h>`) | NOT-REQUIRED (Windows-only) | Patch lines 9, 19, 23. Harmless to keep. |
| 0002 | Android SDL cross-build via `SDL_INSTALL_ROOT` | REQUIRED | Lines 12–14, 32. Without it the Android engine build shells out to a host `sdl-config`. |
| 0003 | `--NOASM=1` → `--NOASM=0` on `android-arm64` (DRC) | REQUIRED | Line 10; `build-engine.ps1:69-83` fails unless `drcuml.o` contains `make_drcbe_arm64`. |
| 0004 | Offline scene capture to files (diagnostics) | DIAGNOSTIC-ONLY (stack prerequisite for 0005) | Lines 15–27, 84. Gated on `VCOP2_SCENE_DIR`, 12 captures max (`model2_trace.h:20-25`). |
| 0005 | Live scene publication + input injection + host polling | REQUIRED | The core bridge. Input injection is 0005 only; `native_tick` latching and `poll_host` are REQUIRED. The `native_weapons` sub-hunk is UNKNOWN (U8 below). |
| 0006 | Cumulative-deadline throttle replacing stock `update_throttle` | UNKNOWN | Lines 20, 47–71. Mechanism is display-agnostic wall-clock math, not VR-only; necessity vs stock throttling unproven. |
| 0007 | Skip CPU rasterizer when host replays polygons | REQUIRED (conditional) | Lines 8–11. REQUIRED iff capture-replay is reused (it is). |
| 0008 | Per-polygon cull reasons; `raster_only` from test mode | VR-ONLY | Lines 7–11. Sole consumer gates the rescue on the immersive path (`model2_scene.h:104-106`); with `immersive=false` it is never consulted. REQUIRED if flat ever uses immersive rendering. |
| 0009 | Camera-widening CPU taps + native-panel raster-only | UNKNOWN | Lines 4–19 (taps at `0x506044`/`0x506140`, driven by `camera_fov`), 33–40 (`native_panel_requested()`). Split-or-keep undecided; with `camera_fov=0` the taps are inert. |
| 0010 | Full 512-wide sky bitmap into `frame->sky` | DIAGNOSTIC-ONLY (in final stack) | Lines 5–15. After 0012's gate it runs only for diagnostics; `gl_scene.cpp:159-166` falls back to background pixels when `sky_width==0`. |
| 0011 | `draw_full_period` (512×≤512 draw) | DIAGNOSTIC-ONLY | Lines 5, 17, 111–123. Only caller is 0010's sky block. Drop as a set with 0010+0012, or keep all three. |
| 0012 | Gate 0010's redundant render on `VCOP2_CAPTURE_FULL_SKY_PERIOD` | REQUIRED (conditional) | Lines 8–11. REQUIRED iff 0010 is kept; moot if {0010,0011,0012} drop as a set. |
| 0013 | Audio-feed diagnostics (5 s log when `VCOP2_VR` set) | DIAGNOSTIC-ONLY | Lines 21, 30–56. No behavior change. Activates if flat keeps `VCOP2_VR=1` — harmless. |
| 0014 | Persist audio report to `$VCOP2_XR_LOG.audio` | DIAGNOSTIC-ONLY | Lines 6–11. Inert unless `VCOP2_XR_LOG` is set. |
| 0015 | Opt-in native CPU timing scopes | DIAGNOSTIC-ONLY | Scopes compile away by default (`VCOP2_NATIVE_TIMING_AVAILABLE` defaults 0, `build-engine.ps1:41-52`) and are runtime-gated (`native_timing.h`). |
| 0016 | Opt-in screen-stage timing scopes | DIAGNOSTIC-ONLY | Same mechanism as 0015. |
| 0017 | Recycled snapshot pool for live frames | REQUIRED | Line 7. 19.14% wall-time reduction in a controlled Quest comparison (`docs/validation.md:77`). |
| 0018 | Trivial clip-accept fastpath | UNKNOWN | Lines 27–34. Capture happens before clipping, so benefit is raster/panel frames only. |
| 0019 | Pass `m_videocontrol` to `native_tick` (cadence datum) | DIAGNOSTIC-ONLY (the delta) | Line 7. The underlying `native_tick` call from 0005 is REQUIRED; the datum is observational. |
| 0020 | Camera-zoom snapshot at `screen_update` | STACK-ONLY | Deleted by 0021 (`0021:19-30`); net-zero in the final stack. Keep for apply order or drop {0020,0021} together. |
| 0021 | Latch camera zoom in `render_frame_start` | UNKNOWN | Lines 5–12. Nothing stereo/HMD-specific; whether flat keeps zoom at all is undecided. |
| 0022 | Hide white gun-strobe flash with a zero window mask | UNKNOWN (leaning keep) | Lines 12–20, 100–103. The flash is in the captured tile rasters, so a flat presenter shows it too — but the gate/fingerprint need the U7 check below. |

The engine build copies ALL of `native/*.h` into
`<engine>/src/lib/vcop2/` plus a generated `native_timing_config.h`
(`build-engine.ps1:44-52`). The minimal header subset is UNKNOWN —
copy all headers as upstream does until proven otherwise.

## 8. Phase 1 exclusions

- Player 2 (P2 hand/gun wiring, second touch zone).
- Settings UI polish (settings editing lives in Android UI, not in
  native code; the file format is reused as-is).
- Performance tuning (no perf work beyond the inherited patch stack;
  no timings are claimed anywhere).
- MAME build verification (no MAME build is possible in the overlay
  environment; the engine build is unproven until run on Windows).
- Immersive rendering, camera widening, zoom presentation, native
  held-gun models, haptics, and any VR/stereo/head-tracking behavior.
- The Android app layer itself (`ANDROID-GAP.md`).

## 9. UNKNOWN list (with proving checks)

Do not guess these. Each needs its stated check.

| # | Unknown | Proving check |
|---|---|---|
| U1 | Pacing: is swap-interval vsync enough, or is patch 0006 / stock throttling needed? | Device A/B: vsync-only vs pacer vs stock throttle, with audio enabled; watch native speed + audio starvation. |
| U2 | EGL surface acquisition/lifecycle from Java (who creates/destroys the `ANativeWindow`, rotation, backgrounding). | Android-layer design + device test: rotate, background/foreground, lock/unlock; GL must rebuild without crashing. |
| U3 | Panel vs immersive: is panel mode sufficient, and what would an immersive flat VP be? | Device test in panel mode first. Immersive flat VP has no derivation; do not attempt without one. |
| U4 | Touch `u/v` correctness (edge convention, ≤1px). | Device test: tap known targets (e.g. calibration screen, menu items); compare hit position vs crosshair. |
| U5 | First-tap / solo-hand convergence (`solo_hand_` transfer). | Device test: first tap after launch must fire at the tapped point, not eat the input. |
| U6 | Minimum trigger duration vs vblank latching. | Device test: short taps vs held taps; count registered shots. |
| U7 | Whether the host could `dlsym` SDL events (must not be relied on). | Not needed if the Java layer forwards touch (the design). Only investigate if Java forwarding proves impossible. |
| U8 | Live frame dimensions/offsets (are they 496×384? do `xoffset`/`yoffset` matter?). | Log `frame->width/height/xoffset/yoffset` on device in menu + in mission; check the pillarbox rect against the visible picture. |
| U9 | `dynamic_module` failure modes (what happens if the host lib fails to load/bind). | Force a bad `VCOP2_XR_LIBRARY` path on device; observe engine behavior (must fail loudly, not silently run headless). |
| U10 | `host_ready` readers beyond `poll_host`. | Grep the kept engine + host code for `host_ready` reads; confirm no reader blocks on it in a way the flat worker violates. |
| U11 | `Settings::background` effect. | Grep confirms no reader outside settings I/O/tests; device test with different values to confirm no visual change. |
| U12 | Haptics replacement (none in Phase 1). | Design decision, not a check: Phase 1 ships without haptics. |
| U13 | Patch subset decisions (0006 keep/drop, 0009 split, 0018, 0020/0021, 0022, `native_weapons` sub-hunk, header subset). | Per-patch checks in §7 + `git apply --check` on a fresh MAME clone at the pin; subset builds must re-run the adapted patch verification. |
| U14 | SDL source acquisition (no pinned URL/commit in upstream). | Decide a source matching SDL 2.33.0; verify by building + the engine link succeeding. Do not invent a URL. See `BUILD.md`. |
| U15 | Version pins for the flat build (NDK/SDK/build-tools/CMake/JDK/Gradle). | Decide explicitly; pins must agree across the build script, Gradle files, and CMake toolchain flags. See `BUILD.md`. |

## 10. Biggest risks

1. **The Android app layer does not exist.** Without it there is no
   APK at all: no surface, no touch forwarding, no env setup, no MAME
   launch. See `ANDROID-GAP.md`. This is the critical path.
2. **Nothing is compiled or device-tested.** The flat host, the patch,
   and the CMake file are written against header evidence only. The
   first Windows build will find whatever the headers did not say
   (include paths, EGL types, JNI signatures, STL behavior).
3. **Performance on phone hardware is unproven and likely poor.**
   Upstream's own numbers show sub-100% native speed in combat on a
   Quest 3S; G99-class phones are weaker. The port may run but not
   playably, and no perf work is in Phase 1 scope.
