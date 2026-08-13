package glaxium.snb.model.bbssnb;

import glaxium.snb.model.fbx.scene.JavaScene;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies the shading mode stored by the BBS S&amp;B Blockbench exporter. */
public final class BBSSNBShading
{
    private BBSSNBShading() {}

    public static void apply(JavaScene scene, boolean smooth)
    {
        if (scene == null)
        {
            return;
        }

        if (!smooth)
        {
            for (JavaScene.Mesh mesh : scene.meshes)
            {
                makeFlat(mesh);
            }

            return;
        }

        Set<Integer> processed = new HashSet<>();
        smoothNodes(scene, scene.root, processed);

        /* Normally every mesh is referenced by a node. Keep malformed but
         * otherwise usable glTF scenes deterministic too. */
        for (int i = 0; i < scene.meshes.size(); i++)
        {
            if (!processed.contains(i))
            {
                smoothMeshes(scene, List.of(i));
            }
        }
    }

    private static void smoothNodes(JavaScene scene, JavaScene.Node node, Set<Integer> processed)
    {
        List<Integer> meshes = new ArrayList<>();

        for (int index : node.meshes)
        {
            if (index >= 0 && index < scene.meshes.size() && processed.add(index))
            {
                meshes.add(index);
            }
        }

        if (!meshes.isEmpty())
        {
            /* A glTF node can contain several material primitives. Smooth
             * them together so a material boundary does not become a seam. */
            smoothMeshes(scene, meshes);
        }

        for (JavaScene.Node child : node.children)
        {
            smoothNodes(scene, child, processed);
        }
    }

    private static void smoothMeshes(JavaScene scene, List<Integer> indices)
    {
        Map<PositionKey, Vector3f> sums = new HashMap<>();

        for (int meshIndex : indices)
        {
            JavaScene.Mesh mesh = scene.meshes.get(meshIndex);

            for (int[] face : mesh.faces)
            {
                if (!validFace(mesh, face))
                {
                    continue;
                }

                Vector3f normal = faceNormal(mesh.vertices[face[0]], mesh.vertices[face[1]], mesh.vertices[face[2]]);

                for (int vertex : face)
                {
                    sums.computeIfAbsent(new PositionKey(mesh.vertices[vertex]), ignored -> new Vector3f()).add(normal);
                }
            }
        }

        for (int meshIndex : indices)
        {
            JavaScene.Mesh mesh = scene.meshes.get(meshIndex);
            Vector3f[] normals = new Vector3f[mesh.vertices.length];

            for (int i = 0; i < normals.length; i++)
            {
                Vector3f sum = sums.get(new PositionKey(mesh.vertices[i]));
                normals[i] = normalizedOrUp(sum);
            }

            mesh.normals = normals;
        }
    }

