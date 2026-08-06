package glaxium.snb.model.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java scene graph that replaces Assimp's {@code AIScene} as the
 * shared intermediate between format parsers (FBX / glTF / GLB) and the
 * BOBJ conversion pipeline.
 */
public final class Scene
{
    public SceneNode rootNode;
    public final List<SceneMesh> meshes = new ArrayList<>();
    public final List<SceneMaterial> materials = new ArrayList<>();
    public final List<SceneAnimation> animations = new ArrayList<>();
    public final List<SceneTexture> textures = new ArrayList<>();
    public final SceneMetadata metadata = new SceneMetadata();
}
