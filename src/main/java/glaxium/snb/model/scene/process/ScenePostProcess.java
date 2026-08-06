package glaxium.snb.model.scene.process;

import glaxium.snb.model.fbx.loaders.SceneFormat;
import glaxium.snb.model.scene.Scene;
import glaxium.snb.model.scene.SceneBone;
import glaxium.snb.model.scene.SceneMesh;
import glaxium.snb.model.scene.SceneMorphTarget;
import glaxium.snb.model.scene.SceneNode;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-process steps that Assimp previously applied via import flags:
 * triangulate, limit bone weights, join identical vertices, generate smooth
 * normals, flip UVs, and (for FBX) collapse geometric pivot helper nodes.
 */
public final class ScenePostProcess
{
    private static final int MAX_WEIGHTS = 4;

    private ScenePostProcess() {}

    public static void apply(Scene scene, SceneFormat format)
    {
        if (scene == null)
        {
            return;
        }

        if (format != null && format.fbxProperties())
        {
            collapseFbxPivots(scene);
        }

        for (SceneMesh mesh : scene.meshes)
        {
            triangulate(mesh);
            limitBoneWeights(mesh);
            joinIdenticalVertices(mesh);
            generateSmoothNormalsIfMissing(mesh);
            flipUVs(mesh);
        }
    }

    /** Fan-triangulate polygon index lists that still use negative end markers. */
    public static void triangulate(SceneMesh mesh)
    {
        if (mesh.triangulated || mesh.indices.length == 0)
        {
            mesh.triangulated = true;
            return;
        }

        boolean hasNegative = false;
        for (int index : mesh.indices)
        {
            if (index < 0)
            {
                hasNegative = true;
                break;
            }
        }

        if (!hasNegative && mesh.indices.length % 3 == 0)
        {
            mesh.triangulated = true;
            return;
        }

        List<Integer> out = new ArrayList<>(mesh.indices.length);
        List<Integer> poly = new ArrayList<>();

        for (int raw : mesh.indices)
        {
            if (raw < 0)
            {
                poly.add(~raw);
                fanTriangulate(poly, out);
                poly.clear();
            }
            else
            {
                poly.add(raw);
            }
        }

        if (!poly.isEmpty())
        {
            fanTriangulate(poly, out);
        }

        mesh.indices = out.stream().mapToInt(Integer::intValue).toArray();
        mesh.triangulated = true;
    }

    private static void fanTriangulate(List<Integer> poly, List<Integer> out)
    {
        if (poly.size() < 3)
        {
            return;
        }

        int first = poly.get(0);
        for (int i = 1; i + 1 < poly.size(); i++)
        {
            out.add(first);
            out.add(poly.get(i));
            out.add(poly.get(i + 1));
        }
    }

