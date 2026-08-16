package glaxium.snb.mixin.basecml;

import glaxium.snb.model.bobj.ArmorJoint;
import glaxium.snb.model.bobj.EmoticonArmorSidecar;
import glaxium.snb.model.fbx.loaders.FBXCompiledData;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelSimpleVAO;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Base/CML counterpart of {@code fs.BOBJModelArmorMixinFS}: keeps the
 * Simple+ body's native UV-based 90-degree hinge while keeping the merged
 * armor shells out of it.
 *
 * <p>On FS the armor sidecar meshes are separate VAOs, so the FS mixin can
 * swap whole armor VAOs to the geometric {@link ArmorJoint} hinge. Base and
 * CML compile the whole model (body + armor) into ONE {@code CompiledData}
 * shared by a single {@code BOBJModelSimpleVAO}, so the native UV-band
 * classification sees the armor vertices too -- armor uses the 64x32 atlas,
 * whose V coordinates land inside the body's 64x64 hinge bands and pull
 * whole triangles across the model (the "ruined armor" symptom).</p>
 *
 * <p>Two-part fix, both parts in {@code processData}: the armor vertices'
 * V coordinates (read only by that classification, the GPU UV buffer is
 * uploaded once at VAO init and never re-read) are temporarily moved out of
 * the bands so the native pass only collects body vertices, then restored;
 * and the armor vertices -- identified per-vertex via
 * {@code FBXCompiledData.materialIndexData}/{@code materialNames} -- get the
 * same geometric hinge the FS armor uses, selected by bind-pose joint plane
 * and bone name.</p>
 */
@Mixin(value = BOBJModelSimpleVAO.class, remap = false)
public abstract class BOBJModelSimpleVAOMixinBaseCML
{
    private static final float ARM_BODY_HINGE_Y = 1.125F;
    private static final float LEG_HINGE_Y = 0.375F;
    private static final float HINGE_BAND = 0.09F;

    /** Vertex -> 1 when that vertex belongs to an armor mesh, else 0/null (no armor at all). */
    @Unique
    private int[] bbsFbx$armorVertexFlags;

    @Unique
    private int[] bbsFbx$armorIndices = new int[0];

    @Unique
    private float[] bbsFbx$armorSavedV = new float[0];

    @Unique
    private int bbsFbx$armorCount;

    @Unique
    private ArmorJoint[] bbsFbx$armorJoints;

    @Inject(method = "processData([F[F)V", at = @At("HEAD"), remap = false)
    private void bbsFbx$hingeArmorGeometrically(float[] newVertices, float[] newNormals, CallbackInfo info)
    {
        BOBJModelVAO self = (BOBJModelVAO) (Object) this;

        if (!(self.data instanceof FBXCompiledData fbx) || !this.bbsFbx$collectArmor(fbx))
        {
            return;
        }

        this.bbsFbx$hideArmorFromUvHinge(fbx);

        if (this.bbsFbx$armorJoints == null)
        {
            this.bbsFbx$classifyHingeVertices(self);
        }

        for (ArmorJoint joint : this.bbsFbx$armorJoints)
        {
            joint.process(self.data, self.armature, newVertices, newNormals);
        }
    }

    @Inject(method = "processData([F[F)V", at = @At("RETURN"), remap = false)
    private void bbsFbx$restoreArmorV(float[] newVertices, float[] newNormals, CallbackInfo info)
    {
        BOBJModelVAO self = (BOBJModelVAO) (Object) this;

        if (self.data instanceof FBXCompiledData fbx && this.bbsFbx$collectArmor(fbx))
        {
            this.bbsFbx$restoreArmorV(fbx);
        }
    }

    /**
     * Per-vertex armor flags from the material split. Builds once per VAO;
     * returns false when the data carries no armor material at all.
     */
    @Unique
    private boolean bbsFbx$collectArmor(FBXCompiledData fbx)
    {
        if (this.bbsFbx$armorVertexFlags != null)
        {
            return true;
        }

        int[] materialIndexData = fbx.materialIndexData;
        String[] materialNames = fbx.materialNames;

        if (materialIndexData == null || materialNames == null)
        {
            this.bbsFbx$armorVertexFlags = new int[0];

            return false;
        }

        boolean hasArmor = false;

        for (int i = 0; i < materialIndexData.length; i++)
        {
            int index = materialIndexData[i];

            if (index >= 0 && index < materialNames.length && EmoticonArmorSidecar.isArmorMesh(materialNames[index]))
            {
                hasArmor = true;

                break;
            }
        }

        if (!hasArmor)
        {
            this.bbsFbx$armorVertexFlags = new int[0];

            return false;
        }

        int[] flags = new int[materialIndexData.length];

        for (int i = 0; i < materialIndexData.length; i++)
        {
            int index = materialIndexData[i];

            if (index >= 0 && index < materialNames.length && EmoticonArmorSidecar.isArmorMesh(materialNames[index]))
            {
                flags[i] = 1;
            }
        }

        this.bbsFbx$armorVertexFlags = flags;

        return true;
    }

