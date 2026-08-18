# BBS_S&B_Addon

**S&B** / **SNB** stands for **Skins and Bones**. The name is inspired by the
old (now inactive) Minecraft mod
[S&B](https://www.curseforge.com/minecraft/mc-mods/snb) — this project is not
affiliated with it.

Adds FBX and glTF (`.gltf` / `.glb`) model loading support to **BBS** -- runs unmodified on **BBS Base**,
**BBS FS**, and **BBS CML EDITION**, with a single mixin plugin
(`BBSFbxMixinPlugin`) picking the right fork-specific code at load time. No
BBS Addon Engine required.

## Features

- `BBS S&B.js`, a Blockbench 5 importer/exporter that writes one portable
  `model.bbs.json` with embedded textures, armatures, bind matrices,
  weight-painted skinning and sampled animations. Its Smooth Shading option
  is off by default; BBS applies the exported flat/smooth normal mode while
  preserving the same vertex weights. Importing that file restores an
  editable Blockbench project with its meshes, embedded textures, armature,
  weight paint, animations and shading mode
- Geometry + skeletal animation, converted from binary FBX 7.x, glTF 2.0 and
  GLB by the addon's pure-Java readers into BBS's cubic/BOBJ model format.
  No Assimp binaries or platform-specific native libraries are bundled.
  Separate glTF exports (`.gltf` + `.bin` + loose images) are imported with
  their referenced files alongside them
- Per-material textures -- BBS's own single-mesh renderers only support one
  texture (or per-bone overrides) per model; this addon splits multi-material
  FBX models into per-material draw calls so each material renders with its
  own texture, including under Iris/Sodium (PBR `_n`/`_s` companion maps
  tracked correctly per material, not just per model)
- Shape keys, blended per-vertex on the same per-material draw path
- Armature/skeletal animation baking from FBX's node hierarchy into BBS's
  bone format
- Old Emoticons-style skinned armor for `emoticons/steve`, `alex`, and the
  Steve/Alex Bend models (`steve_simple`/`alex_simple` internally). Armor is supplied by independent
  `armor.bobj` sidecars, rebound to the untouched model armature by bone
  name, hidden per empty equipment slot, and textured from the equipped
  vanilla/modded armor material. BBS Bend is the old Emoticons Simple+ body
  and uses its matching `props_simple.bobj` armor shell. An armor-specific
  geometric hinge applies the same sharp 90-degree elbow, knee, and waist bend
  as the body without relying on incompatible player-skin UV ranges. Alex has
  separately generated narrow sleeves aligned to the slim arm centers.
  Dyeable leather armor receives its item color.

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

This branch targets **Minecraft 1.20.1** (use a matching BBS 1.20.1 jar).

1. Drop the released jar for whichever BBS fork you're building against
   (Base, FS, or CML EDITION) into `libs/`.
2. `./gradlew build`
3. Output jar lands in `build/libs/`.

## Blockbench importer and exporter

Load `BBS S&B.js` as a local Blockbench plugin, then use **File → Export →
Export BBS S&B model**. Put the resulting `model.bbs.json` in the model's BBS
asset folder. The addon detects the `bbs_snb` package marker before BBS's
legacy cubic JSON loader; ordinary `.bbs.json`, OBJ, BOBJ, FBX, glTF and GLB
models keep using their existing loaders.

Use **File → Import → Import BBS S&B model** to reopen an exported
`model.bbs.json` as an editable Blockbench project. The importer reconstructs
meshes, textures, armatures, bone weights, animation clips and the exported
flat/smooth shading choice. Runtime export merges geometry where necessary,
so the imported mesh-object split may differ from the original project even
though the rendered geometry and rig data are retained.

## Credits

This project began as a fork of [BBS FBX Addon](https://github.com/ElGatoPro300/BBS-FBX-Addon)
by ElGatoPro300 (originally CML-only), since substantially rewritten to
support all three BBS forks and to add per-material rendering, shape keys,
and armature baking. See [LICENSE.md](LICENSE.md) for full attribution and
license terms.
