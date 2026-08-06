package glaxium.snb.model.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Parsed glTF JSON and its resolved binary buffers. */
final class GltfDocument
{
    final JsonObject root;
    final File baseDir;

    private final List<byte[]> buffers = new ArrayList<>();

    GltfDocument(String json, byte[] glbBinaryChunk, File baseDir) throws IOException
    {
        this.root = parseRoot(json);
        this.baseDir = baseDir;
        validateAsset();
        loadBuffers(glbBinaryChunk);
    }

    JsonArray array(String name)
    {
        JsonElement value = root.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    BufferView bufferView(int index) throws IOException
    {
        JsonArray views = array("bufferViews");
        JsonObject view = objectAt(views, index, "bufferView");
        int bufferIndex = requiredInt(view, "buffer", "bufferView " + index);
        if (bufferIndex < 0 || bufferIndex >= buffers.size())
        {
            throw new IOException("bufferView " + index + " references invalid buffer " + bufferIndex);
        }

        int offset = optionalInt(view, "byteOffset", 0, "bufferView " + index);
        int length = requiredInt(view, "byteLength", "bufferView " + index);
        int stride = optionalInt(view, "byteStride", 0, "bufferView " + index);
        if (offset < 0 || length < 0 || stride < 0)
        {
            throw new IOException("bufferView " + index + " has a negative offset, length, or stride");
        }

        byte[] buffer = buffers.get(bufferIndex);
        long end = (long) offset + length;
        if (end > buffer.length)
        {
            throw new IOException("bufferView " + index + " exceeds buffer " + bufferIndex);
        }
        return new BufferView(buffer, offset, length, stride);
    }

    byte[] bufferViewBytes(int index) throws IOException
    {
        BufferView view = bufferView(index);
        byte[] result = new byte[view.length];
        System.arraycopy(view.buffer, view.offset, result, 0, view.length);
        return result;
    }

    private static JsonObject parseRoot(String json) throws IOException
    {
        try
        {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject())
            {
                throw new IOException("glTF JSON root must be an object");
            }
            return parsed.getAsJsonObject();
        }
        catch (JsonParseException | IllegalStateException exception)
        {
            throw new IOException("Invalid glTF JSON: " + exception.getMessage(), exception);
        }
    }

    private void validateAsset() throws IOException
    {
        JsonElement assetElement = root.get("asset");
        if (assetElement == null || !assetElement.isJsonObject())
        {
            throw new IOException("glTF JSON is missing its asset object");
        }
        JsonObject asset = assetElement.getAsJsonObject();
        String version = requiredString(asset, "version", "asset");
        if (!version.startsWith("2."))
        {
            throw new IOException("Unsupported glTF asset version " + version + " (expected 2.x)");
        }
    }

    private void loadBuffers(byte[] glbBinaryChunk) throws IOException
    {
        JsonArray definitions = array("buffers");
        for (int index = 0; index < definitions.size(); index++)
        {
            JsonObject definition = objectAt(definitions, index, "buffer");
            int declaredLength = requiredInt(definition, "byteLength", "buffer " + index);
            if (declaredLength < 0)
            {
                throw new IOException("buffer " + index + " has a negative byteLength");
            }

            byte[] data;
            JsonElement uriElement = definition.get("uri");
            if (uriElement != null && !uriElement.isJsonNull())
            {
                if (!uriElement.isJsonPrimitive() || !uriElement.getAsJsonPrimitive().isString())
                {
                    throw new IOException("buffer " + index + " uri must be a string");
                }
                data = loadUri(uriElement.getAsString(), "buffer " + index);
            }
            else if (index == 0 && glbBinaryChunk != null)
            {
                data = glbBinaryChunk;
            }
            else
            {
                throw new IOException("buffer " + index + " has no URI or GLB BIN chunk");
            }

            if (data.length < declaredLength)
            {
                throw new IOException("buffer " + index + " contains " + data.length
                    + " bytes, but declares " + declaredLength);
            }
            buffers.add(data);
        }
    }

