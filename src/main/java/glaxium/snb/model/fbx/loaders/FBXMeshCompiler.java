package glaxium.snb.model.fbx.loaders;

import glaxium.snb.model.fbx.FBXMesh;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.CompiledData;

import org.joml.Vector2d;
import org.joml.Vector3f;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Flattens a single BOBJMesh (plus the shared vertex/texture/normal pools in
 * BOBJData) into the packed float/int arrays BBS FS's renderer expects, as a
 * CompiledData.
 */
public final class FBXMeshCompiler
{
    private FBXMeshCompiler() {}

    public static CompiledData compile(BOBJData data, BOBJMesh mesh)
    {
        int totalVertices = 0;
        for (BOBJLoader.Face face : mesh.faces)
        {
            totalVertices += face.idxGroups.length;
        }

        float[] pos = new float[totalVertices * 3];
        float[] tex = new float[totalVertices * 2];
        float[] norm = new float[totalVertices * 3];
        float[] weights = new float[totalVertices * 4];
        int[] bones = new int[totalVertices * 4];
        int[] indices = new int[totalVertices];

        int vIndex = 0;  // >> Vertex index */
        int wIndex = 0;  // >> Weight/Bone index (x4) */
        int pIndex = 0;  // >> Position/Normal index (x3) */
        int tIndex = 0;  // >> Texture index (x2) */

        for (BOBJLoader.Face face : mesh.faces)
        {
            for (BOBJLoader.IndexGroup group : face.idxGroups)
            {
                BOBJLoader.Vertex v = data.vertices.get(group.idxPos);
                Vector2d t = data.textures.get(group.idxTextCoord);
                Vector3f n = data.normals.get(group.idxVecNormal);

                pos[pIndex] = v.x; pos[pIndex+1] = v.y; pos[pIndex+2] = v.z;
                norm[pIndex] = n.x; norm[pIndex+1] = n.y; norm[pIndex+2] = n.z;
                pIndex += 3;

                tex[tIndex] = (float) t.x; tex[tIndex+1] = (float) t.y;
                tIndex += 2;

                if (v.weights.isEmpty())
                {
                    weights[wIndex] = 1.0f; bones[wIndex] = 0;
                    weights[wIndex+1] = 0.0f; bones[wIndex+1] = -1;
                    weights[wIndex+2] = 0.0f; bones[wIndex+2] = -1;
                    weights[wIndex+3] = 0.0f; bones[wIndex+3] = -1;
                } else
                {
                    for (int i = 0; i < 4; i++)
                    {
                        if (i < v.weights.size())
                        {
                            BOBJLoader.Weight w = v.weights.get(i);
                            weights[wIndex+i] = w.factor;
                            BOBJBone bone = mesh.armature != null ? mesh.armature.bones.get(w.name) : null;
                            bones[wIndex+i] = (bone == null ? 0 : bone.index);
                        } else
                        {
                            weights[wIndex+i] = 0f;
                            bones[wIndex+i] = -1;
                        }
                    }
                }
                wIndex += 4;
                indices[vIndex] = vIndex;
                vIndex++;
            }
        }

        Map<String, float[]> shapeKeyVerticesCompiled = new HashMap<>();
        Map<String, float[]> shapeKeyNormalsCompiled = new HashMap<>();

        if (mesh instanceof FBXMesh fbxMesh && fbxMesh.shapeKeyVertices != null)
        {
            int vertexBaseIndex = fbxMesh.vertexBaseIndex;
            int normalBaseIndex = fbxMesh.normalBaseIndex;

            for (String key : fbxMesh.shapeKeyVertices.keySet())
            {
                shapeKeyVerticesCompiled.put(key, new float[totalVertices * 3]);
                shapeKeyNormalsCompiled.put(key, new float[totalVertices * 3]);
            }

            int pIdx = 0;
            for (BOBJLoader.Face face : mesh.faces)
            {
                for (BOBJLoader.IndexGroup group : face.idxGroups)
                {
                    int localVertIndex = group.idxPos - vertexBaseIndex;
                    int localNormalIndex = group.idxVecNormal - normalBaseIndex;

                    for (String key : fbxMesh.shapeKeyVertices.keySet())
                    {
                        List<Vector3f> shapeVerts = fbxMesh.shapeKeyVertices.get(key);
                        List<Vector3f> shapeNorms = fbxMesh.shapeKeyNormals.get(key);

                        float[] sPos = shapeKeyVerticesCompiled.get(key);
                        float[] sNorm = shapeKeyNormalsCompiled.get(key);

                        if (localVertIndex >= 0 && localVertIndex < shapeVerts.size())
                        {
                            Vector3f sv = shapeVerts.get(localVertIndex);
                            sPos[pIdx] = sv.x;
                            sPos[pIdx + 1] = sv.y;
                            sPos[pIdx + 2] = sv.z;
                        }
                        else
                        {
                            sPos[pIdx] = pos[pIdx];
                            sPos[pIdx + 1] = pos[pIdx + 1];
                            sPos[pIdx + 2] = pos[pIdx + 2];
                        }

                        if (localNormalIndex >= 0 && localNormalIndex < shapeNorms.size())
                        {
                            Vector3f sn = shapeNorms.get(localNormalIndex);
                            sNorm[pIdx] = sn.x;
                            sNorm[pIdx + 1] = sn.y;
                            sNorm[pIdx + 2] = sn.z;
                        }
                        else
                        {
                            sNorm[pIdx] = norm[pIdx];
                            sNorm[pIdx + 1] = norm[pIdx + 1];
                            sNorm[pIdx + 2] = norm[pIdx + 2];
                        }
                    }
                    pIdx += 3;
                }
            }
        }

        Map<String, FBXShapeKeyDelta> shapeKeyDeltas = new HashMap<>();

        for (Map.Entry<String, float[]> entry : shapeKeyVerticesCompiled.entrySet())
        {
            shapeKeyDeltas.put(entry.getKey(), FBXShapeKeyDelta.fromDense(
                    pos, entry.getValue(), norm, shapeKeyNormalsCompiled.get(entry.getKey())));
        }

        return new FBXCompiledData(pos, tex, norm, weights, bones, indices, mesh, shapeKeyDeltas);
    }

