package glaxium.snb.model.fbx.loaders;

import org.lwjgl.BufferUtils;
import org.lwjgl.assimp.AIFileIO;
import org.lwjgl.assimp.AIPropertyStore;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Drives Assimp's import of a model file into an AIScene, with the property
 * store setup (pivot handling, scale factor) and post-process flags BBS needs
 * per {@link SceneFormat}.
 *
 * <p>All import entry points take {@link #IMPORT_LOCK}: Assimp's native
 * importer is heavy and not safe to run on several models at once. Concurrent
 * imports (plus a {@code ModelManager.reload()} clearing the model map mid-
 * load) have frozen the whole desktop under memory pressure on Linux.</p>
 */
public final class FBXAssimpImporter
{
    /**
     * Serializes every Assimp import / release of the property store. Keep
     * scene release ({@code aiReleaseImport}) outside this lock so a slow
     * converter doesn't block the next import -- only the native parse does.
     */
    private static final Object IMPORT_LOCK = new Object();

    private FBXAssimpImporter() {}

    /**
     * @return the imported scene, or null (with the Assimp error already
     * logged) if the import failed.
     */
    public static AIScene importScene(byte[] bytes)
    {
        return importScene(bytes, SceneFormat.FBX);
    }

    /**
     * Imports straight from bytes. Self-contained formats only in practice --
     * a {@code .gltf} that keeps its buffers/images in sibling files can't
     * resolve them without a filesystem, which is why
     * {@link #importScene(File, SceneFormat)} is preferred whenever the asset
     * is a real file on disk.
     */
    public static AIScene importScene(byte[] bytes, SceneFormat format)
    {
        ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
        buffer.put(bytes);
        buffer.flip();

        synchronized (IMPORT_LOCK)
        {
            AIPropertyStore store = createStore(format);

            try
            {
                return check(Assimp.aiImportFileFromMemoryWithProperties(buffer,
                        format.postProcessFlags(),
                        format.hint(),
                        store), format);
            }
            finally
            {
                Assimp.aiReleasePropertyStore(store);
            }
        }
    }

    /**
     * Imports through Assimp's own file IO so relative references resolve
     * against the model's folder. Required for the common "separate" glTF
     * export ({@code .gltf} + {@code .bin} + loose image files) and harmless
     * for the self-contained ones.
     */
    public static AIScene importScene(File file, SceneFormat format)
    {
        synchronized (IMPORT_LOCK)
        {
            AIPropertyStore store = createStore(format);

            try
            {
                return check(Assimp.aiImportFileExWithProperties(file.getAbsolutePath(),
                        format.postProcessFlags(),
                        (AIFileIO) null,
                        store), format);
            }
            finally
            {
                Assimp.aiReleasePropertyStore(store);
            }
        }
    }

    private static AIPropertyStore createStore(SceneFormat format)
    {
        AIPropertyStore store = Assimp.aiCreatePropertyStore();

        assert store != null;

        if (format.fbxProperties())
        {
            Assimp.aiSetImportPropertyInteger(store, Assimp.AI_CONFIG_IMPORT_FBX_PRESERVE_PIVOTS, 0);
        }

        Assimp.aiSetImportPropertyFloat(store, Assimp.AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY, 1.0f);

        return store;
    }

    private static AIScene check(AIScene scene, SceneFormat format)
    {
        if (scene == null)
        {
            System.err.println("Error loading " + format.name() + " model: " + Assimp.aiGetErrorString());
        }

        return scene;
    }
}
