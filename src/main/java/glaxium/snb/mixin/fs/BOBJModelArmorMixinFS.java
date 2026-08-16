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
 * Gives FS Bend armor the Simple+ sharp hinge without relying on player-skin
 * UVs.
 *
 * <p>The Simple+ body locates its elbow, knee, and waist hinge vertices from
 * fixed regions of the 64x64 player texture. Armor uses the 64x32 armor atlas,
 * so that test selects unrelated vertices and pulls whole triangles across the
 * model. The old props_simple mesh already contains narrow transition rows at
 * the same bind-pose joint planes as the body. This mixin identifies those
 * rows by position and bone name, collapses each row to the shared hinge, and
 * then uses the same front/back angle correction as BBS's body VAO. The actual
 * hinge math lives in {@link ArmorJoint}, which must stay OUTSIDE the
 * {@code glaxium.snb.mixin.*} package: Mixin refuses to load classes in a
 * mixin package that a transformed target class references directly
 * (IllegalClassLoadError "cannot be referenced directly"), which used to
 * break Simple+ model setup on the FS fork entirely.</p>
 *
 * <p>This must be a mixin rather than a VAO subclass: BBS's VAO constructor
 * differs per fork (Base/FS pass only {@code data}, CML EDITION also passes an
 * armature), and a Fabric/Loom project only has one host jar on its compile
 * classpath at a time.</p>
 */
@Mixin(value = BOBJModelSimpleVAO.class, remap = false)
public abstract class BOBJModelArmorMixinFS
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
