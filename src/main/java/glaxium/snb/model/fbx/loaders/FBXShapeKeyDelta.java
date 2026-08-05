package glaxium.snb.model.fbx.loaders;

import java.util.Arrays;

/**
 * One shape key stored as the sparse set of vertex components it actually
 * moves, rather than a full copy of the mesh.
 *
 * <p>Shape keys used to be compiled into a dense {@code float[]} per key
 * holding the ABSOLUTE position (and normal) of every vertex in the model,
 * with vertices the key doesn't touch simply repeating the rest pose. That
 * costs {@code 24 * keyCount * vertexCount} bytes -- a 200k-vertex head with
 * 20 blend shapes allocates ~96 MB of mostly-identical floats at load time,
 * and the per-frame blend then had to walk every one of those floats for
 * every active key just to add zero to the vast majority of them.</p>
 *
 * <p>A blend shape realistically moves a small fraction of the mesh (an
 * eyelid, a mouth corner), so this stores only the components whose value
 * actually differs from the rest pose, as {@code (componentIndex, delta)}
 * pairs. Positions and normals are tracked separately because their
 * sparsity patterns differ. The blend the renderer performs is unchanged --
 * it was already {@code morphed[i] += weight * (shape[i] - base[i])}, and
 * {@code shape[i] - base[i]} is exactly the delta precomputed here, so the
 * arithmetic (and therefore the result) is identical; it just skips every
 * component whose delta is zero.</p>
 */
public final class FBXShapeKeyDelta
{
    private static final int[] NO_INDICES = new int[0];
    private static final float[] NO_DELTAS = new float[0];

    /** Component indices into {@code CompiledData.posData} this key moves. */
    public final int[] positionIndices;

    /** {@code shapePosition - restPosition} for each entry in {@link #positionIndices}. */
    public final float[] positionDeltas;

    /** Component indices into {@code CompiledData.normData} this key moves. */
    public final int[] normalIndices;

    /** {@code shapeNormal - restNormal} for each entry in {@link #normalIndices}. */
    public final float[] normalDeltas;

    public FBXShapeKeyDelta(int[] positionIndices, float[] positionDeltas, int[] normalIndices, float[] normalDeltas)
    {
        this.positionIndices = positionIndices;
        this.positionDeltas = positionDeltas;
        this.normalIndices = normalIndices;
        this.normalDeltas = normalDeltas;
    }

    /** True when this key moves nothing at all -- the renderer can skip it outright. */
    public boolean isEmpty()
    {
        return this.positionIndices.length == 0 && this.normalIndices.length == 0;
    }

    /**
     * Converts the older dense representation (one absolute value per
     * component) into this sparse one. Only used by compile paths that still
     * build dense arrays first; {@link FBXMeshCompiler#compileMergedWithMaterials}
     * builds the sparse form directly and never materialises the dense arrays.
     */
    public static FBXShapeKeyDelta fromDense(float[] basePositions, float[] shapePositions, float[] baseNormals, float[] shapeNormals)
    {
        Builder builder = new Builder();

        if (shapePositions != null)
        {
            int length = Math.min(basePositions.length, shapePositions.length);

            for (int i = 0; i < length; i++)
            {
                if (shapePositions[i] != basePositions[i])
                {
                    builder.position(i, shapePositions[i] - basePositions[i]);
                }
            }
        }

        if (shapeNormals != null)
        {
            int length = Math.min(baseNormals.length, shapeNormals.length);

            for (int i = 0; i < length; i++)
            {
                if (shapeNormals[i] != baseNormals[i])
                {
                    builder.normal(i, shapeNormals[i] - baseNormals[i]);
                }
            }
        }

        return builder.build();
    }

    /**
     * Collects {@code (component, delta)} pairs as the compiler walks the
     * mesh, growing geometrically so a key of unknown size costs one
     * amortised append per moved component.
     */
    public static final class Builder
    {
        private int[] positionIndices = NO_INDICES;
        private float[] positionDeltas = NO_DELTAS;
        private int positionCount;

        private int[] normalIndices = NO_INDICES;
        private float[] normalDeltas = NO_DELTAS;
        private int normalCount;

        public void position(int component, float delta)
        {
            if (this.positionCount == this.positionIndices.length)
            {
                int capacity = this.positionCount == 0 ? 256 : this.positionCount * 2;

                this.positionIndices = Arrays.copyOf(this.positionIndices, capacity);
                this.positionDeltas = Arrays.copyOf(this.positionDeltas, capacity);
            }

            this.positionIndices[this.positionCount] = component;
            this.positionDeltas[this.positionCount] = delta;
            this.positionCount++;
        }

        public void normal(int component, float delta)
        {
            if (this.normalCount == this.normalIndices.length)
            {
                int capacity = this.normalCount == 0 ? 256 : this.normalCount * 2;

                this.normalIndices = Arrays.copyOf(this.normalIndices, capacity);
                this.normalDeltas = Arrays.copyOf(this.normalDeltas, capacity);
            }

            this.normalIndices[this.normalCount] = component;
            this.normalDeltas[this.normalCount] = delta;
            this.normalCount++;
        }

        public FBXShapeKeyDelta build()
        {
            return new FBXShapeKeyDelta(
                    Arrays.copyOf(this.positionIndices, this.positionCount),
                    Arrays.copyOf(this.positionDeltas, this.positionCount),
                    Arrays.copyOf(this.normalIndices, this.normalCount),
                    Arrays.copyOf(this.normalDeltas, this.normalCount));
        }
    }
}
