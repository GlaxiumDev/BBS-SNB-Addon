package glaxium.snb.model.scene;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Hierarchy node with a local transform and optional mesh attachments. */
public final class SceneNode
{
    public String name = "";
    public final Matrix4f localTransform = new Matrix4f();
    public final List<Integer> meshIndices = new ArrayList<>();
    public final List<SceneNode> children = new ArrayList<>();

    public SceneNode() {}

    public SceneNode(String name)
    {
        this.name = name == null ? "" : name;
    }
}