    /**
     * Flattens EVERY mesh in a BOBJData into a single merged CompiledData.
     *
     * <p>Targets that only support one CompiledData per model (BBS CML
     * EDITION's {@code BOBJModel} takes a single mesh, not a list like BBS
     * FS's does) use this instead of {@link #compile(BOBJData, BOBJMesh)}
     * per mesh. Shape keys are intentionally not carried over here - CML's
     * BOBJModel doesn't expose a shape-key slot to plug them into.
     *
     * <p>Unlike merging several independently-compiled CompiledData objects
     * (which would need each mesh's index array re-based against the
     * others), this fills one running set of arrays across all meshes in a
     * single pass, so the vertex/texture/normal/weight indices are already
     * correctly offset as they're written.
     */
    public static CompiledData compileMerged(BOBJData data)
    {
        int totalVertices = 0;
        for (BOBJMesh mesh : data.meshes)
        {
            for (BOBJLoader.Face face : mesh.faces)
            {
                totalVertices += face.idxGroups.length;
            }
        }

        float[] pos = new float[totalVertices * 3];
        float[] tex = new float[totalVertices * 2];
        float[] norm = new float[totalVertices * 3];
        float[] weights = new float[totalVertices * 4];
        int[] bones = new int[totalVertices * 4];
        int[] indices = new int[totalVertices];

        int vIndex = 0;
        int wIndex = 0;
        int pIndex = 0;
        int tIndex = 0;

        BOBJMesh nameSource = data.meshes.isEmpty() ? null : data.meshes.get(0);

        for (BOBJMesh mesh : data.meshes)
        {
            for (BOBJLoader.Face face : mesh.faces)
            {
                for (BOBJLoader.IndexGroup group : face.idxGroups)
                {
                    BOBJLoader.Vertex v = data.vertices.get(group.idxPos);
                    Vector2d t = data.textures.get(group.idxTextCoord);
                    Vector3f n = data.normals.get(group.idxVecNormal);

                    pos[pIndex] = v.x; pos[pIndex + 1] = v.y; pos[pIndex + 2] = v.z;
                    norm[pIndex] = n.x; norm[pIndex + 1] = n.y; norm[pIndex + 2] = n.z;
                    pIndex += 3;

                    tex[tIndex] = (float) t.x; tex[tIndex + 1] = (float) t.y;
                    tIndex += 2;

                    if (v.weights.isEmpty())
                    {
                        weights[wIndex] = 1.0f; bones[wIndex] = 0;
                        weights[wIndex + 1] = 0.0f; bones[wIndex + 1] = -1;
                        weights[wIndex + 2] = 0.0f; bones[wIndex + 2] = -1;
                        weights[wIndex + 3] = 0.0f; bones[wIndex + 3] = -1;
                    }
                    else
                    {
                        for (int i = 0; i < 4; i++)
                        {
                            if (i < v.weights.size())
                            {
                                BOBJLoader.Weight w = v.weights.get(i);
                                weights[wIndex + i] = w.factor;
                                BOBJBone bone = mesh.armature != null ? mesh.armature.bones.get(w.name) : null;
                                bones[wIndex + i] = (bone == null ? 0 : bone.index);
                            }
                            else
                            {
                                weights[wIndex + i] = 0f;
                                bones[wIndex + i] = -1;
                            }
                        }
                    }
                    wIndex += 4;
                    indices[vIndex] = vIndex;
                    vIndex++;
                }
            }
        }

        return new CompiledData(pos, tex, norm, weights, bones, indices, nameSource);
    }

