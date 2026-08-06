package glaxium.snb.model.fbx.convert;

import glaxium.snb.model.scene.SceneNode;

import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads the scene node tree in a single traversal: per-mesh world transforms,
 * which node each mesh belongs to, each node's parent name, and every node's
 * local (relative-to-parent) transform.
 */
public final class FBXSceneWalker
{
    private FBXSceneWalker() {}

    public static Map<Integer, Matrix4f> collectMeshTransforms(SceneNode rootNode, Map<Integer, String> meshNodeNames, Map<String, String> nodeParents, Map<String, Matrix4f> nodeLocals, Map<String, Matrix4f> nodeWorldTransforms)
    {
        Map<Integer, Matrix4f> meshTransforms = new HashMap<>();
        collectMeshTransforms(rootNode, new Matrix4f(), meshTransforms, meshNodeNames, nodeParents, nodeLocals, nodeWorldTransforms);
        return meshTransforms;
    }

    private static void collectMeshTransforms(SceneNode node, Matrix4f parentGlobal, Map<Integer, Matrix4f> meshTransforms, Map<Integer, String> meshNodeNames, Map<String, String> nodeParents, Map<String, Matrix4f> nodeLocals, Map<String, Matrix4f> nodeWorldTransforms)
    {
        Matrix4f local = new Matrix4f(node.localTransform);
        Matrix4f global = new Matrix4f(parentGlobal).mul(local);

        String nodeName = node.name;
        nodeLocals.put(nodeName, local);
        nodeWorldTransforms.put(nodeName, new Matrix4f(global));

        for (int meshIndex : node.meshIndices)
        {
            meshTransforms.putIfAbsent(meshIndex, new Matrix4f(global));
            meshNodeNames.putIfAbsent(meshIndex, nodeName);
        }

        for (SceneNode child : node.children)
        {
            String childName = child.name;
            String parentForChild = (nodeName.equals("RootNode") || nodeName.equals("Armature")) ? "" : nodeName;
            nodeParents.putIfAbsent(childName, parentForChild);
            collectMeshTransforms(child, global, meshTransforms, meshNodeNames, nodeParents, nodeLocals, nodeWorldTransforms);
        }
    }
}
