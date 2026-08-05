package glaxium.snb.mixin;

import glaxium.snb.model.fbx.loaders.FBXCompiledData;
import glaxium.snb.model.fbx.loaders.IShapeKeyHolder;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.joml.Matrices;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL15;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fully replaces {@code BOBJModelVAO.updateMesh} to blend
 * {@code FBXCompiledData}'s shape-key vertex/normal deltas by the live
 * {@link ShapeKeys} weights BEFORE the bone-skinning blend, reusing the
 * same skinning/lighting math the host's own {@code updateMesh} uses so
 * behavior is identical when no shape keys are active.
 *
 * <p>Fork-agnostic: every {@code @Shadow}'d field and the {@code updateMesh}
 * signature below are byte-for-byte identical across the Base 1.7.7-1.20.4
 * and BBS CML EDITION 2.0-beta-1-1.20.4 jars (checked directly), and match
 * what the FS-targeted sibling addon already relies on. This addon only
 * supports one texture (or one flat color) per model, same as the host's own
 * {@code render()} already handles natively, so nothing here touches texture
 * binding or draw calls -- that divergence lives entirely in
 * {@code ModelInstanceMixin} (see {@code mixin/base}, {@code mixin/fs},
 * {@code mixin/cml}), which is the one spot where Base, FS and CML actually
 * disagree.</p>
 */
@Mixin(value = BOBJModelVAO.class, remap = false)
public abstract class BOBJModelVAOMixin implements IShapeKeyHolder
{
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

    @Shadow protected abstract void processData(float[] newVertices, float[] newNormals);

    /**
     * Scratch buffers for the shape-key-blended vertex/normal data before
     * bone skinning - reused across calls instead of allocating two fresh
     * {@code float[]} arrays (sized to the model's full vertex count) every
     * single {@code updateMesh} call that has any active shape-key weight.
     * Same "allocate once, reuse forever" pattern the host itself already
     * uses for {@code tmpVertices}/{@code tmpNormals} above. Re-allocated
     * only if the required length changes (model swap) - a plain length
     * check, no extra bookkeeping needed since {@code oldVertices.length}
     * is already read fresh every call.
     */
    private float[] bbsFbx$morphedVertices = new float[0];
    private float[] bbsFbx$morphedNormals = new float[0];

    @Unique
    private float[] bbsFbx$morphScratch(float[] current, int length)
    {
        return current.length == length ? current : new float[length];
    }

    /**
     * Precomputed per-bone normal (3x3) matrices, reused across the entire
     * vertex loop for a single {@code updateMesh} call instead of rebuilding
     * one via {@code Matrices.TEMP_3F.set(matrices[index])} every time a
     * vertex-weight pair references that bone. There are only
     * {@code matrices.length} unique bones, but up to {@code count * 4}
     * vertex-weight pairs - on a high-poly skinned FBX (hundreds of
     * thousands of vertices, ~100 bones) the old per-weight conversion redid
     * the same handful of 3x3s hundreds of thousands of times over, every
     * single frame an animation is actually playing. Building this array
     * once per call turns that into O(bones) instead of O(vertices).
     * Allocate-once-reuse-forever, same as the morph scratch buffers above -
     * only rebuilt (new {@code Matrix3f} instances) when the bone count
     * itself changes.
     */
    private Matrix3f[] bbsFbx$normalMatrices = new Matrix3f[0];

    @Unique
    private Matrix3f[] bbsFbx$normalMatrixScratch(int boneCount)
    {
        if (this.bbsFbx$normalMatrices.length != boneCount)
        {
            Matrix3f[] fresh = new Matrix3f[boneCount];

            for (int i = 0; i < boneCount; i++)
            {
                fresh[i] = new Matrix3f();
            }

            this.bbsFbx$normalMatrices = fresh;
        }

        return this.bbsFbx$normalMatrices;
    }

