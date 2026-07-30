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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
            morphedVertices = new float[oldVertices.length];
            System.arraycopy(oldVertices, 0, morphedVertices, 0, oldVertices.length);

            morphedNormals = new float[oldNormals.length];
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

        Matrix4f[] matrices = this.armature.matrices;

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
}
