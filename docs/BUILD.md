# Build guide (Windows)

Adapted from upstream `docs/BUILD.md` and `tools/build-quest.ps1` /
`tools/build-engine.ps1` for the flat target. All upstream citations
are against the pinned commit `def8d32`. The flat Android app layer
is now provided in `flat/android/` and `flat/native/flat_jni.cpp`.

**No build-weight evidence exists.** Upstream states no build duration,
disk usage, RAM, or object count anywhere. Do not guess timings. The
full MAME source tree is cloned twice (source dir + work copy).

## 0. Ground rules

- **Windows only.** All steps run on Windows (upstream `docs/BUILD.md:9`).
- **No spaces in any work path.** Both upstream scripts abort otherwise
  (`build-engine.ps1:19`, `build-quest.ps1:30`). The fetch script
  enforces this.
- **No drive-letter defaults.** Every path is a parameter. The
  hardcoded author-local paths found in upstream scripts are listed in
  §5 with what to do instead; none of them appear in the overlay's
  scripts.
- **Pins are single-sourced.** `upstream-lock.json` holds the vcop2-vr
  and MAME pins. Toolchain pins (§1) must agree across the build
  scripts, the Gradle files, and the CMake toolchain flags (UNKNOWN
  U15 in `FLAT-PORT.md` — the values below are upstream's, kept until
  a flat decision says otherwise).

## 1. Prerequisites

Per upstream `docs/BUILD.md:9,40`:

- Windows, Git, MSYS2 MinGW64 (GCC/make, with MAME's build
  dependencies), MinGW64 `bin` on PATH.
- CMake 3.22+, Ninja, Python 3.12+, JDK 21.
- Android SDK: platform 36.1, build-tools 36.0.0, NDK
  `29.0.14206865`, ARM64, API 29.
- Gradle 9.4.1 (wrapper), AGP 9.2.1 (upstream `android/` pins).
- SDL 2.33.0 development source — **acquisition UNKNOWN** (see §4).
- No OpenXR SDK is needed. The flat build does not use OpenXR.

## 2. Fetch the overlay tree

```powershell
.\tools\fetch-upstream.ps1 -BuildDir <path-with-no-spaces>
```

This clones upstream vcop2-vr at the pinned commit into
`<path>\vcop2-vr`, verifies HEAD equals the pin, applies the patches
in `patches/series` order (after `git apply --check`), overlays the
`flat/` files, and verifies the resulting tree state. It fails loudly
on any mismatch. Delete `<path>\vcop2-vr` to re-fetch from scratch.
(`tools/fetch-upstream.sh` is the same procedure for bash.)

Resulting tree state (exact): modified `CMakeLists.txt`,
`native/gl_context.cpp`, `native/gl_context.h`; untracked
`native/flat_host.cpp`. Everything else is pristine upstream at the pin.

## 3. Engine steps (identical to upstream)

These steps are unchanged from the Quest build. They are described
here; the flat build scripts that automate them do not exist yet — run
the upstream scripts from the fetched tree, or adapt them per §5.

1. **Obtain the MAME source.** Clone
   `https://github.com/mamedev/mame.git` at tag `mame0289`
   (`engine-lock.json`). Upstream's script also accepts an
   `engine-source.bundle` (absent from the clone; ships only in the
   release source ZIP) and falls back to a sibling `../carnevil-vr/engine`
   checkout — **do not use the sibling fallback** (H12). Clone into
   your own directory only.
2. **Verify the pin.** Work-tree HEAD must equal
   `f34f02505e32c1993c6a782b6814232cbfc74e36`, else abort
   (`build-engine.ps1:38-41`).
3. **Copy headers + timing config** (`build-engine.ps1:44-52`). Copy
   ALL of `native/*.h` into `<engine-work>/src/lib/vcop2/`; generate
   `native_timing_config.h` with `VCOP2_NATIVE_TIMING_AVAILABLE` 0
   (production default). The minimal header subset is UNKNOWN — copy
   all headers as upstream does.
