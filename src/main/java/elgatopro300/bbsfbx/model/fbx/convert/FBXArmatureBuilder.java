package elgatopro300.bbsfbx.model.fbx.convert;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AIScene;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link BOBJArmature} bone hierarchy for both the skinned path
 * (nodes -> bones, following Assimp's offset matrices) and the non-skinned
 * path (one bone per object, anchored at that object's Blender origin).
 */
public final class FBXArmatureBuilder
{
    private FBXArmatureBuilder() {}

    /**
     * Finds every AIBone referenced by any mesh in the scene, keyed by bone
     * name. {@code skinnedBoneMeshIndex} is filled in with, per bone name,
     * the index of the first mesh that uses it.
     */
    public static Map<String, AIBone> collectSkinnedBones(AIScene scene, Map<String, Integer> skinnedBoneMeshIndex)
    {
        Map<String, AIBone> skinnedBones = new HashMap<>();
        int numMeshes = scene.mNumMeshes();

        for (int i = 0; i < numMeshes; i++)
        {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            int numBones = aiMesh.mNumBones();

            for (int j = 0; j < numBones; j++)
            {
                AIBone aiBone = AIBone.create(aiMesh.mBones().get(j));
                String boneName = aiBone.mName().dataString();
                skinnedBones.putIfAbsent(boneName, aiBone);
                skinnedBoneMeshIndex.putIfAbsent(boneName, i);
            }
        }

        return skinnedBones;
    }

    /**
     * For each skinned bone, the rotation-only part of the world transform of
     * the mesh it belongs to. Skinned vertices are already rotated into mesh
     * space, so bones need the same rotation folded in to stay in sync.
     */
    public static Map<String, Matrix4f> collectBoneMeshRotations(Map<String, Integer> skinnedBoneMeshIndex, Map<Integer, Matrix4f> meshTransforms)
    {
        Map<String, Matrix4f> boneMeshRotations = new HashMap<>();

        for (Map.Entry<String, Integer> entry : skinnedBoneMeshIndex.entrySet())
        {
            Matrix4f meshWorld = meshTransforms.get(entry.getValue());
            if (meshWorld != null)
            {
                Quaternionf rot = new Quaternionf();
                meshWorld.getUnnormalizedRotation(rot);
                boneMeshRotations.put(entry.getKey(), new Matrix4f().rotation(rot));
            }
        }

        return boneMeshRotations;
    }

    /** Synthetic wrapper nodes Assimp/FBX always emits; never turned into bones. */
    private static boolean isSyntheticRoot(String nodeName)
    {
        return nodeName.equals("RootNode") || nodeName.equals("Armature");
    }

    /**
     * Non-skinned path: gives every scene node its own bone — both
     * mesh-owning objects AND mesh-less "Empty" objects (Blockbench/Blender
     * locators, held-item points, camera targets, group pivots, etc.) —
     * anchored at that node's Blender origin (requires OptimizeGraph OFF in
     * the loader so each object keeps its own node). An Empty shows up in
     * BBS exactly like any other limb/group ({@link mchorse.bbs_mod.cubic.model.bobj.BOBJModel}
     * derives its group list straight from {@code BOBJArmature.bones}), and
     * parent/child Empty chains are preserved via {@code nodeParents} so
     * nesting (Empty -> Empty -> mesh, etc.) round-trips correctly.
     *
     * @param nodeWorldTransforms every node's world transform, keyed by node
     *                            name (from {@link FBXSceneWalker}) — this is
     *                            what makes Empty (mesh-less) nodes buildable,
     *                            since they have no entry in {@code meshTransforms}.
     */
    public static void buildObjectBones(BOBJArmature armature, Map<String, Matrix4f> nodeWorldTransforms, Map<String, String> nodeParents, Matrix4f rootCorrection, float globalScale)
    {
        for (Map.Entry<String, Matrix4f> entry : nodeWorldTransforms.entrySet())
        {
            String objectName = entry.getKey();

            if (isSyntheticRoot(objectName) || armature.bones.containsKey(objectName))
            {
                continue;
            }

            String parentName = nodeParents.getOrDefault(objectName, "");
            // Only keep the parent link if that parent is itself becoming a
            // bone (i.e. it isn't a synthetic root); every real Empty/mesh
            // node in the chain becomes a bone now, so no other node is ever
            // filtered out from underneath it.
            if (!parentName.isEmpty() && isSyntheticRoot(parentName))
            {
                parentName = "";
            }

            Matrix4f nodeWorld = entry.getValue();
            Matrix4f boneRest = nodeWorld == null
                    ? new Matrix4f(rootCorrection)
                    : new Matrix4f(rootCorrection).mul(nodeWorld);

            boneRest.m30(boneRest.m30() * globalScale);
            boneRest.m31(boneRest.m31() * globalScale);
            boneRest.m32(boneRest.m32() * globalScale);
            boneRest.normalize3x3();

            armature.addBone(new BOBJBone(armature.bones.size(), objectName, parentName, boneRest));
        }
    }

    /**
     * Marks every node that is (or has a descendant that is) a skinned bone.
     */
    public static boolean markNeededNodes(AINode node, Set<String> skinnedBones, Set<String> neededNodes)
    {
        String name = node.mName().dataString();
        boolean needed = skinnedBones.contains(name);

        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();

        for (int i = 0; i < numChildren; i++)
        {
            AINode child = AINode.create(children.get(i));
            if (markNeededNodes(child, skinnedBones, neededNodes))
            {
                needed = true;
            }
        }

        if (needed)
        {
            neededNodes.add(name);
        }

        return needed;
    }

    /**
     * Skinned path: recursively builds the bone hierarchy from the node
     * tree, using each skinned bone's inverse-bind (offset) matrix for its
     * rest pose.
     */
    public static void buildSkinnedHierarchy(AINode node, String parentName, Matrix4f parentGlobal, BOBJArmature armature, Map<String, AIBone> skinnedBones, Map<String, Matrix4f> boneMeshRotations, Set<String> neededNodes, float[] globalScale, Matrix4f rootCorrection, float offsetX, float offsetY, float offsetZ)
    {
        String name = node.mName().dataString();
        Matrix4f local = FBXMath.toMatrix4f(node.mTransformation());
        Matrix4f global = new Matrix4f(parentGlobal).mul(local);

        String nextParent = parentName;
        boolean skip = name.equals("RootNode") || name.equals("Armature");

        if (neededNodes.contains(name) && !skip)
        {
            Matrix4f boneMat;
            if (skinnedBones.containsKey(name))
            {
                Matrix4f offset = FBXMath.toMatrix4f(skinnedBones.get(name).mOffsetMatrix());
                Matrix4f boneWorld = offset.invert();

                Matrix4f meshRotation = boneMeshRotations.get(name);
                if (meshRotation != null)
                {
                    boneWorld = new Matrix4f(meshRotation).mul(boneWorld);
                }

                boneMat = new Matrix4f(rootCorrection).mul(boneWorld);

                boneMat.m30(boneMat.m30() * globalScale[0]);
                boneMat.m31(boneMat.m31() * globalScale[0]);
                boneMat.m32(boneMat.m32() * globalScale[0]);

                boneMat.m30(boneMat.m30() + offsetX);
                boneMat.m31(boneMat.m31() + offsetY);
                boneMat.m32(boneMat.m32() + offsetZ);
            }
            else
            {
                boneMat = new Matrix4f(global);
            }

            if (armature.bones.isEmpty())
            {
                if (!skinnedBones.containsKey(name))
                {
                    boneMat.mul(rootCorrection);
                }
            }

            boneMat.normalize3x3();

            BOBJBone bone = new BOBJBone(armature.bones.size(), name, parentName, boneMat);
            armature.addBone(bone);
            nextParent = name;
        }

        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();

        for (int i = 0; i < numChildren; i++)
        {
            AINode child = AINode.create(children.get(i));
            buildSkinnedHierarchy(child, nextParent, global, armature, skinnedBones, boneMeshRotations, neededNodes, globalScale, rootCorrection, offsetX, offsetY, offsetZ);
        }
    }
}