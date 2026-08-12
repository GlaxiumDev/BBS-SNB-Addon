package glaxium.snb.anim;

import glaxium.snb.model.fbx.loaders.IFbxModel;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.IModelInstance;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.pose.Transform;

import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Head look-at for addon-imported FBX/GLB/glTF only (models carrying
 * {@link IFbxModel} data). Native BOBJ is left entirely to stock BBS.
 *
 * <p>Does not call {@code ModelInstance.getView()} directly — that method
 * exists on BBS 2.4+ but not on BBS 1.7.x (public {@code view} field only).
 * Settings are resolved reflectively; bone name defaults to {@code head}.</p>
 */
public final class HeadLookAt
{
    private static final String DEFAULT_HEAD = "head";
    private static final float DEFAULT_LIMIT = 45.0F;
    private static final LookSettings DEFAULTS = new LookSettings(DEFAULT_HEAD, true, DEFAULT_LIMIT);
    private static final ThreadLocal<LookSettings> SETTINGS = ThreadLocal.withInitial(
            () -> new LookSettings(DEFAULT_HEAD, true, DEFAULT_LIMIT));
    private static final Method GET_VIEW = findGetView();
    private static final Field VIEW_FIELD = findViewField();
    private static final ClassValue<ViewFields> VIEW_FIELDS = new ClassValue<>()
    {
        @Override
        protected ViewFields computeValue(Class<?> type)
        {
            try
            {
                return new ViewFields(
                        type.getField("headBone"),
                        type.getField("pitch"),
                        type.getField("constraint"));
            }
            catch (ReflectiveOperationException e)
            {
                return ViewFields.MISSING;
            }
        }
    };

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

    /**
     * Resolve look settings without linking against BBS 2.4-only
     * {@code getView()}. Tries {@code getView()}, then public field
     * {@code view} (BBS 1.7.x).
     */
    private static LookSettings resolveLookSettings(IModelInstance instance)
    {
        if (!(instance instanceof ModelInstance modelInstance))
        {
            return DEFAULTS;
        }

        Object view = null;

        try
        {
            if (GET_VIEW != null)
            {
                view = GET_VIEW.invoke(modelInstance);
            }
            else if (VIEW_FIELD != null)
            {
                view = VIEW_FIELD.get(modelInstance);
            }
        }
        catch (ReflectiveOperationException | RuntimeException ignored)
        {
            return DEFAULTS;
        }

        if (view == null)
        {
            return DEFAULTS;
        }

        try
        {
            ViewFields fields = VIEW_FIELDS.get(view.getClass());

            if (fields == ViewFields.MISSING)
            {
                return DEFAULTS;
            }

            String headBone = (String) fields.headBone.get(view);
            boolean pitch = fields.pitch.getBoolean(view);
            float limit = fields.constraint.getFloat(view);

            if (headBone == null || headBone.trim().isEmpty())
            {
                return DEFAULTS;
            }

            LookSettings settings = SETTINGS.get();

            settings.boneName = headBone.trim();
            settings.pitch = pitch;
            settings.limit = limit > 0.0F ? limit : DEFAULT_LIMIT;

            return settings;
        }
        catch (ReflectiveOperationException | ClassCastException ignored)
        {
            return DEFAULTS;
        }
    }

    private static Method findGetView()
    {
        try
        {
            return ModelInstance.class.getMethod("getView");
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    private static Field findViewField()
    {
        try
        {
            return ModelInstance.class.getField("view");
        }
        catch (ReflectiveOperationException e)
        {
            return null;
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

    private static final class LookSettings
    {
        private String boneName;
        private boolean pitch;
        private float limit;

        LookSettings(String boneName, boolean pitch, float limit)
        {
            this.boneName = boneName;
            this.pitch = pitch;
            this.limit = limit;
        }
    }

    private static final class ViewFields
    {
        private static final ViewFields MISSING = new ViewFields(null, null, null);

        private final Field headBone;
        private final Field pitch;
        private final Field constraint;

        private ViewFields(Field headBone, Field pitch, Field constraint)
        {
            this.headBone = headBone;
            this.pitch = pitch;
            this.constraint = constraint;
        }
    }
}
