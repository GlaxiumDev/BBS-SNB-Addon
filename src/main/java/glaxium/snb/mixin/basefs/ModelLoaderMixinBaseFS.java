package glaxium.snb.mixin.basefs;

import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.ModelLoader;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * <b>Parked — not registered in {@code bbs_snb_addon.mixins.json}.</b>
 * Parallel model loads (4–8 workers) plus {@code ModelManager.reload()}
 * clearing the shared {@code HashMap} mid-load froze the whole desktop on
 * Linux under RAM pressure. Stock BBS's single loader thread is used again
 * until reload can be synchronized against the model map and concurrency can
 * be capped safely.
 *
 * <p>Replaces Base/FS's {@code ModelLoader} -- one dedicated {@code "BBS model
 * loader"} thread draining a queue -- with a small worker pool, so opening a
 * morph category with many distinct models doesn't load them strictly one
 * after another (the visible symptom: models "pop in" one-by-one, each
 * paying the full parse-plus-convert cost before the next even
 * starts).</p>
 *
 * <p>Must be paired with {@link ModelManagerConcurrencyMixinBaseFS}. Gated to
 * Base/FS only (CML already has its own multi-worker loader).</p>
 */
@Mixin(value = ModelLoader.class, remap = false)
public abstract class ModelLoaderMixinBaseFS
{
    /** Two workers: enough to overlap Assimp + convert, not enough to thrash. */
    @Unique
    private static final int bbsFbx$WORKER_COUNT = 2;

    @Shadow private ModelManager manager;

    @Unique
    private final Queue<String> bbsFbx$queue = new ConcurrentLinkedQueue<>();

    /** Dedupes queued/in-flight ids so a repeated {@code add(id)} for a model that's already
     *  queued or loading doesn't spawn a second redundant load of the same model. */
    @Unique
    private final Set<String> bbsFbx$loading = ConcurrentHashMap.newKeySet();

    /** Guarded by {@code this} -- only ever read/written from inside a {@code synchronized (this)} block. */
    @Unique
    private int bbsFbx$activeWorkers = 0;

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$addParallel(String key, CallbackInfo info)
    {
        info.cancel();

        if (key == null || key.isEmpty() || !this.bbsFbx$loading.add(key))
        {
            return;
        }

        this.bbsFbx$queue.add(key);
        this.bbsFbx$ensureWorkers();
    }

    @Unique
    private synchronized void bbsFbx$ensureWorkers()
    {
        while (this.bbsFbx$activeWorkers < bbsFbx$WORKER_COUNT && !this.bbsFbx$queue.isEmpty())
        {
            this.bbsFbx$activeWorkers++;

            Thread thread = new Thread(this::bbsFbx$runWorker, "BBS model loader-" + this.bbsFbx$activeWorkers);

            thread.setDaemon(true);
            thread.start();
        }
    }

    @Unique
    private void bbsFbx$runWorker()
    {
        while (true)
        {
            String model = this.bbsFbx$queue.poll();

            if (model == null)
            {
                synchronized (this)
                {
                    /* Re-check under the same monitor add()/ensureWorkers() uses:
                     * a key can land in the queue between the poll() above and
                     * this thread deciding to exit. Without this re-check, that
                     * key could be stranded -- ensureWorkers() would see this
                     * worker as still "active" (not yet decremented) and skip
                     * spawning a replacement, while this worker exits without
                     * ever having seen the new entry. */
                    if (this.bbsFbx$queue.isEmpty())
                    {
                        this.bbsFbx$activeWorkers--;

                        return;
                    }
                }

                continue;
            }

            try
            {
                this.manager.loadModel(model);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                this.bbsFbx$loading.remove(model);
            }
        }
    }
}
