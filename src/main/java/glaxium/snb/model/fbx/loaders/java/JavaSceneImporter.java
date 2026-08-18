package glaxium.snb.model.fbx.loaders.java;

import glaxium.snb.model.fbx.loaders.SceneFormat;
import glaxium.snb.model.fbx.scene.JavaScene;

import java.io.File;
import java.io.IOException;

/** Entry point for the addon's native-free model import path. */
public final class JavaSceneImporter
{
    private JavaSceneImporter() {}

    public static JavaScene importScene(byte[] bytes, SceneFormat format) throws IOException
    {
        return importScene(bytes, format, null);
    }

    public static JavaScene importScene(byte[] bytes, SceneFormat format, File sourceFile) throws IOException
    {
        if (bytes == null) throw new IOException("Model data is null");
        if (format == null) throw new IOException("Unknown model format");

        return switch (format)
        {
            case FBX -> JavaFbxImporter.read(bytes);
            case GLTF -> JavaGltfImporter.read(bytes, false, sourceFile);
            case GLB -> JavaGltfImporter.read(bytes, true, sourceFile);
        };
    }
}