    public static void limitBoneWeights(SceneMesh mesh)
    {
        for (SceneBone bone : mesh.bones)
        {
            if (bone.vertexIds.length != bone.weights.length)
            {
                int n = Math.min(bone.vertexIds.length, bone.weights.length);
                bone.vertexIds = Arrays.copyOf(bone.vertexIds, n);
                bone.weights = Arrays.copyOf(bone.weights, n);
            }
        }

        int vertexCount = mesh.vertexCount();
        if (vertexCount <= 0 || mesh.bones.isEmpty())
        {
            return;
        }

        @SuppressWarnings("unchecked")
        List<float[]>[] perVertex = new List[vertexCount];

        for (int b = 0; b < mesh.bones.size(); b++)
        {
            SceneBone bone = mesh.bones.get(b);
            for (int i = 0; i < bone.vertexIds.length; i++)
            {
                int vid = bone.vertexIds[i];
                if (vid < 0 || vid >= vertexCount)
                {
                    continue;
                }
                if (perVertex[vid] == null)
                {
                    perVertex[vid] = new ArrayList<>(MAX_WEIGHTS + 1);
                }
                perVertex[vid].add(new float[] {b, bone.weights[i]});
            }
        }

        List<List<Integer>> newIds = new ArrayList<>(mesh.bones.size());
        List<List<Float>> newWeights = new ArrayList<>(mesh.bones.size());
        for (int i = 0; i < mesh.bones.size(); i++)
        {
            newIds.add(new ArrayList<>());
            newWeights.add(new ArrayList<>());
        }

        for (int v = 0; v < vertexCount; v++)
        {
            List<float[]> entries = perVertex[v];
            if (entries == null || entries.isEmpty())
            {
                continue;
            }

            entries.sort((a, b) -> Float.compare(b[1], a[1]));
            int keep = Math.min(MAX_WEIGHTS, entries.size());
            float sum = 0f;
            for (int i = 0; i < keep; i++)
            {
                sum += entries.get(i)[1];
            }
            if (sum <= 1e-8f)
            {
                continue;
            }
            for (int i = 0; i < keep; i++)
            {
                int boneIndex = (int) entries.get(i)[0];
                float w = entries.get(i)[1] / sum;
                newIds.get(boneIndex).add(v);
                newWeights.get(boneIndex).add(w);
            }
        }

        for (int b = 0; b < mesh.bones.size(); b++)
        {
            List<Integer> ids = newIds.get(b);
            List<Float> ws = newWeights.get(b);
            SceneBone bone = mesh.bones.get(b);
            bone.vertexIds = ids.stream().mapToInt(Integer::intValue).toArray();
            bone.weights = new float[ws.size()];
            for (int i = 0; i < ws.size(); i++)
            {
                bone.weights[i] = ws.get(i);
            }
        }
    }

    public static void joinIdenticalVertices(SceneMesh mesh)
    {
        if (!mesh.triangulated || mesh.indices.length == 0)
        {
            return;
        }

        int vertexCount = mesh.vertexCount();
        if (vertexCount <= 0)
        {
            return;
        }

        boolean hasNormals = mesh.normals.length == vertexCount * 3;
        boolean hasUvs = mesh.uvs.length == vertexCount * 2;
        Map<String, Integer> remap = new HashMap<>();
        List<Float> pos = new ArrayList<>();
        List<Float> norm = new ArrayList<>();
        List<Float> uv = new ArrayList<>();
        int[] oldToNew = new int[vertexCount];
        String[] boneKeys = boneKeys(mesh, vertexCount);
        Arrays.fill(oldToNew, -1);
        int next = 0;

        for (int v = 0; v < vertexCount; v++)
        {
            String key = vertexKey(mesh, v, hasNormals, hasUvs, boneKeys[v]);
            Integer existing = remap.get(key);
            if (existing != null)
            {
                oldToNew[v] = existing;
                continue;
            }

            remap.put(key, next);
            oldToNew[v] = next;
            pos.add(mesh.positions[v * 3]);
            pos.add(mesh.positions[v * 3 + 1]);
            pos.add(mesh.positions[v * 3 + 2]);
            if (hasNormals)
            {
                norm.add(mesh.normals[v * 3]);
                norm.add(mesh.normals[v * 3 + 1]);
                norm.add(mesh.normals[v * 3 + 2]);
            }
            if (hasUvs)
            {
                uv.add(mesh.uvs[v * 2]);
                uv.add(mesh.uvs[v * 2 + 1]);
            }
            next++;
        }

        if (next == vertexCount)
        {
            return;
        }

        mesh.positions = toFloatArray(pos);
        mesh.normals = hasNormals ? toFloatArray(norm) : new float[0];
        mesh.uvs = hasUvs ? toFloatArray(uv) : new float[0];

        for (int i = 0; i < mesh.indices.length; i++)
        {
            int old = mesh.indices[i];
            if (old >= 0 && old < oldToNew.length)
            {
                mesh.indices[i] = oldToNew[old];
            }
        }

        for (SceneBone bone : mesh.bones)
        {
            Map<Integer, Float> merged = new HashMap<>();
            for (int i = 0; i < bone.vertexIds.length; i++)
            {
                int old = bone.vertexIds[i];
                if (old < 0 || old >= oldToNew.length)
                {
                    continue;
                }
                int neu = oldToNew[old];
                merged.putIfAbsent(neu, bone.weights[i]);
            }
            bone.vertexIds = merged.keySet().stream().mapToInt(Integer::intValue).toArray();
            bone.weights = new float[bone.vertexIds.length];
            for (int i = 0; i < bone.vertexIds.length; i++)
            {
                bone.weights[i] = merged.get(bone.vertexIds[i]);
            }
        }

        for (SceneMorphTarget morph : mesh.morphTargets)
        {
            if (morph.positions.length == vertexCount * 3)
            {
                float[] np = new float[next * 3];
                for (int v = 0; v < vertexCount; v++)
                {
                    int neu = oldToNew[v];
                    np[neu * 3] = morph.positions[v * 3];
                    np[neu * 3 + 1] = morph.positions[v * 3 + 1];
                    np[neu * 3 + 2] = morph.positions[v * 3 + 2];
                }
                morph.positions = np;
            }
            if (morph.normals.length == vertexCount * 3)
            {
                float[] nn = new float[next * 3];
                for (int v = 0; v < vertexCount; v++)
                {
                    int neu = oldToNew[v];
                    nn[neu * 3] = morph.normals[v * 3];
                    nn[neu * 3 + 1] = morph.normals[v * 3 + 1];
                    nn[neu * 3 + 2] = morph.normals[v * 3 + 2];
                }
                morph.normals = nn;
            }
        }
    }