4. **Apply/verify patches** (`build-engine.ps1:53-54` →
   `tools/sync_engine_patches.py`). Phase 1 applies the FULL upstream
   stack of 22 patches as-is (see `FLAT-PORT.md` §7 for verdicts and
   why dropping middle patches needs rebasing). The sync script
   reconstructs cumulative states from the pinned commit and throws on
   unrecognized edits; a subset build must adapt that step.
5. **Build the engine** (`build-engine.ps1 -Platform Android`, MSYS2
   `make`, `-j<Jobs>` default 8, range 1–32):
   `SUBTARGET=vcop2 SOURCES=src/mame/sega/model2.cpp`, plus
   `USE_QTDEBUG=0 USE_TAPTUN=0 USE_PCAP=0 NO_USE_PORTAUDIO=1
   USE_BGFX=0 PYTHON_EXECUTABLE=<python basename> NOWERROR=1 SYMBOLS=0
   IGNORE_GIT=1`, Android additions `target android-arm64 NO_X11=1
   NO_USE_XINPUT=1 NO_OPENGL=1 USE_BGFX=0 NO_USE_PIPEWIRE=1
   NO_USE_PULSEAUDIO=1 SDL_INSTALL_ROOT=<sdl install, forward slashes>`
   with `ANDROID_NDK_HOME` set. The build verifies the ARM64 DRC symbol
   (`drcuml.o` must contain `make_drcbe_arm64`,
   `build-engine.ps1:69-83`). Output:
   `<work>/android-project/app/src/main/libs/arm64-v8a/libmain.so`.
   No `OPTIMIZE`/`ARCH`/`LTO` flags appear in upstream scripts — do not
   assume any.

## 4. SDL (unresolved source)

Upstream names SDL 2.33.0 development source (`CREDITS.md:15`) but pins
no URL or commit in the repo; `docs/BUILD.md:5` says the release ZIP
contains exact dependency sources, but the clone has no `source-deps/`
and no manifest. **Do not invent a URL.** The flat build needs a
decided SDL source matching 2.33.0, verified by building it and by the
engine link succeeding.

Once SDL source is decided, build it for Android (only if no prebuilt
`SdlRoot` with `lib/libSDL2.so` exists): CMake + Ninja with the common
Android flags (step 6 below), `-DSDL_SHARED=ON -DSDL_STATIC=OFF
-DSDL_TEST=OFF -DSDL_TESTS=OFF`, then `cmake --install` into `SdlRoot`
(upstream `build-quest.ps1:67-72`).

## 5. Hardcoded paths and sibling dependencies found upstream

Every item below was found in the upstream tree at the pin. Flat
scripts and docs must not repeat them.

