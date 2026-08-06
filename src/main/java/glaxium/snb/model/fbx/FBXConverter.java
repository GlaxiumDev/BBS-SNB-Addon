package glaxium.snb.model.fbx;

import glaxium.snb.model.fbx.convert.FBXAnimationBaker;
import glaxium.snb.model.fbx.convert.FBXArmatureBuilder;
import glaxium.snb.model.fbx.convert.FBXMath;
import glaxium.snb.model.fbx.convert.FBXMeshBuilder;
import glaxium.snb.model.fbx.convert.FBXSceneWalker;
import glaxium.snb.model.fbx.convert.FBXTextureExtractor;
import glaxium.snb.model.scene.Scene;
import glaxium.snb.model.scene.SceneBone;
import glaxium.snb.model.scene.SceneMesh;
import glaxium.snb.model.scene.SceneNode;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a pure-Java {@link Scene} into BBS {@link BOBJData}.
 *
 * <p>Orchestrates:
 * <ul>
 *   <li>{@link FBXSceneWalker} — node tree walk</li>
 *   <li>{@link FBXArmatureBuilder} — skinned or per-object bones</li>
 *   <li>{@link FBXMeshBuilder} — geometry + weights</li>
 *   <li>{@link FBXAnimationBaker} — animation clips</li>
 *   <li>{@link FBXTextureExtractor} — embedded textures</li>
 * </ul>
 */
public class FBXConverter
{
    /** Undoes the 100x cm->m scale Blender bakes into FBX node transforms. */
    private static final float FBX_UNIT_SCALE = 0.01f;

    public static BOBJData convert(Scene scene)
    {
        return convert(scene, FBX_UNIT_SCALE);
    }

    /**
     * @param unitScale scale applied to non-skinned geometry --
     * {@link #FBX_UNIT_SCALE} for FBX, 1.0 for glTF/GLB (meters).
     */
    public static BOBJData convert(Scene scene, float unitScale)
    {
        List<Vertex> vertices = new ArrayList<>();
        List<Vector2d> textures = new ArrayList<>();
        List<Vector3f> normals = new ArrayList<>();
        List<BOBJMesh> meshes = new ArrayList<>();
        Map<String, BOBJAction> actions = new HashMap<>();
        Map<String, BOBJArmature> armatures = new HashMap<>();

        SceneNode rootNode = scene.rootNode;
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
        Map<String, SceneBone> skinnedBones = FBXArmatureBuilder.collectSkinnedBones(scene, skinnedBoneMeshIndex);
        Map<String, Matrix4f> boneMeshRotations = FBXArmatureBuilder.collectBoneMeshRotations(skinnedBoneMeshIndex, meshTransforms);
        boolean ibmInSceneSpace = FBXArmatureBuilder.ibmInSceneSpace(skinnedBones, nodeWorldTransforms, skinnedBoneMeshIndex, meshTransforms);

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

            boolean centimeterGeometry = needsCentimeterScale(scene, meshTransforms);
            globalScale[0] = centimeterGeometry ? FBX_UNIT_SCALE : 1.0f;
            animScale = globalScale[0];

            if (ibmInSceneSpace)
            {
                float meshScale = meshNodeScale(skinnedBoneMeshIndex, meshTransforms);
                if (meshScale > 0F && meshScale < 0.5F)
                {
                    animScale = meshScale;
                }
            }
        }
        else
        {
            FBXArmatureBuilder.buildObjectBones(globalArmature, nodeWorldTransforms, nodeParents, rootCorrection, globalScale[0]);
            animScale = globalScale[0];
        }

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
            SceneMesh sceneMesh = scene.meshes.get(i);
            String objectBoneName = meshNodeNames.getOrDefault(i, "object_" + i);
            FBXMeshBuilder.buildMesh(scene, sceneMesh, i, vertices, textures, normals, meshes, globalArmature, globalScale[0], rootCorrection, offsetX, offsetY, offsetZ, meshTransforms, objectBoneName);
        }

        FBXMeshBuilder.finalizeWeights(vertices, globalArmature);

        if (!scene.animations.isEmpty())
        {
            Map<String, Matrix4f> bindLocals = FBXAnimationBaker.computeBindLocals(skinnedBones, globalArmature, skinnedBoneMeshIndex, meshTransforms, nodeWorldTransforms, ibmInSceneSpace, nodeLocals);

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

    private static boolean needsCentimeterScale(Scene scene, Map<Integer, Matrix4f> meshTransforms)
    {
        float maxExtent = 0F;

        for (SceneMesh mesh : scene.meshes)
        {
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            float[] verts = mesh.positions;

            for (int i = 0; i + 2 < verts.length; i += 3)
            {
                float x = verts[i], y = verts[i + 1], z = verts[i + 2];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }

            if (verts.length >= 3)
            {
                maxExtent = Math.max(maxExtent, Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)));
            }
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

    public static Set<String> extractEmbeddedTextures(Scene scene, AssetProvider provider, Link model)
    {
        return FBXTextureExtractor.extract(scene, provider, model);
    }
}