    private static String[] boneKeys(SceneMesh mesh, int vertexCount)
    {
        StringBuilder[] builders = new StringBuilder[vertexCount];

        for (int boneIndex = 0; boneIndex < mesh.bones.size(); boneIndex++)
        {
            SceneBone bone = mesh.bones.get(boneIndex);
            int count = Math.min(bone.vertexIds.length, bone.weights.length);

            for (int influence = 0; influence < count; influence++)
            {
                int vertex = bone.vertexIds[influence];

                if (vertex < 0 || vertex >= vertexCount)
                {
                    continue;
                }
                if (builders[vertex] == null)
                {
                    builders[vertex] = new StringBuilder();
                }
                builders[vertex].append(boneIndex).append(':')
                        .append(Float.floatToIntBits(bone.weights[influence])).append(',');
            }
        }

        String[] keys = new String[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++)
        {
            keys[vertex] = builders[vertex] == null ? "" : builders[vertex].toString();
        }
        return keys;
    }

    private static String vertexKey(SceneMesh mesh, int v, boolean hasNormals, boolean hasUvs,
            String boneKey)
    {
        StringBuilder sb = new StringBuilder(64);
        sb.append(Float.floatToIntBits(mesh.positions[v * 3])).append(',')
                .append(Float.floatToIntBits(mesh.positions[v * 3 + 1])).append(',')
                .append(Float.floatToIntBits(mesh.positions[v * 3 + 2]));
        if (hasNormals)
        {
            sb.append('|').append(Float.floatToIntBits(mesh.normals[v * 3])).append(',')
                    .append(Float.floatToIntBits(mesh.normals[v * 3 + 1])).append(',')
                    .append(Float.floatToIntBits(mesh.normals[v * 3 + 2]));
        }
        if (hasUvs)
        {
            sb.append('|').append(Float.floatToIntBits(mesh.uvs[v * 2])).append(',')
                    .append(Float.floatToIntBits(mesh.uvs[v * 2 + 1]));
        }
        sb.append("|b:").append(boneKey);

        for (int morphIndex = 0; morphIndex < mesh.morphTargets.size(); morphIndex++)
        {
            SceneMorphTarget morph = mesh.morphTargets.get(morphIndex);
            sb.append("|m").append(morphIndex).append(':');

            if (morph.positions.length == mesh.positions.length)
            {
                sb.append(Float.floatToIntBits(morph.positions[v * 3])).append(',')
                        .append(Float.floatToIntBits(morph.positions[v * 3 + 1])).append(',')
                        .append(Float.floatToIntBits(morph.positions[v * 3 + 2]));
            }
            sb.append('/');
            if (morph.normals.length == mesh.positions.length)
            {
                sb.append(Float.floatToIntBits(morph.normals[v * 3])).append(',')
                        .append(Float.floatToIntBits(morph.normals[v * 3 + 1])).append(',')
                        .append(Float.floatToIntBits(morph.normals[v * 3 + 2]));
            }
        }
        return sb.toString();
    }

