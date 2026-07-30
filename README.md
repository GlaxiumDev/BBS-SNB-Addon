# BBS FBX Addon (CML target)

Adds FBX model loading support to **BBS CML EDITION**, including:

- Geometry + skeletal animation (shared conversion core)
- Per-material textures - CML's own BOBJModel/renderer only supports one
  texture (or per-bone overrides) per model, so this addon mixes in a
  per-material draw-call split (`BOBJModelVAOMixinCML`) to recover it
- Shape keys - CML's own `BOBJModel.getShapeKeys()` is hardcoded empty, so
  `FBXShapeKeyModelCML` overrides it and `BOBJModelVAOMixinCML` does the
  actual per-vertex blending, mirroring how the BBS FS build of this addon
  already does the same thing against BBS FS's own BOBJModelVAO

All CML-specific behavior lives in this addon's own mixins - nothing here
modifies BBS CML EDITION's source. This is a CML-only build: it will NOT
work against BBS FS, and vice versa - the two mods diverged enough
(single-mesh vs multi-mesh models, different ModelInstance/texture APIs)
that they need separate jars/builds.

**Untested**: this was written by carefully cross-referencing BBS CML
EDITION's actual source (mixin target signatures, field names, the
`glDrawArrays` call this addon redirects), but never compiled or run in a
real Minecraft/Loom environment. Build it and report back anything that
doesn't compile or misbehaves at runtime.

## Building

1. Drop a released BBS CML EDITION jar into `libs/`.
2. `./gradlew build`
3. Output jar lands in `build/libs/`.

See `LICENSE.md` for license information.
