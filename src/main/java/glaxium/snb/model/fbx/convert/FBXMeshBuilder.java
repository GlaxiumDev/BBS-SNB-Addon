package glaxium.snb.model.fbx.convert;

import glaxium.snb.model.fbx.FBXMesh;
import glaxium.snb.model.fbx.FBXShapeKeyNames;
import glaxium.snb.model.fbx.scene.JavaScene;

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

import java.util.List;
import java.util.Map;

/**
 * Converts a single scene mesh into a {@link BOBJMesh} (as an
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
    public static void buildMesh(JavaScene scene, JavaScene.Mesh sourceMesh, int meshIndex, List<Vertex> vertices, List<Vector2d> textures, List<Vector3f> normals, List<BOBJMesh> meshes, BOBJArmature armature, float scaleFactor, Matrix4f rootCorrection, float offsetX, float offsetY, float offsetZ, Map<Integer, Matrix4f> meshTransforms, String objectBoneName)
    {
        buildMesh(scene, sourceMesh, meshIndex, vertices, textures, normals, meshes, armature, scaleFactor, rootCorrection, offsetX, offsetY, offsetZ, meshTransforms, objectBoneName, false);
    }

    /**
     * @param ibmInSceneSpace when true (typical glTF), inverse-binds already
     *                        match joint world matrices — do <em>not</em> bake
     *                        the mesh node's rotation into vertices. Applying
     *                        it there (while bones skip it) twists Mixamo-style
     *                        rigs whose mesh carries a non-identity rotation
     *                        (e.g. Verity GLB: ~180° yaw + tilt).
     */
    public static void buildMesh(JavaScene scene, JavaScene.Mesh sourceMesh, int meshIndex, List<Vertex> vertices, List<Vector2d> textures, List<Vector3f> normals, List<BOBJMesh> meshes, BOBJArmature armature, float scaleFactor, Matrix4f rootCorrection, float offsetX, float offsetY, float offsetZ, Map<Integer, Matrix4f> meshTransforms, String objectBoneName, boolean ibmInSceneSpace)
    {
        FBXMesh mesh = new FBXMesh(sourceMesh.name);
        mesh.armatureName = armature.name;
        mesh.armature = armature;

        Matrix4f meshTransform = meshTransforms.get(meshIndex);
        boolean skinned = !sourceMesh.bones.isEmpty();
        boolean applyNodeTransform = !skinned && meshTransform != null;
        Matrix4f meshRotationOnly = null;
        /* Mesh-local IBMs (Blender FBX): lift verts by the mesh rotation so
         * they share the space boneWorld = meshRot * ibm^-1 uses. Scene-space
         * IBMs already include that, so rotating verts again = spaghetti. */
        if (skinned && meshTransform != null && !ibmInSceneSpace)
        {
            Quaternionf rot = new Quaternionf();
            meshTransform.getUnnormalizedRotation(rot);
            meshRotationOnly = new Matrix4f().rotation(rot);
        }

        int vertexBaseIndex = vertices.size();
        int textureBaseIndex = textures.size();
        int normalBaseIndex = normals.size();

        Vector3f pos = new Vector3f();

        for (Vector3f source : sourceMesh.vertices)
        {
            pos.set(source);
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

        if (sourceMesh.normals.length > 0)
        {
            for (Vector3f source : sourceMesh.normals)
            {
                Vector3f norm = new Vector3f(source);
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
            int count = sourceMesh.vertices.length;
            for (int i = 0; i < count; i++)
            {
                normals.add(new Vector3f(0, 1, 0));
            }
        }

        if (sourceMesh.texCoords.length > 0)
        {
            for (org.joml.Vector2f uv : sourceMesh.texCoords)
            {
                textures.add(new Vector2d(uv.x, uv.y));
            }
        }
        else
        {
            int count = sourceMesh.vertices.length;
            for (int i = 0; i < count; i++)
            {
                textures.add(new Vector2d(0, 0));
            }
        }

        if (!sourceMesh.shapeKeys.isEmpty())
        {
            mesh.shapeKeyVertices = new java.util.HashMap<>();
            mesh.shapeKeyNormals = new java.util.HashMap<>();
            mesh.vertexBaseIndex = vertexBaseIndex;
            mesh.normalBaseIndex = normalBaseIndex;

            String meshName = FBXShapeKeyNames.safeName(sourceMesh.name);

            for (int animIndex = 0; animIndex < sourceMesh.shapeKeys.size(); animIndex++)
            {
                JavaScene.ShapeKey shapeKey = sourceMesh.shapeKeys.get(animIndex);
                String shapeKeyName = FBXShapeKeyNames.buildShapeKeyName(shapeKey, meshName, animIndex);

                if (shapeKeyName.isBlank())
                {
                    continue;
                }

                java.util.List<Vector3f> shapeVertices = new java.util.ArrayList<>();
                if (shapeKey.vertices.length > 0)
                {
                    Vector3f animPos = new Vector3f();
                    for (Vector3f source : shapeKey.vertices)
                    {
                        animPos.set(source);

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

                java.util.List<Vector3f> shapeNormals = new java.util.ArrayList<>();
                if (shapeKey.normals.length > 0)
                {
                    for (Vector3f source : shapeKey.normals)
                    {
                        Vector3f animNorm = new Vector3f(source);

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
                    int count = sourceMesh.vertices.length;
                    for (int i = 0; i < count; i++)
                    {
                        shapeNormals.add(new Vector3f(0, 1, 0));
                    }
                }
                mesh.shapeKeyNormals.put(shapeKeyName, shapeNormals);
            }
        }


        for (int[] faceIndices : sourceMesh.faces)
        {
            if (faceIndices.length == 3)
            {
                Face face = new Face();
                for (int j = 0; j < 3; j++)
                {
                    int index = faceIndices[j];
                    IndexGroup group = new IndexGroup();
                    group.idxPos = vertexBaseIndex + index;
                    group.idxVecNormal = normalBaseIndex + index;
                    group.idxTextCoord = textureBaseIndex + index;
                    face.idxGroups[j] = group;
                }
                mesh.faces.add(face);
            }
        }

        if (skinned)
        {
            for (JavaScene.Bone bone : sourceMesh.bones)
            {
                String boneName = bone.name;

                for (JavaScene.VertexWeight sourceWeight : bone.weights)
                {
                    int vertexId = sourceWeight.vertexId();
                    float weight = sourceWeight.weight();

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

        int materialIndex = sourceMesh.materialIndex;
        if (materialIndex >= 0 && materialIndex < scene.materials.size())
        {
            JavaScene.Material material = scene.materials.get(materialIndex);
            if (material.name != null && !material.name.isEmpty())
            {
                mesh.name = material.name;
            }
            if (material.texturePath != null && !material.texturePath.isEmpty())
            {
                String texturePath = material.texturePath;

                if (!texturePath.isEmpty())
                {
                    texturePath = texturePath.replace('\\', '/');
                    int lastSlash = texturePath.lastIndexOf('/');

                    if (lastSlash >= 0)
                    {
                        texturePath = texturePath.substring(lastSlash + 1);
                    }

                    mesh.texture = texturePath;
                }
            }

            /* No image texture on this material: capture its flat diffuse/base
             * color so the loader can hand BBS a synthetic color texture Link
             * (LinkUtils.color) instead of baking a PNG to disk. */
            if (mesh.texture == null && material.color != null)
            {
                mesh.color = material.color.clone();
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