    public static void generateSmoothNormalsIfMissing(SceneMesh mesh)
    {
        int vertexCount = mesh.vertexCount();
        if (vertexCount <= 0 || mesh.normals.length == vertexCount * 3)
        {
            return;
        }
        if (!mesh.triangulated || mesh.indices.length < 3)
        {
            mesh.normals = new float[vertexCount * 3];
            for (int i = 0; i < vertexCount; i++)
            {
                mesh.normals[i * 3 + 1] = 1f;
            }
            return;
        }

        float[] normals = new float[vertexCount * 3];
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        Vector3f ab = new Vector3f();
        Vector3f ac = new Vector3f();
        Vector3f n = new Vector3f();

        for (int i = 0; i + 2 < mesh.indices.length; i += 3)
        {
            int i0 = mesh.indices[i];
            int i1 = mesh.indices[i + 1];
            int i2 = mesh.indices[i + 2];
            if (i0 < 0 || i1 < 0 || i2 < 0 || i0 >= vertexCount || i1 >= vertexCount || i2 >= vertexCount)
            {
                continue;
            }
            a.set(mesh.positions[i0 * 3], mesh.positions[i0 * 3 + 1], mesh.positions[i0 * 3 + 2]);
            b.set(mesh.positions[i1 * 3], mesh.positions[i1 * 3 + 1], mesh.positions[i1 * 3 + 2]);
            c.set(mesh.positions[i2 * 3], mesh.positions[i2 * 3 + 1], mesh.positions[i2 * 3 + 2]);
            b.sub(a, ab);
            c.sub(a, ac);
            ab.cross(ac, n);
            if (n.lengthSquared() < 1e-12f)
            {
                continue;
            }
            n.normalize();
            for (int idx : new int[] {i0, i1, i2})
            {
                normals[idx * 3] += n.x;
                normals[idx * 3 + 1] += n.y;
                normals[idx * 3 + 2] += n.z;
            }
        }

        for (int i = 0; i < vertexCount; i++)
        {
            n.set(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]);
            if (n.lengthSquared() < 1e-12f)
            {
                n.set(0, 1, 0);
            }
            else
            {
                n.normalize();
            }
            normals[i * 3] = n.x;
            normals[i * 3 + 1] = n.y;
            normals[i * 3 + 2] = n.z;
        }

        mesh.normals = normals;
    }

    public static void flipUVs(SceneMesh mesh)
    {
        for (int i = 1; i < mesh.uvs.length; i += 2)
        {
            mesh.uvs[i] = 1f - mesh.uvs[i];
        }
    }

    /**
     * Collapse trivial single-child transform helper nodes (common FBX pivot
     * leftovers when pivots are not preserved). Merges the child's local
     * transform into the parent and re-parents grandchildren.
     */
    public static void collapseFbxPivots(Scene scene)
    {
        if (scene.rootNode == null)
        {
            return;
        }
        collapseNode(scene.rootNode);
    }

    private static void collapseNode(SceneNode node)
    {
        for (SceneNode child : new ArrayList<>(node.children))
        {
            collapseNode(child);
        }

        List<SceneNode> rebuilt = new ArrayList<>();
        for (SceneNode child : node.children)
        {
            if (isPivotHelper(child))
            {
                SceneNode grandchild = child.children.get(0);
                grandchild.localTransform.set(new Matrix4f(child.localTransform).mul(grandchild.localTransform));
                rebuilt.add(grandchild);
            }
            else
            {
                rebuilt.add(child);
            }
        }
        node.children.clear();
        node.children.addAll(rebuilt);
    }

    private static boolean isPivotHelper(SceneNode node)
    {
        if (node.children.size() != 1 || !node.meshIndices.isEmpty())
        {
            return false;
        }
        String name = node.name == null ? "" : node.name;
        return name.contains("_$AssimpFbx$");
    }

    private static float[] toFloatArray(List<Float> list)
    {
        float[] out = new float[list.size()];
        for (int i = 0; i < list.size(); i++)
        {
            out[i] = list.get(i);
        }
        return out;
    }
}
