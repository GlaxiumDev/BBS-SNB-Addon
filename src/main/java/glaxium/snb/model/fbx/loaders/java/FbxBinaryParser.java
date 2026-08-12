package glaxium.snb.model.fbx.loaders.java;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Strict, allocation-conscious reader for the documented binary FBX tree. */
final class FbxBinaryParser
{
    private static final byte[] MAGIC = "Kaydara FBX Binary  \0\u001a\0".getBytes(StandardCharsets.ISO_8859_1);

    static final class Element
    {
        final String name;
        final Object[] properties;
        final List<Element> children = new ArrayList<>();

        Element(String name, Object[] properties)
        {
            this.name = name;
            this.properties = properties;
        }

        Element child(String childName)
        {
            for (Element child : this.children)
            {
                if (child.name.equals(childName))
                {
                    return child;
                }
            }
            return null;
        }

        List<Element> children(String childName)
        {
            List<Element> result = new ArrayList<>();
            for (Element child : this.children)
            {
                if (child.name.equals(childName))
                {
                    result.add(child);
                }
            }
            return result;
        }

        Object property(int index)
        {
            return index >= 0 && index < this.properties.length ? this.properties[index] : null;
        }

        String string(int index)
        {
            Object value = property(index);
            return value == null ? "" : String.valueOf(value);
        }

        long longValue(int index)
        {
            Object value = property(index);
            return value instanceof Number number ? number.longValue() : 0L;
        }
    }

    record Document(int version, List<Element> roots) {}

    static Document parse(byte[] bytes) throws IOException
    {
        if (bytes == null || bytes.length < MAGIC.length + 4)
        {
            throw new IOException("FBX file is truncated");
        }

        for (int i = 0; i < MAGIC.length; i++)
        {
            if (bytes[i] != MAGIC[i])
            {
                throw new IOException("Only binary FBX files are supported by the Java importer");
            }
        }

        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        input.position(MAGIC.length);
        int version = input.getInt();

        if (version < 7100 || version >= 8000)
        {
            throw new IOException("Unsupported binary FBX version " + version + " (expected 7.1-7.9)");
        }

        List<Element> roots = new ArrayList<>();
        while (input.remaining() >= (version >= 7500 ? 25 : 13))
        {
            Element element = readElement(input, version, bytes.length);
            if (element == null)
            {
                break;
            }
            roots.add(element);
        }

        return new Document(version, roots);
    }

    private static Element readElement(ByteBuffer input, int version, int fileLength) throws IOException
    {
        int headerStart = input.position();
        long endOffset;
        long propertyCount;

        if (version >= 7500)
        {
            endOffset = input.getLong();
            propertyCount = input.getLong();
            input.getLong(); // property-list byte length
        }
        else
        {
            endOffset = Integer.toUnsignedLong(input.getInt());
            propertyCount = Integer.toUnsignedLong(input.getInt());
            Integer.toUnsignedLong(input.getInt()); // property-list byte length
        }

        int nameLength = Byte.toUnsignedInt(input.get());
        if (endOffset == 0)
        {
            return null;
        }
        if (endOffset <= headerStart || endOffset > fileLength || propertyCount > Integer.MAX_VALUE || nameLength > input.remaining())
        {
            throw new IOException("Corrupt FBX node header at byte " + headerStart);
        }

        byte[] nameBytes = new byte[nameLength];
        input.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);
        Object[] properties = new Object[(int) propertyCount];

        for (int i = 0; i < properties.length; i++)
        {
            if (!input.hasRemaining())
            {
                throw new IOException("Truncated FBX property in " + name);
            }
            properties[i] = readProperty(input, (char) Byte.toUnsignedInt(input.get()));
        }

        Element result = new Element(name, properties);
        int sentinelLength = version >= 7500 ? 25 : 13;
        long childrenEnd = endOffset - sentinelLength;

        while (input.position() < childrenEnd)
        {
            Element child = readElement(input, version, fileLength);
            if (child == null)
            {
                break;
            }
            result.children.add(child);
        }

