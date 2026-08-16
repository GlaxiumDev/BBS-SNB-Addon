package glaxium.snb.model.bobj;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.utils.MathUtils;

import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * One geometric joint for an armor mesh (helmet/chest/leggings/feet) merged
 * into a Simple+ model. The Simple+ body locates its elbow, knee, and waist
 * hinge vertices from fixed regions of the 64x64 player texture; armor uses
 * the 64x32 armor atlas, so that test selects unrelated vertices and pulls
 * whole triangles across the model. The old props_simple armor meshes already
 * contain narrow transition rows at the same bind-pose joint planes as the
 * body, so {@link BOBJModelArmorMixinFS} identifies those rows by position
 * and bone name, collapses each row to the shared hinge, and then applies the
 * same front/back angle correction as BBS's body VAO.
 *
 * <p>Deliberately a plain class, not an inner class of the mixin: Mixin
 * refuses to load classes that live in a mixin package
 * ({@code glaxium.snb.mixin.*}) when a transformed target class references
 * them (IllegalClassLoadError "cannot be referenced directly"), which broke
 * Simple+ model setup on the FS fork.</p>
 */
public final class ArmorJoint
{
    private final Vector4f temporary = new Vector4f();
    private final List<Integer> front = new ArrayList<>();
    private final List<Integer> back = new ArrayList<>();
    private final BOBJBone upper;
    private final BOBJBone lower;
    private final float hingeY;

    public ArmorJoint(BOBJBone upper, BOBJBone lower, float hingeY)
    {
        this.upper = upper;
        this.lower = lower;
        this.hingeY = hingeY;
    }

    public List<Integer> front()
    {
        return this.front;
    }

    public List<Integer> back()
    {
        return this.back;
    }

    public void process(BOBJLoader.CompiledData data, BOBJArmature armature, float[] positions, float[] normals)
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
