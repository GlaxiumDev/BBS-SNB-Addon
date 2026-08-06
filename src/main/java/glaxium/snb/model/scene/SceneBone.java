package glaxium.snb.model.scene;

import org.joml.Matrix4f;

/**
 * Skinning bone attached to a mesh: inverse-bind matrix plus per-vertex
 * weights.
 */
public final class SceneBone
{
    public String name = "";
    public final Matrix4f offsetMatrix = new Matrix4f();
    public int[] vertexIds = new int[0];
    public float[] weights = new float[0];
}
