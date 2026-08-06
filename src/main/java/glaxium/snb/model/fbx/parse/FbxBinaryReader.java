package glaxium.snb.model.fbx.parse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Reader for the 32-bit (pre-7500) and 64-bit FBX 7.x binary layouts. */
final class FbxBinaryReader
{
    private static final byte[] HEADER =
            "Kaydara FBX Binary  \0".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_DEPTH = 512;

    private final byte[] data;
    private int position;
    private boolean wideRecords;

    private FbxBinaryReader(byte[] data)
    {
        this.data = data;
    }

    static FbxDocument read(byte[] data) throws IOException
    {
        return new FbxBinaryReader(data).readDocument();
    }

    static boolean hasBinaryHeader(byte[] data)
    {
        if (data == null || data.length < HEADER.length)
        {
            return false;
        }

        for (int i = 0; i < HEADER.length; i++)
        {
            if (data[i] != HEADER[i])
            {
                return false;
            }
        }

        return true;
    }

    private FbxDocument readDocument() throws IOException
    {
        if (!hasBinaryHeader(this.data))
        {
            throw error("missing binary FBX header");
        }

        this.position = HEADER.length;

        /*
         * The standard magic has 0x1a,0x00 after its terminating NUL. A few
         * hand-written files omit those two bytes, so accept the documented
         * NUL-terminated-header form as well.
         */
        if (remaining() >= 2 && unsignedByte(this.position) == 0x1a
                && unsignedByte(this.position + 1) == 0)
        {
            this.position += 2;
        }

        FbxDocument document = new FbxDocument();
        document.version = readIntLE();

        if (document.version < 7000 || document.version > 9999)
        {
            throw error("unsupported or invalid binary FBX version " + document.version);
        }

        this.wideRecords = document.version >= 7500;

        while (remaining() >= recordHeaderSize())
        {
            FbxNode node = readNode(0);

            if (node == null)
            {
                break;
            }

            document.roots.add(node);
        }

        return document;
    }

    private FbxNode readNode(int depth) throws IOException
    {
        if (depth > MAX_DEPTH)
        {
            throw error("FBX node nesting exceeds " + MAX_DEPTH);
        }

        int recordStart = this.position;
        long endOffset = readRecordInteger();
        long propertyCount = readRecordInteger();
        long propertyListLength = readRecordInteger();
        int nameLength = readUnsignedByte();

        if (endOffset == 0)
        {
            if (propertyCount != 0 || propertyListLength != 0 || nameLength != 0)
            {
                throw error("malformed null node record");
            }

            return null;
        }

        if (endOffset <= recordStart || endOffset > this.data.length)
        {
            throw error("node end offset " + endOffset + " is outside the file");
        }
        if (propertyCount < 0 || propertyCount > Integer.MAX_VALUE)
        {
            throw error("node property count is too large: " + propertyCount);
        }
        if (propertyListLength < 0 || propertyListLength > Integer.MAX_VALUE)
        {
            throw error("node property list is too large: " + propertyListLength);
        }

        String name = new String(readBytes(nameLength), StandardCharsets.UTF_8);
        long propertyEnd = (long) this.position + propertyListLength;

        if (propertyEnd > endOffset || propertyEnd > this.data.length)
        {
            throw error("property list for " + name + " overruns its node");
        }

        List<Object> properties = new ArrayList<>((int) propertyCount);

        for (int i = 0; i < propertyCount; i++)
        {
            properties.add(readProperty());
        }

        if (this.position > propertyEnd)
        {
            throw error("properties for " + name + " exceed PropertyListLen");
        }

        /* PropertyListLen should be exact. Tolerate zero padding from lenient exporters. */
        while (this.position < propertyEnd)
        {
            if (readUnsignedByte() != 0)
            {
                throw error("non-zero padding at the end of " + name + "'s properties");
            }
        }

        List<FbxNode> children = new ArrayList<>();

        while ((long) this.position < endOffset)
        {
            if (endOffset - this.position < recordHeaderSize())
            {
                throw error("truncated child record in " + name);
            }

            FbxNode child = readNode(depth + 1);

            if (child == null)
            {
                break;
            }

            children.add(child);
        }

        if ((long) this.position > endOffset)
        {
            throw error("node " + name + " extends beyond its EndOffset");
        }

        /*
         * EndOffset normally points just after the null child marker. Some
         * writers add zero padding before it; never interpret that as nodes.
         */
        while ((long) this.position < endOffset)
        {
            if (readUnsignedByte() != 0)
            {
                throw error("unexpected bytes before the end of node " + name);
            }
        }

        return new FbxNode(name, properties, children);
    }

