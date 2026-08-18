package glaxium.snb.model.fbx.loaders;

import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Skips re-running the Java scene parse and BOBJData conversion when a
 * model file's content hasn't actually changed since it was last loaded.
 *
 * <p><b>Reload speed:</b> entries survive {@code ModelManager.reload()}
 * (F6). A reload of an unchanged file only compares size and mtime
 * ({@link #get(String, File)}), which avoids re-reading and re-parsing
 * every scene file on each reload. The content-hash lookup
 * ({@link #get(String, long)}) remains as the fallback for jar-served
 * assets and for files whose mtime changed (or is unreliable).</p>
 *
 * <p><b>Correctness:</b> a stale/mutated entry can no longer be served
 * because:
 * <ul>
 *   <li>the stat lookup requires size <i>and</i> mtime to match, and the
 *       WatchDog invalidates the entry on any file event
 *       ({@link glaxium.snb.mixin.ModelManagerMixin}) — including the
 *       delete-then-re-add-with-the-same-name case that corrupted model
 *       data before this cache existed;</li>
 *   <li>the byte-hash lookup requires the content hash to match;</li>
 *   <li>the load pipeline no longer mutates the cached {@code BOBJData}
 *       (armature init is idempotent, mesh compile only reads).</li>
 * </ul>
 * </p>
 */
public final class FBXModelLoadCache
{
    private static final class Entry
    {
        final long hash;
        final long length;
        final long lastModified;
        final BOBJData data;
        final Set<String> shapeKeyNames;
        final Set<String> texturedMaterials;

        Entry(long hash, long length, long lastModified, BOBJData data, Set<String> shapeKeyNames, Set<String> texturedMaterials)
        {
            this.hash = hash;
            this.length = length;
            this.lastModified = lastModified;
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

    /**
     * Fast path for reloads: returns the cached entry when the file on disk
     * has the same size and mtime as when it was imported. No file read is
     * performed, which is what makes reloading many unchanged models cheap.
     *
     * @return the cached entry, or null when there is none, the file is not
     *         file-backed (jar asset), or its stat changed.
     */
    public static Cached get(String key, File file)
    {
        Entry entry = CACHE.get(key);

        if (entry == null || file == null)
        {
            return null;
        }

        if (file.length() != entry.length || file.lastModified() != entry.lastModified)
        {
            return null;
        }

        return new Cached(entry.data, entry.shapeKeyNames, entry.texturedMaterials);
    }

    /**
     * Content-hash lookup, used after the stat fast path missed (file
     * changed or not file-backed) but before paying for a full re-import.
     */
    public static Cached get(String key, long hash)
    {
        Entry entry = CACHE.get(key);

        if (entry == null || entry.hash != hash)
        {
            return null;
        }

        return new Cached(entry.data, entry.shapeKeyNames, entry.texturedMaterials);
    }

    public static void put(String key, long hash, BOBJData data, Set<String> shapeKeyNames, Set<String> texturedMaterials, File file)
    {
        long length = file == null ? -1 : file.length();
        long lastModified = file == null ? -1 : file.lastModified();

        CACHE.put(key, new Entry(hash, length, lastModified, data, shapeKeyNames, texturedMaterials));
    }

    /** Drop one entry (e.g. when a specific model is deleted). */
    public static void invalidate(String key)
    {
        CACHE.remove(key);
    }

    /** Drop EVERY entry -- a full cold start. */
    public static void clear()
    {
        CACHE.clear();
    }

    public static int size()
    {
        return CACHE.size();
    }
}
