package glaxium.snb.model.fbx;

import glaxium.snb.model.fbx.scene.JavaScene;

import java.util.LinkedHashSet;
import java.util.Set;

public class FBXShapeKeyNames
{
    /**
     * Scans every scene mesh for shape keys/blend shapes and returns their
     * resolved names in encounter order.
     */
    public static Set<String> collectShapeKeyNames(JavaScene scene)
    {
        LinkedHashSet<String> names = new LinkedHashSet<>();

        if (scene == null)
        {
            return names;
        }

        for (JavaScene.Mesh mesh : scene.meshes)
        {
            if (mesh == null || mesh.shapeKeys.isEmpty())
            {
                continue;
            }

            String meshName = FBXShapeKeyNames.safeName(mesh.name);

            for (int animIndex = 0; animIndex < mesh.shapeKeys.size(); animIndex++)
            {
                String shapeKeyName = FBXShapeKeyNames.buildShapeKeyName(mesh.shapeKeys.get(animIndex), meshName, animIndex);

                if (!shapeKeyName.isBlank())
                {
                    names.add(shapeKeyName);
                }
            }
        }

        return names;
    }

    public static String buildShapeKeyName(JavaScene.ShapeKey shapeKey, String meshName, int animIndex)
    {
        String name = safeName(shapeKey == null ? null : shapeKey.name);

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
