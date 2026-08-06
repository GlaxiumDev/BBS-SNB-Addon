package glaxium.snb.model.gltf;

import glaxium.snb.model.scene.Scene;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Public entry point for the pure-Java glTF 2.0 and GLB importer. */
public final class GltfParser
{
    private GltfParser() {}

    public static Scene parse(File file) throws IOException
    {
        if (file == null)
        {
            throw new IOException("glTF file is null");
        }

        final byte[] bytes;
        try
        {
            bytes = Files.readAllBytes(file.toPath());
        }
        catch (IOException exception)
        {
            throw new IOException("Could not read glTF file " + file, exception);
        }
        File absolute = file.getAbsoluteFile();
        return parse(bytes, absolute.getParentFile());
    }

    public static Scene parse(byte[] bytes, File baseDir) throws IOException
    {
        if (bytes == null)
        {
            throw new IOException("glTF input is null");
        }
        if (bytes.length == 0)
        {
            throw new IOException("glTF input is empty");
        }

        try
        {
            String json;
            byte[] binaryChunk = null;
            if (isGlb(bytes))
            {
                GlbReader.Result glb = GlbReader.read(bytes);
                json = glb.json;
                binaryChunk = glb.binaryChunk;
            }
            else
            {
                int offset = hasUtf8Bom(bytes) ? 3 : 0;
                json = new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
            }

            GltfDocument document = new GltfDocument(json, binaryChunk, baseDir);
            return new GltfSceneBuilder(document).build();
        }
        catch (IOException exception)
        {
            throw exception;
        }
        catch (RuntimeException exception)
        {
            String detail = exception.getMessage();
            throw new IOException("Failed to parse glTF"
                + (detail == null || detail.isEmpty() ? "" : ": " + detail), exception);
        }
    }

    private static boolean isGlb(byte[] bytes)
    {
        return bytes.length >= 4
            && ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == GlbReader.MAGIC;
    }

    private static boolean hasUtf8Bom(byte[] bytes)
    {
        return bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF;
    }
}
