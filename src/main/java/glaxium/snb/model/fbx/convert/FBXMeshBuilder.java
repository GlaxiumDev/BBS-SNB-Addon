package glaxium.snb.model.fbx.convert;

import glaxium.snb.model.fbx.FBXMesh;
import glaxium.snb.model.fbx.FBXShapeKeyNames;
import glaxium.snb.model.scene.Scene;
import glaxium.snb.model.scene.SceneBone;
import glaxium.snb.model.scene.SceneMaterial;
import glaxium.snb.model.scene.SceneMesh;
import glaxium.snb.model.scene.SceneMorphTarget;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.Face;
import mchorse.bbs_mod.bobj.BOBJLoader.IndexGroup;
import mchorse.bbs_mod.bobj.BOBJLoader.Vertex;
import mchorse.bbs_mod.bobj.BOBJLoader.Weight;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a single Assimp mesh into a {@link BOBJMesh} (as an
 * {@link FBXMesh}) — vertices, normals, UVs, faces, bone weights, and the
 * diffuse material/texture name.
 */
public final class FBXMeshBuilder
{
    private FBXMeshBuilder() {}

    /**
     * For non-skinned meshes the full node transform (including translation)
     * is applied, and every vertex is weighted to the object's own bone
     * ({@code objectBoneName}) so it pivots at its Blender origin.
     */
    public static void buildMesh(Scene scene, SceneMesh sceneMesh, int meshIndex, List<Vertex> vertices, List<Vector2d> textures, List<Vector3f> normals, List<BOBJMesh> meshes, BOBJArmature armature, float scaleFactor, Matrix4f rootCorrection, float offsetX, float offsetY, float offsetZ, Map<Integer, Matrix4f> meshTransforms, String objectBoneName)
    {
        FBXMesh mesh = new FBXMesh(sceneMesh.name);
        mesh.armatureName = armature.name;
        mesh.armature = armature;

        Matrix4f meshTransform = meshTransforms.get(meshIndex);
        boolean skinned = !sceneMesh.bones.isEmpty();
        boolean applyNodeTransform = !skinned && meshTransform != null;
        Matrix4f meshRotationOnly = null;
        if (skinned && meshTransform != null)
        {
            Quaternionf rot = new Quaternionf();
            meshTransform.getUnnormalizedRotation(rot);
            meshRotationOnly = new Matrix4f().rotation(rot);
        }

        int vertexBaseIndex = vertices.size();
        int textureBaseIndex = textures.size();
        int normalBaseIndex = normals.size();

        Vector3f pos = new Vector3f();

        for (int i = 0; i < sceneMesh.positions.length; i += 3)
        {
            pos.set(sceneMesh.positions[i], sceneMesh.positions[i + 1], sceneMesh.positions[i + 2]);
            if (applyNodeTransform)
            {
                meshTransform.transformPosition(pos);
            }
            else if (meshRotationOnly != null)
            {
                meshRotationOnly.transformPosition(pos);
            }

            pos.mul(scaleFactor);
            rootCorrection.transformPosition(pos);

            pos.x += offsetX;
            pos.y += offsetY;
            pos.z += offsetZ;

            vertices.add(new Vertex(pos.x, pos.y, pos.z));
        }

        if (sceneMesh.normals.length > 0)
        {
            for (int i = 0; i < sceneMesh.normals.length; i += 3)
            {
                Vector3f norm = new Vector3f(sceneMesh.normals[i], sceneMesh.normals[i + 1], sceneMesh.normals[i + 2]);
                if (applyNodeTransform)
                {
                    meshTransform.transformDirection(norm);
                }
                else if (meshRotationOnly != null)
                {
                    meshRotationOnly.transformDirection(norm);
                }

                rootCorrection.transformDirection(norm);
                norm.normalize();

                normals.add(norm);
            }
        }
        else
        {
            int count = sceneMesh.vertexCount();
            for (int i = 0; i < count; i++)
            {
                normals.add(new Vector3f(0, 1, 0));
            }
        }

        if (sceneMesh.uvs.length > 0)
        {
            for (int i = 0; i < sceneMesh.uvs.length; i += 2)
            {
                textures.add(new Vector2d(sceneMesh.uvs[i], sceneMesh.uvs[i + 1]));
            }
        }
        else
        {
            int count = sceneMesh.vertexCount();
            for (int i = 0; i < count; i++)
            {
                textures.add(new Vector2d(0, 0));
            }
        }

        int numMorphTargets = sceneMesh.morphTargets.size();
        if (numMorphTargets > 0)
        {
            mesh.shapeKeyVertices = new HashMap<>();
            mesh.shapeKeyNormals = new HashMap<>();
            mesh.vertexBaseIndex = vertexBaseIndex;
            mesh.normalBaseIndex = normalBaseIndex;

            String meshName = FBXShapeKeyNames.safeName(sceneMesh.name);

            for (int animIndex = 0; animIndex < numMorphTargets; animIndex++)
            {
                SceneMorphTarget morph = sceneMesh.morphTargets.get(animIndex);

                if (morph == null)
                {
                    continue;
                }

                String shapeKeyName = FBXShapeKeyNames.buildShapeKeyName(morph, meshName, animIndex);

                if (shapeKeyName.isBlank())
                {
                    continue;
                }

                List<Vector3f> shapeVertices = new ArrayList<>();
                if (morph.positions.length > 0)
                {
                    Vector3f animPos = new Vector3f();
                    for (int i = 0; i < morph.positions.length; i += 3)
                    {
                        animPos.set(morph.positions[i], morph.positions[i + 1], morph.positions[i + 2]);

                        if (applyNodeTransform)
                        {
                            meshTransform.transformPosition(animPos);
                        }
                        else if (meshRotationOnly != null)
                        {
                            meshRotationOnly.transformPosition(animPos);
                        }

                        animPos.mul(scaleFactor);
                        rootCorrection.transformPosition(animPos);

                        animPos.x += offsetX;
                        animPos.y += offsetY;
                        animPos.z += offsetZ;

                        shapeVertices.add(new Vector3f(animPos.x, animPos.y, animPos.z));
                    }
                }
                mesh.shapeKeyVertices.put(shapeKeyName, shapeVertices);

                List<Vector3f> shapeNormals = new ArrayList<>();
                if (morph.normals.length > 0)
                {
                    for (int i = 0; i < morph.normals.length; i += 3)
                    {
                        Vector3f animNorm = new Vector3f(morph.normals[i], morph.normals[i + 1], morph.normals[i + 2]);

                        if (applyNodeTransform)
                        {
                            meshTransform.transformDirection(animNorm);
                        }
                        else if (meshRotationOnly != null)
                        {
                            meshRotationOnly.transformDirection(animNorm);
                        }

                        rootCorrection.transformDirection(animNorm);
                        animNorm.normalize();

                        shapeNormals.add(animNorm);
                    }
                }
                else
                {
                    int count = sceneMesh.vertexCount();
                    for (int i = 0; i < count; i++)
                    {
                        shapeNormals.add(new Vector3f(normals.get(normalBaseIndex + i)));
                    }
                }
                mesh.shapeKeyNormals.put(shapeKeyName, shapeNormals);
            }
        }


        int numFaces = sceneMesh.indices.length / 3;
        for (int i = 0; i < numFaces; i++)
        {
            Face face = new Face();
            for (int j = 0; j < 3; j++)
            {
                int index = sceneMesh.indices[i * 3 + j];
                IndexGroup group = new IndexGroup();
                group.idxPos = vertexBaseIndex + index;
                group.idxVecNormal = normalBaseIndex + index;
                group.idxTextCoord = textureBaseIndex + index;
                face.idxGroups[j] = group;
            }
            mesh.faces.add(face);
        }

        if (skinned)
        {
            for (SceneBone bone : sceneMesh.bones)
            {
                String boneName = bone.name;
                for (int i = 0; i < bone.vertexIds.length; i++)
                {
                    int vertexId = bone.vertexIds[i];
                    float weight = bone.weights[i];

                    if (vertexId + vertexBaseIndex < vertices.size())
                    {
                        vertices.get(vertexBaseIndex + vertexId).weights.add(new Weight(boneName, weight));
                    }
                }
            }
        }
        else if (objectBoneName != null)
        {
            for (int v = vertexBaseIndex; v < vertices.size(); v++)
            {
                vertices.get(v).weights.add(new Weight(objectBoneName, 1.0f));
            }
        }

        int materialIndex = sceneMesh.materialIndex;
        if (materialIndex >= 0 && materialIndex < scene.materials.size())
        {
            SceneMaterial material = scene.materials.get(materialIndex);
            String materialName = material.name;
            if (materialName != null && !materialName.isEmpty())
            {
                mesh.name = materialName;
            }

            String texturePath = material.diffuseTexturePath;
            if (texturePath != null && !texturePath.isEmpty())
            {
                texturePath = texturePath.replace('\\', '/');
                int lastSlash = texturePath.lastIndexOf('/');

                if (lastSlash >= 0)
                {
                    texturePath = texturePath.substring(lastSlash + 1);
                }

                mesh.texture = texturePath;
            }

            /* No image texture on this material: capture its flat diffuse/base
             * color so the loader can hand BBS a synthetic color texture Link
             * (LinkUtils.color) instead of baking a PNG to disk. */
            if (mesh.texture == null && material.color != null)
            {
                mesh.color = new float[] { material.color[0], material.color[1], material.color[2] };
            }
        }

        meshes.add(mesh);
    }

    /**
     * Ensures every vertex ends up with at least one bone weight (falling
     * back to the armature's first bone), eliminates near-zero weights, and
     * renormalizes each vertex's weights to sum to 1.
     */
    public static void finalizeWeights(List<Vertex> vertices, BOBJArmature globalArmature)
    {
        for (Vertex vertex : vertices)
        {
            if (vertex.weights.isEmpty())
            {
                if (!globalArmature.orderedBones.isEmpty())
                {
                    vertex.weights.add(new Weight(globalArmature.orderedBones.get(0).name, 1.0f));
                }
            }
            else
            {
                vertex.eliminateTinyWeights();

                float sum = 0;
                for (Weight w : vertex.weights)
                {
                    sum += w.factor;
                }

                if (sum > 0 && Math.abs(sum - 1.0f) > 0.001f)
                {
                    for (Weight w : vertex.weights)
                    {
                        w.factor /= sum;
                    }
                }
            }
        }
    }
}