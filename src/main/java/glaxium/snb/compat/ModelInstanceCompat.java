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
import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Bridges the public-field Base/CML model API and FS / BBS&nbsp;2.1 ModelConfig APIs. */
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
            return;
        }

        Object viaGetter = invokeNoArgs(instance, "getScale");

        if (viaGetter instanceof Vector3f vector)
        {
            vector.set(value);
            return;
        }

        try
        {
            setConfigValue(instance, "scale", new Vector3f(value));
        }
        catch (ReflectiveOperationException e)
        {
            throw incompatible("scale", e);
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

            /* Wemppy FS: LookAtValue with nested head/pitch settings. */
            Object headSetting = read(lookAt, "head");

            if (headSetting != null)
            {
                setValue(headSetting, head);
                setValue(read(lookAt, "pitch"), pitch);
                return;
            }

            /* BBS 2.1: config.lookAt is ValueView; value starts null. */
            if (lookAt != null)
            {
                View view = null;
                Object existing = invokeNoArgs(lookAt, "get");

                if (existing instanceof View current)
                {
                    view = current;
                }
                else
                {
                    view = new View();
                }

                view.headBone = head;
                view.pitch = pitch;

                try
                {
                    setValue(lookAt, view);
                }
                catch (ReflectiveOperationException e)
                {
                    /* Last resort: write BaseValueBasic.value directly. */
                    Field valueField = findDeclaredField(lookAt.getClass(), "value");

                    if (valueField == null)
                    {
                        throw e;
                    }

                    valueField.setAccessible(true);
                    valueField.set(lookAt, view);
                }

                return;
            }

            throw new NoSuchFieldException("look-at");
        }
        catch (ReflectiveOperationException e)
        {
            throw incompatible("look-at", e);
        }
    }

    private static Field findDeclaredField(Class<?> type, String name)
    {
        Class<?> cursor = type;

        while (cursor != null && cursor != Object.class)
        {
            try
            {
                return cursor.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored)
            {
                cursor = cursor.getSuperclass();
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<ArmorSlot> getItemsMain(ModelInstance instance)
    {
        return itemSlots(instance, "getItemsMain", "itemsMain");
    }

    @SuppressWarnings("unchecked")
    public static List<ArmorSlot> getItemsOff(ModelInstance instance)
    {
        return itemSlots(instance, "getItemsOff", "itemsOff");
    }

    @SuppressWarnings("unchecked")
    public static Map<ArmorType, ArmorSlot> getArmorSlots(ModelInstance instance)
    {
        Object value = get(instance, "getArmorSlots", "armorSlots");

        if (value instanceof Map<?, ?> map)
        {
            /* BBS 2.1 rebuilds a fresh HashMap each getArmorSlots() call — wrap put. */
            Object config = read(instance, "config");
            Object armorSlots = read(config, "armorSlots");

            if (armorSlots != null && hasMethod(armorSlots.getClass(), "getSlot", ArmorType.class))
            {
                return armorSlotMap(armorSlots, (Map<ArmorType, ArmorSlot>) map);
            }

            return (Map<ArmorType, ArmorSlot>) map;
        }

        Object config = read(instance, "config");
        Object fromConfig = invokeNoArgs(config, "getArmorSlots");

        if (fromConfig instanceof Map<?, ?> map)
        {
            return (Map<ArmorType, ArmorSlot>) map;
        }

        Object armorSlots = read(config, "armorSlots");

        if (armorSlots != null && hasMethod(armorSlots.getClass(), "getSlot", ArmorType.class))
        {
            return armorSlotMap(armorSlots, Collections.emptyMap());
        }

        return null;
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

    @SuppressWarnings("unchecked")
    private static List<ArmorSlot> itemSlots(ModelInstance instance, String getter, String field)
    {
        Object value = get(instance, getter, field);

        if (value instanceof List<?> list)
        {
            return (List<ArmorSlot>) list;
        }

        Object config = read(instance, "config");
        Object fromConfig = invokeNoArgs(config, getter);

        if (fromConfig instanceof List<?> list)
        {
            return (List<ArmorSlot>) list;
        }

        Object holder = read(config, field);

        if (holder != null)
        {
            Object slots = invokeNoArgs(holder, "getSlots");

            if (slots instanceof List<?> list && hasMethod(holder.getClass(), "addSlot", ArmorSlot.class))
            {
                return slotAddList(holder, (List<ArmorSlot>) list);
            }

            if (slots instanceof List<?> list)
            {
                return (List<ArmorSlot>) list;
            }

            if (hasMethod(holder.getClass(), "addSlot", ArmorSlot.class))
            {
                return slotAddList(holder, List.of());
            }
        }

        return new java.util.ArrayList<>();
    }

    private static List<ArmorSlot> slotAddList(Object holder, List<ArmorSlot> snapshot)
    {
        return new AbstractList<>()
        {
            @Override
            public ArmorSlot get(int index)
            {
                List<ArmorSlot> slots = currentSlots(holder, snapshot);
                return slots.get(index);
            }

            @Override
            public int size()
            {
                return currentSlots(holder, snapshot).size();
            }

            @Override
            public boolean add(ArmorSlot slot)
            {
                try
                {
                    holder.getClass().getMethod("addSlot", ArmorSlot.class).invoke(holder, slot);
                    return true;
                }
                catch (ReflectiveOperationException e)
                {
                    throw incompatible("item slot", e);
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<ArmorSlot> currentSlots(Object holder, List<ArmorSlot> fallback)
    {
        Object slots = invokeNoArgs(holder, "getSlots");
        return slots instanceof List<?> list ? (List<ArmorSlot>) list : fallback;
    }

    private static Map<ArmorType, ArmorSlot> armorSlotMap(Object holder, Map<ArmorType, ArmorSlot> snapshot)
    {
        return new java.util.AbstractMap<>()
        {
            @Override
            public java.util.Set<Entry<ArmorType, ArmorSlot>> entrySet()
            {
                return snapshot.entrySet();
            }

            @Override
            public ArmorSlot put(ArmorType key, ArmorSlot value)
            {
                try
                {
                    Object slotValue = holder.getClass().getMethod("getSlot", ArmorType.class).invoke(holder, key);
                    setValue(slotValue, value);
                    return null;
                }
                catch (ReflectiveOperationException e)
                {
                    throw incompatible("armor slots", e);
                }
            }
        };
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

        /* BaseValueBasic erases to set(Object); ValueVector3f (BBS 2.1) only has set(Vector3f). */
        for (Method method : setting.getClass().getMethods())
        {
            if (!"set".equals(method.getName()) || method.getParameterCount() != 1)
            {
                continue;
            }

            Class<?> param = method.getParameterTypes()[0];

            if (param.isInstance(value) || param == Object.class)
            {
                method.invoke(setting, value);
                return;
            }
        }

        throw new NoSuchMethodException("No compatible set(...) on " + setting.getClass().getName());
    }

    private static Object get(Object owner, String method, String field)
    {
        Object value = invokeNoArgs(owner, method);
        return value != null ? value : read(owner, field);
    }

    private static Object invokeNoArgs(Object owner, String method)
    {
        if (owner == null) return null;

        try
        {
            return owner.getClass().getMethod(method).invoke(owner);
        }
        catch (ReflectiveOperationException ignored)
        {
            return null;
        }
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... params)
    {
        try
        {
            type.getMethod(name, params);
            return true;
        }
        catch (NoSuchMethodException e)
        {
            return false;
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
