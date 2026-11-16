# Attribution

This repo is an overlay: it contains no upstream source code. Everything
it builds on is credited here, and the licenses below govern the
corresponding upstream or third-party work. New files written for this
flat port are licensed GPL-2.0-or-later (see the license section).

## Upstream project

- **vcop2-vr** by vr4all-standalone — <https://github.com/vr4all-standalone/vcop2-vr>,
  pinned at commit `def8d32914911d03c9c8d24680bf21b3f869524c` (tag
  `v0.2.15`). The pinned MAME 0.289 engine integration, the Model 2
  scene capture and GLES replay renderer, the shared-runtime host
  bridge, and the cabinet-input path all come from upstream. The flat
  host in `flat/` reuses upstream's renderer and input policy; the
  patch in `patches/` modifies upstream's GL context. Upstream host
  code carries GPL-2.0-or-later SPDX headers; the tree also carries a
  MAME GPL-2.0 license file.

## Emulation and rendering lineage

- **MAME 0.289** by the MAME developers — <https://github.com/mamedev/mame.git>,
  pinned at commit `f34f02505e32c1993c6a782b6814232cbfc74e36` (tag
  `mame0289`). GPL-2.0. The engine that emulates the Model 2A arcade
  hardware. MAME source is fetched at build time; it is not in this
  repo or in the upstream tree.
- The Model 2 replay rules follow the BSD-licensed renderer by
  R. Belmont, Olivier Galibert, ElSemi, Angelo Salese, and
  Matthew Daniels, as credited upstream.
- The VR4ALL panel/theme, GLB reader, and baked Arcade gun in upstream
  were adapted from mikermak's Death Crimson OX / hotd2-vr work, and
  upstream gun effects were adapted from mikermak's VR4ALL
  Confidential Mission `xr_gun.cpp` (GPL-2.0-or-later, Copyright 2026
  mikermak). The flat port does not use the VR panel, gun models, or
  gun effects, but the lineage is recorded here because the renderer
  it reuses descends from the same tree.

## 3D models (upstream assets, not used by the flat build)

Upstream credits these models; they are listed for completeness because
they ship in the upstream tree the build fetches. The flat build loads
none of them.

- "Namco Arcade Gun" by Martoscar — CC BY 4.0.
- "Nintendo Zapper" by BIGDOGLOBAL — CC BY-ND 4.0.
- Meta Quest controller models by declan02530 — CC BY 4.0.

## Components

- **SDL 2.33.0** development source (zlib license) — the engine's OSD
  layer and the Android activity base. Source acquisition is unresolved;
  see `docs/BUILD.md`. Not in this repo.
- **GLM** (MIT) — math library used by the renderer and the flat host.
- **glad** (public domain / CC0) — GLES function loader.
- **cgltf** (MIT) and **stb_image / stb_truetype** (MIT / public
  domain) — upstream asset loading; not used by the flat host.
- **Roboto Bold** (Apache 2.0) — upstream font asset; not used by the
  flat build.
- **Khronos OpenXR SDK 1.1.43** — upstream VR dependency only. The
  flat build does not use OpenXR, does not build the OpenXR loader,
  and does not link it.

## Game

Virtua Cop 2 belongs to its owners. This port is not an official MAME
or Sega product. No game data, ROMs, or Sega assets are included in
this repo; the port loads user-supplied arcade ROMs at runtime only.

## License of new code in this repo

All new files in this overlay (`flat/`, `tools/`, and the patch files
in `patches/`) are licensed under the GNU General Public License
version 2 or later (GPL-2.0-or-later), matching upstream's host-code
license. Each new code file carries an `SPDX-License-Identifier:
GPL-2.0-or-later` header. Documentation files (`README.md`, `docs/`,
this file) are provided under the same license terms for simplicity.
No license notice from upstream is removed or weakened by this overlay:
patches are diffs against upstream files and do not touch upstream
license headers, and the fetch scripts apply them to an unmodified
upstream checkout.
