package glaxium.snb.model.fbx.loaders;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dedupes in-flight {@code ModelLoader.add}/{@code ModelManager.loadModel}
 * work. Stock BBS {@code ModelLoader.add} blindly {@code offer}s every id, and
 * {@code getModel} only keeps a single null placeholder while loading — so a
 * mid-load {@code reload()} or watchdog {@code remove} clears that placeholder
 * and every subsequent render frame queues another full Assimp/BOBJ import of
 * the same model (dozens of "Model X was loaded!" lines and a flickering mesh).
 */
public final class ModelLoadInFlight
{
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private ModelLoadInFlight() {}

    /** @return true if this id was not already loading and is now marked. */
    public static boolean tryBegin(String id)
    {
        return id != null && !id.isEmpty() && IN_FLIGHT.add(id);
    }

    public static void end(String id)
    {
        if (id != null)
        {
            IN_FLIGHT.remove(id);
        }
    }

    public static boolean isLoading(String id)
    {
        return id != null && IN_FLIGHT.contains(id);
    }
}