    /**
     * Worker pool the skin loop below splits across when a model is big
     * enough to make dispatch worth it (see {@link #bbsFbx$PARALLEL_THRESHOLD}).
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
     * ever write into disjoint slices of {@code newVertices}/{@code newNormals}/
     * {@code tmpLight} (each vertex index is owned by exactly one chunk) and
     * read {@code matrices}/{@code normalMatrices} (read-only from every
     * thread once built). The actual {@code glBufferData} calls after the
     * loop stay on the render thread, same as before -- OpenGL contexts are
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
     * render thread, same as before this change -- splitting work across
     * threads costs a fixed amount of dispatch/join overhead no matter how
     * small the job is, and for a handful of thousand vertices that
     * overhead is bigger than the loop itself. 20,000 is comfortably above
     * typical prop/character meshes (where the old single-threaded path is
     * already sub-millisecond) and comfortably below where a high-poly FBX
     * (hundreds of thousands of vertices) actually saturates a core.
     */
    private static final int bbsFbx$PARALLEL_THRESHOLD = 20_000;

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

        if (matrices != null)
        {
            for (int i = 0; i < matrixCount; i++)
            {
                Matrix4f m = matrices[i];
                sig[p] = m.m00(); sig[p + 1] = m.m01(); sig[p + 2] = m.m02(); sig[p + 3] = m.m03();
                sig[p + 4] = m.m10(); sig[p + 5] = m.m11(); sig[p + 6] = m.m12(); sig[p + 7] = m.m13();
                sig[p + 8] = m.m20(); sig[p + 9] = m.m21(); sig[p + 10] = m.m22(); sig[p + 11] = m.m23();
                sig[p + 12] = m.m30(); sig[p + 13] = m.m31(); sig[p + 14] = m.m32(); sig[p + 15] = m.m33();
                p += 16;
            }
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

    private ShapeKeys bbsFbx$shapeKeys;

    @Override
    public void bbsFbx$setShapeKeys(ShapeKeys shapeKeys)
    {
        this.bbsFbx$shapeKeys = shapeKeys;
    }

    // ---------------------------------------------------------------
    // Skinning (shape keys + per-bone normal-matrix cache)
    // ---------------------------------------------------------------

    /**
     * Was shape-key-only ({@code bbsFbx$updateMeshWithShapeKeys}): it took
     * over from the host's {@code updateMesh} only when shape keys were
     * active, so any ordinarily-animated model (armature playing, no shape
     * keys) fell through to the host's own copy of this exact loop every
     * single frame -- including the host's per-vertex-weight
     * {@code Matrices.TEMP_3F.set(matrices[index])} normal-matrix rebuild
     * (see {@link #bbsFbx$normalMatrices} doc). Now takes over unconditionally
     * for any real pose change (the pose cache above still short-circuits
     * unchanged frames exactly as before), so every animated model gets the
     * bone-matrix cache, not just shape-keyed ones. The shape-key blend
     * itself is untouched and still only runs when active.
     */
    @Inject(method = "updateMesh", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$updateMeshOptimized(StencilMap stencilMap, CallbackInfo info)
    {
        /* Pose cache: skip skinning + upload entirely when the pose hasn't
         * changed since the last call (static props pay O(bones) instead of
         * O(vertices)). Shape-keyed models bypass the cache -- their blend
         * output depends on the per-frame key weights. */
        boolean shapeKeysActive = this.bbsFbx$shapeKeys != null && !this.bbsFbx$shapeKeys.shapeKeys.isEmpty()
                && this.data instanceof FBXCompiledData fbxCheck
                && fbxCheck.shapeKeyVertices != null && !fbxCheck.shapeKeyVertices.isEmpty();

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

        /* From here on this always takes over the skin -- not just for
         * shape-keyed models. This is the exact same skinning/lighting math
         * the host's own updateMesh runs (see class doc); the shape-key
         * blend below is skipped entirely when inactive (same as the host
         * never doing it), and the per-bone normal-matrix cache is the only
         * real behavioral difference from the host's loop -- it changes
         * nothing about the output, just how many times the same handful of
         * 3x3s get built. */
        info.cancel();

        float[] oldVertices = this.data.posData;
        float[] oldNormals = this.data.normData;

        float[] morphedVertices = oldVertices;
        float[] morphedNormals = oldNormals;

        if (shapeKeysActive)
        {
            FBXCompiledData fbxData = (FBXCompiledData) this.data;

            this.bbsFbx$morphedVertices = this.bbsFbx$morphScratch(this.bbsFbx$morphedVertices, oldVertices.length);
            morphedVertices = this.bbsFbx$morphedVertices;
            System.arraycopy(oldVertices, 0, morphedVertices, 0, oldVertices.length);

            this.bbsFbx$morphedNormals = this.bbsFbx$morphScratch(this.bbsFbx$morphedNormals, oldNormals.length);
            morphedNormals = this.bbsFbx$morphedNormals;
            System.arraycopy(oldNormals, 0, morphedNormals, 0, oldNormals.length);

            for (Map.Entry<String, Float> entry : this.bbsFbx$shapeKeys.shapeKeys.entrySet())
            {
                float weight = entry.getValue();

                if (weight == 0F)
                {
                    continue;
                }

                float[] shapeVerts = fbxData.shapeKeyVertices.get(entry.getKey());
                float[] shapeNorms = fbxData.shapeKeyNormals.get(entry.getKey());

                if (shapeVerts != null)
                {
                    for (int i = 0; i < morphedVertices.length; i++)
                    {
                        morphedVertices[i] += weight * (shapeVerts[i] - oldVertices[i]);
                    }
                }

                if (shapeNorms != null)
                {
                    for (int i = 0; i < morphedNormals.length; i++)
                    {
                        morphedNormals[i] += weight * (shapeNorms[i] - oldNormals[i]);
                    }
                }
            }
        }

        /* Per-bone normal-matrix cache: build all matrices.length 3x3s once
         * up front instead of once per vertex-weight pair inside the loop
         * below (see field doc on bbsFbx$normalMatrices). */
        Matrix3f[] normalMatrices = this.bbsFbx$normalMatrixScratch(matrices.length);

        for (int b = 0; b < matrices.length; b++)
        {
            normalMatrices[b].set(matrices[b]);
        }

        float[] newVertices = this.tmpVertices;
        float[] newNormals = this.tmpNormals;

        int vertexCount = this.count;

        if (bbsFbx$pool != null && vertexCount >= bbsFbx$PARALLEL_THRESHOLD)
        {
            this.bbsFbx$skinParallel(morphedVertices, morphedNormals, matrices, normalMatrices,
                    newVertices, newNormals, stencilMap, vertexCount);
        }
        else
        {
            this.bbsFbx$skinRange(morphedVertices, morphedNormals, matrices, normalMatrices,
                    newVertices, newNormals, stencilMap, 0, vertexCount);
        }

        this.processData(newVertices, newNormals);

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
     * Splits {@code [0, vertexCount)} into {@code bbsFbx$WORKER_COUNT + 1}
     * contiguous chunks -- one run inline on the calling (render) thread,
     * the rest submitted to {@link #bbsFbx$pool} -- and blocks until every
     * chunk has written its slice of {@code newVertices}/{@code newNormals}/
     * {@code tmpLight}. Each chunk only ever touches indices inside its own
     * range, so there's no synchronization needed on the output arrays
     * themselves, only on "has every chunk finished" (the latch).
     *
     * <p>Any exception thrown inside a worker chunk is captured (the pool's
     * {@code Runnable} can't just throw - nothing would catch it) and
     * re-thrown here on the render thread once every chunk has reported in,
     * so a bad frame surfaces as a normal crash/log entry pointing at this
     * method instead of silently vanishing on a background thread.</p>
     */
    @Unique
    private void bbsFbx$skinParallel(
            float[] morphedVertices, float[] morphedNormals, Matrix4f[] matrices, Matrix3f[] normalMatrices,
            float[] newVertices, float[] newNormals, StencilMap stencilMap, int vertexCount)
    {
        int chunks = bbsFbx$WORKER_COUNT + 1;
        int chunkSize = (vertexCount + chunks - 1) / chunks;

        CountDownLatch latch = new CountDownLatch(chunks - 1);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        int ownStart = 0;
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
                    this.bbsFbx$skinRange(morphedVertices, morphedNormals, matrices, normalMatrices,
                            newVertices, newNormals, stencilMap, start, end);
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
        if (ownStart < ownEnd)
        {
            this.bbsFbx$skinRange(morphedVertices, morphedNormals, matrices, normalMatrices,
                    newVertices, newNormals, stencilMap, ownStart, ownEnd);
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
     * The actual skin math for vertices {@code [start, end)} -- identical to
     * the single-threaded loop this replaced, just bounded to a sub-range
     * and with its own local scratch vectors (required for this to be safe
     * to call from multiple threads at once: {@code sum}/{@code result}/
     * {@code sumNormal}/{@code resultNormal} used to be shared method-locals
     * reused every iteration, which is exactly the kind of state that can't
     * be shared across threads). {@code matrices}/{@code normalMatrices} are
     * read-only here and already fully built before any chunk starts, and
     * every write below lands at index {@code i}, which belongs to exactly
     * one chunk - no two chunks ever touch the same array slot.
     */
    @Unique
    private void bbsFbx$skinRange(
            float[] morphedVertices, float[] morphedNormals, Matrix4f[] matrices, Matrix3f[] normalMatrices,
            float[] newVertices, float[] newNormals, StencilMap stencilMap, int start, int end)
    {
        Vector4f sum = new Vector4f();
        Vector4f result = new Vector4f(0F, 0F, 0F, 0F);
        Vector3f sumNormal = new Vector3f();
        Vector3f resultNormal = new Vector3f();

        for (int i = start; i < end; i++)
        {
            int boneCount = 0;
            float maxWeight = -1;
            int lightBone = -1;

            for (int w = 0; w < 4; w++)
            {
                float weight = this.data.weightData[i * 4 + w];

                if (weight > 0)
                {
                    int index = this.data.boneIndexData[i * 4 + w];

                    sum.set(morphedVertices[i * 3], morphedVertices[i * 3 + 1], morphedVertices[i * 3 + 2], 1F);
                    matrices[index].transform(sum);
                    result.add(sum.mul(weight));

                    sumNormal.set(morphedNormals[i * 3], morphedNormals[i * 3 + 1], morphedNormals[i * 3 + 2]);
                    normalMatrices[index].transform(sumNormal);
                    resultNormal.add(sumNormal.mul(weight));

                    boneCount++;

                    if (weight > maxWeight)
                    {
                        lightBone = index;
                        maxWeight = weight;
                    }
                }
            }

            if (boneCount == 0)
            {
                result.set(morphedVertices[i * 3], morphedVertices[i * 3 + 1], morphedVertices[i * 3 + 2], 1F);
                resultNormal.set(morphedNormals[i * 3], morphedNormals[i * 3 + 1], morphedNormals[i * 3 + 2]);
            }

            result.x /= result.w;
            result.y /= result.w;
            result.z /= result.w;

            newVertices[i * 3] = result.x;
            newVertices[i * 3 + 1] = result.y;
            newVertices[i * 3 + 2] = result.z;

            newNormals[i * 3] = resultNormal.x;
            newNormals[i * 3 + 1] = resultNormal.y;
            newNormals[i * 3 + 2] = resultNormal.z;

            result.set(0F, 0F, 0F, 0F);
            resultNormal.set(0F, 0F, 0F);

            if (stencilMap != null)
            {
                this.tmpLight[i * 2] = Math.max(0, stencilMap.increment ? lightBone : 0);
                this.tmpLight[i * 2 + 1] = 0;
            }
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
            java.util.Arrays.fill(this.tmpLight, 0);
        }

        this.processData(newVertices, newNormals);

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
}