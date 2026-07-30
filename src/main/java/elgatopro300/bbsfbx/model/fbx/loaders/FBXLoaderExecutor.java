package elgatopro300.bbsfbx.model.fbx.loaders;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared worker pool for the part of FBX loading that's both CPU-heavy and
 * safe to parallelize: compiling a model's individual meshes into packed
 * renderer arrays ({@link FBXMeshCompiler#compile}), which only reads from
 * the already-built, unchanging {@code BOBJData} vertex/texture/normal
 * pools and writes into its own local arrays - no shared mutable state
 * between meshes.
 *
 * <p>BBS FS's own {@code ModelLoader} already keeps model loading off the
 * render thread (a dedicated background thread pulls model ids off a
 * queue), so this doesn't affect that; it just lets one model's independent
 * per-mesh work spread across the machine's other cores too, instead of
 * compiling meshes one at a time within that single load.</p>
 *
 * <p>Daemon threads, so they never hold the JVM open on their own.</p>
 */
final class FBXLoaderExecutor
{
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    static final ExecutorService POOL = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            (ThreadFactory) runnable ->
            {
                Thread thread = new Thread(runnable, "BBS FBX mesh compiler " + THREAD_COUNTER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
    );

    private FBXLoaderExecutor() {}
}
