package glaxium.snb.compat;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Resolves BOBJ bones on forks that expose {@code IModel.getAllBOBJBones()}
 * (Base/FS/CML) and on BBS&nbsp;2.1, which only keeps bones on the armature.
 */
public final class ModelBonesCompat
{
    private static final Method GET_ALL_BOBJ_BONES;

    static
    {
        Method method = null;

        try
        {
            method = IModel.class.getMethod("getAllBOBJBones");
        }
        catch (NoSuchMethodException ignored)
        {
        }

        GET_ALL_BOBJ_BONES = method;
    }

    private ModelBonesCompat()
    {
    }

    @SuppressWarnings("unchecked")
    public static Collection<BOBJBone> getAllBOBJBones(IModel model)
    {
        if (model == null)
        {
            return Collections.emptyList();
        }

        if (GET_ALL_BOBJ_BONES != null)
        {
            try
            {
                Object bones = GET_ALL_BOBJ_BONES.invoke(model);

                if (bones instanceof Collection<?> collection)
                {
                    return (Collection<BOBJBone>) collection;
                }
            }
            catch (ReflectiveOperationException ignored)
            {
            }
        }

        if (model instanceof BOBJModel bobj)
        {
            BOBJArmature armature = bobj.getArmature();

            if (armature != null && armature.orderedBones != null)
            {
                return armature.orderedBones;
            }

            if (armature != null && armature.bones != null)
            {
                return armature.bones.values();
            }
        }

        return List.of();
    }
}
