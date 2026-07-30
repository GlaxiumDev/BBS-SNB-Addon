package elgatopro300.bbsfbx.model.fbx;

import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIAnimMesh;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;

import java.util.LinkedHashSet;
import java.util.Set;

public class FBXShapeKeyNames
{
    /**
     * Scans every mesh in the scene for Assimp AnimMeshes (FBX shape
     * keys/blend shapes) and returns their resolved names, in encounter
     * order. Pure Assimp scanning - shared by both the FS and CML loaders.
     */
    public static Set<String> collectShapeKeyNames(AIScene scene)
    {
        LinkedHashSet<String> names = new LinkedHashSet<>();

        if (scene == null || scene.mNumMeshes() <= 0 || scene.mMeshes() == null)
        {
            return names;
        }

        PointerBuffer meshes = scene.mMeshes();

        for (int meshIndex = 0; meshIndex < scene.mNumMeshes(); meshIndex++)
        {
            AIMesh mesh = AIMesh.createSafe(meshes.get(meshIndex));

            if (mesh == null || mesh.mNumAnimMeshes() <= 0 || mesh.mAnimMeshes() == null)
            {
                continue;
            }

            String meshName = FBXShapeKeyNames.safeName(mesh.mName().dataString());
            PointerBuffer animMeshes = mesh.mAnimMeshes();

            for (int animIndex = 0; animIndex < mesh.mNumAnimMeshes(); animIndex++)
            {
                AIAnimMesh animMesh = AIAnimMesh.createSafe(animMeshes.get(animIndex));

                if (animMesh == null)
                {
                    continue;
                }

                String shapeKeyName = FBXShapeKeyNames.buildShapeKeyName(animMesh, meshName, animIndex);

                if (!shapeKeyName.isBlank())
                {
                    names.add(shapeKeyName);
                }
            }
        }

        return names;
    }

    public static String buildShapeKeyName(AIAnimMesh animMesh, String meshName, int animIndex)
    {
        String name = safeName(animMesh.mName().dataString());

        if (!name.isBlank())
        {
            return normalizeShapeKeyName(name);
        }

        if (!meshName.isBlank())
        {
            return normalizeShapeKeyName(meshName + "_ShapeKey_" + animIndex);
        }

        return "ShapeKey_" + animIndex;
    }

    public static String safeName(String name)
    {
        return name == null ? "" : name.trim();
    }

    private static String normalizeShapeKeyName(String name)
    {
        return stripRepeatedName(safeName(name));
    }

    private static String stripRepeatedName(String name)
    {
        if (name == null)
        {
            return "";
        }

        name = name.trim();

        if (name.isEmpty())
        {
            return "";
        }

        int dot = name.lastIndexOf('.');

        if (dot > 0 && dot < name.length() - 1)
        {
            String left = name.substring(0, dot).trim();
            String right = name.substring(dot + 1).trim();

            if (left.equals(right))
            {
                return left;
            }
        }

        return name;
    }
}