package glaxium.snb.anim;

import glaxium.snb.model.fbx.loaders.IFbxModel;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.IModelInstance;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.View;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.pose.Transform;

import org.joml.Vector3f;

/**
 * Head look-at for addon-imported FBX/GLB/glTF only (models carrying
 * {@link IFbxModel} data). Native BOBJ is left entirely to stock BBS.
 *
 * <p>Imported armatures use the opposite yaw/pitch signs from
 * {@code ProceduralAnimator}'s BOBJ convention.</p>
 */
public final class HeadLookAt
{
    private static final String DEFAULT_HEAD = "head";

    private HeadLookAt()
    {
    }

    public static void apply(IEntity entity, IModelInstance instance, float transition)
    {
        if (entity == null || instance == null)
        {
            return;
        }

        IModel model = instance.getModel();

        if (model == null || !isImportedArmature(model))
        {
            return;
        }

        View view = instance instanceof ModelInstance modelInstance ? modelInstance.getView() : null;
        String boneName = DEFAULT_HEAD;
        boolean applyPitch = true;

        if (view != null && view.headBone != null && !view.headBone.trim().isEmpty())
        {
            boneName = view.headBone.trim();
            applyPitch = view.pitch;
        }

        float bodyYaw = Lerps.lerp(entity.getPrevBodyYaw(), entity.getBodyYaw(), transition);
        float headYaw = Lerps.lerp(entity.getPrevHeadYaw(), entity.getHeadYaw(), transition);
        float yaw = headYaw - bodyYaw;
        float pitch = Lerps.lerp(entity.getPrevPitch(), entity.getPitch(), transition);

        if (view != null)
        {
            float limit = view.constraint;

            if (limit > 0.0F)
            {
                yaw = clamp(yaw, -limit, limit);
                pitch = clamp(pitch, -limit, limit);
            }
        }

        // Yaw matches stock BOBJ (-yaw). Pitch is inverted vs BOBJ (+pitch)
        // for imported FBX/GLB/glTF local head axes.
        final float yawSign = -1.0F;
        final float pitchSign = 1.0F;

        for (BOBJBone bone : model.getAllBOBJBones())
        {
            if (bone != null && boneName.equals(bone.name))
            {
                applyBobj(bone.transform, yaw, pitch, applyPitch, yawSign, pitchSign);
                return;
            }
        }

        for (ModelGroup group : model.getAllGroups())
        {
            if (group != null && boneName.equals(group.id))
            {
                applyCubic(group.current, yaw, pitch, applyPitch, yawSign, pitchSign);
                return;
            }
        }
    }

    private static boolean isImportedArmature(IModel model)
    {
        return model instanceof IFbxModel fbx
            && fbx.bbsFbx$getFbxData() != null;
    }

    private static void applyBobj(Transform transform, float yawDeg, float pitchDeg, boolean applyPitch,
            float yawSign, float pitchSign)
    {
        Vector3f rotate = transform.rotate;
        rotate.y = MathUtils.toRad(yawSign * yawDeg);

        if (applyPitch)
        {
            rotate.x = MathUtils.toRad(pitchSign * pitchDeg);
        }
    }

    private static void applyCubic(Transform transform, float yawDeg, float pitchDeg, boolean applyPitch,
            float yawSign, float pitchSign)
    {
        Vector3f rotate = transform.rotate;
        rotate.y = yawSign * yawDeg;

        if (applyPitch)
        {
            rotate.x = pitchSign * pitchDeg;
        }
    }

    private static float clamp(float value, float min, float max)
    {
        return value < min ? min : (value > max ? max : value);
    }
}
