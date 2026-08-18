package glaxium.snb.model.fbx;

import glaxium.snb.model.fbx.convert.FBXAnimationBaker;
import glaxium.snb.model.fbx.convert.FBXArmatureBuilder;
import glaxium.snb.model.fbx.convert.FBXMath;
import glaxium.snb.model.fbx.convert.FBXMeshBuilder;
import glaxium.snb.model.fbx.convert.FBXSceneWalker;
import glaxium.snb.model.fbx.convert.FBXTextureExtractor;
import glaxium.snb.model.fbx.scene.JavaScene;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.Vertex;

import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import org.joml.Matrix4f;
import org.joml.Vector2d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts the addon's pure-Java scene representation into BBS FS {@link BOBJData}.
 *
 * <p>This class is now just the orchestrator; the actual work is split into:
 * <ul>
 *   <li>{@link FBXSceneWalker} — reads the format-neutral node tree</li>
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
 *       vertices are pre-multiplied by {@link #FBX_UNIT_SCALE} (0.01). Formats
 *       that are already in meters (glTF/GLB) pass 1.0 instead -- see
 *       {@link #convert(JavaScene, float)}.</li>
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

    public static BOBJData convert(JavaScene scene)
    {
        return convert(scene, FBX_UNIT_SCALE);
    }

    /**
     * @param unitScale scale applied to non-skinned geometry to cancel out
     * whatever the source format baked into its node transforms -- {@link
     * #FBX_UNIT_SCALE} for FBX, 1.0 for the formats that are already in
     * meters (see {@code SceneFormat}). Skinned scenes ignore it entirely and
     * always use 1.0, since their vertices come in bind (meter) space with no
     * node scale applied.
     */
    public static BOBJData convert(JavaScene scene, float unitScale)
    {
        List<Vertex> vertices = new ArrayList<>();
        List<Vector2d> textures = new ArrayList<>();
        List<Vector3f> normals = new ArrayList<>();
        List<BOBJMesh> meshes = new ArrayList<>();
        Map<String, BOBJAction> actions = new HashMap<>();
        Map<String, BOBJArmature> armatures = new HashMap<>();

        JavaScene.Node rootNode = scene.root;
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
        Map<String, JavaScene.Bone> skinnedBones = FBXArmatureBuilder.collectSkinnedBones(scene, skinnedBoneMeshIndex);
        Map<String, Matrix4f> boneMeshRotations = FBXArmatureBuilder.collectBoneMeshRotations(skinnedBoneMeshIndex, meshTransforms);
        /* FBX cluster transforms are mesh-local by definition in the files
         * handled here.  The scene-space IBM heuristic is needed for glTF,
         * but empty FBX clusters can otherwise outvote the real weighted
         * clusters and incorrectly suppress the mesh-node rotation. */
        boolean ibmInSceneSpace = unitScale >= 0.5F &&
                FBXArmatureBuilder.ibmInSceneSpace(skinnedBones, nodeWorldTransforms, skinnedBoneMeshIndex, meshTransforms);
        float splitMeshAnimationScale = FBXArmatureBuilder.detectSplitMeshAnimationScale(skinnedBones,
                nodeWorldTransforms, skinnedBoneMeshIndex, meshTransforms, ibmInSceneSpace);

        BOBJArmature globalArmature = new BOBJArmature("Armature");
        armatures.put(globalArmature.name, globalArmature);

        float[] globalScale = {unitScale};
        Set<String> neededNodes = new HashSet<>();
        int numMeshes = scene.meshes.size();

        Matrix4f boneSpace = FBXArmatureBuilder.buildBoneSpace(rootCorrection, skinnedBoneMeshIndex, meshTransforms, ibmInSceneSpace);
        float animScale = unitScale;

        if (!skinnedBones.isEmpty())
        {
            FBXArmatureBuilder.markNeededNodes(rootNode, skinnedBones.keySet(), neededNodes);

            /* Skinned Blender FBX: vertices are already meters and the 100x
             * lives on the mesh node (ignored for skinned verts) -- keep 1.0.
             * Skinned Source/cm FBX: vertices AND bones are centimetres with
             * no 100x on the mesh node -- apply 0.01 or the model is ~100x
             * too tall. glTF that already baked 0.01 into the hierarchy stays
             * at 1.0 for geometry (mesh AABB is meters). */
            boolean centimeterGeometry = needsCentimeterScale(scene, meshTransforms);
            globalScale[0] = centimeterGeometry ? FBX_UNIT_SCALE : 1.0f;
            animScale = globalScale[0];

            /* Scene-space IBMs (glTF): geometry is already meters, but node
             * animation keys can still carry the pre-scale local translations
             * (cm). Scale those deltas by the mesh node's scale so a camera
             * key of 139 doesn't move the bone 139 meters. */
            if (ibmInSceneSpace)
            {
                float meshScale = meshNodeScale(skinnedBoneMeshIndex, meshTransforms);
                if (meshScale > 0F && meshScale < 0.5F)
                {
                    animScale = meshScale;
                }
            }
            else if (splitMeshAnimationScale < 0.5F)
            {
                /* Some Blender FBXs put 100x only on the mesh while their
                 * skeleton animation remains in centimetres. The structural
                 * detector above returns the matching IBM unit bridge. */
                animScale = globalScale[0] * splitMeshAnimationScale;
            }
        }
        else
        {
            // One bone per scene node — every mesh object AND every mesh-less
            // Empty (locator/group) — anchored at its own Blender origin, so
            // meshes pivot around their own point and Empties show up in BBS
            // as animatable, nestable limbs/groups just like mesh objects.
            FBXArmatureBuilder.buildObjectBones(globalArmature, nodeWorldTransforms, nodeParents, rootCorrection, globalScale[0]);
            animScale = globalScale[0];

            /* Blender glTF/GLB often puts a 0.01 unit scale on a parent Empty
             * while child object locals stay in centimetres. Object bones
             * normalize that scale away (meter rests), but animation keys
             * still use the raw locals — without animScale the deltas are
             * ~100x and per-object clips explode. FBX already gets 0.01 from
             * unitScale; pick up the same bridge from the node tree here. */
            float bridge = unitBridgeScale(nodeLocals);
            if (bridge > 0F && bridge < 0.5F)
            {
                animScale = bridge;
            }
        }

        // Respect Blender's coordinates exactly: no centering/grounding/normalization.
        float offsetX = 0;
        float offsetY = 0;
        float offsetZ = 0;

        if (!skinnedBones.isEmpty())
        {
            Matrix4f initialGlobal = new Matrix4f().translate(offsetX, offsetY, offsetZ);
            FBXArmatureBuilder.buildSkinnedHierarchy(rootNode, "", initialGlobal, globalArmature, skinnedBones, boneMeshRotations, neededNodes, globalScale, rootCorrection, boneSpace, ibmInSceneSpace, offsetX, offsetY, offsetZ);
        }

        for (int i = 0; i < numMeshes; i++)
        {
            JavaScene.Mesh sourceMesh = scene.meshes.get(i);
            String objectBoneName = meshNodeNames.getOrDefault(i, "object_" + i);
            FBXMeshBuilder.buildMesh(scene, sourceMesh, i, vertices, textures, normals, meshes, globalArmature, globalScale[0], rootCorrection, offsetX, offsetY, offsetZ, meshTransforms, objectBoneName, ibmInSceneSpace);
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
        if (!scene.animations.isEmpty())
        {
            Map<String, Matrix4f> bindLocals = FBXAnimationBaker.computeBindLocals(skinnedBones, globalArmature,
                    skinnedBoneMeshIndex, meshTransforms, nodeWorldTransforms, ibmInSceneSpace, nodeLocals,
                    splitMeshAnimationScale);

            /* Non-skinned: no IBMs — always rest against source node locals
             * (same space as the animation keys). */
            if (skinnedBones.isEmpty() && nodeLocals != null && !nodeLocals.isEmpty())
            {
                bindLocals = new HashMap<>();
                for (BOBJBone bone : globalArmature.orderedBones)
                {
                    Matrix4f local = nodeLocals.get(bone.name);
                    if (local != null)
                    {
                        bindLocals.put(bone.name, new Matrix4f(local));
                    }
                }
            }

            FBXAnimationBaker.processAnimations(scene, actions, globalArmature, nodeLocals, bindLocals, animScale);
        }

        return new BOBJData(vertices, textures, normals, meshes, actions, armatures);
    }

    private static float meshNodeScale(Map<String, Integer> skinnedBoneMeshIndex, Map<Integer, Matrix4f> meshTransforms)
    {
        Matrix4f meshWorld = FBXArmatureBuilder.firstSkinnedMeshTransform(skinnedBoneMeshIndex, meshTransforms);

        if (meshWorld == null)
        {
            return 1F;
        }

        Vector3f scale = new Vector3f();
        meshWorld.getScale(scale);

        return Math.max(scale.x, Math.max(scale.y, scale.z));
    }

    /**
     * Smallest node-local scale in {@code (0, 0.5)} — Blender's glTF unit
     * bridge (typically 0.01). Returns 1 when the hierarchy has no such node.
     */
    private static float unitBridgeScale(Map<String, Matrix4f> nodeLocals)
    {
        float bridge = 1F;

        for (Matrix4f local : nodeLocals.values())
        {
            Vector3f scale = new Vector3f();
            local.getScale(scale);
            float s = Math.max(scale.x, Math.max(scale.y, scale.z));

            if (s > 1e-6F && s < 0.5F)
            {
                bridge = Math.min(bridge, s);
            }
        }

        return bridge;
    }

    /**
     * True when mesh geometry is in centimetres (AABB extent &gt; ~10) and the
     * mesh nodes don't already carry Blender's compensating 100x scale. That
     * pattern is Source-engine / SFM FBX exports; applying {@link #FBX_UNIT_SCALE}
     * brings them down to Minecraft-sized meters. Returns false for Blender
     * FBX (small AABB, 100x on the node) and for glTF that already scaled.
     */
    private static boolean needsCentimeterScale(JavaScene scene, Map<Integer, Matrix4f> meshTransforms)
    {
        float maxExtent = 0F;

        for (JavaScene.Mesh mesh : scene.meshes)
        {
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (Vector3f v : mesh.vertices)
            {
                minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
                minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
                minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
            }

            maxExtent = Math.max(maxExtent, Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)));
        }

        float maxMeshScale = 1F;

        for (Matrix4f meshWorld : meshTransforms.values())
        {
            Vector3f scale = new Vector3f();
            meshWorld.getScale(scale);
            maxMeshScale = Math.max(maxMeshScale, Math.max(scale.x, Math.max(scale.y, scale.z)));
        }

        return maxExtent > 10F && maxMeshScale < 10F;
    }

    /**
     * Extracts embedded FBX textures into the model's per-material texture
     * folders. Thin wrapper kept here so {@code FBXModelLoader} doesn't need
     * to depend on the {@code convert} sub-package directly.
     */
    public static Set<String> extractEmbeddedTextures(JavaScene scene, AssetProvider provider, Link model)
    {
        return FBXTextureExtractor.extract(scene, provider, model);
    }
}
