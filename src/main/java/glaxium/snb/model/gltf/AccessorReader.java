package glaxium.snb.model.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes tightly packed or interleaved glTF accessors into Java arrays. */
final class AccessorReader
{
    static final int BYTE = 5120;
    static final int UNSIGNED_BYTE = 5121;
    static final int SHORT = 5122;
    static final int UNSIGNED_SHORT = 5123;
    static final int UNSIGNED_INT = 5125;
    static final int FLOAT = 5126;

    private final GltfDocument document;

    AccessorReader(GltfDocument document)
    {
        this.document = document;
    }

    AccessorInfo info(int accessorIndex) throws IOException
    {
        JsonObject accessor = GltfDocument.objectAt(document.array("accessors"), accessorIndex, "accessor");
        int componentType = GltfDocument.requiredInt(accessor, "componentType", "accessor " + accessorIndex);
        int componentSize = componentSize(componentType, accessorIndex);
        String type = GltfDocument.requiredString(accessor, "type", "accessor " + accessorIndex);
        int components = componentCount(type, accessorIndex);
        int count = GltfDocument.requiredInt(accessor, "count", "accessor " + accessorIndex);
        if (count < 0)
        {
            throw new IOException("accessor " + accessorIndex + " has a negative count");
        }

        boolean normalized = false;
        JsonElement normalizedElement = accessor.get("normalized");
        if (normalizedElement != null && !normalizedElement.isJsonNull())
        {
            if (!normalizedElement.isJsonPrimitive()
                || !normalizedElement.getAsJsonPrimitive().isBoolean())
            {
                throw new IOException("accessor " + accessorIndex + " normalized must be boolean");
            }
            normalized = normalizedElement.getAsBoolean();
        }

        int total;
        try
        {
            total = Math.multiplyExact(count, components);
        }
        catch (ArithmeticException exception)
        {
            throw new IOException("accessor " + accessorIndex + " is too large", exception);
        }
        return new AccessorInfo(accessorIndex, accessor, componentType, componentSize,
            type, components, count, total, normalized);
    }

    float[] readFloats(int accessorIndex) throws IOException
    {
        AccessorInfo info = info(accessorIndex);
        float[] result = new float[info.totalComponents];
        readDenseFloats(info, result);
        applySparseFloats(info, result);
        return result;
    }

    int[] readInts(int accessorIndex) throws IOException
    {
        AccessorInfo info = info(accessorIndex);
        if (info.componentType == FLOAT)
        {
            throw new IOException("accessor " + accessorIndex + " cannot be decoded as integers");
        }
        int[] result = new int[info.totalComponents];
        readDenseInts(info, result);
        applySparseInts(info, result);
        return result;
    }

    private void readDenseFloats(AccessorInfo info, float[] output) throws IOException
    {
        DenseSource source = denseSource(info);
        if (source == null)
        {
            return;
        }

        ByteBuffer buffer = littleEndian(source.view.buffer);
        for (int element = 0; element < info.count; element++)
        {
            int elementOffset = source.start + element * source.stride;
            int outputOffset = element * info.components;
            for (int component = 0; component < info.components; component++)
            {
                int offset = elementOffset + component * info.componentSize;
                output[outputOffset + component] = readFloat(
                    buffer, offset, info.componentType, info.normalized);
            }
        }
    }

    private void readDenseInts(AccessorInfo info, int[] output) throws IOException
    {
        DenseSource source = denseSource(info);
        if (source == null)
        {
            return;
        }

        ByteBuffer buffer = littleEndian(source.view.buffer);
        for (int element = 0; element < info.count; element++)
        {
            int elementOffset = source.start + element * source.stride;
            int outputOffset = element * info.components;
            for (int component = 0; component < info.components; component++)
            {
                int offset = elementOffset + component * info.componentSize;
                output[outputOffset + component] = readInt(buffer, offset, info.componentType, info.index);
            }
        }
    }

    private DenseSource denseSource(AccessorInfo info) throws IOException
    {
        int accessorOffset = GltfDocument.optionalInt(
            info.json, "byteOffset", 0, "accessor " + info.index);
        if (accessorOffset < 0)
        {
            throw new IOException("accessor " + info.index + " has a negative byteOffset");
        }

        JsonElement viewElement = info.json.get("bufferView");
        if (viewElement == null || viewElement.isJsonNull())
        {
            if (accessorOffset != 0)
            {
                throw new IOException("accessor " + info.index
                    + " has byteOffset but no bufferView");
            }
            return null;
        }
        if (!viewElement.isJsonPrimitive() || !viewElement.getAsJsonPrimitive().isNumber())
        {
            throw new IOException("accessor " + info.index + " bufferView must be an integer");
        }

        int viewIndex = GltfDocument.requiredInt(info.json, "bufferView", "accessor " + info.index);
        GltfDocument.BufferView view = document.bufferView(viewIndex);
        int packedSize;
        try
        {
            packedSize = Math.multiplyExact(info.components, info.componentSize);
        }
        catch (ArithmeticException exception)
        {
            throw new IOException("accessor " + info.index + " element size overflows", exception);
        }
        int stride = view.stride == 0 ? packedSize : view.stride;
        if (stride < packedSize)
        {
            throw new IOException("accessor " + info.index + " byteStride " + stride
                + " is smaller than its element size " + packedSize);
        }
        if (accessorOffset % info.componentSize != 0)
        {
            throw new IOException("accessor " + info.index + " byteOffset is not component-aligned");
        }

        long required = accessorOffset;
        if (info.count > 0)
        {
            required += (long) (info.count - 1) * stride + packedSize;
        }
        if (required > view.length)
        {
            throw new IOException("accessor " + info.index + " exceeds bufferView " + viewIndex);
        }
        return new DenseSource(view, view.offset + accessorOffset, stride);
    }

