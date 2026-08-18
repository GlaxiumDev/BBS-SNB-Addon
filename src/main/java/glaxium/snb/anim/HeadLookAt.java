package glaxium.snb.anim;

import glaxium.snb.model.fbx.loaders.IFbxModel;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.IModelInstance;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.pose.Transform;

import org.joml.Vector3f;

/**
 * Head look-at for models animated by the clip {@code Animator}, which
 * stock BBS never look-ats (only {@code ProceduralAnimator} rotates a bone
 * named {@code head}). Covers addon-imported FBX/GLB/glTF (models carrying
 * {@link IFbxModel} data) AND native BOBJ models, so a bone named
 * {@code head} follows the entity's look on every fork.
 *
 * <p>The override must also clear the bone's {@code orient} quaternion:
 * BBS's {@code BOBJBone.applyTransformations()} prefers {@code orient} over
 * the euler {@code rotate} triple whenever it is non-null, and the clip
 * Animator composes it every frame ({@code postApply}). Leaving it set makes
 * the euler look override silently ignored -- the head freezes even though
 * the values below are correct. {@code ProceduralAnimator} does exactly the
 * same clearing for its sneaking pose. Cubic groups get the same treatment
 * reflectively, because Base 1.7.7's {@code ModelGroup} has no
 * {@code orient} field.</p>
 *
 * <p>Does not call {@code ModelInstance.getView()} directly — that method
 * exists on BBS 2.4+ but not on BBS 1.7.x (public {@code view} field only).
 * Settings are resolved reflectively; bone name defaults to {@code head}.</p>
 */
public final class HeadLookAt
{
    private static final String DEFAULT_HEAD = "head";
    private static final float DEFAULT_LIMIT = 45.0F;

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

        if (model == null || !supportsLookAt(model))
        {
            return;
        }

        LookSettings look = resolveLookSettings(instance);
        String boneName = look.boneName;
        boolean applyPitch = look.pitch;

        float bodyYaw = Lerps.lerp(entity.getPrevBodyYaw(), entity.getBodyYaw(), transition);
        float headYaw = Lerps.lerp(entity.getPrevHeadYaw(), entity.getHeadYaw(), transition);
        float yaw = headYaw - bodyYaw;
        float pitch = Lerps.lerp(entity.getPrevPitch(), entity.getPitch(), transition);

        if (look.limit > 0.0F)
        {
            yaw = clamp(yaw, -look.limit, look.limit);
            pitch = clamp(pitch, -look.limit, look.limit);
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

                /* The clip Animator's postApply composes this bone's orient
                 * quaternion every frame, and applyTransformations() prefers
                 * it over the euler triple we just wrote. Clear it so the
                 * look override is authoritative -- the same trick
                 * ProceduralAnimator uses for the sneaking pose. */
                clearOrient(bone);

                return;
            }
        }

        for (ModelGroup group : model.getAllGroups())
        {
            if (group != null && boneName.equals(group.id))
            {
                applyCubic(group.current, yaw, pitch, applyPitch, yawSign, pitchSign);
                clearOrient(group);

                return;
            }
        }
    }

    /**
     * Resolve look settings without linking against BBS 2.4-only
     * {@code getView()}. Tries {@code getView()}, then public field
     * {@code view} (BBS 1.7.x).
     */
    private static LookSettings resolveLookSettings(IModelInstance instance)
    {
        LookSettings defaults = new LookSettings(DEFAULT_HEAD, true, DEFAULT_LIMIT);

        if (!(instance instanceof ModelInstance modelInstance))
        {
            return defaults;
        }

        Object view = null;

        try
        {
            view = ModelInstance.class.getMethod("getView").invoke(modelInstance);
        }
        catch (ReflectiveOperationException ignored)
        {
            try
            {
                view = ModelInstance.class.getField("view").get(modelInstance);
            }
            catch (ReflectiveOperationException ignoredAgain)
            {
                return defaults;
            }
        }

        if (view == null)
        {
            return defaults;
        }

        try
        {
            Class<?> viewClass = view.getClass();
            String headBone = (String) viewClass.getField("headBone").get(view);
            boolean pitch = viewClass.getField("pitch").getBoolean(view);
            float limit = viewClass.getField("constraint").getFloat(view);

            if (headBone == null || headBone.trim().isEmpty())
            {
                return defaults;
            }

            return new LookSettings(headBone.trim(), pitch, limit > 0.0F ? limit : DEFAULT_LIMIT);
        }
        catch (ReflectiveOperationException | ClassCastException ignored)
        {
            return defaults;
        }
    }

    private static boolean supportsLookAt(IModel model)
    {
        /* Native BOBJ models get the same look-at as imported armatures:
         * stock BBS only rotates a "head" bone inside ProceduralAnimator,
         * so clip-animator BOBJ heads never follow the camera without this. */
        return model instanceof BOBJModel
            || (model instanceof IFbxModel fbx && fbx.bbsFbx$getFbxData() != null);
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

    /**
     * Bone equivalent of {@link #clearOrient(ModelGroup)}. Reflective because
     * the {@code orient} field only exists on forks with the quaternion
     * composition path (FS, CML); Base 1.7.7 has no such field and honors
     * the euler triple directly, so a no-op there is the correct behavior.
     */
    private static void clearOrient(BOBJBone bone)
    {
        try
        {
            BOBJBone.class.getField("orient").set(bone, null);
        }
        catch (ReflectiveOperationException ignored)
        {}
    }

    /**
     * Cubic equivalent of {@link #clearOrient(BOBJBone)}. Reflective because
     * Base 1.7.7's {@code ModelGroup} has no {@code orient} field; on forks
     * that have it, clearing it keeps the euler look override authoritative.
     */
    private static void clearOrient(ModelGroup group)
    {
        try
        {
            group.getClass().getField("orient").set(group, null);
        }
        catch (ReflectiveOperationException ignored)
        {}
    }

    private static float clamp(float value, float min, float max)
    {
        return value < min ? min : (value > max ? max : value);
    }

    private static final class LookSettings
    {
        final String boneName;
        final boolean pitch;
        final float limit;

        LookSettings(String boneName, boolean pitch, float limit)
        {
            this.boneName = boneName;
            this.pitch = pitch;
            this.limit = limit;
        }
    }
}
