package glaxium.snb.compat;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.ArmorSlot;
import mchorse.bbs_mod.cubic.model.ArmorType;
import mchorse.bbs_mod.cubic.model.View;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.pose.Pose;

import org.joml.Vector3f;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Bridges the public-field Base/CML model API and FS's ModelConfig API. */
public final class ModelInstanceCompat
{
    private ModelInstanceCompat() {}

    public static void setProcedural(ModelInstance instance, boolean value) { set(instance, "procedural", value); }
    public static void setCulling(ModelInstance instance, boolean value) { set(instance, "culling", value); }
    public static void setUiScale(ModelInstance instance, float value) { set(instance, "uiScale", value); }
    public static void setAnchor(ModelInstance instance, String value) { set(instance, "anchorGroup", "anchor", value); }
    public static void setSneakingPose(ModelInstance instance, Pose value) { set(instance, "sneakingPose", value); }

    public static void setScale(ModelInstance instance, Vector3f value)
    {
        Object current = read(instance, "scale");

        if (current instanceof Vector3f vector)
        {
            vector.set(value);
        }
        else
        {
            try
            {
                setConfigValue(instance, "scale", new Vector3f(value));
            }
            catch (ReflectiveOperationException e)
            {
                throw incompatible("scale", e);
            }
        }
    }

    public static void setView(ModelInstance instance, String head, boolean pitch)
    {
        try
        {
            Field field = publicField(instance.getClass(), "view");

            if (field != null)
            {
                View view = (View) field.get(instance);

                if (view == null)
                {
                    view = new View();
                    field.set(instance, view);
                }

                view.headBone = head;
                view.pitch = pitch;
                return;
            }

            Object config = read(instance, "config");
            Object lookAt = read(config, "lookAt");
            setValue(read(lookAt, "head"), head);
            setValue(read(lookAt, "pitch"), pitch);
        }
        catch (ReflectiveOperationException e)
        {
            throw incompatible("look-at", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<ArmorSlot> getItemsMain(ModelInstance instance)
    {
        return (List<ArmorSlot>) get(instance, "getItemsMain", "itemsMain");
    }

    @SuppressWarnings("unchecked")
    public static List<ArmorSlot> getItemsOff(ModelInstance instance)
    {
        return (List<ArmorSlot>) get(instance, "getItemsOff", "itemsOff");
    }

    @SuppressWarnings("unchecked")
    public static Map<ArmorType, ArmorSlot> getArmorSlots(ModelInstance instance)
    {
        return (Map<ArmorType, ArmorSlot>) get(instance, "getArmorSlots", "armorSlots");
    }

    public static String getPoseGroup(ModelInstance instance)
    {
        Object value = get(instance, "getPoseGroup", "poseGroup");
        return value instanceof String string && !string.isEmpty() ? string : instance.id;
    }

    public static Link getTexture(ModelInstance instance)
    {
        Object value = invokeNoArgs(instance, "getTexture");

        if (value == null) value = read(instance, "texture");
        if (value == null) value = read(instance, "baseTexture");

        return value instanceof Link link ? link : null;
    }

    public static ArmorSlot newArmorSlot(String id, String group)
    {
        try
        {
            Constructor<ArmorSlot> constructor;

            try
            {
                constructor = ArmorSlot.class.getConstructor();
            }
            catch (NoSuchMethodException e)
            {
                constructor = ArmorSlot.class.getConstructor(String.class);
            }

            ArmorSlot slot = constructor.getParameterCount() == 0
                    ? constructor.newInstance()
                    : constructor.newInstance(id);
            Object groupField = read(slot, "group");

            if (groupField instanceof String)
            {
                publicField(slot.getClass(), "group").set(slot, group);
            }
            else
            {
                setValue(groupField, group);
            }

            return slot;
        }
        catch (ReflectiveOperationException e)
        {
            throw incompatible("armor slot", e);
        }
    }

    private static void set(ModelInstance instance, String name, Object value)
    {
        set(instance, name, name, value);
    }

    private static void set(ModelInstance instance, String fieldName, String configName, Object value)
    {
        try
        {
            Field field = publicField(instance.getClass(), fieldName);

            if (field != null)
            {
                field.set(instance, value);
                return;
            }

            setConfigValue(instance, configName, value);
        }
        catch (ReflectiveOperationException e)
        {
            throw incompatible(fieldName, e);
        }
    }

    private static void setConfigValue(ModelInstance instance, String name, Object value) throws ReflectiveOperationException
    {
        Object config = read(instance, "config");
        Object setting = read(config, name);
        setValue(setting, value);
    }

    private static void setValue(Object setting, Object value) throws ReflectiveOperationException
    {
        if (setting == null) throw new NoSuchFieldException("Missing setting");

        Method method = setting.getClass().getMethod("set", Object.class);
        method.invoke(setting, value);
    }

    private static Object get(Object owner, String method, String field)
    {
        Object value = invokeNoArgs(owner, method);
        return value != null ? value : read(owner, field);
    }

    private static Object invokeNoArgs(Object owner, String method)
    {
        try
        {
            return owner.getClass().getMethod(method).invoke(owner);
        }
        catch (ReflectiveOperationException ignored)
        {
            return null;
        }
    }

    private static Object read(Object owner, String name)
    {
        if (owner == null) return null;

        try
        {
            Field field = publicField(owner.getClass(), name);
            return field == null ? null : field.get(owner);
        }
        catch (ReflectiveOperationException ignored)
        {
            return null;
        }
    }

    private static Field publicField(Class<?> type, String name)
    {
        try
        {
            return type.getField(name);
        }
        catch (NoSuchFieldException ignored)
        {
            return null;
        }
    }

    private static IllegalStateException incompatible(String feature, Exception cause)
    {
        return new IllegalStateException("Unsupported BBS model API for " + feature, cause);
    }
}