    private byte[] loadUri(String uri, String description) throws IOException
    {
        if (uri.startsWith("data:"))
        {
            return decodeDataUri(uri).data;
        }
        if (baseDir == null)
        {
            throw new IOException(description + " uses external URI '" + uri + "', but baseDir is null");
        }

        final URI parsed;
        try
        {
            parsed = new URI(uri);
        }
        catch (URISyntaxException exception)
        {
            throw new IOException("Invalid URI for " + description + ": " + uri, exception);
        }

        Path path;
        if (parsed.getScheme() == null)
        {
            if (parsed.getRawAuthority() != null)
            {
                throw new IOException("Unsupported network URI for " + description + ": " + uri);
            }
            String decodedPath = parsed.getPath();
            if (decodedPath == null || decodedPath.isEmpty())
            {
                throw new IOException("Empty external URI for " + description);
            }
            path = baseDir.toPath().resolve(decodedPath).normalize();
        }
        else if ("file".equalsIgnoreCase(parsed.getScheme()))
        {
            try
            {
                path = Path.of(parsed);
            }
            catch (IllegalArgumentException exception)
            {
                throw new IOException("Invalid file URI for " + description + ": " + uri, exception);
            }
        }
        else
        {
            throw new IOException("Unsupported URI scheme for " + description + ": " + parsed.getScheme());
        }

        try
        {
            return Files.readAllBytes(path);
        }
        catch (IOException exception)
        {
            throw new IOException("Could not read " + description + " from " + path, exception);
        }
    }

    static DataUri decodeDataUri(String uri) throws IOException
    {
        int comma = uri.indexOf(',');
        if (!uri.startsWith("data:") || comma < 5)
        {
            throw new IOException("Malformed data URI");
        }

        String metadata = uri.substring(5, comma);
        boolean base64 = metadata.endsWith(";base64");
        String mediaType = metadata;
        if (base64)
        {
            mediaType = metadata.substring(0, metadata.length() - 7);
        }
        int parameter = mediaType.indexOf(';');
        if (parameter >= 0)
        {
            mediaType = mediaType.substring(0, parameter);
        }

        byte[] decodedPayload = percentDecode(uri.substring(comma + 1));
        if (base64)
        {
            try
            {
                decodedPayload = Base64.getMimeDecoder().decode(decodedPayload);
            }
            catch (IllegalArgumentException exception)
            {
                throw new IOException("Malformed base64 data URI", exception);
            }
        }
        return new DataUri(mediaType, decodedPayload);
    }

    private static byte[] percentDecode(String value) throws IOException
    {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream(utf8.length);
        for (int index = 0; index < utf8.length; index++)
        {
            int valueByte = utf8[index] & 0xFF;
            if (valueByte == '%')
            {
                if (index + 2 >= utf8.length)
                {
                    throw new IOException("Truncated percent escape in data URI");
                }
                int high = Character.digit((char) utf8[++index], 16);
                int low = Character.digit((char) utf8[++index], 16);
                if (high < 0 || low < 0)
                {
                    throw new IOException("Invalid percent escape in data URI");
                }
                output.write((high << 4) | low);
            }
            else
            {
                output.write(valueByte);
            }
        }
        return output.toByteArray();
    }

    static JsonObject objectAt(JsonArray array, int index, String description) throws IOException
    {
        if (index < 0 || index >= array.size())
        {
            throw new IOException("Invalid " + description + " index " + index);
        }
        JsonElement element = array.get(index);
        if (!element.isJsonObject())
        {
            throw new IOException(description + " " + index + " must be an object");
        }
        return element.getAsJsonObject();
    }

    static int requiredInt(JsonObject object, String name, String description) throws IOException
    {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        {
            throw new IOException(description + " is missing numeric " + name);
        }
        try
        {
            return value.getAsInt();
        }
        catch (NumberFormatException exception)
        {
            throw new IOException(description + " has invalid integer " + name, exception);
        }
    }

    static int optionalInt(JsonObject object, String name, int fallback, String description) throws IOException
    {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull())
        {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        {
            throw new IOException(description + " has non-numeric " + name);
        }
        try
        {
            return value.getAsInt();
        }
        catch (NumberFormatException exception)
        {
            throw new IOException(description + " has invalid integer " + name, exception);
        }
    }

    static String requiredString(JsonObject object, String name, String description) throws IOException
    {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
        {
            throw new IOException(description + " is missing string " + name);
        }
        return value.getAsString();
    }

    static final class BufferView
    {
        final byte[] buffer;
        final int offset;
        final int length;
        final int stride;

        BufferView(byte[] buffer, int offset, int length, int stride)
        {
            this.buffer = buffer;
            this.offset = offset;
            this.length = length;
            this.stride = stride;
        }
    }

    static final class DataUri
    {
        final String mediaType;
        final byte[] data;

        DataUri(String mediaType, byte[] data)
        {
            this.mediaType = mediaType;
            this.data = data;
        }
    }
}
