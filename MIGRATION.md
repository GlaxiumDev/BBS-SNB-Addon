# BBS_FBX_for_CML → runs on BBS Base, BBS FS(*) and BBS CML EDITION

This is a "one addon jar, all forks" port, same goal as `BBS-Minema-Addon`'s
BBS Addon Engine port -- but it gets there differently, because the two
addons hit different problems.

## Why this doesn't actually use BBS Addon Engine

I read the engine's `docs/ADDONS.md` and its source before writing anything.
What it buys you: a guaranteed post-init timing signal for ~40 `Register*Event`
types, delivered identically on Base/FS (it does nothing on CML -- see below).
That's the right fix when an addon needs to register into UI factories or
camera-clip factories that only exist after BBS's own init has run, which is
`BBS-Minema-Addon`'s actual problem.

This addon's problem is different: it registers a model loader and an
importer by mixing directly into `ModelManager`/`Importers`, and those mixins
apply at class-load time, not at some "after host init" point -- there's no
race to fix. I checked the two hooks this addon's mixins use
(`ModelManager.setupLoaders` TAIL, `.isRelodable` HEAD) against the engine's
`RegisterModelLoadersEvent`, and its `registerLoader(...)` covers the first
but there's no event equivalent for the reload-path filtering the second one
does. So the mixins were staying regardless.

Once the mixins are staying, the question becomes "do they already work on
every fork, or not" -- and checked directly against real jars, they mostly
already did:

