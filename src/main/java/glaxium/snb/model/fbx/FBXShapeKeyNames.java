package glaxium.snb.model.fbx;

import glaxium.snb.model.scene.Scene;
import glaxium.snb.model.scene.SceneMesh;
import glaxium.snb.model.scene.SceneMorphTarget;

import java.util.LinkedHashSet;
import java.util.Set;

public class FBXShapeKeyNames
{
    /**
     * Scans every mesh for morph targets (shape keys / blend shapes) and
     * returns their resolved names, in encounter order.
     */
    public static Set<String> collectShapeKeyNames(Scene scene)
    {
        LinkedHashSet<String> names = new LinkedHashSet<>();

        if (scene == null || scene.meshes.isEmpty())
        {
            return names;
        }

        for (SceneMesh mesh : scene.meshes)
        {
            String meshName = safeName(mesh.name);

            for (int animIndex = 0; animIndex < mesh.morphTargets.size(); animIndex++)
            {
                SceneMorphTarget morph = mesh.morphTargets.get(animIndex);
                String shapeKeyName = buildShapeKeyName(morph, meshName, animIndex);

                if (!shapeKeyName.isBlank())
                {
                    names.add(shapeKeyName);
                }
            }
        }

        return names;
    }

    public static String buildShapeKeyName(SceneMorphTarget morph, String meshName, int animIndex)
    {
        String name = safeName(morph == null ? null : morph.name);

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
