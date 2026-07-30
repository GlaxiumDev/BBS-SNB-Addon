package elgatopro300.bbsfbx.model.fbx.convert;

import elgatopro300.bbsfbx.model.fbx.FBXMetadata;

import org.joml.Matrix4f;
import org.lwjgl.assimp.AIMatrix4x4;

/**
 * Small matrix utilities shared by the scene walker, armature builder, and
 * animation baker.
 */
public final class FBXMath
{
    private FBXMath() {}

    public static Matrix4f toMatrix4f(AIMatrix4x4 m)
    {
        return new Matrix4f(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4()
        );
    }

    public static Matrix4f buildRootCorrection(FBXMetadata metadata)
    {
        Matrix4f correction = new Matrix4f();

        if (metadata.upAxis == 2)
        {
            correction.rotateX((float) Math.toRadians(-90));
        }
        else if (metadata.upAxis == 0)
        {
            correction.rotateZ((float) Math.toRadians(90));
        }

        correction.rotateY((float) Math.PI);

        return correction;
    }
}