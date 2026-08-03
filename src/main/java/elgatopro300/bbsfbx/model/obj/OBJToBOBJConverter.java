package elgatopro300.bbsfbx.model.obj;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.Face;
import mchorse.bbs_mod.bobj.BOBJLoader.IndexGroup;
import mchorse.bbs_mod.bobj.BOBJLoader.Vertex;
import mchorse.bbs_mod.obj.MeshOBJ;
import mchorse.bbs_mod.obj.MeshesOBJ;
import mchorse.bbs_mod.obj.OBJMaterial;

import org.joml.Matrix4f;
import org.joml.Vector2d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a compiled OBJ scene -- the {@code Map<String, MeshesOBJ>} that
 * {@code OBJParser.compile()} returns -- into a {@code BOBJData} with one
 * {@code BOBJMesh} per OBJ material, so Base/CML can drive OBJ models through
 * the addon's per-material pipeline (the same one the FBX loader uses) instead
 * of the native baked-atlas single texture.
 *
 * <p>Details that keep the result identical to what native Base/CML OBJ
 * rendering produced:
 * <ul>
 *   <li><b>Scale:</b> the native cubic path scales OBJ positions by 16
 *       ({@code ModelData.fill} does {@code pos * 16.0f}); this does the same,
 *       so OBJ models keep their expected size.</li>
 *   <li><b>UVs:</b> copied straight through as normalized coordinates (native
 *       passes {@code texData} through untouched; it never flips V).</li>
 *   <li><b>Normals:</b> copied straight through.</li>
 *   <li><b>Fallback pools:</b> a shared (0,0) UV and (0,1,0) normal are kept
 *       at index 0 so meshes without UVs/normals still point at valid indices
 *       ({@code FBXMeshCompiler} reads texture/normal pools unconditionally).</li>
 * </ul>
 *
 * <p>The output uses a single dummy armature ("Armature", one bone) so the
 * model rides the exact same {@code BOBJModel} + {@code BOBJModelVAO} render
 * path as FBX models; every OBJ vertex is unweighted and skinned to bone 0,
 * i.e. static.</p>
 */
public final class OBJToBOBJConverter
{
    public static final String DUMMY_ARMATURE_NAME = "Armature";

    private OBJToBOBJConverter() {}

    public static BOBJData convert(Map<String, MeshesOBJ> compile)
    {
        List<Vertex> vertices = new ArrayList<>();
        List<Vector2d> textures = new ArrayList<>();
        List<Vector3f> normals = new ArrayList<>();
        List<BOBJMesh> meshes = new ArrayList<>();
        Map<String, BOBJMesh> meshByName = new HashMap<>();

        textures.add(new Vector2d(0.0, 0.0));
        normals.add(new Vector3f(0.0f, 1.0f, 0.0f));

        for (MeshesOBJ value : compile.values())
        {
            for (MeshOBJ mesh : value.meshes)
            {
                OBJMaterial material = mesh.material;
                String name = material != null && material.name != null && !material.name.isEmpty()
                        ? material.name
                        : "default";

                BOBJMesh bobjMesh = meshByName.get(name);

                if (bobjMesh == null)
                {
                    bobjMesh = new BOBJMesh(name);
                    bobjMesh.armatureName = DUMMY_ARMATURE_NAME;
                    meshes.add(bobjMesh);
                    meshByName.put(name, bobjMesh);
                }

                /* NOTE: Mesh.triangles is really the VERTEX count -- posData
                 * is already expanded to 3 floats per face-vertex, so
                 * posData.length / 3 vertices, 3 vertices per triangle.
                 * Native callers iterate it as "one vertex per index" too
                 * (ModelData.fill / applyBakedOffsets). */
                int count = mesh.triangles;
                int triangleCount = count / 3;
                int[] posIdx = new int[count];
                int[] texIdx = new int[count];
                int[] normIdx = new int[count];

                for (int i = 0; i < count; i++)
                {
                    int p = i * 3;
                    int t = i * 2;

                    posIdx[i] = vertices.size();
                    vertices.add(new Vertex(
                            mesh.posData[p] * 16.0f,
                            mesh.posData[p + 1] * 16.0f,
                            mesh.posData[p + 2] * 16.0f));

                    if (mesh.texData != null && t + 1 < mesh.texData.length)
                    {
                        texIdx[i] = textures.size();
                        textures.add(new Vector2d(mesh.texData[t], mesh.texData[t + 1]));
                    }
                    else
                    {
                        texIdx[i] = 0;
                    }

                    if (mesh.normData != null && p + 2 < mesh.normData.length)
                    {
                        normIdx[i] = normals.size();
                        normals.add(new Vector3f(mesh.normData[p], mesh.normData[p + 1], mesh.normData[p + 2]));
                    }
                    else
                    {
                        normIdx[i] = 0;
                    }
                }

                for (int tri = 0; tri < triangleCount; tri++)
                {
                    Face face = new Face();

                    for (int k = 0; k < 3; k++)
                    {
                        int i = tri * 3 + k;
                        face.idxGroups[k] = new IndexGroup(posIdx[i], texIdx[i], normIdx[i]);
                    }

                    bobjMesh.faces.add(face);
                }
            }
        }

        Map<String, BOBJArmature> armatures = new HashMap<>();
        BOBJArmature armature = new BOBJArmature(DUMMY_ARMATURE_NAME);
        armature.addBone(new BOBJBone(0, "bone0", "", new Matrix4f()));
        armature.initArmature();
        armature.setupMatrices();
        armatures.put(DUMMY_ARMATURE_NAME, armature);

        for (BOBJMesh mesh : meshes)
        {
            mesh.armature = armature;
        }

        return new BOBJData(vertices, textures, normals, meshes, new HashMap<>(), armatures);
    }
}
