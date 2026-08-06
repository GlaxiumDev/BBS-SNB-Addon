package glaxium.snb.model.fbx.loaders;

import glaxium.snb.model.fbx.parse.FbxParser;
import glaxium.snb.model.gltf.GltfParser;
import glaxium.snb.model.scene.Scene;
import glaxium.snb.model.scene.process.ScenePostProcess;

import java.io.File;
import java.io.IOException;

/**
 * Dispatches FBX / glTF / GLB bytes or files to the pure-Java parsers and
 * runs shared post-process (triangulate, weights, normals, UV flip, pivots).
 */
public final class SceneImporter
{
    private SceneImporter() {}

    public static Scene importScene(byte[] bytes, SceneFormat format) throws IOException
    {
        return importScene(bytes, format, null);
    }

    public static Scene importScene(File file, SceneFormat format) throws IOException
    {
        if (file == null || !file.isFile())
        {
            throw new IOException("Model file missing: " + file);
        }

        Scene scene = switch (format)
        {
            case FBX -> FbxParser.parse(file);
            case GLTF, GLB -> GltfParser.parse(file);
        };

        ScenePostProcess.apply(scene, format);
        return scene;
    }

    public static Scene importScene(byte[] bytes, SceneFormat format, File baseDir) throws IOException
    {
        if (bytes == null || bytes.length == 0)
        {
            throw new IOException("Empty model bytes for " + format.name());
        }

        Scene scene = switch (format)
        {
            case FBX -> FbxParser.parse(bytes);
            case GLTF, GLB -> GltfParser.parse(bytes, baseDir);
        };

        ScenePostProcess.apply(scene, format);
        return scene;
    }
}
