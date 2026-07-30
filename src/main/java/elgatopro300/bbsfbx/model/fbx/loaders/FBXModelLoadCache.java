package elgatopro300.bbsfbx.model.fbx.loaders;

import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Skips re-running the native Assimp import and FBX -> BOBJData conversion
 * when a model's .fbx file content hasn't actually changed since it was
 * last loaded.
 *
 * <p><b>CRITICAL FIX:</b> This cache is now cleared on every
 * {@code ModelManager.reload()} (F6 press) via the mixin. Previously the
 * static map lived forever, so deleting a model and re-adding it with the
 * same name returned stale, mutated {@code BOBJData} from the previous
 * load — meshes would have corrupted names or missing material data,
 * causing BBS FS to save a single-material config to disk.</p>
 */
public final class FBXModelLoadCache
{
    private static final class Entry
    {
        final long hash;
        final BOBJData data;
        final Set<String> shapeKeyNames;
        final Set<String> texturedMaterials;

        Entry(long hash, BOBJData data, Set<String> shapeKeyNames, Set<String> texturedMaterials)
        {
            this.hash = hash;
            this.data = data;
            this.shapeKeyNames = shapeKeyNames;
            this.texturedMaterials = texturedMaterials;
        }
    }

    public static final class Cached
    {
        public final BOBJData data;
        public final Set<String> shapeKeyNames;
        public final Set<String> texturedMaterials;

        private Cached(BOBJData data, Set<String> shapeKeyNames, Set<String> texturedMaterials)
        {
            this.data = data;
            this.shapeKeyNames = shapeKeyNames;
            this.texturedMaterials = texturedMaterials;
        }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private FBXModelLoadCache() {}

    public static long hash(byte[] bytes)
    {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (crc.getValue() << 1) ^ bytes.length;
    }

    public static Cached get(String key, long hash)
    {
        Entry entry = CACHE.get(key);
        if (entry == null || entry.hash != hash)
        {
            return null;
        }
        return new Cached(entry.data, entry.shapeKeyNames, entry.texturedMaterials);
    }

    public static void put(String key, long hash, BOBJData data, Set<String> shapeKeyNames, Set<String> texturedMaterials)
    {
        CACHE.put(key, new Entry(hash, data, shapeKeyNames, texturedMaterials));
    }

    /** Drop one entry (e.g. when a specific model is deleted). */
    public static void invalidate(String key)
    {
        CACHE.remove(key);
    }

    /** Drop EVERY entry — called by ModelManagerMixin on reload. */
    public static void clear()
    {
        CACHE.clear();
    }

    public static int size()
    {
        return CACHE.size();
    }
}