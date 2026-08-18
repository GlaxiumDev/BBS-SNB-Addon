package glaxium.snb.mixin.basefs;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.ModelManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 * <b>Parked — not registered in {@code bbs_snb_addon.mixins.json}.</b>
 * Only needed alongside {@link ModelLoaderMixinBaseFS}; that parallel loader
 * is disabled because concurrent model loads plus an unsynchronized
 * {@code reload()} froze Linux desktops. See that class's doc for details.
 *
 * <p>Gated to Base/FS only (CML already uses {@code ConcurrentHashMap}).</p>
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

    /**
     * {@code reload()} used to iterate {@code models.values()} then
     * {@code clear()} with no lock while loader workers {@code put}. Snapshot
     * and clear atomically; VAO {@code delete()} then runs on the snapshot
     * outside the lock so GL cleanup does not block new loads.
     */
    @Redirect(
            method = "reload",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;values()Ljava/util/Collection;", remap = false),
            remap = false)
    private Collection<ModelInstance> bbsFbx$snapshotAndClear(Map<String, ModelInstance> map)
    {
        synchronized (map)
        {
            Collection<ModelInstance> snapshot = new ArrayList<>(map.values());
            map.clear();
            return snapshot;
        }
    }

    @Redirect(
            method = "reload",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V", remap = false),
            remap = false)
    private void bbsFbx$reloadClearAlreadyDone(Map<String, ModelInstance> map)
    {
        /* Cleared in {@link #bbsFbx$snapshotAndClear}. */
    }
}