    /**
     * Moves every armor vertex's V coordinate out of the native UV-band
     * classification's range (values 2.0 are never equal to any 64th band),
     * remembering the originals for the RETURN handler. The GPU UV buffer is
     * uploaded once at VAO init from {@code texData} and never re-read, so
     * this transient mutation cannot affect what the model actually draws.
     */
    @Unique
    private void bbsFbx$hideArmorFromUvHinge(FBXCompiledData fbx)
    {
        int[] flags = this.bbsFbx$armorVertexFlags;
        int count = 0;

        for (int i = 0; i < flags.length; i++)
        {
            if (flags[i] == 1)
            {
                count++;
            }
        }

        if (this.bbsFbx$armorIndices.length < count)
        {
            this.bbsFbx$armorIndices = new int[count];
            this.bbsFbx$armorSavedV = new float[count];
        }

        this.bbsFbx$armorCount = count;

        float[] texData = fbx.texData;
        int slot = 0;

        for (int i = 0; i < flags.length; i++)
        {
            if (flags[i] == 1)
            {
                this.bbsFbx$armorIndices[slot] = i;
                this.bbsFbx$armorSavedV[slot] = texData[i * 2 + 1];
                texData[i * 2 + 1] = 2.0F;
                slot++;
            }
        }
    }

    @Unique
    private void bbsFbx$restoreArmorV(FBXCompiledData fbx)
    {
        float[] texData = fbx.texData;

        for (int j = 0; j < this.bbsFbx$armorCount; j++)
        {
            texData[this.bbsFbx$armorIndices[j] * 2 + 1] = this.bbsFbx$armorSavedV[j];
        }
    }

    @Unique
    private void bbsFbx$classifyHingeVertices(BOBJModelVAO self)
    {
        this.bbsFbx$armorJoints = new ArmorJoint[] {
                this.joint(self, "left_arm", "low_left_arm", ARM_BODY_HINGE_Y),
                this.joint(self, "right_arm", "low_right_arm", ARM_BODY_HINGE_Y),
                this.joint(self, "left_leg", "low_left_leg", LEG_HINGE_Y),
                this.joint(self, "right_leg", "low_leg_right", LEG_HINGE_Y),
                this.joint(self, "body", "low_body", ARM_BODY_HINGE_Y)
        };

        int[] flags = this.bbsFbx$armorVertexFlags;
        int vertices = self.data.posData.length / 3;

        for (int i = 0; i < vertices; i++)
        {
            if (i >= flags.length || flags[i] != 1)
            {
                continue;
            }

            float y = self.data.posData[i * 3 + 1];
            ArmorJoint joint = this.findJoint(self, i, y);

            if (joint != null)
            {
                float z = self.data.posData[i * 3 + 2];
                (z < 0F ? joint.back() : joint.front()).add(i);
            }
        }
    }

    @Unique
    private ArmorJoint joint(BOBJModelVAO self, String upper, String lower, float hingeY)
    {
        return new ArmorJoint(
                self.armature.bones.get(upper),
                self.armature.bones.get(lower),
                hingeY
        );
    }

    @Unique
    private ArmorJoint findJoint(BOBJModelVAO self, int vertex, float y)
    {
        boolean armPlane = near(y, ARM_BODY_HINGE_Y);
        boolean legPlane = near(y, LEG_HINGE_Y);

        if (!armPlane && !legPlane)
        {
            return null;
        }

        for (int i = 0; i < 4; i++)
        {
            int index = self.data.boneIndexData[vertex * 4 + i];
            float weight = self.data.weightData[vertex * 4 + i];

            if (index < 0 || weight <= 0F)
            {
                continue;
            }

            String name = self.armature.orderedBones.get(index).name;

            if (armPlane)
            {
                if (name.contains("left_arm")) return this.bbsFbx$armorJoints[0];
                if (name.contains("right_arm")) return this.bbsFbx$armorJoints[1];
                if (name.equals("body") || name.equals("low_body")) return this.bbsFbx$armorJoints[4];
            }

            if (legPlane)
            {
                if (name.contains("left_leg")) return this.bbsFbx$armorJoints[2];
                if (name.contains("right_leg") || name.contains("leg_right")) return this.bbsFbx$armorJoints[3];
            }
        }

        return null;
    }

    @Unique
    private static boolean near(float value, float target)
    {
        return Math.abs(value - target) <= HINGE_BAND;
    }
}
