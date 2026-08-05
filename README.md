# BBS_S&B_Addon

Adds FBX model loading support to **BBS** -- runs unmodified on **BBS Base**,
**BBS FS**, and **BBS CML EDITION**, with a single mixin plugin
(`BBSFbxMixinPlugin`) picking the right fork-specific code at load time. No
BBS Addon Engine required.

## Features

- Geometry + skeletal animation, converted from FBX via Assimp
  (`org.lwjgl:lwjgl-assimp`) into BBS's native cubic/BOBJ model format
- Per-material textures -- BBS's own single-mesh renderers only support one
  texture (or per-bone overrides) per model; this addon splits multi-material
  FBX models into per-material draw calls so each material renders with its
  own texture, including under Iris/Sodium (PBR `_n`/`_s` companion maps
  tracked correctly per material, not just per model)
- Shape keys, blended per-vertex on the same per-material draw path
- Armature/skeletal animation baking from FBX's node hierarchy into BBS's
  bone format

## Supported forks

| Fork | Status |
|---|---|
| BBS Base | Supported |
| BBS FS | Supported |
| BBS CML EDITION | Supported |

Only one mixin variant per fork-divergent target is applied at runtime
(`compat/BBSFork` detects which fork is loaded from Fabric Loader metadata
during mixin bootstrap); everything else is fork-agnostic and shared.

## Building

1. Drop the released jar for whichever BBS fork you're building against
   (Base, FS, or CML EDITION) into `libs/`.
2. `./gradlew build`
3. Output jar lands in `build/libs/`.

## Credits

This project began as a fork of [BBS FBX Addon](https://github.com/ElGatoPro300/BBS-FBX-Addon)
by ElGatoPro300 (originally CML-only), since substantially rewritten to
support all three BBS forks and to add per-material rendering, shape keys,
and armature baking. See [LICENSE.md](LICENSE.md) for full attribution and
license terms.
