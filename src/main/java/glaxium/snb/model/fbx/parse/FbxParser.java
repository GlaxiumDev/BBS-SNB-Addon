package glaxium.snb.model.fbx.parse;

import glaxium.snb.model.scene.Scene;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Entry point for the dependency-free FBX 7.x importer. */
public final class FbxParser
{
    private FbxParser() {}

    public static Scene parse(File file) throws IOException
    {
        if (file == null)
        {
            throw new IOException("FBX file must not be null");
        }

        return parse(Files.readAllBytes(file.toPath()));
    }

    public static Scene parse(byte[] bytes) throws IOException
    {
        if (bytes == null)
        {
            throw new IOException("FBX data must not be null");
        }
        if (bytes.length == 0)
        {
            throw new IOException("FBX data is empty");
        }

        try
        {
            FbxDocument document = FbxBinaryReader.hasBinaryHeader(bytes)
                    ? FbxBinaryReader.read(bytes)
                    : FbxAsciiReader.read(bytes);
            return new FbxSceneBuilder(document).build();
        }
        catch (IOException exception)
        {
            throw exception;
        }
        catch (RuntimeException exception)
        {
            throw new IOException("Failed to convert FBX scene: " + exception.getMessage(), exception);
        }
    }
}
