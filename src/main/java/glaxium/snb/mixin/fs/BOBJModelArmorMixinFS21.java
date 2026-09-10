package glaxium.snb.mixin.fs;

import glaxium.snb.model.bobj.ArmorJoint;
import glaxium.snb.model.bobj.EmoticonArmorSidecar;

import mchorse.bbs_mod.cubic.render.vao.BOBJModelSimpleVAO;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BBS&nbsp;2.1 (FS fork) counterpart of {@link BOBJModelArmorMixinFS}.
 *
 * <p>FS&nbsp;2.5 skins then hinges in {@code processData([F[F[Matrix4f;)V}.
 * BBS&nbsp;2.1 keeps FS's per-mesh armor VAOs but calls the Base/CML-shaped
 * {@code processData([F[F)V} after skinning. The 3-arg FS mixin cannot apply;
 * the Base/CML merged-{@code FBXCompiledData} mixin early-outs on plain
 * per-mesh {@code CompiledData}. Without this hook, native UV-band hinging
 * runs on the 64x32 armor atlas and tears simple armor off the bones.</p>
 */
@Mixin(value = BOBJModelSimpleVAO.class, remap = false)
public abstract class BOBJModelArmorMixinFS21
{
    private static final float ARM_BODY_HINGE_Y = 1.125F;
    private static final float LEG_HINGE_Y = 0.375F;
    private static final float HINGE_BAND = 0.09F;

    @Unique private ArmorJoint[] bbsFbx$armorJoints;

    @Inject(method = "processData([F[F)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$applyGeometricHingeToArmor(float[] newVertices, float[] newNormals, CallbackInfo info)
    {
        BOBJModelVAO self = (BOBJModelVAO) (Object) this;
        String mesh = self.data == null || self.data.mesh == null ? null : self.data.mesh.name;

        if (EmoticonArmorSidecar.isArmorMesh(mesh))
        {
            for (ArmorJoint joint : this.armorJoints(self))
            {
                joint.process(self.data, self.armature, newVertices, newNormals);
            }

            info.cancel();
        }
    }

    @Unique
    private ArmorJoint[] armorJoints(BOBJModelVAO self)
    {
        if (this.bbsFbx$armorJoints == null)
        {
            this.bbsFbx$armorJoints = new ArmorJoint[] {
                    this.joint(self, "left_arm", "low_left_arm", ARM_BODY_HINGE_Y),
                    this.joint(self, "right_arm", "low_right_arm", ARM_BODY_HINGE_Y),
                    this.joint(self, "left_leg", "low_left_leg", LEG_HINGE_Y),
                    this.joint(self, "right_leg", "low_leg_right", LEG_HINGE_Y),
                    this.joint(self, "body", "low_body", ARM_BODY_HINGE_Y)
            };

            this.classifyHingeVertices(self);
        }

        return this.bbsFbx$armorJoints;
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
    private void classifyHingeVertices(BOBJModelVAO self)
    {
        int vertices = self.data.posData.length / 3;

        for (int i = 0; i < vertices; i++)
        {
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