    /**
     * Like {@link #compileMerged}, but ALSO tracks which originating mesh
     * (material) each vertex came from, and merges shape keys across all
     * meshes the same way {@link #compile} does for a single mesh.
     *
     * <p>This is what lets the CML target recover both per-object materials
     * and shape keys despite BOBJModel only holding one CompiledData: the
     * material split is read back out by {@code BOBJModelVAOMixinCML} to
     * issue one draw call per material (see that class), and the shape key
     * deltas are read back out the same way the FS target's mixin already
     * does for its own per-mesh CompiledData.
     */
    public static FBXCompiledData compileMergedWithMaterials(BOBJData data)
    {
        return compileMergedWithMaterials(data, false);
    }

    /**
     * Like {@link #compileMergedWithMaterials(BOBJData)}, with two options
     * the FBX path doesn't need but the native-BOBJ path does:
     * <ul>
     *   <li><b>{@code flipV}</b>: native BOBJ rendering flips V
     *       ({@code 1 - y}, see {@code BOBJLoader.processFaceVertex}) because
     *       BOBJ files author UVs top-left-origin. FBX/OBJ UVs are standard
     *       bottom-left-origin and must NOT flip -- pass {@code false}.</li>
     *   <li><b>missing UV/normal/position indices</b>: native
     *       {@code processFaceVertex} guards {@code idxTextCoord/idxVecNormal
     *       >= 0} and leaves {@code 0} otherwise. FBX data always has valid
     *       indices, native BOBJ files can have {@code -1} (e.g. faces with
     *       no UVs), so this guards too instead of throwing.</li>
     * </ul>
     */
    public static FBXCompiledData compileMergedWithMaterials(BOBJData data, boolean flipV)
    {
        int totalVertices = 0;
        for (BOBJMesh mesh : data.meshes)
        {
            for (BOBJLoader.Face face : mesh.faces)
            {
                totalVertices += face.idxGroups.length;
            }
        }

        float[] pos = new float[totalVertices * 3];
        float[] tex = new float[totalVertices * 2];
        float[] norm = new float[totalVertices * 3];
        float[] weights = new float[totalVertices * 4];
        int[] bones = new int[totalVertices * 4];
        int[] indices = new int[totalVertices];
        int[] materialIndex = new int[totalVertices];

        /* Shape keys are collected into one globally-indexed list up front so
         * the per-vertex loop below can address them by array slot. It used
         * to look every key up by name in three HashMaps for every single
         * vertex, then rescan the whole key set again to fill in the keys the
         * current mesh doesn't define -- on a 200k-vertex model with 20 blend
         * shapes that is over 10 million map lookups spent producing what is
         * almost entirely rest-pose data. */
        List<String> shapeKeyNames = new java.util.ArrayList<>();
        Map<String, Integer> shapeKeyIndices = new HashMap<>();

        for (BOBJMesh mesh : data.meshes)
        {
            if (mesh instanceof FBXMesh fbxMesh && fbxMesh.shapeKeyVertices != null)
            {
                for (String key : fbxMesh.shapeKeyVertices.keySet())
                {
                    if (shapeKeyIndices.putIfAbsent(key, shapeKeyNames.size()) == null)
                    {
                        shapeKeyNames.add(key);
                    }
                }
            }
        }

        int shapeKeyCount = shapeKeyNames.size();
        FBXShapeKeyDelta.Builder[] shapeKeyBuilders = new FBXShapeKeyDelta.Builder[shapeKeyCount];

        for (int k = 0; k < shapeKeyCount; k++)
        {
            shapeKeyBuilders[k] = new FBXShapeKeyDelta.Builder();
        }

        /* Per-mesh view of the global key list, refilled once per mesh:
         * meshShapePositions[k] is the current mesh's data for global key k,
         * or null when this mesh doesn't define that key (which then
         * contributes no delta at all, instead of the old code writing a
         * redundant copy of the rest pose). */
        float[][] meshShapePositions = new float[shapeKeyCount][];
        float[][] meshShapeNormals = new float[shapeKeyCount][];

        BOBJMesh nameSource = data.meshes.isEmpty() ? null : data.meshes.get(0);
        List<String> materialNames = new java.util.ArrayList<>();
        Map<String, Integer> materialIndexByName = new HashMap<>();

        int vIndex = 0;
        int wIndex = 0;
        int pIndex = 0;
        int tIndex = 0;

        for (BOBJMesh mesh : data.meshes)
        {
            String materialName = mesh.name == null ? "" : mesh.name;
            int thisMaterialIndex = materialIndexByName.computeIfAbsent(materialName, k ->
            {
                materialNames.add(materialName);
                return materialNames.size() - 1;
            });

            FBXMesh fbxMesh = mesh instanceof FBXMesh fm ? fm : null;
            int vertexBaseIndex = fbxMesh != null ? fbxMesh.vertexBaseIndex : 0;
            int normalBaseIndex = fbxMesh != null ? fbxMesh.normalBaseIndex : 0;

            /* Flatten this mesh's shape keys into the globally-indexed slots
             * once per mesh. Flat float[] rather than the source
             * List<Vector3f>: the vertex loop reads these in triangulated
             * (i.e. scattered) order, and chasing a boxed Vector3f per read
             * is what made this loop memory-bound. */
            java.util.Arrays.fill(meshShapePositions, null);
            java.util.Arrays.fill(meshShapeNormals, null);

            if (fbxMesh != null && fbxMesh.shapeKeyVertices != null)
            {
                for (Map.Entry<String, List<Vector3f>> entry : fbxMesh.shapeKeyVertices.entrySet())
                {
                    int k = shapeKeyIndices.get(entry.getKey());

                    meshShapePositions[k] = flatten(entry.getValue());
                    meshShapeNormals[k] = flatten(fbxMesh.shapeKeyNormals.get(entry.getKey()));
                }
            }

            for (BOBJLoader.Face face : mesh.faces)
            {
                for (BOBJLoader.IndexGroup group : face.idxGroups)
                {
                    BOBJLoader.Vertex v = group.idxPos >= 0 ? data.vertices.get(group.idxPos) : null;

                    float tx = 0.0f;
                    float ty = 0.0f;

                    if (group.idxTextCoord >= 0)
                    {
                        Vector2d t = data.textures.get(group.idxTextCoord);
                        tx = (float) t.x;
                        ty = flipV ? (float) (1.0 - t.y) : (float) t.y;
                    }

                    float nx = 0.0f;
                    float ny = 0.0f;
                    float nz = 0.0f;

                    if (group.idxVecNormal >= 0)
                    {
                        Vector3f n = data.normals.get(group.idxVecNormal);
                        nx = n.x;
                        ny = n.y;
                        nz = n.z;
                    }

                    float vx = v != null ? v.x : 0.0f;
                    float vy = v != null ? v.y : 0.0f;
                    float vz = v != null ? v.z : 0.0f;

                    pos[pIndex] = vx; pos[pIndex + 1] = vy; pos[pIndex + 2] = vz;
                    norm[pIndex] = nx; norm[pIndex + 1] = ny; norm[pIndex + 2] = nz;

                    /* Record only what each key actually moves. A key this
                     * mesh doesn't define, or a vertex outside the key's
                     * range, sat at the rest pose before and so contributes a
                     * zero delta -- nothing to store. */
                    if (shapeKeyCount > 0)
                    {
                        int localVertIndex = (group.idxPos - vertexBaseIndex) * 3;
                        int localNormalIndex = (group.idxVecNormal - normalBaseIndex) * 3;

                        for (int k = 0; k < shapeKeyCount; k++)
                        {
                            float[] shapePositions = meshShapePositions[k];

                            if (shapePositions != null && localVertIndex >= 0 && localVertIndex + 2 < shapePositions.length)
                            {
                                FBXShapeKeyDelta.Builder builder = shapeKeyBuilders[k];

                                appendDelta(builder, pIndex, shapePositions[localVertIndex], vx, true);
                                appendDelta(builder, pIndex + 1, shapePositions[localVertIndex + 1], vy, true);
                                appendDelta(builder, pIndex + 2, shapePositions[localVertIndex + 2], vz, true);
                            }

                            float[] shapeNormals = meshShapeNormals[k];

                            if (shapeNormals != null && localNormalIndex >= 0 && localNormalIndex + 2 < shapeNormals.length)
                            {
                                FBXShapeKeyDelta.Builder builder = shapeKeyBuilders[k];

                                appendDelta(builder, pIndex, shapeNormals[localNormalIndex], nx, false);
                                appendDelta(builder, pIndex + 1, shapeNormals[localNormalIndex + 1], ny, false);
                                appendDelta(builder, pIndex + 2, shapeNormals[localNormalIndex + 2], nz, false);
                            }
                        }
                    }

                    pIndex += 3;

                    tex[tIndex] = tx; tex[tIndex + 1] = ty;
                    tIndex += 2;

                    if (v == null || v.weights.isEmpty())
                    {
                        boolean hasBones = mesh.armature != null && !mesh.armature.bones.isEmpty();
                        weights[wIndex] = hasBones ? 1.0f : 0.0f;
                        bones[wIndex] = hasBones ? 0 : -1;
                        weights[wIndex + 1] = 0.0f; bones[wIndex + 1] = -1;
                        weights[wIndex + 2] = 0.0f; bones[wIndex + 2] = -1;
                        weights[wIndex + 3] = 0.0f; bones[wIndex + 3] = -1;
                    }
                    else
                    {
                        for (int i = 0; i < 4; i++)
                        {
                            if (i < v.weights.size())
                            {
                                BOBJLoader.Weight w = v.weights.get(i);
                                weights[wIndex + i] = w.factor;
                                BOBJBone bone = mesh.armature != null ? mesh.armature.bones.get(w.name) : null;
                                bones[wIndex + i] = (bone == null ? 0 : bone.index);
                            }
                            else
                            {
                                weights[wIndex + i] = 0f;
                                bones[wIndex + i] = -1;
                            }
                        }
                    }
                    wIndex += 4;

                    materialIndex[vIndex] = thisMaterialIndex;
                    indices[vIndex] = vIndex;
                    vIndex++;
                }
            }
        }

        Map<String, FBXShapeKeyDelta> shapeKeyDeltas = new HashMap<>();

        for (int k = 0; k < shapeKeyCount; k++)
        {
            FBXShapeKeyDelta delta = shapeKeyBuilders[k].build();

            if (!delta.isEmpty())
            {
                shapeKeyDeltas.put(shapeKeyNames.get(k), delta);
            }
        }

        FBXCompiledData compiled = new FBXCompiledData(pos, tex, norm, weights, bones, indices, nameSource, shapeKeyDeltas);
        compiled.setMaterialSplit(materialIndex, materialNames.toArray(new String[0]));

        return compiled;
    }

    /** Records one component's shape-key offset, skipping the (overwhelmingly common) case of it not moving at all. */
    private static void appendDelta(FBXShapeKeyDelta.Builder builder, int component, float shapeValue, float restValue, boolean position)
    {
        if (shapeValue == restValue)
        {
            return;
        }

        if (position)
        {
            builder.position(component, shapeValue - restValue);
        }
        else
        {
            builder.normal(component, shapeValue - restValue);
        }
    }

    /** Packs a {@code Vector3f} list into a flat xyz array for cache-friendly random access. */
    private static float[] flatten(List<Vector3f> source)
    {
        if (source == null)
        {
            return null;
        }

        float[] flat = new float[source.size() * 3];
        int i = 0;

        for (Vector3f vector : source)
        {
            flat[i] = vector.x;
            flat[i + 1] = vector.y;
            flat[i + 2] = vector.z;
            i += 3;
        }

        return flat;
    }
}