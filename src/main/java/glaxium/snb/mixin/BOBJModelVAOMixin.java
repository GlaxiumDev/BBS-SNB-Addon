package glaxium.snb.mixin;

import glaxium.snb.model.fbx.loaders.FBXCompiledData;
import glaxium.snb.model.fbx.loaders.FBXShapeKeyDelta;
import glaxium.snb.model.fbx.loaders.IShapeKeyHolder;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL15;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fully replaces {@code BOBJModelVAO.updateMesh} with a faster equivalent:
 * same skinning and lighting results as the host, but with the per-frame
 * work reduced to what actually changed since the last frame, and the inner
 * vertex loop rewritten to run on flat float arrays instead of JOML objects.
 * It also blends {@code FBXCompiledData}'s shape keys by the live
 * {@link ShapeKeys} weights before the bone skinning, which the host has no
 * concept of.
 *
 * <p>Fork-agnostic: every {@code @Shadow}'d field and the {@code updateMesh}
 * signature below are byte-for-byte identical across the Base 1.7.7-1.20.4
 * and BBS CML EDITION 2.0-beta-1-1.20.4 jars (checked directly), and match
 * what the FS-targeted sibling addon already relies on. Nothing here touches
 * texture binding or draw calls -- that divergence lives entirely in
 * {@code ModelInstanceMixin} (see {@code mixin/base}, {@code mixin/fs},
 * {@code mixin/cml}), which is the one spot where Base, FS and CML actually
 * disagree.</p>
 */
@Mixin(value = BOBJModelVAO.class, remap = false)
public abstract class BOBJModelVAOMixin implements IShapeKeyHolder
{
    @Unique private static Method bbsFbx$processDataMethod;
    @Unique private static boolean bbsFbx$processDataHasMatrices;

    @Shadow public BOBJLoader.CompiledData data;
    @Shadow public BOBJArmature armature;
    @Shadow private int count;
    @Shadow public int vertexBuffer;
    @Shadow public int normalBuffer;
    @Shadow public int lightBuffer;
    @Shadow public int tangentBuffer;
    @Shadow private float[] tmpVertices;
    @Shadow private float[] tmpNormals;
    @Shadow private int[] tmpLight;
    @Shadow private float[] tmpTangents;

    // ---------------------------------------------------------------
    // Bone matrices, flattened
    // ---------------------------------------------------------------

    /**
     * Every bone's matrix packed into one flat {@code float[]}, 16 floats
     * apiece in JOML's own column-major order ({@code Matrix4f.get}), rebuilt
     * once per frame.
     *
     * <p>The host's loop reaches through a {@code Matrix4f} object per
     * vertex-weight pair and, worse, rebuilds a {@code Matrix3f} normal
     * matrix from scratch on every one of those pairs
     * ({@code Matrices.TEMP_3F.set(matrices[index])}). There are only
     * {@code matrices.length} distinct bones but up to {@code count * 4}
     * vertex-weight pairs, so a 650k-vertex model with 123 bones redid the
     * same 123 conversions upwards of two million times per frame.</p>
     *
     * <p>Flattening also removes the object indirection from the inner loop
     * entirely: reading {@code bones[offset + n]} is a bounds-checked array
     * load the JIT can keep in registers and unroll, where
     * {@code matrices[index].transform(v)} is a virtual call that loads and
     * stores through two separate objects. The 3x3 normal matrix needs no
     * storage of its own -- it is literally the upper-left block of the same
     * 16 floats, so the normal transform below just omits the translation
     * terms.</p>
     */
    @Unique
    private float[] bbsFbx$boneMatrices = new float[0];

    @Unique
    private float[] bbsFbx$boneMatrixScratch(int boneCount)
    {
        int required = boneCount * 16;

        if (this.bbsFbx$boneMatrices.length != required)
        {
            this.bbsFbx$boneMatrices = new float[required];
        }

        return this.bbsFbx$boneMatrices;
    }

    /**
     * True when no bone carries a projective row, which is the case for every
     * armature BBS actually builds (bone matrices are only ever composed from
     * translation, rotation and scale).
     *
     * <p>Worth an O(bones) check once per frame because it lets the vertex
     * loop drop the {@code w} row entirely: with {@code m03/m13/m23} zero and
     * {@code m33} one, that row evaluates to exactly 1 for every vertex, so
     * the homogeneous divisor is just the sum of the weights. That removes
     * four multiply-adds per bone influence -- roughly a fifth of the
     * arithmetic in the loop -- without changing a single output bit.</p>
     */
    @Unique
    private boolean bbsFbx$allBonesAffine(float[] bones, int boneCount)
    {
        for (int b = 0; b < boneCount; b++)
        {
            int m = b * 16;

            if (bones[m + 3] != 0F || bones[m + 7] != 0F || bones[m + 11] != 0F || bones[m + 15] != 1F)
            {
                return false;
            }
        }

        return true;
    }

