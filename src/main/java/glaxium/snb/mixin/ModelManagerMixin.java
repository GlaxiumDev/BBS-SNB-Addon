package glaxium.snb.mixin;

import glaxium.snb.BBSFbxAddon;
import glaxium.snb.model.bbssnb.BBSSNBModelLoader;
import glaxium.snb.model.blockbuster.BlockbusterModelLoader;
import glaxium.snb.model.fbx.loaders.FBXModelLoadCache;
import glaxium.snb.model.fbx.loaders.FBXModelLoader;
import glaxium.snb.model.fbx.loaders.ModelLoadInFlight;
import glaxium.snb.model.fbx.loaders.SceneFormat;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.watchdog.WatchDogEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.List;

/**
 * Registers the BBS S&amp;B and FBX/glTF model loaders with {@link ModelManager}
 * and teaches it which paths under {@code models/} are importable scene files
 * ({@link SceneFormat}: {@code .fbx}, {@code .gltf}, {@code .glb}) that should
 * trigger a reload watch. Stock BBS already watches {@code .bbs.json} files.
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
        /* Stock order starts with BOBJ then Cubic. Keep BOBJ's established
         * priority, but claim marked BBS S&B packages before CubicModelLoader
         * mistakes their .bbs.json extension for the legacy cubic schema. */
        manager.loaders.add(Math.min(1, manager.loaders.size()), new BBSSNBModelLoader());
        manager.loaders.add(Math.min(2, manager.loaders.size()), new BlockbusterModelLoader());

        /* Slot our scene loader ahead of the rest. CML EDITION ships its own
         * GLTFModelLoader, which imports glTF/GLB with a single model texture
         * (no per-material split) -- placing ours after it (the old add-at-end
         * behavior) meant every glTF/GLB on CML went through the native
         * loader and never saw multi-texture. FBXModelLoader returns null for
         * any folder without an importable scene file, so running it earlier
         * never steals BOBJ/cubic/vox/.bbs.json models from the native
         * loaders. Found by simple-name so this compiles against Base/FS too
         * (neither has the CML class). */
        FBXModelLoader fbxLoader = new FBXModelLoader();
        int insertAt = -1;

        for (int i = 0; i < manager.loaders.size(); i++)
        {
            if (manager.loaders.get(i).getClass().getSimpleName().equals("GLTFModelLoader"))
            {
                insertAt = i;
                break;
            }
        }

        if (insertAt < 0)
        {
            insertAt = Math.min(3, manager.loaders.size());
        }

        manager.loaders.add(insertAt, fbxLoader);
        BBSFbxAddon.LOGGER.info("BBS S&B and FBX/glTF model loaders registered");
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
         * invalidate the live model (that re-queues parsing every frame). */
        if (path.contains("/textures/") || path.endsWith("bbs_fbx_materials.txt"))
        {
            info.setReturnValue(false);
            return;
        }

        if (path.startsWith(ModelManager.MODELS_PREFIX)
                && !path.contains("/animations/")
                && !path.contains("/shapes/")
                && BlockbusterModelLoader.isFolderModelJson(link))
        {
            info.setReturnValue(true);
            return;
        }

        if (path.startsWith(ModelManager.MODELS_PREFIX)
                && !path.contains("/animations/")
                && !path.contains("/shapes/")
                && BlockbusterModelLoader.isFolderStandaloneLegacyAsset(((ModelManager) (Object) this).provider, link))
        {
            info.setReturnValue(true);
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

    @Inject(method = "getAvailableKeys", at = @At("RETURN"), remap = false)
    private void bbsFbx$addLegacyStandaloneModels(CallbackInfoReturnable<List<String>> info)
    {
        List<String> keys = info.getReturnValue();

        if (keys == null)
        {
            return;
        }

        ModelManager manager = (ModelManager) (Object) this;

        for (String id : BlockbusterModelLoader.discoverStandalone(manager.provider))
        {
            if (!keys.contains(id))
            {
                keys.add(id);
            }
        }
    }

    @Inject(method = "accept", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$reloadLegacyStandalone(Path path, WatchDogEvent event, CallbackInfo info)
    {
        ModelManager manager = (ModelManager) (Object) this;
        Link link = manager.provider.getLink(path.toFile());

        if (link != null && SceneFormat.fromPath(link.path) != null)
        {
            /* Any file event on a scene file invalidates its cached import,
             * so the next load re-reads and re-parses it. Unchanged files
             * keep serving from FBXModelLoadCache, which is what makes F6
             * reloads cheap (stat check only, no file read or re-parse).
             * The game's own accept() below still removes the live
             * instance, since isRelodable() covers scene paths. */
            FBXModelLoadCache.invalidate(link.path);
            return;
        }

        if (link == null || !BlockbusterModelLoader.isStandaloneCandidatePath(link.path))
        {
            return;
        }

        if (event != WatchDogEvent.DELETED && !BlockbusterModelLoader.isStandaloneLegacyAsset(manager.provider, link))
        {
            return;
        }

        String id = BlockbusterModelLoader.standaloneId(link.path);
        ModelInstance instance = manager.models.remove(id);

        if (instance != null)
        {
            instance.delete();
        }

        info.cancel();
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
