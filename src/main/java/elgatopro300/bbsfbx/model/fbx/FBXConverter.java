package elgatopro300.bbsfbx.model.fbx;

import elgatopro300.bbsfbx.model.fbx.convert.FBXAnimationBaker;
import elgatopro300.bbsfbx.model.fbx.convert.FBXArmatureBuilder;
import elgatopro300.bbsfbx.model.fbx.convert.FBXMath;
import elgatopro300.bbsfbx.model.fbx.convert.FBXMeshBuilder;
import elgatopro300.bbsfbx.model.fbx.convert.FBXSceneWalker;
import elgatopro300.bbsfbx.model.fbx.convert.FBXTextureExtractor;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.Vertex;

import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import org.joml.Matrix4f;
import org.joml.Vector2d;
import org.joml.Vector3f;

import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AIScene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FBXConverter converts an Assimp {@link AIScene} into BBS FS {@link BOBJData}.
 *
 * <p>This class is now just the orchestrator; the actual work is split into:
 * <ul>
 *   <li>{@link FBXSceneWalker} — reads the raw Assimp node tree</li>
 *   <li>{@link FBXArmatureBuilder} — builds the BOBJArmature (skinned or per-object bones)</li>
 *   <li>{@link FBXMeshBuilder} — converts mesh geometry + weights</li>
 *   <li>{@link FBXAnimationBaker} — bakes animation clips into BOBJActions</li>
 *   <li>{@link FBXTextureExtractor} — extracts/generates textures</li>
 *   <li>{@link FBXMath} — shared matrix utilities</li>
 * </ul>
 *
 * <p>Coordinate handling:
 * <ul>
 *   <li>Blender bakes a 100x (cm->m) scale into the FBX node transform, so
 *       vertices are pre-multiplied by {@link #FBX_UNIT_SCALE} (0.01).</li>
 *   <li>No auto-centering, grounding, or height-normalization. The model keeps
 *       the exact position/scale it had in Blender.</li>
 *   <li>For non-skinned scenes, each object becomes its own bone named after the
 *       object, anchored at that object's Blender origin, so every mesh pivots
 *       around its own point (requires OptimizeGraph to be OFF in the loader).</li>
 * </ul>
 */
public class FBXConverter
{
    /** Undoes the 100x cm->m scale Blender bakes into FBX node transforms. */
    private static final float FBX_UNIT_SCALE = 0.01f;

    public static BOBJData convert(AIScene scene)
    {
        List<Vertex> vertices = new ArrayList<>();
        List<Vector2d> textures = new ArrayList<>();
        List<Vector3f> normals = new ArrayList<>();
        List<BOBJMesh> meshes = new ArrayList<>();
        Map<String, BOBJAction> actions = new HashMap<>();
        Map<String, BOBJArmature> armatures = new HashMap<>();

        AINode rootNode = scene.mRootNode();
        if (rootNode == null)
        {
            return new BOBJData(vertices, textures, normals, meshes, actions, armatures);
        }

        FBXMetadata metadata = new FBXMetadata(scene);
        Matrix4f rootCorrection = FBXMath.buildRootCorrection(metadata);

        Map<Integer, String> meshNodeNames = new HashMap<>();
        Map<String, String> nodeParents = new HashMap<>();
        Map<String, Matrix4f> nodeLocals = new HashMap<>();
        Map<String, Matrix4f> nodeWorldTransforms = new HashMap<>();
        Map<Integer, Matrix4f> meshTransforms = FBXSceneWalker.collectMeshTransforms(rootNode, meshNodeNames, nodeParents, nodeLocals, nodeWorldTransforms);

        Map<String, Integer> skinnedBoneMeshIndex = new HashMap<>();
        Map<String, AIBone> skinnedBones = FBXArmatureBuilder.collectSkinnedBones(scene, skinnedBoneMeshIndex);
        Map<String, Matrix4f> boneMeshRotations = FBXArmatureBuilder.collectBoneMeshRotations(skinnedBoneMeshIndex, meshTransforms);

        BOBJArmature globalArmature = new BOBJArmature("Armature");
        armatures.put(globalArmature.name, globalArmature);

        float[] globalScale = {FBX_UNIT_SCALE};
        Set<String> neededNodes = new HashSet<>();
        int numMeshes = scene.mNumMeshes();

        if (!skinnedBones.isEmpty())
        {
            FBXArmatureBuilder.markNeededNodes(rootNode, skinnedBones.keySet(), neededNodes);

            // Skinned vertices are already in bind (meter) space and never
            // receive the node's baked 100x cm scale, so the 0.01 unit-cancel
            // must NOT apply. Set to 1.0 = true scale (fixes the ~150x shrink).
            globalScale[0] = 1.0f;
        }
        else
        {
            // One bone per scene node — every mesh object AND every mesh-less
            // Empty (locator/group) — anchored at its own Blender origin, so
            // meshes pivot around their own point and Empties show up in BBS
            // as animatable, nestable limbs/groups just like mesh objects.
            FBXArmatureBuilder.buildObjectBones(globalArmature, nodeWorldTransforms, nodeParents, rootCorrection, globalScale[0]);
        }

        // Respect Blender's coordinates exactly: no centering/grounding/normalization.
        float offsetX = 0;
        float offsetY = 0;
        float offsetZ = 0;

        if (!skinnedBones.isEmpty())
        {
            Matrix4f initialGlobal = new Matrix4f().translate(offsetX, offsetY, offsetZ);
            FBXArmatureBuilder.buildSkinnedHierarchy(rootNode, "", initialGlobal, globalArmature, skinnedBones, boneMeshRotations, neededNodes, globalScale, rootCorrection, offsetX, offsetY, offsetZ);
        }

        for (int i = 0; i < numMeshes; i++)
        {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            String objectBoneName = meshNodeNames.getOrDefault(i, "object_" + i);
            FBXMeshBuilder.buildMesh(scene, aiMesh, i, vertices, textures, normals, meshes, globalArmature, globalScale[0], rootCorrection, offsetX, offsetY, offsetZ, meshTransforms, objectBoneName);
        }

        FBXMeshBuilder.finalizeWeights(vertices, globalArmature);

        /* Extract animation clips into BOBJActions, mirroring how BOBJ models
         * carry actions. This now runs for BOTH paths:
         *  - skinned scenes: channels targeting skinned bones are diffed
         *    against their bind-pose local transform (bindLocals);
         *  - non-skinned scenes: channels targeting an object/Empty bone fall
         *    back to that node's raw local transform (nodeLocals) as its
         *    rest pose, giving per-object (including per-Empty) animation.
         * FBXAnimationBaker.processAnimations() already skips any channel
         * whose node isn't a bone in the armature, so this is safe to run
         * unconditionally whenever the scene has animation data. */
        if (scene.mNumAnimations() > 0)
        {
            Map<String, Matrix4f> bindLocals = FBXAnimationBaker.computeBindLocals(skinnedBones, globalArmature);

            FBXAnimationBaker.processAnimations(scene, actions, globalArmature, nodeLocals, bindLocals, globalScale[0]);
        }

        return new BOBJData(vertices, textures, normals, meshes, actions, armatures);
    }

    /**
     * Extracts embedded FBX textures into the model's per-material texture
     * folders. Thin wrapper kept here so {@code FBXModelLoader} doesn't need
     * to depend on the {@code convert} sub-package directly.
     */
    public static Set<String> extractEmbeddedTextures(AIScene scene, AssetProvider provider, Link model)
    {
        return FBXTextureExtractor.extract(scene, provider, model);
    }
}