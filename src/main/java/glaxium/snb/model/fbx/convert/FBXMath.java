package glaxium.snb.model.fbx.convert;

import glaxium.snb.model.fbx.FBXMetadata;

import org.joml.Matrix4f;

/**
 * Small matrix utilities shared by the scene walker, armature builder, and
 * animation baker.
 */
public final class FBXMath
{
    private FBXMath() {}

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
