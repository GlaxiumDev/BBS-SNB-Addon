# Plan: Pose Keyframes → Animation (.bobj) — "Pose to Animation"

## Overview

Reverse of BBS's existing "animation to pose keyframes" feature: select pose keyframes in the
film replay's pose track, right-click → **Pose to animation**, name it in a modal box
(name field + OK/Cancel + X close), and the addon writes a standalone animation-only
`.bobj` to `config/bbs/assets/<modelId>/animations/<name>.bobj`. The animation is merged
into the model's `Animations` on the next model load and shows up in the animation
keyframe like any embedded animation — **only for that model** (not global like emotes).

## Answers to your questions

1. **Where do animations get saved?**
   `config/bbs/assets/<modelId>/animations/<name>.bobj` — the writable assets root
   (same root the FBX/GLTF importers write to via `BBSMod.getAssetsFolder()`).
   Poses carry no geometry/armature, so a single file holds only: bone names,
   duration, and keyframe values. The `.bobj` extension is deliberate — on disk all
   BBS loaders recognize, a valid but empty-ish `.bobj` is the universal carrier,
   and this addon's own loaders already read it back (BOBJ is the common base of
   all formats: FBX/glTF/BBS.json/geo.json/Blockbench bbs_snb all end up with the
   same runtime `Animations` object; poses are keyed by bone name, not model ID).

2. **Works with non-FBX models (.bbs.json, .geo.json, native .bobj, .obj)?**
   **Yes — this was the whole reason for choosing `.bobj`.** All model formats
   normalize to the same `Animations` structure at load time. See details in
   implementation step 4.

3. **Duplicate name?**
   Warn instead of overwrite. BBS convention for "two options + cancel" is
   `UIConfigPanel` — it renders an icon, a message, and exactly N buttons, and
   closes on backdrop/Escape/X (all three act as cancel, matching BBS's existing
   `UIConfigPanel`/`UIConfigOpenOverlayPanel` UX). Proposed: overwrite-confirmation
   dialog with an **Overwrite** button (replaces the existing file) and a
   **Cancel** button. This reuses the existing overlay-close pattern and avoids
   inventing a "Cancel" action BBS doesn't have.
   An alternative would be auto-append `_2`, `_3`... no confirmation needed, but
   overwrite-with-confirm is closer to the BBS preset UX.

## UX design

1. On the pose track in the replay's keyframe editor, right-click → new menu entry
   **Pose to animation** (Icons.POSE, new `UIKeys` entry
   `FILM_REPLAY_CONTEXT_POSE_TO_ANIMATION`).
2. Modal `UIPromptOverlayPanel` pattern: name textbox + **OK** button + close **X**
   (Escape/backdrop = cancel). Textbox pre-selected if the selection already has
   a pose. Cancel does nothing.
3. On OK: if the file already exists → `UIConfigPanel` overwrite confirmation.
   On confirm, or if no duplicate → write the file, update the model, toast.
4. Success toast: "Saved animation 'name.bobj' for model 'steve'" via
   existing toast infrastructure. No toast on cancel.

## Architecture

### 1. Data model: saved animation structure

A saved animation is a bone channel map (like `Animation.parts`):

```
BoneName → {
  channels (one per x/y/z axis):
    - translate (from Pose.translate.x/y/z)
    - rotate    (from Pose.rotate.x/y/z — pose values are already radians in the
                 PLAYER-pose space; verify experimentally with a test bake that
                 BOBJModel.applyPose treats them as local-space; if degrees, add
                 a deg→rad conversion at write time)
    - scale     (from Pose.scale.x/y/z — raw scale, no -1 adjustment)
  each channel: list of (tick, value) keyframes
}
Duration = last keyframe tick
```

Because the addon bakes FBX animations via `FBXAnimationBaker`, the reverse pipeline
is: `Pose` → `BOBJAction`/`BOBJGroup`/`BOBJChannel`/`BOBJKeyframe` → BOBJ text.

### 2. Sampling the selected pose keyframes

The context-menu entry has `sheet.channel` (the `KeyframeChannel<Pose>`) and
`sheet.selection` in scope. Two realistic modes:

- **Keyframe-only mode** (recommended, mirrors the `onlyKeyframes=true` mode of
  animation-to-pose): for each selected keyframe on the pose sheet, evaluate the
  pose value at that keyframe's tick. `KeyframeChannel` has segment-based
  interpolation (findSegment/interpolateSegment used by the animator); we sample at
  each selected keyframe's tick so each keyframe produces exactly one animation
  keyframe — trivially round-trippable and small.
