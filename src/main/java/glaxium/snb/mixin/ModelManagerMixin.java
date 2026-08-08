package glaxium.snb.mixin;

import glaxium.snb.BBSFbxAddon;
import glaxium.snb.model.fbx.loaders.FBXModelLoadCache;
import glaxium.snb.model.fbx.loaders.FBXModelLoader;
import glaxium.snb.model.fbx.loaders.ModelLoadInFlight;
import glaxium.snb.model.fbx.loaders.SceneFormat;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.resources.Link;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Registers the Assimp model loader with {@link ModelManager} and teaches it
 * which paths under {@code models/} are importable model files ({@link
 * SceneFormat}: {@code .fbx}, {@code .gltf}, {@code .glb}) that should trigger
 * a reload watch.
 *
 * <p>Does <b>not</b> wipe {@link FBXModelLoadCache} on {@code reload()}:
 * entries are content-hashed, so unchanged files skip Assimp after F6. The
 * old blanket clear forced a full reparse of every model and made reload
 * feel hung.</p>
 *
 * <p>Fork-agnostic on purpose: {@code setupLoaders}, {@code isRelodable(Link)},
 * the public {@code loaders} field, and {@code reload()} all have the exact
 * same shape on BBS Base, BBS FS and BBS CML EDITION -- verified directly
 * against the Base 1.7.7-1.20.4 and BBS CML EDITION 2.0-beta-1-1.20.4 jars,
 * and matching what the FS-targeted sibling addon already relies on. There is
 * no per-fork divergence here, so a single mixin (not three) covers all of
 * them; no BBS Addon Engine event exists for either hook it uses ({@code
 * setupLoaders} TAIL, {@code isRelodable} HEAD), so this stays a mixin rather
 * than moving to an event subscription.</p>
 */
@Mixin(value = ModelManager.class, remap = false)
public class ModelManagerMixin
{
    @Inject(method = "setupLoaders", at = @At("TAIL"), remap = false)
    private void bbsFbx$registerFbxLoader(CallbackInfo info)
    {
        ModelManager manager = (ModelManager) (Object) this;
        manager.loaders.add(new FBXModelLoader());
        BBSFbxAddon.LOGGER.info("FBX/glTF model loader registered");
    }

    @Inject(method = "isRelodable", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$fbxIsRelodable(Link link, CallbackInfoReturnable<Boolean> info)
    {
        String path = link.path;
        if (path == null)
        {
            return;
        }

        /* Sidecar writes from texture extract / material folders must not
         * invalidate the live model (that re-queues Assimp every frame). */
        if (path.contains("/textures/") || path.endsWith("bbs_fbx_materials.txt"))
        {
            info.setReturnValue(false);
            return;
        }

        if (path.startsWith(ModelManager.MODELS_PREFIX)
                && !path.contains("/animations/")
                && !path.contains("/shapes/")
                && SceneFormat.fromPath(path) != null)
        {
            info.setReturnValue(true);
        }
    }

    /**
     * Always clear the in-flight mark when {@code loadModel} returns so a
     * cancelled duplicate {@code add} can run again after a real failure, and
     * so a successful load does not permanently block reloads of that id.
     */
    @Inject(method = "loadModel", at = @At("RETURN"), remap = false)
    private void bbsFbx$endInFlight(String id, CallbackInfoReturnable<ModelInstance> info)
    {
        ModelLoadInFlight.end(id);
    }
}