    private Object readProperty() throws IOException
    {
        int type = readUnsignedByte();

        return switch (type)
        {
            case 'Y' -> readShortLE();
            case 'C' -> readUnsignedByte() != 0;
            case 'I' -> readIntLE();
            case 'F' -> Float.intBitsToFloat(readIntLE());
            case 'D' -> Double.longBitsToDouble(readLongLE());
            case 'L' -> readLongLE();
            case 'S' -> new String(readLengthPrefixedBytes(), StandardCharsets.UTF_8);
            case 'R' -> readLengthPrefixedBytes();
            case 'i', 'f', 'd', 'l', 'b', 'c' -> readArray(type);
            default -> throw error("unsupported property type '" + printable(type) + "'");
        };
    }

    private Object readArray(int type) throws IOException
    {
        long countLong = readUnsignedIntLE();
        long encoding = readUnsignedIntLE();
        long payloadLengthLong = readUnsignedIntLE();

        if (countLong > Integer.MAX_VALUE || payloadLengthLong > Integer.MAX_VALUE)
        {
            throw error("array property is too large");
        }
        if (encoding != 0 && encoding != 1)
        {
            throw error("unsupported array encoding " + encoding);
        }

        int count = (int) countLong;
        int stride = switch (type)
        {
            case 'i', 'f' -> 4;
            case 'd', 'l' -> 8;
            case 'b', 'c' -> 1;
            default -> throw error("invalid array type");
        };
        long expectedLong = (long) count * stride;

        if (expectedLong > Integer.MAX_VALUE)
        {
            throw error("expanded array property is too large");
        }

        int expected = (int) expectedLong;
        byte[] payload = readBytes((int) payloadLengthLong);
        byte[] bytes;

        if (encoding == 0)
        {
            if (payload.length != expected)
            {
                throw error("raw array length " + payload.length + " does not match " + expected);
            }

            bytes = payload;
        }
        else
        {
            bytes = inflate(payload, expected);
        }

        ArrayCursor cursor = new ArrayCursor(bytes);

        return switch (type)
        {
            case 'i' ->
            {
                int[] values = new int[count];
                for (int i = 0; i < count; i++) values[i] = cursor.readInt();
                yield values;
            }
            case 'f' ->
            {
                float[] values = new float[count];
                for (int i = 0; i < count; i++)
                {
                    values[i] = Float.intBitsToFloat(cursor.readInt());
                }
                yield values;
            }
            case 'd' ->
            {
                double[] values = new double[count];
                for (int i = 0; i < count; i++)
                {
                    values[i] = Double.longBitsToDouble(cursor.readLong());
                }
                yield values;
            }
            case 'l' ->
            {
                long[] values = new long[count];
                for (int i = 0; i < count; i++) values[i] = cursor.readLong();
                yield values;
            }
            case 'b' ->
            {
                boolean[] values = new boolean[count];
                for (int i = 0; i < count; i++) values[i] = bytes[i] != 0;
                yield values;
            }
            case 'c' -> bytes;
            default -> throw error("invalid array type");
        };
    }

    private byte[] inflate(byte[] compressed, int expected) throws IOException
    {
        byte[] output = new byte[expected];
        Inflater inflater = new Inflater();

        try
        {
            inflater.setInput(compressed);
            int written = 0;

            while (written < expected)
            {
                int count = inflater.inflate(output, written, expected - written);

                if (count > 0)
                {
                    written += count;
                }
                else if (inflater.finished())
                {
                    break;
                }
                else if (inflater.needsDictionary())
                {
                    throw error("zlib-compressed array requires a dictionary");
                }
                else if (inflater.needsInput())
                {
                    break;
                }
                else
                {
                    throw error("zlib decompressor made no progress");
                }
            }

            if (written != expected || !inflater.finished())
            {
                throw error("zlib array expanded to " + written + " bytes, expected " + expected);
            }

            return output;
        }
        catch (DataFormatException exception)
        {
            throw error("invalid zlib-compressed array", exception);
        }
        finally
        {
            inflater.end();
        }
    }

