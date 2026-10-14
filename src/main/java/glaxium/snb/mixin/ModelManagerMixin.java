package glaxium.snb.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import glaxium.snb.model.bbssnb.BBSSNBModelLoader;
import glaxium.snb.model.fbx.loaders.FBXModelLoadCache;
import glaxium.snb.model.fbx.loaders.FBXModelLoader;
import glaxium.snb.model.fbx.loaders.ModelLoadInFlight;
import glaxium.snb.model.fbx.loaders.SceneFormat;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
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
    private static final Logger LOGGER = LoggerFactory.getLogger("BBS Fbx Addon");
    @Inject(method = "createLoaders", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void bbsFbx$addLoadersToCreateLoaders(CallbackInfoReturnable<List<IModelLoader>> info)
    {
        List<IModelLoader> list = info.getReturnValue();
        if (list != null)
        {
            list.add(Math.min(1, list.size()), new BBSSNBModelLoader());
            list.add(Math.min(2, list.size()), new FBXModelLoader());
        }
    }

    @Inject(method = "setupLoaders", at = @At("TAIL"), remap = false)
    private void bbsFbx$registerFbxLoader(CallbackInfo info)
    {
        ModelManager manager = (ModelManager) (Object) this;

        boolean hasOurFbx = false;
        for (IModelLoader loader : manager.loaders)
        {
            if (loader instanceof FBXModelLoader)
            {
                hasOurFbx = true;
                break;
            }
        }

        if (!hasOurFbx)
        {
            /* Stock order starts with BOBJ then Cubic. Keep BOBJ's established
             * priority, but claim marked BBS S&B packages before CubicModelLoader
             * mistakes their .bbs.json extension for the legacy cubic schema. */
            boolean hasSnb = false;
            for (IModelLoader loader : manager.loaders)
            {
                if (loader instanceof BBSSNBModelLoader)
                {
                    hasSnb = true;
                    break;
                }
            }

            if (!hasSnb)
            {
                manager.loaders.add(Math.min(1, manager.loaders.size()), new BBSSNBModelLoader());
            }

            /* Slot our scene loader ahead of native scene loaders. CML 2.1.1
             * adds native FBXModelLoader before GLTFModelLoader; either
             * can claim a scene before the addon's material data is populated. */
            FBXModelLoader fbxLoader = new FBXModelLoader();
            int insertAt = -1;

            for (int i = 0; i < manager.loaders.size(); i++)
            {
                IModelLoader loader = manager.loaders.get(i);
                if (!(loader instanceof FBXModelLoader))
                {
                    String name = loader.getClass().getSimpleName();
                    if (name.equals("FBXModelLoader") || name.equals("GLTFModelLoader"))
                    {
                        insertAt = i;
                        break;
                    }
                }
            }

            if (insertAt < 0)
            {
                insertAt = Math.min(2, manager.loaders.size());
            }

            manager.loaders.add(insertAt, fbxLoader);
            LOGGER.info("BBS S&B and FBX/glTF model loaders registered to manager.loaders");
        }

        glaxium.snb.compat.BBSFbxFS27Helper.registerL10n();
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

        if (path.contains("emoticons/mixamo/") || path.contains("/mixamo/"))
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

    @Inject(method = "accept", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$reloadFbxScene(Path path, WatchDogEvent event, CallbackInfo info)
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