    private void applySparseFloats(AccessorInfo info, float[] output) throws IOException
    {
        JsonElement sparseElement = info.json.get("sparse");
        if (sparseElement == null || sparseElement.isJsonNull())
        {
            return;
        }
        if (!sparseElement.isJsonObject())
        {
            throw new IOException("accessor " + info.index + " sparse must be an object");
        }

        SparseSource sparse = sparseSource(info, sparseElement.getAsJsonObject());
        ByteBuffer values = littleEndian(sparse.valuesView.buffer);
        for (int sparseElementIndex = 0; sparseElementIndex < sparse.count; sparseElementIndex++)
        {
            int target = sparse.indices[sparseElementIndex];
            int valueOffset = sparse.valuesStart
                + sparseElementIndex * info.components * info.componentSize;
            int outputOffset = target * info.components;
            for (int component = 0; component < info.components; component++)
            {
                output[outputOffset + component] = readFloat(values,
                    valueOffset + component * info.componentSize,
                    info.componentType, info.normalized);
            }
        }
    }

    private void applySparseInts(AccessorInfo info, int[] output) throws IOException
    {
        JsonElement sparseElement = info.json.get("sparse");
        if (sparseElement == null || sparseElement.isJsonNull())
        {
            return;
        }
        if (!sparseElement.isJsonObject())
        {
            throw new IOException("accessor " + info.index + " sparse must be an object");
        }

        SparseSource sparse = sparseSource(info, sparseElement.getAsJsonObject());
        ByteBuffer values = littleEndian(sparse.valuesView.buffer);
        for (int sparseElementIndex = 0; sparseElementIndex < sparse.count; sparseElementIndex++)
        {
            int target = sparse.indices[sparseElementIndex];
            int valueOffset = sparse.valuesStart
                + sparseElementIndex * info.components * info.componentSize;
            int outputOffset = target * info.components;
            for (int component = 0; component < info.components; component++)
            {
                output[outputOffset + component] = readInt(values,
                    valueOffset + component * info.componentSize,
                    info.componentType, info.index);
            }
        }
    }

    private SparseSource sparseSource(AccessorInfo info, JsonObject sparse) throws IOException
    {
        int sparseCount = GltfDocument.requiredInt(sparse, "count", "accessor " + info.index + " sparse");
        if (sparseCount < 0 || sparseCount > info.count)
        {
            throw new IOException("accessor " + info.index + " has invalid sparse count " + sparseCount);
        }

        JsonElement indicesElement = sparse.get("indices");
        JsonElement valuesElement = sparse.get("values");
        if (indicesElement == null || !indicesElement.isJsonObject()
            || valuesElement == null || !valuesElement.isJsonObject())
        {
            throw new IOException("accessor " + info.index + " sparse indices/values are missing");
        }

        JsonObject indicesJson = indicesElement.getAsJsonObject();
        int indicesViewIndex = GltfDocument.requiredInt(
            indicesJson, "bufferView", "accessor " + info.index + " sparse indices");
        int indicesOffset = GltfDocument.optionalInt(
            indicesJson, "byteOffset", 0, "accessor " + info.index + " sparse indices");
        int indicesType = GltfDocument.requiredInt(
            indicesJson, "componentType", "accessor " + info.index + " sparse indices");
        if (indicesType != UNSIGNED_BYTE && indicesType != UNSIGNED_SHORT && indicesType != UNSIGNED_INT)
        {
            throw new IOException("accessor " + info.index + " has invalid sparse index component type");
        }
        int indicesSize = componentSize(indicesType, info.index);
        GltfDocument.BufferView indicesView = document.bufferView(indicesViewIndex);
        validateTightlyPackedRange(indicesView, indicesOffset, sparseCount, indicesSize,
            "accessor " + info.index + " sparse indices");

        int[] indices = new int[sparseCount];
        ByteBuffer indexBuffer = littleEndian(indicesView.buffer);
        for (int index = 0; index < sparseCount; index++)
        {
            indices[index] = readInt(indexBuffer,
                indicesView.offset + indicesOffset + index * indicesSize,
                indicesType, info.index);
            if (indices[index] < 0 || indices[index] >= info.count)
            {
                throw new IOException("accessor " + info.index
                    + " sparse index " + indices[index] + " is out of range");
            }
            if (index > 0 && indices[index] <= indices[index - 1])
            {
                throw new IOException("accessor " + info.index + " sparse indices are not increasing");
            }
        }

        JsonObject valuesJson = valuesElement.getAsJsonObject();
        int valuesViewIndex = GltfDocument.requiredInt(
            valuesJson, "bufferView", "accessor " + info.index + " sparse values");
        int valuesOffset = GltfDocument.optionalInt(
            valuesJson, "byteOffset", 0, "accessor " + info.index + " sparse values");
        GltfDocument.BufferView valuesView = document.bufferView(valuesViewIndex);
        int valueElementSize = info.components * info.componentSize;
        validateTightlyPackedRange(valuesView, valuesOffset, sparseCount, valueElementSize,
            "accessor " + info.index + " sparse values");

        return new SparseSource(sparseCount, indices, valuesView, valuesView.offset + valuesOffset);
    }