| Class this addon mixes into | Base 1.7.7-1.20.4 | CML 2.0-beta-1-1.20.4 | FS (Wemppy4/bbs-fs, checked directly) |
|---|---|---|---|
| `ModelManager` (`setupLoaders`/`isRelodable`/`loaders`/`reload`) | ✅ identical | ✅ identical | ✅ identical |
| `Importers` (`importers` field + `<clinit>`) | ✅ identical | ✅ identical | ✅ identical |
| `BOBJModelVAO` (`updateMesh`, all `@Shadow`'d fields) | ✅ identical | ✅ identical | ✅ identical |
| `cubic.model.bobj.BOBJModel` constructor | ✅ `(BOBJArmature, CompiledData, boolean)` | ✅ same | **diverges** -- `(BOBJArmature, List<CompiledData> meshes, boolean)`, one per mesh |
| `ModelInstance.render(...)` final parameter | **diverges** -- no texture param at all | **diverges** -- `Link defaultTexture` | **diverges** -- `Function<String,Link> textureResolver`, confirmed at `ModelInstance.java:654`, and its one call to `BOBJModelVAO.updateMesh(stencilMap)` at line 720 sits directly in `render()`'s body (inside a `for (BOBJModelVAO vao : vaos)` loop, but not delegated to a helper method), so `@Redirect` still reaches it correctly |

I cloned `Wemppy4/bbs-fs` and checked all of this against the real source
after writing the first version of this port -- everything in the FS column
above was previously an assumption inherited from the original CML addon's
own comments; all of it turned out to be correct, including the exact
`Function<String,Link>` signature `ModelInstanceMixinFS` already used. No
code changes were needed as a result of this check.

So: three of four mixin targets were **already** fork-agnostic. Renamed them
off "CML" (they're not CML-specific, never were really) and left everything
else alone. The one genuine divergence -- `ModelInstance.render(...)`'s final
parameter -- gets fork-gated mixin variants (see below), the same technique
`BBS-Minema-Addon`'s `MinemaMixinPlugin` uses, just applied to the one place
this addon actually needs it.

## What changed

### Renamed (content-identical, just dropped the misleading "CML" suffix)
- `mixin/ModelManagerMixinCML.java` → `mixin/ModelManagerMixin.java`
- `mixin/ImportersMixinCML.java` → `mixin/ImportersMixin.java`
- `mixin/BOBJModelVAOMixinCML.java` → `mixin/BOBJModelVAOMixin.java`

`FBXModelLoaderCML.java` / `FBXShapeKeyModelCML.java` / `FBXTextureResolverCML.java`
were **left named as-is** -- renaming them means touching every file that
imports them, which isn't safely doable without a compiler to catch mistakes
(see "What I could not verify" below). Their doc comments now note they cover
Base too, confirmed via the `BOBJModel` constructor check above.

### New: fork-gated `ModelInstance` mixin (the one real divergence)
- `mixin/base/ModelInstanceMixinBase.java` -- Base's no-texture-param `render`
- `mixin/fs/ModelInstanceMixinFS.java` -- FS's `Function<String,Link>` `render`
- `mixin/cml/ModelInstanceMixinCML.java` -- moved here unchanged (was `mixin/ModelInstanceMixinCML.java`)
- `BBSFbxMixinPlugin.java` -- new. `IMixinConfigPlugin` that applies exactly
  one of the three above, based on...
- `compat/BBSFork.java` -- new. Loader-metadata-only Base/FS/CML detection,
  adapted from `BBS-Minema-Addon`'s own `compat/BBSFork.java` (including its
  fix for the addon engine's own broken `BBSVersion.detectFS()` --
  `Class.forName("ReplayListEntry")` with no package always throws; the fully
  qualified name is used here instead).

### `bbs_fbx.mixins.json`
Added `"plugin": "elgatopro300.bbsfbx.BBSFbxMixinPlugin"`, renamed the three
always-on mixins, added the three fork-gated ones as
`"base.ModelInstanceMixinBase"` / `"fs.ModelInstanceMixinFS"` /
`"cml.ModelInstanceMixinCML"` (paths relative to the mixin `package`).
`shouldApplyMixin` returning `false` for the non-matching two means Mixin
never even checks whether their target method exists on the running fork --
so `defaultRequire: 1` doesn't fire for the ones that don't apply.

### `BBSFbxAddon.java`
Logic unchanged (still just `RegisterL10nEvent`, which is genuinely native on
every fork). Doc comment rewritten to explain *why* model-loader/importer
registration stays on the mixin path instead of also subscribing to
`RegisterModelLoadersEvent`/`RegisterImportersEvent` -- doing both would
double-register on CML (native) or on Base/FS with the engine installed.

### `fabric.mod.json`
Dropped "(CML)" from the name and "for BBS CML EDITION" from the
description; both were never accurate to what the mixins actually target.
No `bbs-addon-engine` dependency (hard or soft) -- this addon doesn't call
into it anywhere, so listing it would be misleading. `minecraft: "~1.20.4"`
and everything else in `depends` is untouched, per your call to stay on
1.20.4 rather than downgrade.

### Unchanged
Everything under `model/fbx/convert/`, `FBXConverter`, `FBXMesh`,
`FBXMetadata`, `FBXShapeKeyNames`, `FBXImporter`, `FBXCompiledData`,
`FBXModelLoadCache`, `IShapeKeyHolder`, `build.gradle`, `gradle.properties`,
`libs/`. None of it touches anything fork-specific.

## What I could not verify

Same honest caveat as `BBS-Minema-Addon`'s own MIGRATION.md: **nothing here
was compiled or run.** This sandbox has network access to GitHub (for cloning
the three repos) but not to Fabric/Minecraft/BBS's actual Maven/Modrinth
hosts, so there was no way to run `./gradlew build`. What I *did* do instead
of guessing:

- Cloned all four repos (`BBS_FBX_for_CML`, `BBS-ADDON-ENGINE`,
  `BBS-Minema-Ultimate`, and later `Wemppy4/bbs-fs`, BBS FS's actual source)
  fresh from GitHub rather than working from memory.
- Installed a JDK in-sandbox and inspected the actual `.class` files inside
  `libs/bbs-1.7.7-1.20.4.jar` (Base, pulled in from the Minema repo) and
  `libs/bbs-cml-edition-2.0-beta-1-1.20.4.jar` (already in this repo) --
  every Base/CML "confirmed"/"verified" claim above is a direct read of a
  real constant pool and field/method table, not an assumption.
- Once `Wemppy4/bbs-fs` became available, checked every FS-specific claim
  directly against its actual `.java` source (not decompiled bytecode this
  time, the real thing) -- see the table above. All of it held up.

Nothing here was compiled or run, though -- no Fabric/Minecraft/BBS Maven
access in this sandbox, only GitHub itself. Before shipping: build it
(`./gradlew build`, needs your usual Fabric/BBS Maven access) and install on
a Base, an FS, and a CML instance to confirm an FBX model still imports,
loads, renders, and blends shape keys correctly on all three.
