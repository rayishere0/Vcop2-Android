# Virtua Cop 2 — flat Android touch port (overlay)

A flat (non-VR) Android touch port of Virtua Cop 2, built as an **overlay**
on top of [vcop2-vr](https://github.com/vr4all-standalone/vcop2-vr).
The game itself runs in pinned MAME 0.289 emulating the Model 2A arcade
hardware; the overlay replaces only the VR host with a single flat GLES
view driven by touch input.

**This repo contains no vcop2-vr source code.** It holds only new
flat-port code, unified-diff patches, fetch/build scripts, and docs.
The build fetches upstream itself, at the pinned commit, and applies
this overlay on top. See `docs/FLAT-PORT.md` for the architecture and
`docs/BUILD.md` for the build steps.

## Status: not built, not tested

- The native flat host (`flat/native/flat_host.cpp`) and the EGL
  window-surface patch are written but **not compile-checked and not
  device-tested**. No MAME build is possible in the environment that
  produced this overlay.
- **The Android app layer is implemented.** Activity, manifest, Gradle
  configuration, JNI glue, touch overlay, and surface provider are now
  provided in `flat/android/` and `flat/native/flat_jni.cpp` (see `docs/ANDROID-GAP.md`).
- Every unverified claim is marked UNKNOWN in `docs/FLAT-PORT.md` with
  the check that would prove it.

## Upstream pin

- vcop2-vr: <https://github.com/vr4all-standalone/vcop2-vr> at commit
  `def8d32914911d03c9c8d24680bf21b3f869524c` (tag `v0.2.15`).
- MAME: <https://github.com/mamedev/mame.git> at commit
  `f34f02505e32c1993c6a782b6814232cbfc74e36` (tag `mame0289`),
  subtarget `vcop2`. Copied from upstream `engine-lock.json`.
- Both pins live in `upstream-lock.json`. The fetch scripts verify the
  vcop2-vr HEAD after cloning and fail loudly on any mismatch.

## Bring your own ROMs

This port loads **user-supplied arcade ROMs at runtime only**: `vcop2.zip`
and `segabill.zip` (31 ROMs, validated by size/CRC32/SHA-1). No game
data, ROMs, or Sega assets are in this repo or in upstream. The PC
version of Virtua Cop 2 (VCop2.exe and friends) does **not** work with
this port — it needs the arcade ROM set, not the PC game files.

## Layout

| Path | Contents |
|---|---|
| `patches/` | Unified diffs against the pinned upstream commit, plus `series` (apply order). Generated from real edits, never hand-written. |
| `flat/` | New files only: the flat GLES host and its Android-only CMake file. Overlaid onto the upstream tree by the fetch scripts. |
| `tools/` | `fetch-upstream.ps1` / `fetch-upstream.sh`: clone, verify HEAD, apply patches, overlay `flat/`. |
| `docs/` | `FLAT-PORT.md` (architecture, contract, patch table, touch spec), `BUILD.md` (Windows build steps), `ANDROID-GAP.md` (the missing app layer). |
| `upstream-lock.json` | Pinned upstream + MAME commits. |
| `ATTRIBUTION.md` | Credits and license statement. |

## Build overview

1. Run `tools/fetch-upstream.ps1 -BuildDir <path>` on Windows (no
   spaces in the path). It clones upstream at the pin, applies the
   patches, and overlays `flat/`.
2. Follow `docs/BUILD.md`: fetch MAME at its pin, apply upstream's 22
   engine patches, build SDL + the engine + the flat host, stage the
   native libraries, and assemble the APK — once the Android app layer
   exists (see `docs/ANDROID-GAP.md`).

## Touch controls (design)

- **Tap-to-shoot** (default): tap fires at that point; the crosshair
  moves there. **Aim+fire** mode: drag moves the crosshair, a FIRE
  button shoots. Switchable in settings.
- Large **RELOAD** button plus two-finger tap reload. Start, pause,
  and service/test controls. UI buttons are hit-tested before the game
  so they never fire the gun. Multi-touch: reload while aiming with the
  other thumb. Full spec: `docs/FLAT-PORT.md`.

## Performance expectation

Upstream measured 88–98% native speed in live combat on a Quest 3S
(Adreno 740), with audio starvation following. Weaker phones — e.g.
Mali G99-class — are likely too slow. This is an expectation from
upstream's numbers, not a device-tested fact for this port.

## License and attribution

New code in this repo is GPL-2.0-or-later (SPDX headers on every new
file). Upstream vcop2-vr, MAME, and all component credits are listed
in `ATTRIBUTION.md`. Virtua Cop 2 belongs to its owners; this is not
an official MAME or Sega product.