| # | Location | Literal / default | Flat treatment |
|---|---|---|---|
| H1 | `tools/build-quest.ps1:2` | `MsysRoot` default `D:/msys64` | Parameter; never default to a drive letter. Require `usr/bin/make.exe` + `mingw64/bin/g++.exe` to exist. |
| H2 | `tools/build-quest.ps1:3` | `PythonPath` default `D:/Python314/python.exe` | Parameter, no default (or resolve `python.exe` from PATH). Python 3.12+. |
| H3 | `tools/build-quest.ps1:35` | SDK fallback `E:/AndroidSdk` | Parameter / `ANDROID_HOME` / `local.properties` only. |
| H4 | `tools/build-quest.ps1:37` | JDK fallback `E:/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2` | Parameter / `JAVA_HOME` only. JDK 21. |
| H5 | `tools/build-quest.ps1:38` | CMake default = VS2022 BuildTools CMake path, fallback SDK `cmake/3.22.1/bin/cmake.exe` | Parameter / PATH / SDK-cmake fallback. CMake 3.22+. |
| H6 | `tools/build-quest.ps1:39` | Ninja = SDK `cmake/3.22.1/bin/ninja.exe` | Parameter; keep the SDK-cmake fallback but pin the CMake version deliberately. |
| H7 | `tools/build-quest.ps1:24-25,40` | `SdlRoot` default `<workspace>/.cache/carnevil-port/sdl-android` (symlink-resolved) | No sibling-cache default. Explicit `SdlRoot` or `SdlSource` + build dir inside the flat work root. |
| H8 | `tools/build-quest.ps1:41` | `OpenXRLoader` default `<workspace>/.cache/carnevil-port/openxr-android/…` | DELETE — no OpenXR loader in the flat build. |
| H9 | `tools/build-quest.ps1:42` | `SdlSource` default `<local-development-tree>/death-crimson-ox-vr/core/deps/SDL` (placeholder) | Replace with a real pinned SDL acquisition (§4). |
| H10 | `tools/build-quest.ps1:43` | `OpenXRSource` default `<local-development-tree>/death-crimson-ox-vr/build-win/_deps/openxr_sdk-src` (placeholder) | DELETE — no OpenXR in the flat build. |
| H11 | `tools/build-quest.ps1:46-49,57,114` | Scattered pins: NDK `29.0.14206865`, build-tools `36.0.0`, platform `android-36.1`, `ANDROID_PLATFORM=android-29`, CMake dir `3.22.1` | Centralize as named pins at the top of the flat script. Script and Gradle pins must agree. |
| H12 | `tools/build-engine.ps1:15-16` | Engine fallback: sibling `../carnevil-vr/engine` if it has `.git` | DELETE. Clone from `engine-lock.json` only (or a supplied bundle). Keep the pin check. |
| H13 | `tools/build-engine.ps1:28` | `engine-source.bundle` in project root (absent from clone) | Clone from the network per `engine-lock.json`, or accept an explicit bundle path. Never assume it exists. |
| H14 | `tools/package.py:151` | `--apksigner` default `E:/AndroidSdk/build-tools/36.0.0/apksigner.bat` | Derive from resolved SDK + build-tools pin. |
| H15 | `docs/BUILD.md:29,43-47,54` | Example paths `D:/VRBuild/vcop2`, `D:/AndroidSdk`, `D:/jdk-21`, `C:/Tools/…`, `D:/msys64`, `C:/Python/python.exe` | Docs examples only. Flat docs use placeholders/parameters. |
| H16 | `docs/github/README.md:73-77` | Example paths `D:/Build/vcop2-engine`, `D:/Build/vcop2-host`, `D:/Build/vcop2-quest` | Docs examples only. Same treatment as H15. |
| H17 | `CMakeLists.txt:20-23` | Optional test fixture `research/camera-native-v1/program.bin`, guarded by `EXISTS` | `research/` is absent from the clone. Flat CMake must not require it (the overlay CMake has no test targets). |
| H18 | `CMakeLists.txt:34` | `VCOP2_CLIP_REFERENCE_SOURCE` default `build/quest/mame-source/src/mame/sega/model2_v.cpp` | Cache variable, overridable. Point at the flat work tree or drop the differential test. Test-only. |
| H19 | `tools/build-quest.ps1:27-31` | `WorkRoot` default `<project>/build/quest` (or symlinked `build` target + `/quest`) | Keep the short-path, no-spaces requirement; default to a plain project-relative path with no symlink chasing. |
| H20 | `tools/build-quest.ps1:44-45` | Default engine/host lib paths under `$WorkRoot` | Keep the pattern (derived from WorkRoot); host lib name follows the flat target (`libvcop2_xr.so`, kept). |

Verified absent from the upstream clone: `engine/`,
`engine-source.bundle`, `source-deps/`, `research/`, `.cache/`,
`private/`. No `/home/` or `hotd2` references were found.
`docs/BUILD.md:9` mentions "CarnEvil's full Python suite" (NumPy /
Pillow / ModernGL) in prose only — not a vcop2 build requirement.

## 6. Host library + staging (flat changes)