        if (input.position() > endOffset)
        {
            throw new IOException("FBX node " + name + " exceeds its declared boundary");
        }
        input.position((int) endOffset);
        return result;
    }

    private static Object readProperty(ByteBuffer input, char type) throws IOException
    {
        return switch (type)
        {
            case 'Y' -> input.getShort();
            case 'C' -> input.get() != 0;
            case 'I' -> input.getInt();
            case 'F' -> input.getFloat();
            case 'D' -> input.getDouble();
            case 'L' -> input.getLong();
            case 'S' -> new String(readBytes(input, input.getInt()), StandardCharsets.UTF_8);
            case 'R' -> readBytes(input, input.getInt());
            case 'f' -> floats(readArrayPayload(input, 4));
            case 'd' -> doubles(readArrayPayload(input, 8));
            case 'i' -> ints(readArrayPayload(input, 4));
            case 'l' -> longs(readArrayPayload(input, 8));
            case 'b', 'c' -> readArrayPayload(input, 1);
            default -> throw new IOException("Unsupported FBX property type '" + type + "'");
        };
    }

    private static byte[] readArrayPayload(ByteBuffer input, int elementSize) throws IOException
    {
        long count = Integer.toUnsignedLong(input.getInt());
        int encoding = input.getInt();
        long encodedLength = Integer.toUnsignedLong(input.getInt());
        long decodedLength = count * elementSize;

        if (encodedLength > Integer.MAX_VALUE || decodedLength > Integer.MAX_VALUE)
        {
            throw new IOException("FBX array is too large");
        }

        byte[] encoded = readBytes(input, (int) encodedLength);
        if (encoding == 0)
        {
            if (encoded.length != (int) decodedLength)
            {
                throw new IOException("FBX array length mismatch");
            }
            return encoded;
        }
        if (encoding != 1)
        {
            throw new IOException("Unsupported FBX array encoding " + encoding);
        }

        Inflater inflater = new Inflater();
        inflater.setInput(encoded);
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) decodedLength);
        byte[] chunk = new byte[Math.min(64 * 1024, Math.max(1024, (int) decodedLength))];

        try
        {
            while (!inflater.finished())
            {
                int read = inflater.inflate(chunk);
                if (read > 0)
                {
                    if (output.size() + read > decodedLength)
                    {
                        throw new IOException("Inflated FBX array exceeds its declared length");
                    }
                    output.write(chunk, 0, read);
                }
                else if (inflater.needsDictionary() || inflater.needsInput())
                {
                    break;
                }
                else
                {
                    throw new IOException("Compressed FBX array made no progress");
                }
            }
        }
        catch (DataFormatException e)
        {
            throw new IOException("Invalid compressed FBX array", e);
        }
        finally
        {
            inflater.end();
        }

        byte[] decoded = output.toByteArray();
        if (decoded.length != (int) decodedLength)
        {
            throw new IOException("Inflated FBX array length mismatch (expected " + decodedLength + ", got " + decoded.length + ")");
        }
        return decoded;
    }

    private static byte[] readBytes(ByteBuffer input, int count) throws IOException
    {
        if (count < 0 || count > input.remaining())
        {
            throw new IOException("Truncated FBX byte payload");
        }
        byte[] result = new byte[count];
        input.get(result);
        return result;
    }

    private static float[] floats(byte[] bytes)
    {
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / 4];
        for (int i = 0; i < result.length; i++) result[i] = input.getFloat();
        return result;
    }

    private static double[] doubles(byte[] bytes)
    {
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double[] result = new double[bytes.length / 8];
        for (int i = 0; i < result.length; i++) result[i] = input.getDouble();
        return result;
    }

    private static int[] ints(byte[] bytes)
    {
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int[] result = new int[bytes.length / 4];
        for (int i = 0; i < result.length; i++) result[i] = input.getInt();
        return result;
    }

    private static long[] longs(byte[] bytes)
    {
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long[] result = new long[bytes.length / 8];
        for (int i = 0; i < result.length; i++) result[i] = input.getLong();
        return result;
    }
}
