package glaxium.snb.model.scene;

/**
 * Absolute-position morph target (Assimp AnimMesh / FBX blend shape /
 * glTF morph target after applying base + delta).
 */
public final class SceneMorphTarget
{
    public String name = "";
    /** Absolute xyz positions; length == mesh vertexCount * 3, or empty. */
    public float[] positions = new float[0];
    /** Absolute xyz normals; length == mesh vertexCount * 3, or empty. */
    public float[] normals = new float[0];
}