    // ---------------------------------------------------------------
    // Parallel skinning
    // ---------------------------------------------------------------

    /**
     * Worker pool the skin loop splits across when a model is big enough to
     * make dispatch worth it (see {@link #bbsFbx$PARALLEL_THRESHOLD}).
     * {@code count - 1} threads, not {@code count}: this runs on the render
     * thread, which does its own share of the work as "chunk 0" rather than
     * sitting idle waiting on the pool. Static + shared across every VAO
     * instance and every model on screen (not one pool per model) since
     * skinning for different instances already happens one {@code updateMesh}
     * call at a time on the render thread -- one small fixed pool reused
     * every call, not spun up and torn down per frame.
     *
     * <p>Daemon threads: never blocks JVM shutdown, no explicit teardown
     * needed. Deliberately NOT touching any GL state -- worker tasks only
     * ever write into disjoint slices of {@code newVertices}/
     * {@code newNormals} (each vertex index is owned by exactly one chunk)
     * and read the geometry and bone arrays (read-only from every thread
     * once built). The actual {@code glBufferData} calls after the loop stay
     * on the render thread, same as before -- OpenGL contexts are
     * thread-bound and none of that is safe to move.</p>
     */
    private static final int bbsFbx$WORKER_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private static final ExecutorService bbsFbx$pool = bbsFbx$WORKER_COUNT <= 1 ? null : Executors.newFixedThreadPool(
            bbsFbx$WORKER_COUNT,
            r ->
            {
                Thread t = new Thread(r, "bbs-fbx-skin-worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * Below this vertex count the loop just runs single-threaded on the
     * render thread -- splitting work across threads costs a fixed amount of
     * dispatch/join overhead no matter how small the job is, and for a
     * handful of thousand vertices that overhead is bigger than the loop
     * itself. 20,000 is comfortably above typical prop/character meshes
     * (where the single-threaded path is already sub-millisecond) and
     * comfortably below where a high-poly FBX (hundreds of thousands of
     * vertices) actually saturates a core.
     */
    private static final int bbsFbx$PARALLEL_THRESHOLD = 20_000;

    // ---------------------------------------------------------------
    // Pose cache
    // ---------------------------------------------------------------

    /**
     * Pose cache: skips the entire per-frame CPU skinning + GL re-upload when
     * the last-uploaded buffers would be byte-identical. The host calls
     * {@code updateMesh} unconditionally every frame from
     * {@code ModelInstance.render} (after {@code armature.setupMatrices()});
     * a static prop therefore re-skins and re-uploads ALL of its vertices
     * every single frame for zero visual change. A single high-poly FBX car
     * (650k vertices, 123 bones) drops below 40 FPS from this alone.
     *
     * <p>The skinned output depends only on the armature matrices, the
     * geometry arrays (captured via {@code data}/{@code armature} identity +
     * {@code count}) and the stencil light flag -- {@code processData} is a
     * no-op in every fork's {@code BOBJModelVAO} -- so a signature of exactly
     * those inputs is an exact "has anything changed" test. When unchanged we
     * cancel the whole method and the GPU buffers keep the previous frame's
     * (correct) content: skinning + upload become O(bones) instead of
     * O(vertices). Any real pose change (animation, editor drag) alters the
     * matrices and re-triggers a full re-skin. Disabled while shape keys are
     * active (their weights aren't part of the signature).</p>
     */
    @Unique
    private boolean bbsFbx$poseCacheValid;

    @Unique
    private float[] bbsFbx$poseCache;

    @Unique
    private float[] bbsFbx$poseScratch;

    @Unique
    private Object bbsFbx$poseDataRef;

    @Unique
    private Object bbsFbx$poseArmatureRef;

    /**
     * @return true when updateMesh would produce exactly what's already in the
     *         GPU buffers (caller should cancel), false when it must re-run.
     *         On a "changed" result the cache is re-armed with the current
     *         signature so the next unchanged frame short-circuits.
     */
    @Unique
    private boolean bbsFbx$poseUnchanged(StencilMap stencilMap)
    {
        if (this.data == null || this.armature == null)
        {
            return false;
        }

        Matrix4f[] matrices = this.armature.matrices;
        int matrixCount = matrices == null ? 0 : matrices.length;
        int sigSize = matrixCount * 16 + 2;

        if (this.bbsFbx$poseCache == null || this.bbsFbx$poseScratch == null
                || this.bbsFbx$poseCache.length != sigSize)
        {
            this.bbsFbx$poseCache = new float[sigSize];
            this.bbsFbx$poseScratch = new float[sigSize];
            this.bbsFbx$poseCacheValid = false;
        }

        if (this.bbsFbx$poseDataRef != this.data || this.bbsFbx$poseArmatureRef != this.armature)
        {
            this.bbsFbx$poseCacheValid = false;
        }

        float[] sig = this.bbsFbx$poseScratch;
        int p = 0;

        for (int i = 0; i < matrixCount; i++)
        {
            matrices[i].get(sig, p);
            p += 16;
        }

        sig[p] = stencilMap != null && stencilMap.increment ? 1.0f : 0.0f;
        p++;
        sig[p] = this.count;

        if (this.bbsFbx$poseCacheValid && Arrays.equals(sig, this.bbsFbx$poseCache))
        {
            return true;
        }

        float[] swap = this.bbsFbx$poseCache;
        this.bbsFbx$poseCache = this.bbsFbx$poseScratch;
        this.bbsFbx$poseScratch = swap;
        this.bbsFbx$poseCacheValid = true;
        this.bbsFbx$poseDataRef = this.data;
        this.bbsFbx$poseArmatureRef = this.armature;

        return false;
    }

    // ---------------------------------------------------------------
    // Lightmap cache
    // ---------------------------------------------------------------

    /**
     * The stencil lightmap buffer only ever depends on things that never
     * change while a model is loaded, so it is built (and uploaded) once
     * instead of every frame.
     *
     * <p>Each vertex's entry is the index of its heaviest-weighted bone, or
     * 0 when {@code stencilMap.increment} is false. Bone weights live in
     * {@code CompiledData} and are fixed at load, so for a given
     * {@code (data, increment)} pair the whole {@code int[]} is a constant --
     * yet the host recomputed it inside the skinning loop and re-uploaded the
     * entire buffer on every frame the stencil map was active. On a 650k
     * vertex model that is a wasted 5 MB upload plus 1.3 million redundant
     * array writes per frame. Nothing else writes {@code lightBuffer}, so
     * once the correct contents are on the GPU they stay correct until the
     * model itself changes.</p>
     */
    @Unique
    private boolean bbsFbx$lightValid;

    @Unique
    private boolean bbsFbx$lightIncrement;

    @Unique
    private Object bbsFbx$lightDataRef;

    /** @return true when the buffer was (re)built and therefore needs uploading. */
    @Unique
    private boolean bbsFbx$rebuildLightIfStale(StencilMap stencilMap)
    {
        if (stencilMap == null)
        {
            return false;
        }

        boolean increment = stencilMap.increment;

        if (this.bbsFbx$lightValid && this.bbsFbx$lightIncrement == increment && this.bbsFbx$lightDataRef == this.data)
        {
            return false;
        }

        int[] light = this.tmpLight;
        int vertexCount = this.count;

        if (!increment)
        {
            Arrays.fill(light, 0, Math.min(light.length, vertexCount * 2), 0);
        }
        else
        {
            float[] weightData = this.data.weightData;
            int[] boneIndexData = this.data.boneIndexData;

            for (int i = 0; i < vertexCount; i++)
            {
                int b = i * 4;
                float maxWeight = -1F;
                int lightBone = -1;

                for (int w = 0; w < 4; w++)
                {
                    float weight = weightData[b + w];

                    if (weight > 0F && weight > maxWeight)
                    {
                        lightBone = boneIndexData[b + w];
                        maxWeight = weight;
                    }
                }

                light[i * 2] = Math.max(0, lightBone);
                light[i * 2 + 1] = 0;
            }
        }

        this.bbsFbx$lightValid = true;
        this.bbsFbx$lightIncrement = increment;
        this.bbsFbx$lightDataRef = this.data;

        return true;
    }

    // ---------------------------------------------------------------
    // Shape keys
    // ---------------------------------------------------------------

    @Unique
    private ShapeKeys bbsFbx$shapeKeys;

    @Override
    public void bbsFbx$setShapeKeys(ShapeKeys shapeKeys)
    {
        this.bbsFbx$shapeKeys = shapeKeys;
    }

    /**
     * Shape-key-blended geometry, fed into the skinning pass in place of the
     * rest pose. Allocated once and reused, same "allocate once, reuse
     * forever" pattern the host uses for {@code tmpVertices}/
     * {@code tmpNormals}.
     */
    @Unique
    private float[] bbsFbx$morphedVertices = new float[0];

    @Unique
    private float[] bbsFbx$morphedNormals = new float[0];

    /**
     * Components written by last frame's blend, so the next frame can undo
     * exactly those instead of memcpy'ing the entire rest pose back over the
     * morph buffers. On a 650k-vertex model that copy alone is ~16 MB of
     * memory traffic per frame, to overwrite data that a blend shape barely
     * touches. Cleared to a full copy whenever the model changes, or when a
     * frame dirties more components than the buffer can track (see
     * {@link #bbsFbx$markDirty}).
     */
    @Unique
    private int[] bbsFbx$dirtyPositions = new int[0];

    @Unique
    private int bbsFbx$dirtyPositionCount;

    @Unique
    private int[] bbsFbx$dirtyNormals = new int[0];

    @Unique
    private int bbsFbx$dirtyNormalCount;

    @Unique
    private boolean bbsFbx$morphBaseValid;

    @Unique
    private Object bbsFbx$morphDataRef;

    /**
     * Blends every active shape key into the morph buffers and leaves them
     * ready for skinning.
     */
    @Unique
    private void bbsFbx$blendShapeKeys(FBXCompiledData fbxData, float[] restVertices, float[] restNormals)
    {
        boolean resized = this.bbsFbx$morphedVertices.length != restVertices.length
                || this.bbsFbx$morphedNormals.length != restNormals.length;

        if (resized)
        {
            this.bbsFbx$morphedVertices = new float[restVertices.length];
            this.bbsFbx$morphedNormals = new float[restNormals.length];
        }

        float[] morphedVertices = this.bbsFbx$morphedVertices;
        float[] morphedNormals = this.bbsFbx$morphedNormals;

        if (resized || !this.bbsFbx$morphBaseValid || this.bbsFbx$morphDataRef != this.data)
        {
            System.arraycopy(restVertices, 0, morphedVertices, 0, restVertices.length);
            System.arraycopy(restNormals, 0, morphedNormals, 0, restNormals.length);

            this.bbsFbx$morphBaseValid = true;
            this.bbsFbx$morphDataRef = this.data;
        }
        else
        {
            int[] dirtyPositions = this.bbsFbx$dirtyPositions;

            for (int i = 0, n = this.bbsFbx$dirtyPositionCount; i < n; i++)
            {
                int component = dirtyPositions[i];

                morphedVertices[component] = restVertices[component];
            }

            int[] dirtyNormals = this.bbsFbx$dirtyNormals;

            for (int i = 0, n = this.bbsFbx$dirtyNormalCount; i < n; i++)
            {
                int component = dirtyNormals[i];

                morphedNormals[component] = restNormals[component];
            }
        }

        this.bbsFbx$dirtyPositionCount = 0;
        this.bbsFbx$dirtyNormalCount = 0;

        for (Map.Entry<String, Float> entry : this.bbsFbx$shapeKeys.shapeKeys.entrySet())
        {
            float weight = entry.getValue();

            if (weight == 0F)
            {
                continue;
            }

            FBXShapeKeyDelta delta = fbxData.shapeKeyDeltas.get(entry.getKey());

            if (delta == null)
            {
                continue;
            }

            int[] positionIndices = delta.positionIndices;
            float[] positionDeltas = delta.positionDeltas;

            for (int i = 0; i < positionIndices.length; i++)
            {
                morphedVertices[positionIndices[i]] += weight * positionDeltas[i];
            }

            int[] normalIndices = delta.normalIndices;
            float[] normalDeltas = delta.normalDeltas;

            for (int i = 0; i < normalIndices.length; i++)
            {
                morphedNormals[normalIndices[i]] += weight * normalDeltas[i];
            }

            this.bbsFbx$markDirty(positionIndices, normalIndices);
        }
    }

    /**
     * Appends one key's touched components to the restore lists. If the lists
     * would grow past the size of the buffers they undo, tracking is
     * abandoned and the next frame falls back to a full copy -- the restore
     * would cost more than the memcpy it is avoiding at that point, and this
     * keeps the lists from outgrowing the geometry itself.
     */
    @Unique
    private void bbsFbx$markDirty(int[] positionIndices, int[] normalIndices)
    {
        if (!this.bbsFbx$morphBaseValid)
        {
            return;
        }

        int positions = this.bbsFbx$dirtyPositionCount + positionIndices.length;
        int normals = this.bbsFbx$dirtyNormalCount + normalIndices.length;

        if (positions > this.bbsFbx$morphedVertices.length || normals > this.bbsFbx$morphedNormals.length)
        {
            this.bbsFbx$morphBaseValid = false;

            return;
        }

        if (this.bbsFbx$dirtyPositions.length < positions)
        {
            this.bbsFbx$dirtyPositions = Arrays.copyOf(this.bbsFbx$dirtyPositions,
                    Math.max(positions, this.bbsFbx$dirtyPositions.length * 2));
        }

        if (this.bbsFbx$dirtyNormals.length < normals)
        {
            this.bbsFbx$dirtyNormals = Arrays.copyOf(this.bbsFbx$dirtyNormals,
                    Math.max(normals, this.bbsFbx$dirtyNormals.length * 2));
        }

        System.arraycopy(positionIndices, 0, this.bbsFbx$dirtyPositions, this.bbsFbx$dirtyPositionCount, positionIndices.length);
        System.arraycopy(normalIndices, 0, this.bbsFbx$dirtyNormals, this.bbsFbx$dirtyNormalCount, normalIndices.length);

        this.bbsFbx$dirtyPositionCount = positions;
        this.bbsFbx$dirtyNormalCount = normals;
    }

    // ---------------------------------------------------------------
    // updateMesh
    // ---------------------------------------------------------------

    @Inject(method = "updateMesh", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$updateMeshOptimized(StencilMap stencilMap, CallbackInfo info)
    {
        /* Pose cache: skip skinning + upload entirely when the pose hasn't
         * changed since the last call (static props pay O(bones) instead of
         * O(vertices)). Shape-keyed models bypass the cache -- their blend
         * output depends on the per-frame key weights. */
        boolean shapeKeysActive = this.bbsFbx$shapeKeys != null && !this.bbsFbx$shapeKeys.shapeKeys.isEmpty()
                && this.data instanceof FBXCompiledData fbxCheck
                && fbxCheck.shapeKeyDeltas != null && !fbxCheck.shapeKeyDeltas.isEmpty();

        boolean unchanged = this.bbsFbx$poseUnchanged(stencilMap);

        if (!shapeKeysActive && unchanged)
        {
            info.cancel();

            return;
        }

        /* Boneless armatures have a zero-length matrices array, but data
         * producers that don't guard (e.g. this addon's own
         * FBXMeshCompiler.compile) still write weight > 0 with bone index 0
         * for unweighted vertices. The host's updateMesh then indexes
         * matrices[boneIndex] unconditionally and throws
         * ArrayIndexOutOfBoundsException ("Index 0 out of bounds for length
         * 0") before a single model in the UI palette can draw -- see
         * crash report crash-2026-08-03_10.16.14-client.txt. With no bones
         * there is nothing to skin, so upload the geometry unchanged and
         * skip the loop entirely. This guards every data source (FBX,
         * native BOBJ, merged), not just FBX shape-keyed meshes. */
        Matrix4f[] matrices = this.armature != null ? this.armature.matrices : null;

        if (matrices == null || matrices.length == 0)
        {
            info.cancel();
            this.bbsFbx$uploadUnskinned(stencilMap);

            return;
        }

        info.cancel();

        float[] restVertices = this.data.posData;
        float[] restNormals = this.data.normData;

        float[] sourceVertices = restVertices;
        float[] sourceNormals = restNormals;

        if (shapeKeysActive)
        {
            this.bbsFbx$blendShapeKeys((FBXCompiledData) this.data, restVertices, restNormals);

            sourceVertices = this.bbsFbx$morphedVertices;
            sourceNormals = this.bbsFbx$morphedNormals;
        }

        float[] bones = this.bbsFbx$boneMatrixScratch(matrices.length);

        for (int b = 0; b < matrices.length; b++)
        {
            matrices[b].get(bones, b * 16);
        }

        float[] newVertices = this.tmpVertices;
        float[] newNormals = this.tmpNormals;
        float[] weightData = this.data.weightData;
        int[] boneIndexData = this.data.boneIndexData;

        int vertexCount = this.count;

        boolean affine = this.bbsFbx$allBonesAffine(bones, matrices.length);

        if (bbsFbx$pool != null && vertexCount >= bbsFbx$PARALLEL_THRESHOLD)
        {
            this.bbsFbx$skinParallel(sourceVertices, sourceNormals, bones, weightData, boneIndexData,
                    newVertices, newNormals, affine, vertexCount);
        }
        else
        {
            this.bbsFbx$skinRange(sourceVertices, sourceNormals, bones, weightData, boneIndexData,
                    newVertices, newNormals, affine, 0, vertexCount);
        }

        boolean lightChanged = this.bbsFbx$rebuildLightIfStale(stencilMap);

        this.bbsFbx$processData(newVertices, newNormals, matrices);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vertexBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, newVertices, GL15.GL_DYNAMIC_DRAW);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.normalBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, newNormals, GL15.GL_DYNAMIC_DRAW);

        if (mchorse.bbs_mod.client.BBSRendering.isIrisShadersEnabled())
        {
            mchorse.bbs_mod.client.BBSRendering.calculateTangents(this.tmpTangents, newVertices, newNormals, this.data.texData);

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.tangentBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.tmpTangents, GL15.GL_DYNAMIC_DRAW);
        }

        if (lightChanged)
        {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.lightBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.tmpLight, GL15.GL_DYNAMIC_DRAW);
        }

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Splits {@code [0, vertexCount)} into {@code bbsFbx$WORKER_COUNT + 1}
     * contiguous chunks -- one run inline on the calling (render) thread,
     * the rest submitted to {@link #bbsFbx$pool} -- and blocks until every
     * chunk has written its slice of {@code newVertices}/{@code newNormals}.
     * Each chunk only ever touches indices inside its own range, so there's
     * no synchronization needed on the output arrays themselves, only on
     * "has every chunk finished" (the latch).
     *
     * <p>Any exception thrown inside a worker chunk is captured (the pool's
     * {@code Runnable} can't just throw - nothing would catch it) and
     * re-thrown here on the render thread once every chunk has reported in,
     * so a bad frame surfaces as a normal crash/log entry pointing at this
     * method instead of silently vanishing on a background thread.</p>
     */
    @Unique
    private void bbsFbx$skinParallel(
            float[] positions, float[] normals, float[] bones, float[] weightData, int[] boneIndexData,
            float[] newVertices, float[] newNormals, boolean affine, int vertexCount)
    {
        int chunks = bbsFbx$WORKER_COUNT + 1;
        int chunkSize = (vertexCount + chunks - 1) / chunks;

        CountDownLatch latch = new CountDownLatch(chunks - 1);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        int ownEnd = Math.min(vertexCount, chunkSize);

        for (int c = 1; c < chunks; c++)
        {
            int start = Math.min(vertexCount, c * chunkSize);
            int end = Math.min(vertexCount, start + chunkSize);

            if (start >= end)
            {
                latch.countDown();

                continue;
            }

            bbsFbx$pool.execute(() ->
            {
                try
                {
                    this.bbsFbx$skinRange(positions, normals, bones, weightData, boneIndexData,
                            newVertices, newNormals, affine, start, end);
                }
                catch (RuntimeException e)
                {
                    failure.compareAndSet(null, e);
                }
                finally
                {
                    latch.countDown();
                }
            });
        }

        // The render thread does its own share of the work instead of just
        // waiting on the pool - "chunk 0" isn't free labor for the workers,
        // it's the calling thread pulling its own weight too.
        if (ownEnd > 0)
        {
            this.bbsFbx$skinRange(positions, normals, bones, weightData, boneIndexData,
                    newVertices, newNormals, affine, 0, ownEnd);
        }

        try
        {
            latch.await();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();

            throw new RuntimeException("Interrupted while waiting on FBX skinning workers", e);
        }

        RuntimeException failed = failure.get();

        if (failed != null)
        {
            throw failed;
        }
    }

    /**
     * Linear-blend skinning for vertices {@code [start, end)}, producing the
     * exact same values as the host's loop with none of its object traffic:
     * no {@code Vector4f}/{@code Vector3f} scratch instances, no per-weight
     * {@code Matrix3f} rebuild, and the geometry/weight arrays hoisted into
     * locals instead of being re-read through {@code this.data} on every one
     * of the four weight slots per vertex.
     *
     * <p>Takes everything it needs as parameters and keeps no scratch state,
     * so it is trivially safe to run from several threads at once:
     * {@code bones}/{@code weightData}/{@code boneIndexData} are read-only
     * and fully built before any chunk starts, and every write lands at an
     * index owned by exactly one chunk.</p>
     *
     * <p>The final perspective divide is skipped when {@code w} came out at
     * exactly 1 -- the usual outcome for affine bone matrices with weights
     * summing to one, which covers rigidly-parented props outright -- and is
     * otherwise done as a single reciprocal shared by all three components
     * rather than three separate divides.</p>
     */
    @Unique
    private void bbsFbx$skinRange(
            float[] positions, float[] normals, float[] bones, float[] weightData, int[] boneIndexData,
            float[] newVertices, float[] newNormals, boolean affine, int start, int end)
    {
        if (affine)
        {
            this.bbsFbx$skinRangeAffine(positions, normals, bones, weightData, boneIndexData,
                    newVertices, newNormals, start, end);
        }
        else
        {
            this.bbsFbx$skinRangeProjective(positions, normals, bones, weightData, boneIndexData,
                    newVertices, newNormals, start, end);
        }
    }

    /**
     * Skinning for armatures with no projective row (see
     * {@link #bbsFbx$allBonesAffine}) -- every armature BBS builds, so this
     * is the path that actually runs.
     *
     * <p>Two things it does that the general version can't. The homogeneous
     * divisor is accumulated as a plain sum of weights instead of evaluating
     * a row of the matrix that is known to come out at 1. And a vertex owned
     * outright by a single bone at full weight -- every vertex of a rigidly
     * parented object, and a good share of an ordinary character's -- skips
     * the accumulators, the per-influence weight multiply and the divide, and
     * writes the transformed vertex straight out.</p>
     *
     * <p>Both shortcuts are exact, not approximations: multiplying by a
     * weight of exactly 1 is the identity, dividing by a divisor of exactly 1
     * is the identity, and the omitted matrix row provably evaluates to 1.
     * The results are bit-for-bit what the general path (and the host's own
     * loop) produce.</p>
     */
    @Unique
    private void bbsFbx$skinRangeAffine(
            float[] positions, float[] normals, float[] bones, float[] weightData, int[] boneIndexData,
            float[] newVertices, float[] newNormals, int start, int end)
    {
        for (int i = start; i < end; i++)
        {
            int p = i * 3;
            int b = i * 4;

            float x = positions[p];
            float y = positions[p + 1];
            float z = positions[p + 2];

            float nx = normals[p];
            float ny = normals[p + 1];
            float nz = normals[p + 2];

            if (weightData[b] == 1F && weightData[b + 1] <= 0F && weightData[b + 2] <= 0F && weightData[b + 3] <= 0F)
            {
                int m = boneIndexData[b] * 16;

                float m00 = bones[m];
                float m01 = bones[m + 1];
                float m02 = bones[m + 2];
                float m10 = bones[m + 4];
                float m11 = bones[m + 5];
                float m12 = bones[m + 6];
                float m20 = bones[m + 8];
                float m21 = bones[m + 9];
                float m22 = bones[m + 10];

                newVertices[p] = m00 * x + (m10 * y + (m20 * z + bones[m + 12]));
                newVertices[p + 1] = m01 * x + (m11 * y + (m21 * z + bones[m + 13]));
                newVertices[p + 2] = m02 * x + (m12 * y + (m22 * z + bones[m + 14]));

                newNormals[p] = m00 * nx + (m10 * ny + m20 * nz);
                newNormals[p + 1] = m01 * nx + (m11 * ny + m21 * nz);
                newNormals[p + 2] = m02 * nx + (m12 * ny + m22 * nz);

                continue;
            }

            float rx = 0F;
            float ry = 0F;
            float rz = 0F;
            float rw = 0F;

            float rnx = 0F;
            float rny = 0F;
            float rnz = 0F;

            int influences = 0;

            for (int w = 0; w < 4; w++)
            {
                float weight = weightData[b + w];

                if (weight > 0F)
                {
                    int m = boneIndexData[b + w] * 16;

                    float m00 = bones[m];
                    float m01 = bones[m + 1];
                    float m02 = bones[m + 2];
                    float m10 = bones[m + 4];
                    float m11 = bones[m + 5];
                    float m12 = bones[m + 6];
                    float m20 = bones[m + 8];
                    float m21 = bones[m + 9];
                    float m22 = bones[m + 10];

                    rx += (m00 * x + (m10 * y + (m20 * z + bones[m + 12]))) * weight;
                    ry += (m01 * x + (m11 * y + (m21 * z + bones[m + 13]))) * weight;
                    rz += (m02 * x + (m12 * y + (m22 * z + bones[m + 14]))) * weight;
                    rw += weight;

                    rnx += (m00 * nx + (m10 * ny + m20 * nz)) * weight;
                    rny += (m01 * nx + (m11 * ny + m21 * nz)) * weight;
                    rnz += (m02 * nx + (m12 * ny + m22 * nz)) * weight;

                    influences++;
                }
            }

            if (influences == 0)
            {
                rx = x;
                ry = y;
                rz = z;
                rw = 1F;

                rnx = nx;
                rny = ny;
                rnz = nz;
            }

            if (rw != 1F)
            {
                rx /= rw;
                ry /= rw;
                rz /= rw;
            }

            newVertices[p] = rx;
            newVertices[p + 1] = ry;
            newVertices[p + 2] = rz;

            newNormals[p] = rnx;
            newNormals[p + 1] = rny;
            newNormals[p + 2] = rnz;
        }
    }

    /** Full 4x4 skinning, kept for any data whose bones do carry a projective row. */
    @Unique
    private void bbsFbx$skinRangeProjective(
            float[] positions, float[] normals, float[] bones, float[] weightData, int[] boneIndexData,
            float[] newVertices, float[] newNormals, int start, int end)
    {
        for (int i = start; i < end; i++)
        {
            int p = i * 3;
            int b = i * 4;

            float x = positions[p];
            float y = positions[p + 1];
            float z = positions[p + 2];

            float nx = normals[p];
            float ny = normals[p + 1];
            float nz = normals[p + 2];

            float rx = 0F;
            float ry = 0F;
            float rz = 0F;
            float rw = 0F;

            float rnx = 0F;
            float rny = 0F;
            float rnz = 0F;

            int influences = 0;

            for (int w = 0; w < 4; w++)
            {
                float weight = weightData[b + w];

                if (weight > 0F)
                {
                    /* JOML column-major layout, as written by Matrix4f.get:
                     * m + 0..3 is column 0, m + 4..7 column 1, and so on,
                     * which makes m + 12..14 the translation. The normal
                     * matrix is the same upper-left 3x3 with the translation
                     * terms left off, exactly what Matrix3f.set(Matrix4f)
                     * would have copied out. */
                    int m = boneIndexData[b + w] * 16;

                    float m00 = bones[m];
                    float m01 = bones[m + 1];
                    float m02 = bones[m + 2];
                    float m03 = bones[m + 3];
                    float m10 = bones[m + 4];
                    float m11 = bones[m + 5];
                    float m12 = bones[m + 6];
                    float m13 = bones[m + 7];
                    float m20 = bones[m + 8];
                    float m21 = bones[m + 9];
                    float m22 = bones[m + 10];
                    float m23 = bones[m + 11];

                    /* Bracketed right-to-left to match the association JOML's
                     * own Vector4f.mul/Vector3f.mul use. Float addition isn't
                     * associative, so summing these terms in a different
                     * order would land a bit or two away from what the host
                     * produced; keeping the order identical makes the output
                     * bit-for-bit the same as before rather than merely
                     * close. */
                    rx += (m00 * x + (m10 * y + (m20 * z + bones[m + 12]))) * weight;
                    ry += (m01 * x + (m11 * y + (m21 * z + bones[m + 13]))) * weight;
                    rz += (m02 * x + (m12 * y + (m22 * z + bones[m + 14]))) * weight;
                    rw += (m03 * x + (m13 * y + (m23 * z + bones[m + 15]))) * weight;

                    rnx += (m00 * nx + (m10 * ny + m20 * nz)) * weight;
                    rny += (m01 * nx + (m11 * ny + m21 * nz)) * weight;
                    rnz += (m02 * nx + (m12 * ny + m22 * nz)) * weight;

                    influences++;
                }
            }

            if (influences == 0)
            {
                rx = x;
                ry = y;
                rz = z;
                rw = 1F;

                rnx = nx;
                rny = ny;
                rnz = nz;
            }

            /* Dividing by exactly 1 is a no-op, and that is the normal
             * outcome: affine bone matrices with weights summing to one, plus
             * every unweighted vertex. Skipping it there costs a compare and
             * saves three divides; elsewhere the divides are kept as-is
             * rather than turned into a reciprocal multiply, so the result
             * stays bit-identical to the host's. */
            if (rw != 1F)
            {
                rx /= rw;
                ry /= rw;
                rz /= rw;
            }

            newVertices[p] = rx;
            newVertices[p + 1] = ry;
            newVertices[p + 2] = rz;

            newNormals[p] = rnx;
            newNormals[p + 1] = rny;
            newNormals[p + 2] = rnz;
        }
    }

    /**
     * Uploads this VAO's buffers with the geometry completely unskinned --
     * the exact same output the host's {@code updateMesh} produces for
     * vertices with no bone influence (its "count == 0" branch keeps rest
     * position/normal, and {@code lightBone} stays -1 so every
     * {@code tmpLight} write collapses to 0 either way).
     */
    @Unique
    private void bbsFbx$uploadUnskinned(StencilMap stencilMap)
    {
        float[] newVertices = this.tmpVertices;
        float[] newNormals = this.tmpNormals;

        System.arraycopy(this.data.posData, 0, newVertices, 0, this.data.posData.length);
        System.arraycopy(this.data.normData, 0, newNormals, 0, this.data.normData.length);

        if (stencilMap != null)
        {
            Arrays.fill(this.tmpLight, 0);

            /* This path writes the light buffer behind the cache's back, so
             * drop the cache rather than let a later skinned frame assume the
             * GPU still holds what the cache last built. */
            this.bbsFbx$lightValid = false;
        }

        Matrix4f[] matrices = this.armature == null ? null : this.armature.matrices;

        this.bbsFbx$processData(newVertices, newNormals, matrices);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vertexBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, newVertices, GL15.GL_DYNAMIC_DRAW);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.normalBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, newNormals, GL15.GL_DYNAMIC_DRAW);

        if (mchorse.bbs_mod.client.BBSRendering.isIrisShadersEnabled())
        {
            mchorse.bbs_mod.client.BBSRendering.calculateTangents(this.tmpTangents, newVertices, newNormals, this.data.texData);

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.tangentBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.tmpTangents, GL15.GL_DYNAMIC_DRAW);
        }

        if (stencilMap != null)
        {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.lightBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.tmpLight, GL15.GL_DYNAMIC_DRAW);
        }

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Dispatches to the host VAO post-processor without hard-linking this
     * common mixin to a fork-specific descriptor. Base/CML expose
     * processData(float[], float[]), while BBS FS 2.5.2 adds the armature
     * matrix snapshot as a third argument.
     */
    @Unique
    private void bbsFbx$processData(float[] vertices, float[] normals, Matrix4f[] matrices)
    {
        try
        {
            Method method = bbsFbx$processDataMethod;

            if (method == null)
            {
                try
                {
                    method = BOBJModelVAO.class.getDeclaredMethod("processData", float[].class, float[].class, Matrix4f[].class);
                    bbsFbx$processDataHasMatrices = true;
                }
                catch (NoSuchMethodException ignored)
                {
                    method = BOBJModelVAO.class.getDeclaredMethod("processData", float[].class, float[].class);
                    bbsFbx$processDataHasMatrices = false;
                }

                method.setAccessible(true);
                bbsFbx$processDataMethod = method;
            }

            if (bbsFbx$processDataHasMatrices)
            {
                method.invoke(this, vertices, normals, matrices);
            }
            else
            {
                method.invoke(this, vertices, normals);
            }
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();

            if (cause instanceof RuntimeException runtime)
            {
                throw runtime;
            }

            if (cause instanceof Error error)
            {
                throw error;
            }

            throw new RuntimeException("Failed to post-process BOBJ VAO data", cause);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Unsupported BOBJ VAO processData signature", e);
        }
    }
}
