package elgatopro300.bbsfbx.mixin;

import elgatopro300.bbsfbx.model.fbx.loaders.FBXCompiledData;
import elgatopro300.bbsfbx.model.fbx.loaders.IShapeKeyHolder;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.joml.Matrices;

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
    // Shape keys
    // ---------------------------------------------------------------

    @Inject(method = "updateMesh", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$updateMeshWithShapeKeys(StencilMap stencilMap, CallbackInfo info)
    {
        /* Pose cache: skip skinning + upload entirely when the pose hasn't
         * changed since the last call (static props pay O(bones) instead of
         * O(vertices)). Shape-keyed models bypass the cache -- their blend
         * output depends on the per-frame key weights. */
        boolean shapeKeysActive = this.bbsFbx$shapeKeys != null && !this.bbsFbx$shapeKeys.shapeKeys.isEmpty();

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

        if (!(this.data instanceof FBXCompiledData fbxData) || fbxData.shapeKeyVertices == null || fbxData.shapeKeyVertices.isEmpty())
        {
            return;
        }

        info.cancel();

        Vector4f sum = new Vector4f();
        Vector4f result = new Vector4f(0F, 0F, 0F, 0F);
        Vector3f sumNormal = new Vector3f();
        Vector3f resultNormal = new Vector3f();

        float[] oldVertices = this.data.posData;
        float[] oldNormals = this.data.normData;

        float[] morphedVertices = oldVertices;
        float[] morphedNormals = oldNormals;

        if (this.bbsFbx$shapeKeys != null && !this.bbsFbx$shapeKeys.shapeKeys.isEmpty())
        {
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

        float[] newVertices = this.tmpVertices;
        float[] newNormals = this.tmpNormals;

        for (int i = 0, c = this.count; i < c; i++)
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
                    Matrices.TEMP_3F.set(matrices[index]).transform(sumNormal);
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
