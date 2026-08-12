package glaxium.snb.model.bobj.fs;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.utils.MathUtils;

import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies the Simple+ sharp hinge to armor without relying on player-skin UVs.
 *
 * <p>The Simple+ body locates its elbow, knee, and waist hinge vertices from
 * fixed regions of the 64x64 player texture. Armor uses the 64x32 armor atlas,
 * so that test selects unrelated vertices and pulls whole triangles across the
 * model. The old props_simple mesh already contains narrow transition rows at
 * the same bind-pose joint planes as the body. This VAO identifies those rows
 * by position and bone name, collapses each row to the shared hinge, and then
 * uses the same front/back angle correction as BBS's body VAO.</p>
 */
public final class BOBJModelArmorSimpleVAOFS extends BOBJModelVAO
{
    private static final float ARM_BODY_HINGE_Y = 1.125F;
    private static final float LEG_HINGE_Y = 0.375F;
    private static final float HINGE_BAND = 0.09F;

    private final ArmorJoint armLeft;
    private final ArmorJoint armRight;
    private final ArmorJoint legLeft;
    private final ArmorJoint legRight;
    private final ArmorJoint body;

    public BOBJModelArmorSimpleVAOFS(BOBJLoader.CompiledData data)
    {
        super(data);

        this.armLeft = this.joint("left_arm", "low_left_arm", ARM_BODY_HINGE_Y);
        this.armRight = this.joint("right_arm", "low_right_arm", ARM_BODY_HINGE_Y);
        this.legLeft = this.joint("left_leg", "low_left_leg", LEG_HINGE_Y);
        this.legRight = this.joint("right_leg", "low_leg_right", LEG_HINGE_Y);
        this.body = this.joint("body", "low_body", ARM_BODY_HINGE_Y);

        this.classifyHingeVertices();
    }

    private ArmorJoint joint(String upper, String lower, float hingeY)
    {
        return new ArmorJoint(
                this.armature.bones.get(upper),
                this.armature.bones.get(lower),
                hingeY
        );
    }

    private void classifyHingeVertices()
    {
        int vertices = this.data.posData.length / 3;

        for (int i = 0; i < vertices; i++)
        {
            float y = this.data.posData[i * 3 + 1];
            ArmorJoint joint = this.findJoint(i, y);

            if (joint != null)
            {
                float z = this.data.posData[i * 3 + 2];
                (z < 0F ? joint.back : joint.front).add(i);
            }
        }
    }

    private ArmorJoint findJoint(int vertex, float y)
    {
        boolean armPlane = near(y, ARM_BODY_HINGE_Y);
        boolean legPlane = near(y, LEG_HINGE_Y);

        if (!armPlane && !legPlane)
        {
            return null;
        }

        for (int i = 0; i < 4; i++)
        {
            int index = this.data.boneIndexData[vertex * 4 + i];
            float weight = this.data.weightData[vertex * 4 + i];

            if (index < 0 || weight <= 0F)
            {
                continue;
            }

            String name = this.armature.orderedBones.get(index).name;

            if (armPlane)
            {
                if (name.contains("left_arm")) return this.armLeft;
                if (name.contains("right_arm")) return this.armRight;
                if (name.equals("body") || name.equals("low_body")) return this.body;
            }

            if (legPlane)
            {
                if (name.contains("left_leg")) return this.legLeft;
                if (name.contains("right_leg") || name.contains("leg_right")) return this.legRight;
            }
        }

        return null;
    }

    private static boolean near(float value, float target)
    {
        return Math.abs(value - target) <= HINGE_BAND;
    }

    @Override
    protected void processData(float[] newVertices, float[] newNormals)
    {
        this.armRight.process(this.data, this.armature, newVertices, newNormals);
        this.armLeft.process(this.data, this.armature, newVertices, newNormals);
        this.legRight.process(this.data, this.armature, newVertices, newNormals);
        this.legLeft.process(this.data, this.armature, newVertices, newNormals);
        this.body.process(this.data, this.armature, newVertices, newNormals);
    }

    private static final class ArmorJoint
    {
        private final Vector4f temporary = new Vector4f();
        private final List<Integer> front = new ArrayList<>();
        private final List<Integer> back = new ArrayList<>();
        private final BOBJBone upper;
        private final BOBJBone lower;
        private final float hingeY;

        private ArmorJoint(BOBJBone upper, BOBJBone lower, float hingeY)
        {
            this.upper = upper;
            this.lower = lower;
            this.hingeY = hingeY;
        }

        private void process(
                BOBJLoader.CompiledData data,
                BOBJArmature armature,
                float[] positions,
                float[] normals)
        {
            if (this.upper == null || this.lower == null)
            {
                return;
            }

            float rotation = this.lower.transform.rotate.x;
            float frontFactor = MathUtils.clamp(
                    (rotation + (float) Math.PI / 2F) / (float) Math.PI,
                    0F,
                    1F
            );

            this.processSide(data, armature, this.front, positions, normals, frontFactor);
            this.processSide(data, armature, this.back, positions, normals, 1F - frontFactor);
        }

        private void processSide(
                BOBJLoader.CompiledData data,
                BOBJArmature armature,
                List<Integer> indices,
                float[] positions,
                float[] normals,
                float factor)
        {
            int previous = 0;

            for (int index : indices)
            {
                float x = data.posData[index * 3];
                float z = data.posData[index * 3 + 2];
                float halfDepth = Math.abs(z);
                float y = this.hingeY + (factor * 2F - 1F) * halfDepth;

                this.temporary.set(x, y, z, 1F);
                armature.matrices[this.upper.index].transform(this.temporary);

                positions[index * 3] = this.temporary.x;
                positions[index * 3 + 1] = this.temporary.y;
                positions[index * 3 + 2] = this.temporary.z;

                /* Match BBS's sharp-joint normal handling. */
                int base = index - index % 3;
                int a = index - base;
                int b = previous - base;
                int c = 0;

                if (b >= 0)
                {
                    if ((a == 0 && b == 2) || (b == 0 && a == 2)) c = 1;
                    else if ((a == 0 && b == 1) || (b == 0 && a == 1)) c = 2;
                }
                else
                {
                    c = a == 1 ? 0 : 1;
                }

                c += base;

                normals[index * 3] = normals[c * 3];
                normals[index * 3 + 1] = normals[c * 3 + 1];
                normals[index * 3 + 2] = normals[c * 3 + 2];

                if (b >= 0)
                {
                    normals[previous * 3] = normals[c * 3];
                    normals[previous * 3 + 1] = normals[c * 3 + 1];
                    normals[previous * 3 + 2] = normals[c * 3 + 2];
                }

                previous = index;
            }
        }
    }
}
