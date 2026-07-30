package elgatopro300.bbsfbx.model.fbx;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import java.util.List;
import java.util.Map;
import org.joml.Vector3f;
public class FBXMesh extends BOBJMesh
{
    public String texture;
    /** Diffuse/base color from the FBX material when it has NO image texture.
     *  Used to build a synthetic solid-color texture Link instead of writing a
     *  PNG. Stays null when the material had a real texture. */
    public float[] color;
    public int vertexBaseIndex;
    public int normalBaseIndex;
    public Map<String, List<Vector3f>> shapeKeyVertices;
    public Map<String, List<Vector3f>> shapeKeyNormals;
    public FBXMesh(String name)
    {
        super(name);
    }
}