    private static void validateTightlyPackedRange(GltfDocument.BufferView view, int offset,
        int count, int elementSize, String description) throws IOException
    {
        if (offset < 0)
        {
            throw new IOException(description + " has a negative byteOffset");
        }
        long required = (long) offset + (long) count * elementSize;
        if (required > view.length)
        {
            throw new IOException(description + " exceeds its bufferView");
        }
    }

    private static float readFloat(ByteBuffer buffer, int offset, int componentType, boolean normalized)
    {
        if (componentType == FLOAT)
        {
            return buffer.getFloat(offset);
        }

        long raw = readRaw(buffer, offset, componentType);
        if (!normalized)
        {
            return (float) raw;
        }
        return switch (componentType)
        {
            case BYTE -> Math.max((float) raw / 127.0f, -1.0f);
            case UNSIGNED_BYTE -> (float) raw / 255.0f;
            case SHORT -> Math.max((float) raw / 32767.0f, -1.0f);
            case UNSIGNED_SHORT -> (float) raw / 65535.0f;
            case UNSIGNED_INT -> (float) (raw / 4294967295.0);
            default -> (float) raw;
        };
    }

    private static int readInt(ByteBuffer buffer, int offset, int componentType, int accessorIndex)
        throws IOException
    {
        long value = readRaw(buffer, offset, componentType);
        if (value > Integer.MAX_VALUE)
        {
            throw new IOException("accessor " + accessorIndex
                + " contains an integer too large for a Java array index: " + value);
        }
        return (int) value;
    }

    private static long readRaw(ByteBuffer buffer, int offset, int componentType)
    {
        return switch (componentType)
        {
            case BYTE -> buffer.get(offset);
            case UNSIGNED_BYTE -> Byte.toUnsignedInt(buffer.get(offset));
            case SHORT -> buffer.getShort(offset);
            case UNSIGNED_SHORT -> Short.toUnsignedInt(buffer.getShort(offset));
            case UNSIGNED_INT -> Integer.toUnsignedLong(buffer.getInt(offset));
            default -> throw new IllegalArgumentException("Unsupported integer component type " + componentType);
        };
    }

    private static ByteBuffer littleEndian(byte[] bytes)
    {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int componentSize(int componentType, int accessorIndex) throws IOException
    {
        return switch (componentType)
        {
            case BYTE, UNSIGNED_BYTE -> 1;
            case SHORT, UNSIGNED_SHORT -> 2;
            case UNSIGNED_INT, FLOAT -> 4;
            default -> throw new IOException("accessor " + accessorIndex
                + " uses unsupported componentType " + componentType);
        };
    }

    private static int componentCount(String type, int accessorIndex) throws IOException
    {
        return switch (type)
        {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT4" -> 16;
            default -> throw new IOException("accessor " + accessorIndex
                + " uses unsupported type " + type);
        };
    }

    static final class AccessorInfo
    {
        final int index;
        final JsonObject json;
        final int componentType;
        final int componentSize;
        final String type;
        final int components;
        final int count;
        final int totalComponents;
        final boolean normalized;

        AccessorInfo(int index, JsonObject json, int componentType, int componentSize,
            String type, int components, int count, int totalComponents, boolean normalized)
        {
            this.index = index;
            this.json = json;
            this.componentType = componentType;
            this.componentSize = componentSize;
            this.type = type;
            this.components = components;
            this.count = count;
            this.totalComponents = totalComponents;
            this.normalized = normalized;
        }
    }

    private static final class DenseSource
    {
        final GltfDocument.BufferView view;
        final int start;
        final int stride;

        DenseSource(GltfDocument.BufferView view, int start, int stride)
        {
            this.view = view;
            this.start = start;
            this.stride = stride;
        }
    }

    private static final class SparseSource
    {
        final int count;
        final int[] indices;
        final GltfDocument.BufferView valuesView;
        final int valuesStart;

        SparseSource(int count, int[] indices, GltfDocument.BufferView valuesView, int valuesStart)
        {
            this.count = count;
            this.indices = indices;
            this.valuesView = valuesView;
            this.valuesStart = valuesStart;
        }
    }
}
