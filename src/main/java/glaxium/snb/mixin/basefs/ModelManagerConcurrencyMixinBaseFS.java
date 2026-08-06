package glaxium.snb.mixin.basefs;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.ModelManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

/**
 * Makes {@link ModelManager#models} safe to read/write from multiple threads
 * at once, which {@link ModelLoaderMixinBaseFS}'s worker pool now does.
 *
 * <p>Base/FS's {@code models} field is a plain {@code HashMap} -- fine for
 * the original single-loader-thread design (one background writer, main
 * thread as the only reader), but not for several loader workers calling
 * {@code ModelManager.loadModel} concurrently: concurrent structural
 * modification (put) of a plain {@code HashMap} from multiple threads with
 * no synchronization is undefined behaviour in the JLS sense, and in
 * practice can corrupt the map's internal bucket/tree structure on a resize
 * race -- silently dropped entries at best, an infinite loop or
 * {@code ClassCastException} deep in {@code HashMap} internals at worst.</p>
 *
 * <p>Redirects the three {@code Map} calls Base/FS's {@code getModel}/{@code
 * loadModel} make ({@code containsKey}, {@code get}, {@code put}) to
 * synchronize on the map instance itself, rather than replacing the field
 * with a {@code ConcurrentHashMap} outright -- Base/FS's {@code getModel}
 * relies on being able to store a literal {@code null} value as an
 * "already queued" placeholder ({@code this.models.put(id, null)}), which
 * {@code ConcurrentHashMap} forbids (throws on a null value). Synchronizing
 * the existing {@code HashMap} keeps that null-placeholder behaviour intact
 * while still fixing the actual hazard (concurrent structural writes).</p>
 *
 * <p>This does not make the {@code containsKey} + {@code get} + {@code put}
 * sequence in {@code getModel} atomic as a whole (two threads can still both
 * observe "absent" and both queue the same id) -- that's fine here, since
 * {@link ModelLoaderMixinBaseFS} already dedupes an id that's queued or
 * in-flight via its own {@code loading} set, so a duplicate {@code
 * loader.add(id)} just collapses into the existing queue entry instead of
 * actually loading the model twice. What this mixin guarantees is the one
 * property that duplicate-load dedup can't: the map's internal structure
 * never gets corrupted by two threads mutating it at once.</p>
 *
 * <p>Gated to Base/FS only (see {@code glaxium.snb.mixin.basefs} package
 * gating in {@link glaxium.snb.BBSFbxMixinPlugin}): CML's {@code
 * ModelManager} already uses a {@code ConcurrentHashMap} for {@code models}
 * (plus a separate {@code failedModels} set instead of null placeholders),
 * so it needs none of this.</p>
 */
@Mixin(value = ModelManager.class, remap = false)
public abstract class ModelManagerConcurrencyMixinBaseFS
{
    @Redirect(
            method = {"getModel", "loadModel"},
            at = @At(value = "INVOKE", target = "Ljava/util/Map;containsKey(Ljava/lang/Object;)Z", remap = false),
            remap = false)
    private boolean bbsFbx$syncContainsKey(Map<String, ModelInstance> map, Object key)
    {
        synchronized (map)
        {
            return map.containsKey(key);
        }
    }

    @Redirect(
            method = {"getModel", "loadModel"},
            at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;", remap = false),
            remap = false)
    private Object bbsFbx$syncGet(Map<String, ModelInstance> map, Object key)
    {
        synchronized (map)
        {
            return map.get(key);
        }
    }

    @Redirect(
            method = {"getModel", "loadModel"},
            at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", remap = false),
            remap = false)
    private Object bbsFbx$syncPut(Map<String, ModelInstance> map, Object key, Object value)
    {
        synchronized (map)
        {
            return map.put((String) key, (ModelInstance) value);
        }
    }
}
