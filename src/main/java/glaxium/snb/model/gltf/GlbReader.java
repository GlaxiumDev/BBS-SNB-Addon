package glaxium.snb.model.gltf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reads the container-level chunks from a glTF 2.0 binary (GLB) file. */
final class GlbReader
{
    static final int MAGIC = 0x46546C67;

    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;

    private GlbReader() {}

    static Result read(byte[] bytes) throws IOException
    {
        if (bytes == null || bytes.length < 12)
        {
            throw new IOException("GLB header is missing or truncated");
        }

        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int magic = input.getInt();
        if (magic != MAGIC)
        {
            throw new IOException(String.format("Invalid GLB magic 0x%08X", magic));
        }

        int version = input.getInt();
        if (version != 2)
        {
            throw new IOException("Unsupported GLB version " + Integer.toUnsignedString(version) + " (expected 2)");
        }

        long declaredLength = Integer.toUnsignedLong(input.getInt());
        if (declaredLength != bytes.length)
        {
            throw new IOException("GLB length is " + bytes.length
                + " bytes, but its header declares " + declaredLength);
        }

        String json = null;
        byte[] binary = null;
        int chunkNumber = 0;

        while (input.hasRemaining())
        {
            if (input.remaining() < 8)
            {
                throw new IOException("Truncated GLB chunk header at byte " + input.position());
            }

            long chunkLengthLong = Integer.toUnsignedLong(input.getInt());
            int chunkType = input.getInt();
            if (chunkLengthLong > input.remaining())
            {
                throw new IOException("GLB chunk " + chunkNumber + " extends past the end of the file");
            }
            int chunkLength = (int) chunkLengthLong;
            int start = input.position();

            if (chunkNumber == 0 && chunkType != JSON_CHUNK)
            {
                throw new IOException("The first GLB chunk is not JSON");
            }

            if (chunkType == JSON_CHUNK)
            {
                if (json != null)
                {
                    throw new IOException("GLB contains more than one JSON chunk");
                }
                json = decodeJson(bytes, start, chunkLength);
            }
            else if (chunkType == BIN_CHUNK && binary == null)
            {
                binary = Arrays.copyOfRange(bytes, start, start + chunkLength);
            }

            input.position(start + chunkLength);
            chunkNumber++;
        }

        if (json == null)
        {
            throw new IOException("GLB does not contain a JSON chunk");
        }
        return new Result(json, binary);
    }

    private static String decodeJson(byte[] bytes, int offset, int length)
    {
        int end = offset + length;
        while (end > offset && (bytes[end - 1] == 0 || bytes[end - 1] == ' '))
        {
            end--;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    static final class Result
    {
        final String json;
        final byte[] binaryChunk;

        Result(String json, byte[] binaryChunk)
        {
            this.json = json;
            this.binaryChunk = binaryChunk;
        }
    }
}
