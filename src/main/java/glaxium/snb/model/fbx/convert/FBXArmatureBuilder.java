package glaxium.snb.model.fbx.convert;

import glaxium.snb.model.fbx.scene.JavaScene;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link BOBJArmature} bone hierarchy for both the skinned path
 * (nodes -> bones, following the imported offset matrices) and the non-skinned
 * path (one bone per object, anchored at that object's Blender origin).
 */
public final class FBXArmatureBuilder
{
    private FBXArmatureBuilder() {}

    /**
     * Finds every skinning bone referenced by any mesh, keyed by bone
     * name. {@code skinnedBoneMeshIndex} is filled in with, per bone name,
     * the index of the first mesh that uses it.
     */
    public static Map<String, JavaScene.Bone> collectSkinnedBones(JavaScene scene, Map<String, Integer> skinnedBoneMeshIndex)
    {
        Map<String, JavaScene.Bone> skinnedBones = new HashMap<>();

        for (int i = 0; i < scene.meshes.size(); i++)
        {
            JavaScene.Mesh mesh = scene.meshes.get(i);

            for (JavaScene.Bone bone : mesh.bones)
            {
                String boneName = bone.name;
                skinnedBones.putIfAbsent(boneName, bone);
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

    /** Synthetic wrapper nodes emitted by importers/exporters; never turned into bones. */
    private static boolean isSyntheticRoot(String nodeName)
    {
        return nodeName.equals("RootNode") || nodeName.equals("Armature");
    }

    /**
     * Inverse-bind matrices are sometimes in <b>mesh-local</b> space
     * (Blender FBX: IBMs in meters, mesh node carries the 100x scale separately)
     * and sometimes already in <b>scene</b> space (glTF round-trips of Source/
     * cm rigs: IBM^-1 ≈ nodeWorld, including the armature's 0.01 scale). Using
     * the mesh-local boneSpace correction on a scene-space IBM leaves
     * non-joint parents (COG, armature roots that glTF didn't list as skin
     * joints) in a different unit from their skinned children -- animation
     * deltas then pick up a 100x scale and the model vanishes.
     */
    public static boolean ibmInSceneSpace(Map<String, JavaScene.Bone> skinnedBones, Map<String, Matrix4f> nodeWorldTransforms,
            Map<String, Integer> skinnedBoneMeshIndex, Map<Integer, Matrix4f> meshTransforms)
    {
        int sceneVotes = 0;
        int meshVotes = 0;

        for (Map.Entry<String, JavaScene.Bone> entry : skinnedBones.entrySet())
        {
            Matrix4f nodeWorld = nodeWorldTransforms.get(entry.getKey());

            if (nodeWorld == null)
            {
                continue;
            }

            Matrix4f ibmInv = new Matrix4f(entry.getValue().offsetMatrix).invert();
            float errScene = translationDistance(ibmInv, nodeWorld);

            Integer meshIndex = skinnedBoneMeshIndex.get(entry.getKey());
            Matrix4f meshWorld = meshIndex == null ? null : meshTransforms.get(meshIndex);
            float errMesh = Float.POSITIVE_INFINITY;

            if (meshWorld != null)
            {
                Matrix4f meshLocal = new Matrix4f(meshWorld).invert().mul(nodeWorld);
                errMesh = translationDistance(ibmInv, meshLocal);
            }

            if (errScene <= errMesh)
            {
                sceneVotes++;
            }
            else
            {
                meshVotes++;
            }
        }

        return sceneVotes > meshVotes;
    }

    private static float translationDistance(Matrix4f a, Matrix4f b)
    {
        float dx = a.m30() - b.m30();
        float dy = a.m31() - b.m31();
        float dz = a.m32() - b.m32();

        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Detects the Blender FBX layout where the mesh node carries a 100x
     * centimetre scale, the skeleton node tree stays at scale 1, and the
     * inverse-bind matrices compensate with a 0.01 scale. Animation keys are
     * consequently in centimetres while both geometry and IBM-derived bone
     * rests are in metres.
     *
     * <p>This is deliberately a structural test rather than a filename or a
     * blanket FBX rule. Normal Blender FBX rigs (armature and mesh both at
     * 100x), Source rigs, and already-correct scene-space/glTF rigs return
     * {@code 1}. The returned value converts raw node-animation translations
     * to the IBM/geometry unit for the split-scale layout.</p>
     */
    public static float detectSplitMeshAnimationScale(Map<String, JavaScene.Bone> skinnedBones,
            Map<String, Matrix4f> nodeWorldTransforms, Map<String, Integer> skinnedBoneMeshIndex,
            Map<Integer, Matrix4f> meshTransforms, boolean ibmInSceneSpace)
    {
        if (ibmInSceneSpace || skinnedBones.isEmpty())
        {
            return 1F;
        }

        List<Float> candidates = new ArrayList<>();
        int comparable = 0;

        for (Map.Entry<String, JavaScene.Bone> entry : skinnedBones.entrySet())
        {
            Matrix4f nodeWorld = nodeWorldTransforms.get(entry.getKey());
            Integer meshIndex = skinnedBoneMeshIndex.get(entry.getKey());
            Matrix4f meshWorld = meshIndex == null ? null : meshTransforms.get(meshIndex);

            if (nodeWorld == null || meshWorld == null)
            {
                continue;
            }

            comparable++;

            Matrix4f bindWorld = new Matrix4f(entry.getValue().offsetMatrix).invert();
            float meshScale = maxScale(meshWorld);
            float nodeScale = maxScale(nodeWorld);
            float bindScale = maxScale(bindWorld);

            if (meshScale < 10F || nodeScale < 0.5F || nodeScale > 2F ||
                    bindScale < 1e-4F || bindScale > 0.1F)
            {
                continue;
            }

            float candidate = bindScale / nodeScale;
            float reconstructedScale = meshScale * candidate;

            /* Full mesh * IBM should return to the skeleton's scene scale.
             * Allow exporter/float noise, but reject unrelated tiny scales. */
            if (reconstructedScale >= 0.8F && reconstructedScale <= 1.25F)
            {
                candidates.add(candidate);
            }
        }

        if (candidates.size() < 3 || candidates.size() * 4 < comparable * 3)
        {
            return 1F;
        }

        Collections.sort(candidates);
        return candidates.get(candidates.size() / 2);
    }

    private static float maxScale(Matrix4f matrix)
    {
        Vector3f scale = new Vector3f();
        matrix.getScale(scale);
        return Math.max(scale.x, Math.max(scale.y, scale.z));
    }

    /**
     * The transform that takes a raw node world matrix into the same space
     * the skinned bones' rest matrices live in.
     *
     * <p>A skinned bone's rest comes from its inverse-bind (offset) matrix.
     * When that IBM is mesh-local (Blender FBX), {@link #buildSkinnedHierarchy}
     * lifts it with {@code rootCorrection * meshRotation}, and this method
     * returns {@code rootCorrection * meshRotation * meshWorld^-1} so
     * non-joint nodes land in the same space. When the IBM is already scene
     * space (typical glTF), both paths use {@code rootCorrection} alone --
     * dividing out {@code meshWorld} would re-introduce the centimetre unit
     * the IBM already left behind.</p>
     */
    public static Matrix4f buildBoneSpace(Matrix4f rootCorrection, Map<String, Integer> skinnedBoneMeshIndex,
            Map<Integer, Matrix4f> meshTransforms, boolean ibmInSceneSpace)
    {
        if (ibmInSceneSpace)
        {
            return new Matrix4f(rootCorrection);
        }

        Matrix4f meshWorld = firstSkinnedMeshTransform(skinnedBoneMeshIndex, meshTransforms);

        if (meshWorld == null)
        {
            return new Matrix4f(rootCorrection);
        }

        Quaternionf rot = new Quaternionf();
        meshWorld.getUnnormalizedRotation(rot);

        return new Matrix4f(rootCorrection).rotate(rot).mul(new Matrix4f(meshWorld).invert());
    }

    /** @deprecated use {@link #buildBoneSpace(Matrix4f, Map, Map, boolean)} */
    @Deprecated
    public static Matrix4f buildBoneSpace(Matrix4f rootCorrection, Map<String, Integer> skinnedBoneMeshIndex, Map<Integer, Matrix4f> meshTransforms)
    {
        return buildBoneSpace(rootCorrection, skinnedBoneMeshIndex, meshTransforms, false);
    }

    /**
     * The skinned mesh with the lowest index, so every caller that needs "the
     * mesh space this rig's bind pose is expressed in" agrees on one answer.
     * Rigs with several skinned meshes under one armature share the same node
     * transform anyway.
     */
    public static Matrix4f firstSkinnedMeshTransform(Map<String, Integer> skinnedBoneMeshIndex, Map<Integer, Matrix4f> meshTransforms)
    {
        Integer meshIndex = null;

        for (Integer candidate : skinnedBoneMeshIndex.values())
        {
            if (meshIndex == null || candidate < meshIndex)
            {
                meshIndex = candidate;
            }
        }

        return meshIndex == null ? null : meshTransforms.get(meshIndex);
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
    public static boolean markNeededNodes(JavaScene.Node node, Set<String> skinnedBones, Set<String> neededNodes)
    {
        String name = node.name;
        boolean needed = skinnedBones.contains(name);

        for (JavaScene.Node child : node.children)
        {
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
     * rest pose, and {@code boneSpace} (see {@link #buildBoneSpace}) for the
     * nodes in the chain that aren't skinning bones so every bone's rest
     * matrix ends up in one space.
     *
     * @param ibmInSceneSpace when true, IBMs already include the mesh node
     *                        transform -- skip the mesh-rotation multiply that
     *                        mesh-local Blender FBX IBMs need
     */
    public static void buildSkinnedHierarchy(JavaScene.Node node, String parentName, Matrix4f parentGlobal, BOBJArmature armature, Map<String, JavaScene.Bone> skinnedBones, Map<String, Matrix4f> boneMeshRotations, Set<String> neededNodes, float[] globalScale, Matrix4f rootCorrection, Matrix4f boneSpace, boolean ibmInSceneSpace, float offsetX, float offsetY, float offsetZ)
    {
        String name = node.name;
        Matrix4f local = new Matrix4f(node.transform);
        Matrix4f global = new Matrix4f(parentGlobal).mul(local);

        String nextParent = parentName;
        boolean skip = name.equals("RootNode") || name.equals("Armature");

        if (neededNodes.contains(name) && !skip)
        {
            Matrix4f boneMat;
            if (skinnedBones.containsKey(name))
            {
                Matrix4f offset = new Matrix4f(skinnedBones.get(name).offsetMatrix);
                Matrix4f boneWorld = offset.invert();

                if (!ibmInSceneSpace)
                {
                    Matrix4f meshRotation = boneMeshRotations.get(name);
                    if (meshRotation != null)
                    {
                        boneWorld = new Matrix4f(meshRotation).mul(boneWorld);
                    }
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
                boneMat = new Matrix4f(boneSpace).mul(global);

                boneMat.m30(boneMat.m30() * globalScale[0]);
                boneMat.m31(boneMat.m31() * globalScale[0]);
                boneMat.m32(boneMat.m32() * globalScale[0]);

                boneMat.m30(boneMat.m30() + offsetX);
                boneMat.m31(boneMat.m31() + offsetY);
                boneMat.m32(boneMat.m32() + offsetZ);
            }

            /* Strip inherited scene/IBM scale so rest poses match node
             * animation keys (which carry scale 1 on the bone locals, with any
             * unit conversion sitting on a parent node). Leaving a 0.01 IBM
             * scale here made every animated delta scale by 100. */
            boneMat.normalize3x3();

            BOBJBone bone = new BOBJBone(armature.bones.size(), name, parentName, boneMat);
            armature.addBone(bone);
            nextParent = name;
        }

        for (JavaScene.Node child : node.children)
        {
            buildSkinnedHierarchy(child, nextParent, global, armature, skinnedBones, boneMeshRotations, neededNodes, globalScale, rootCorrection, boneSpace, ibmInSceneSpace, offsetX, offsetY, offsetZ);
        }
    }}
