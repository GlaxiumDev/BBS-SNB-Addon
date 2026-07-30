package elgatopro300.bbsfbx.model.fbx.loaders;

import org.lwjgl.BufferUtils;
import org.lwjgl.assimp.AIPropertyStore;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;

import java.nio.ByteBuffer;

/**
 * Drives Assimp's import of a raw FBX byte buffer into an AIScene, with the
 * property store setup (pivot handling, scale factor) and post-process flags
 * BBS FS needs.
 */
public final class FBXAssimpImporter
{
    private FBXAssimpImporter() {}

    /**
     * @return the imported scene, or null (with the Assimp error already
     * logged) if the import failed.
     */
    public static AIScene importScene(byte[] bytes)
    {
        ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
        buffer.put(bytes);
        buffer.flip();

        AIPropertyStore store = Assimp.aiCreatePropertyStore();
        AIScene scene;
        try
        {
            assert store != null;
            Assimp.aiSetImportPropertyInteger(store, Assimp.AI_CONFIG_IMPORT_FBX_PRESERVE_PIVOTS, 0);
            Assimp.aiSetImportPropertyFloat(store, Assimp.AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY, 1.0f);

            scene = Assimp.aiImportFileFromMemoryWithProperties(buffer,
                    Assimp.aiProcess_Triangulate |
                            Assimp.aiProcess_FlipUVs |
                            Assimp.aiProcess_LimitBoneWeights |
                            Assimp.aiProcess_JoinIdenticalVertices |
                            Assimp.aiProcess_GenSmoothNormals |
                            Assimp.aiProcess_PopulateArmatureData,
                    (ByteBuffer) null,
                    store);
        }
        finally
        {
            assert store != null;
            Assimp.aiReleasePropertyStore(store);
        }

        if (scene == null)
        {
            System.err.println("Error loading FBX model: " + Assimp.aiGetErrorString());
            return null;
        }

        return scene;
    }
}