1. **Build the flat host** (replaces upstream `build-quest.ps1:84`).
   CMake `-S <fetched-tree> -B <work>/host-android`, `-G Ninja`,
   `-DCMAKE_BUILD_TYPE=Release`, NDK `android.toolchain.cmake`,
   `-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29
   -DANDROID_STL=c++_shared`, target `vcop2_xr` → `libvcop2_xr.so`.
   The overlaid `CMakeLists.txt` is Android-only; it builds the flat
   host + kept renderer files with no OpenXR include path and no
   Windows test targets.
2. **Verify the exports.** `llvm-nm` must show `vcop2_xr_start`,
   `vcop2_xr_stop`, `vcop2_xr_android_init` (upstream
   `build-quest.ps1:94-96`, unchanged — the flat host keeps the names).
3. **Stage native libraries** — FOUR, not five (upstream
   `build-quest.ps1:85-92` minus the OpenXR loader): `libmain.so`,
   `libSDL2.so`, `libc++_shared.so` (NDK sysroot), `libvcop2_xr.so`
   into `<work>/jniLibs/arm64-v8a/`. The packaging check
   (`tools/package.py:14` `REQUIRED_ANDROID_LIBS`) must drop
   `libopenxr_loader.so` for the flat build.
4. **Signing key** (upstream `build-quest.ps1:98-102` flow, identity
   TBD). If `private/vcop2.keystore` is missing, upstream generates
   one with `keytool` (RSA 3072, 10000-day validity, development
   passwords literal in the script). The flat package identity is TBD
   — if it changes from `com.vrlightgunhub.vcop2`, the keystore
   alias/DN, manifest, and `aapt2` check must all follow.

## 7. Gradle assemble — BLOCKED

Upstream flow (`build-quest.ps1:107-109`):
`android/gradlew.bat :app:assembleRelease --project-cache-dir
<work>/gradle-cache -Pvcop2NativeDir=<jniLibs parent> --no-daemon`,
with `JAVA_HOME`/`ANDROID_HOME` set; `--offline` optional after the
first assembly. App config: namespace/applicationId
`com.vrlightgunhub.vcop2`, `ndkVersion 29.0.14206865`, compileSdk 36.1,
minSdk 29, targetSdk 36, `arm64-v8a` only, release signing from
`../private/vcop2.keystore` (`android/app/build.gradle.kts`).

**This step cannot run from this overlay**: there is no flat Android
app (no activity, manifest, or Gradle files). See `ANDROID-GAP.md`
for what the Android layer must provide. Whether the flat port keeps
the Quest package ID, the SDK/NDK pins, and the SDL Java layer
(`org.libsdl.app.SDLActivity`, vendored upstream) is undecided.

## 8. Validate the APK

Once an APK exists: `tools/package.py --check-apk` (adapted lib set:
four ARM64 libs, ARM64 ELF + v2 signature) and `aapt2 dump badging`
showing the flat package name (upstream `build-quest.ps1:112-114`).

## 9. Device test (after a successful build)

1. Install the APK on a phone. Supply `vcop2.zip` + `segabill.zip`
   (user-supplied arcade ROMs) in the Downloads folder, as the Quest
   app expects (`-rompath`).
2. Launch: the game must show the attract/menu picture pillarboxed
   (native aspect, no stretch), with black bars, not a stretched or
   cropped frame.
3. Tap-to-shoot: tap a menu item — the shot must land at the tapped
   point on the FIRST tap (U5). Tap targets in-game and confirm the
   crosshair/hit matches the tap (U4).
4. RELOAD button + two-finger tap reload; START produces coin→start;
   pause via menu/focus loss; rotate and background/foreground the app
   without a crash (U2).
5. Aim+fire mode: drag moves the crosshair without firing; FIRE
   shoots.
6. Watch native speed and audio: upstream Quest numbers were 88–98%
   in combat; expect worse on weaker phones. Report what you see —
   no perf claim is made here.
