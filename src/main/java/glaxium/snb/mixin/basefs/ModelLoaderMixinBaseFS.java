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
 * <p>Fork-agnostic across Base and FS only: {@code ModelLoader.java} is
 * byte-for-byte identical between the Base 1.7.7-1.20.4 and FS reference
 * sources (checked directly) -- same private {@code manager}/{@code
 * thread}/{@code queue} fields, same {@code add}/{@code run} shape. CML
 * already ships its own multi-worker {@code ModelLoader} (4-8 threads,
 * {@code ConcurrentHashMap}-backed dedupe), so this mixin is deliberately
 * gated to Base/FS only (see {@code glaxium.snb.mixin.basefs} package
 * gating in {@link glaxium.snb.BBSFbxMixinPlugin}) -- applying it to CML as
 * well would just be a second, redundant worker pool racing the host's own.</p>
 *
 * <p>Doesn't touch the original {@code thread}/{@code queue} fields at all:
 * {@code add} is cancelled at {@code HEAD} before it ever reaches them, so
 * they stay permanently empty/null and harmless rather than needing to be
 * kept in sync with this mixin's own queue. Worker threads are started with
 * a method reference to {@link #bbsFbx$runWorker()} rather than {@code
 * this} (which would re-enter the mixed {@code run()} and require also
 * cancelling that), so the original {@code Runnable#run()} is simply never
 * invoked by anything this mixin does.</p>
 *
 * <p>Thread-safety of {@link ModelManager#models} (a plain, non-concurrent
 * {@code HashMap} on Base/FS) across these new concurrent workers is handled
 * separately by {@link ModelManagerConcurrencyMixinBaseFS} -- that mixin
 * must be present for this one to be safe to use with more than one worker.</p>
 */
@Mixin(value = ModelLoader.class, remap = false)
public abstract class ModelLoaderMixinBaseFS
{
    /**
     * Matches CML's own sizing (see its {@code ModelLoader} doc): enough
     * workers to actually parallelize opening a big morph category, capped
     * so a low-core-count machine doesn't oversubscribe, and floored so a
     * high-core-count machine still gets real concurrency.
     */
    @Unique
    private static final int bbsFbx$WORKER_COUNT = Math.max(4, Math.min(8, Runtime.getRuntime().availableProcessors()));

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
