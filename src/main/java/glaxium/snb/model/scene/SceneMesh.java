package glaxium.snb.model.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Triangle (or pre-triangulation polygon) mesh with optional skinning and
 * morph targets. Positions/normals/uvs are parallel arrays of length
 * {@link #vertexCount()}; indices are flat triples after triangulation.
 */
public final class SceneMesh
{
    public String name = "";
    public int materialIndex = -1;

    /** xyz packed: length == vertexCount * 3 */
    public float[] positions = new float[0];
    /** xyz packed; empty when missing */
    public float[] normals = new float[0];
    /** uv packed; empty when missing */
    public float[] uvs = new float[0];
    /** Face indices (polygon or triangle). Negative sentinel ends a polygon for FBX-style lists. */
    public int[] indices = new int[0];
    /** True when {@link #indices} are already triangle triples. */
    public boolean triangulated = true;

    public final List<SceneBone> bones = new ArrayList<>();
    public final List<SceneMorphTarget> morphTargets = new ArrayList<>();

    public int vertexCount()
    {
        return this.positions.length / 3;
    }
}