    private byte[] readLengthPrefixedBytes() throws IOException
    {
        long length = readUnsignedIntLE();

        if (length > Integer.MAX_VALUE)
        {
            throw error("property payload is too large");
        }

        return readBytes((int) length);
    }

    private int recordHeaderSize()
    {
        return this.wideRecords ? 25 : 13;
    }

    private long readRecordInteger() throws IOException
    {
        if (this.wideRecords)
        {
            long value = readLongLE();

            if (value < 0)
            {
                throw error("64-bit record field exceeds the signed Java range");
            }

            return value;
        }

        return readUnsignedIntLE();
    }

    private int readUnsignedByte() throws IOException
    {
        ensureAvailable(1);
        return unsignedByte(this.position++);
    }

    private short readShortLE() throws IOException
    {
        ensureAvailable(2);
        int result = unsignedByte(this.position)
                | unsignedByte(this.position + 1) << 8;
        this.position += 2;
        return (short) result;
    }

    private int readIntLE() throws IOException
    {
        ensureAvailable(4);
        int result = unsignedByte(this.position)
                | unsignedByte(this.position + 1) << 8
                | unsignedByte(this.position + 2) << 16
                | unsignedByte(this.position + 3) << 24;
        this.position += 4;
        return result;
    }

    private long readUnsignedIntLE() throws IOException
    {
        return Integer.toUnsignedLong(readIntLE());
    }

    private long readLongLE() throws IOException
    {
        ensureAvailable(8);
        long result = (long) unsignedByte(this.position)
                | (long) unsignedByte(this.position + 1) << 8
                | (long) unsignedByte(this.position + 2) << 16
                | (long) unsignedByte(this.position + 3) << 24
                | (long) unsignedByte(this.position + 4) << 32
                | (long) unsignedByte(this.position + 5) << 40
                | (long) unsignedByte(this.position + 6) << 48
                | (long) unsignedByte(this.position + 7) << 56;
        this.position += 8;
        return result;
    }

    private byte[] readBytes(int length) throws IOException
    {
        if (length < 0)
        {
            throw error("negative byte count");
        }

        ensureAvailable(length);
        byte[] result = new byte[length];
        System.arraycopy(this.data, this.position, result, 0, length);
        this.position += length;
        return result;
    }

    private void ensureAvailable(int length) throws IOException
    {
        if (length < 0 || (long) this.position + length > this.data.length)
        {
            throw error("unexpected end of binary FBX");
        }
    }

    private int remaining()
    {
        return this.data.length - this.position;
    }

    private int unsignedByte(int index)
    {
        return this.data[index] & 0xff;
    }

    private String printable(int value)
    {
        return value >= 32 && value <= 126
                ? Character.toString((char) value)
                : String.format("\\x%02x", value);
    }

    private IOException error(String message)
    {
        return new IOException("Binary FBX at byte " + this.position + ": " + message);
    }

    private IOException error(String message, Throwable cause)
    {
        return new IOException("Binary FBX at byte " + this.position + ": " + message, cause);
    }

    /** Tiny unchecked cursor over an already bounds-checked array payload. */
    private static final class ArrayCursor
    {
        private final byte[] bytes;
        private int position;

        private ArrayCursor(byte[] bytes)
        {
            this.bytes = bytes;
        }

        private int readInt()
        {
            int result = unsigned(this.position)
                    | unsigned(this.position + 1) << 8
                    | unsigned(this.position + 2) << 16
                    | unsigned(this.position + 3) << 24;
            this.position += 4;
            return result;
        }

        private long readLong()
        {
            long result = (long) unsigned(this.position)
                    | (long) unsigned(this.position + 1) << 8
                    | (long) unsigned(this.position + 2) << 16
                    | (long) unsigned(this.position + 3) << 24
                    | (long) unsigned(this.position + 4) << 32
                    | (long) unsigned(this.position + 5) << 40
                    | (long) unsigned(this.position + 6) << 48
                    | (long) unsigned(this.position + 7) << 56;
            this.position += 8;
            return result;
        }

        private int unsigned(int index)
        {
            return this.bytes[index] & 0xff;
        }
    }
}