- **Interpolated mode** (stretch goal): sample every tick in [first, last] with a
  user-chosen step (like animation-to-pose's `length`/`step` trackpads) when the
  user wants to capture smooth motion between poses. Keep this out of the first
  pass; can be added later with the same writer.

Implementation step 1 is to verify the exact playback interpolation API for
`Pose` channels (how `ActionPlayback`/`Replay` evaluates a Pose at an arbitrary
tick) by grepping reference-sources at implementation time — use only the public
API, no hacks.

### 3. Writing the .bobj

New class `glaxium.snb.anim.PoseAnimationBobjWriter` producing:

```
o dummy
v 0 0 0
an <name>
ao <bone>
ag location 0
kf <tick> <value>
ag location 1
kf <tick> <value>
...
```

Rules:
- `ag <path> <index>` uses the STANDARD one-channel-per-index style
  (`location`/`rotation`/`scale` + index 0/1/2) — understood by every loader on
  every fork (stock `BOBJLoader.readData` + `copyKeyframes`), including consumers
  outside this addon.
- Skip channels with zero keyframes.
- Emit `o dummy` + `v 0 0 0` so the file is a parseable (geometrically inert)
  `.bobj`.
- Filename sanitize: strip path separators and filesystem-reserved chars.

### 4. Loader hook (the part that makes it work for ALL formats)

All model formats (native `.bobj`/`.obj`, `.bbs.json`, `.geo.json`, FBX, glTF/GLB,
Blockbench bbs_snb) funnel through `ModelManager.loadModel(String id)` (identical
signature confirmed on bbs-mod, bbs-fs, bbs-cml). One mixin on that method:
after the loader returns a non-null `ModelInstance`, scan
`Link.assets("models/" + id + "/animations")` for `.bobj` links, parse each with
`BOBJLoader.readData`, convert to `Animation` objects (reusing the same convert
utility the loaders use — the addon already `@Invoker`s `convertAnimations` in
`mixin/basecml/BOBJModelLoaderMixinBaseCML`; mirror that here so all forks share
one code path), and merge into `instance.animations`.

Why this works uniformly:
- `ModelInstance.animations` has one single shape across forks; merging is a map
  put per animation name.
- Poses are keyed by bone name, so a saved steve animation still looks right if
  bone names stay the same (e.g. a different format / retargeted armature).
- The existing `/animations/` folder exclusion in `ModelManager.isRelodable`
  already covers us: `getAsset` reads work fine, only live-reload watching skips
  that folder — which is exactly the standard behavior for external files anyway.

### 5. Applying the saved animation at replay time — no changes needed

The replay's animation-track dropdown picks the name from `model.animations`.
`Animator.setup` and `ActionPlayback` do per-tick bone pose application
(verified in research: `BOBJModelAnimator.animate` samples each channel through
`CubicModelAnimator.interpolateList` every render frame). Nothing to do here.

### 6. After save: reload the model for that form

After writing the file, clear that model's cache entry and notify the model form
to reload. The add-on's existing importers (`FBXImporter`/`GLTFImporter`) already
have this pattern — find the exact call they use and reuse it. The change is
one method call after the file write, same thread.

## Implementation steps (in order)

1. **Verify the pose-playback interpolation API** — find how `ActionPlayback`
   evaluates `KeyframeChannel<Pose>` at a given tick (public methods only).
2. Create `PoseAnimationBobjWriter` (+ `PoseAnimationConverter` if the
   deg→rad / scale adjustments belong on a data-layer class rather than direct
   writes). Write a small CHECKED text-format round-trip parser test (BOBJ
   syntax → parsed channels must equal what was written).
3. Wire the `ModelManager.loadModel` mixin (all three forks, one class — the
   `BBSFbxMixinPlugin` fork dispatch is already in place). Merge external
   `.bobj` animation files into `ModelInstance.animations`.
4. UI: two overlays + the context-menu entry.
   - `UIPoseToAnimationOverlayPanel` (name textbox + OK + X, modeled on
     `UIPromptOverlayPanel` — which already supports a custom callback on
     confirmation; if stock `UIPromptOverlayPanel` needs a customizable OK
     button, subclass or extend).
   - `UIConfirmOverwriteOverlayPanel` for duplicate names — re-use
     `UIConfigPanel`-style button layout (N buttons, backdrop/Esc/X = cancel).
   - New `UIKeys` entries: `FILM_REPLAY_CONTEXT_POSE_TO_ANIMATION` plus
     overlay title/message/cancel/overwrite keys.
   - Register the entry via mixin on `UIReplaysEditor.updateChannelsList()`
     (or the exact injection point where the existing "animation to pose"
     entry is created on each fork — confirm signature stability; the add-on
     already has `UIReplaysEditorMixin{Base,CML}` redirects for material
     sheets to copy from; base and CML differ but base and FS appear identical
     in this area).
5. Trigger the model reload after save (step 6 of the architecture above).
6. Toast on success.
7. Manual verification per fork (steve/Blockbench/native/FBX model) + overwrite
   flow + README.md bullet.

## Files likely touched

- `src/main/java/glaxium/snb/anim/PoseAnimationBobjWriter.java` (new)
- `src/main/java/glaxium/snb/anim/PoseAnimationConverter.java` (new, if needed)
- `src/main/java/glaxium/snb/mixin/ModelManagerMixin.java` (extend) or a new
  `ModelManagerLoadModelMixin.java`
- `src/main/java/glaxium/snb/ui/UIPoseToAnimationOverlayPanel.java` (new)
- `src/main/java/glaxium/snb/ui/UIConfirmOverwriteOverlayPanel.java` (new)
- One new/extended `UIReplaysEditor` mixin per fork target (Base+FS share, CML
  separate) to add the context-menu entry
- `src/main/resources/assets/bbs_fbx/lang/en_us.lang` (new keys)
- `README.md` (one bullet)