    /**
     * True flat shading requires a separate normal at every triangle corner.
     * Splitting the indexed geometry here also duplicates UVs, skin weights
     * and shape-key data, so disabling Smooth Shading can never damage the
     * rig or its animation.
     */
    private static void makeFlat(JavaScene.Mesh mesh)
    {
        int corners = 0;

        for (int[] face : mesh.faces)
        {
            if (validFace(mesh, face))
            {
                corners += face.length;
            }
        }

        if (corners == 0)
        {
            return;
        }

        Vector3f[] vertices = new Vector3f[corners];
        Vector3f[] normals = new Vector3f[corners];
        Vector2f[] texCoords = new Vector2f[corners];
        int[][] faces = new int[corners / 3][];

        List<JavaScene.ShapeKey> oldShapes = new ArrayList<>(mesh.shapeKeys);
        List<JavaScene.ShapeKey> newShapes = new ArrayList<>(oldShapes.size());

        for (JavaScene.ShapeKey oldShape : oldShapes)
        {
            JavaScene.ShapeKey shape = new JavaScene.ShapeKey();
            shape.name = oldShape.name;
            shape.vertices = new Vector3f[corners];
            shape.normals = new Vector3f[corners];
            newShapes.add(shape);
        }

        List<Map<Integer, Float>> sourceWeights = new ArrayList<>(mesh.bones.size());

        for (JavaScene.Bone bone : mesh.bones)
        {
            Map<Integer, Float> weights = new LinkedHashMap<>();

            for (JavaScene.VertexWeight weight : bone.weights)
            {
                weights.merge(weight.vertexId(), weight.weight(), Math::max);
            }

            sourceWeights.add(weights);
            bone.weights.clear();
        }

        int corner = 0;
        int outputFace = 0;

        for (int[] sourceFace : mesh.faces)
        {
            if (!validFace(mesh, sourceFace))
            {
                continue;
            }

            int[] face = new int[sourceFace.length];

            for (int i = 0; i < sourceFace.length; i++)
            {
                int source = sourceFace[i];
                int target = corner++;
                face[i] = target;
                vertices[target] = new Vector3f(mesh.vertices[source]);
                texCoords[target] = source < mesh.texCoords.length && mesh.texCoords[source] != null
                        ? new Vector2f(mesh.texCoords[source]) : new Vector2f();

                for (int shapeIndex = 0; shapeIndex < oldShapes.size(); shapeIndex++)
                {
                    JavaScene.ShapeKey oldShape = oldShapes.get(shapeIndex);
                    JavaScene.ShapeKey shape = newShapes.get(shapeIndex);
                    shape.vertices[target] = source < oldShape.vertices.length && oldShape.vertices[source] != null
                            ? new Vector3f(oldShape.vertices[source]) : new Vector3f(vertices[target]);
                }

                for (int boneIndex = 0; boneIndex < mesh.bones.size(); boneIndex++)
                {
                    Float weight = sourceWeights.get(boneIndex).get(source);

                    if (weight != null && weight > 0F)
                    {
                        mesh.bones.get(boneIndex).weights.add(new JavaScene.VertexWeight(target, weight));
                    }
                }
            }

            Vector3f normal = faceNormal(vertices[face[0]], vertices[face[1]], vertices[face[2]]);

            for (int target : face)
            {
                normals[target] = normalizedOrUp(normal);
            }

            for (JavaScene.ShapeKey shape : newShapes)
            {
                Vector3f shapeNormal = faceNormal(shape.vertices[face[0]], shape.vertices[face[1]], shape.vertices[face[2]]);

                for (int target : face)
                {
                    shape.normals[target] = normalizedOrUp(shapeNormal);
                }
            }

            faces[outputFace++] = face;
        }

        mesh.vertices = vertices;
        mesh.normals = normals;
        mesh.texCoords = texCoords;
        mesh.faces = faces;
        mesh.shapeKeys.clear();
        mesh.shapeKeys.addAll(newShapes);
    }

    private static boolean validFace(JavaScene.Mesh mesh, int[] face)
    {
        if (face == null || face.length != 3)
        {
            return false;
        }

        for (int vertex : face)
        {
            if (vertex < 0 || vertex >= mesh.vertices.length || mesh.vertices[vertex] == null)
            {
                return false;
            }
        }

        return true;
    }

    private static Vector3f faceNormal(Vector3f a, Vector3f b, Vector3f c)
    {
        return new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
    }

    private static Vector3f normalizedOrUp(Vector3f normal)
    {
        return normal != null && normal.lengthSquared() > 1.0E-12F
                ? new Vector3f(normal).normalize() : new Vector3f(0, 1, 0);
    }

    /** Exact glTF positions are shared bit-for-bit even when UV seams split vertices. */
    private record PositionKey(int x, int y, int z)
    {
        PositionKey(Vector3f position)
        {
            this(Float.floatToIntBits(position.x), Float.floatToIntBits(position.y), Float.floatToIntBits(position.z));
        }
    }
}
