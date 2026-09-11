package glaxium.snb.compat;

import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Bridges Base/FS/CML per-axis {@code AnimationPart} fields ({@code x}/{@code rx}/…)
 * and BBS&nbsp;2.1 vector channels ({@code translate}/{@code rotate}/{@code scale}).
 *
 * <p>Callers write into axis {@link KeyframeChannel}s from {@link #axisChannel},
 * then {@link #commit(AnimationPart)} flushes staging channels into the vector
 * form on BBS&nbsp;2.1. On axis forks commit is a no-op.</p>
 */
public final class AnimationPartCompat
{
    private static final boolean AXIS_MODE = hasPublicField(AnimationPart.class, "x");

    private static final Map<AnimationPart, Staging> STAGING = new IdentityHashMap<>();

    private static final Constructor<?> CHANNEL_CTOR;
    private static final Constructor<?> MOLANG_VECTOR_CTOR;
    private static final Object MOLANG_FACTORY;
    private static final Field TRANSLATE;
    private static final Field ROTATE;
    private static final Field SCALE;

    static
    {
        Constructor<?> ctor = null;
        Constructor<?> molangVectorCtor = null;
        Object molangFactory = null;
        Field translate = null;
        Field rotate = null;
        Field scale = null;

        try
        {
            Class<?> factoryClass = Class.forName("mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory");
            ctor = KeyframeChannel.class.getConstructor(String.class, factoryClass);
        }
        catch (ReflectiveOperationException ignored)
        {
        }

        try
        {
            molangFactory = Class.forName("mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories")
                    .getField("MOLANG")
                    .get(null);
        }
        catch (ReflectiveOperationException ignored)
        {
        }

        if (!AXIS_MODE)
        {
            try
            {
                translate = AnimationPart.class.getField("translate");
                rotate = AnimationPart.class.getField("rotate");
                scale = AnimationPart.class.getField("scale");
                molangVectorCtor = Class.forName("mchorse.bbs_mod.cubic.data.animation.MolangVector")
                        .getConstructor(MolangExpression.class, MolangExpression.class, MolangExpression.class);
            }
            catch (ReflectiveOperationException ignored)
            {
            }
        }

        CHANNEL_CTOR = ctor;
        MOLANG_VECTOR_CTOR = molangVectorCtor;
        MOLANG_FACTORY = molangFactory;
        TRANSLATE = translate;
        ROTATE = rotate;
        SCALE = scale;
    }

    private AnimationPartCompat()
    {
    }

    public static boolean isAxisMode()
    {
        return AXIS_MODE;
    }

    /**
     * Path forms: {@code location.x}, {@code rotation.y}, {@code scale.z},
     * or base {@code location}/{@code rotation}/{@code scale} with {@code axisIndex} 0–2.
     */
    @SuppressWarnings("unchecked")
    public static KeyframeChannel<MolangExpression> axisChannel(AnimationPart part, String path, int axisIndex)
    {
        String field = fieldName(path, axisIndex);

        if (field == null)
        {
            return null;
        }

        if (AXIS_MODE)
        {
            try
            {
                return (KeyframeChannel<MolangExpression>) AnimationPart.class.getField(field).get(part);
            }
            catch (ReflectiveOperationException e)
            {
                throw new IllegalStateException("Missing AnimationPart." + field, e);
            }
        }

        return staging(part).channel(field);
    }

    public static KeyframeChannel<MolangExpression> axisChannel(AnimationPart part, String path)
    {
        return axisChannel(part, path, -1);
    }

    public static boolean isRotationEmpty(AnimationPart part)
    {
        if (AXIS_MODE)
        {
            try
            {
                KeyframeChannel<?> rx = (KeyframeChannel<?>) AnimationPart.class.getField("rx").get(part);

                return rx == null || rx.isEmpty();
            }
            catch (ReflectiveOperationException e)
            {
                return true;
            }
        }

        Staging staging = STAGING.get(part);

        if (staging != null && !staging.channel("rx").isEmpty())
        {
            return false;
        }

        try
        {
            KeyframeChannel<?> rotate = ROTATE == null ? null : (KeyframeChannel<?>) ROTATE.get(part);

            return rotate == null || rotate.isEmpty();
        }
        catch (ReflectiveOperationException e)
        {
            return true;
        }
    }

    public static void insertRotation(AnimationPart part, float frame, MolangExpression rx, MolangExpression ry)
    {
        KeyframeChannel<MolangExpression> x = axisChannel(part, "rotation.x");
        KeyframeChannel<MolangExpression> y = axisChannel(part, "rotation.y");

        if (x != null && rx != null)
        {
            x.insert(frame, rx);
        }

        if (y != null && ry != null)
        {
            y.insert(frame, ry);
        }

        commit(part);
    }

    /** Flush staging axis channels into BBS&nbsp;2.1 vector channels. No-op on axis forks. */
    public static void commit(AnimationPart part)
    {
        if (AXIS_MODE || part == null)
        {
            return;
        }

        Staging staging = STAGING.remove(part);

        if (staging == null || MOLANG_VECTOR_CTOR == null)
        {
            return;
        }

        try
        {
            staging.flush(part, "x", "y", "z", TRANSLATE, MolangParser.ZERO);
            staging.flush(part, "sx", "sy", "sz", SCALE, MolangParser.ONE);
            staging.flush(part, "rx", "ry", "rz", ROTATE, MolangParser.ZERO);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Failed to commit AnimationPart vector channels", e);
        }
    }

    private static Staging staging(AnimationPart part)
    {
        return STAGING.computeIfAbsent(part, ignored -> new Staging());
    }

    private static String fieldName(String path, int axisIndex)
    {
        if (path == null)
        {
            return null;
        }

        return switch (path)
        {
            case "location.x", "x" -> "x";
            case "location.y", "y" -> "y";
            case "location.z", "z" -> "z";
            case "rotation.x", "rx" -> "rx";
            case "rotation.y", "ry" -> "ry";
            case "rotation.z", "rz" -> "rz";
            case "scale.x", "sx" -> "sx";
            case "scale.y", "sy" -> "sy";
            case "scale.z", "sz" -> "sz";
            case "location" -> axisField("x", "y", "z", axisIndex);
            case "rotation" -> axisField("rx", "ry", "rz", axisIndex);
            case "scale" -> axisField("sx", "sy", "sz", axisIndex);
            default -> null;
        };
    }

    private static String axisField(String x, String y, String z, int index)
    {
        return switch (index)
        {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> null;
        };
    }

    private static boolean hasPublicField(Class<?> type, String name)
    {
        try
        {
            type.getField(name);
            return true;
        }
        catch (NoSuchFieldException e)
        {
            return false;
        }
    }

    private static final class Staging
    {
        private final Map<String, KeyframeChannel<MolangExpression>> channels = new HashMap<>();

        @SuppressWarnings("unchecked")
        KeyframeChannel<MolangExpression> channel(String name)
        {
            return channels.computeIfAbsent(name, key -> {
                try
                {
                    if (CHANNEL_CTOR == null || MOLANG_FACTORY == null)
                    {
                        throw new IllegalStateException("KeyframeChannel MOLANG factory unavailable");
                    }

                    return (KeyframeChannel<MolangExpression>) CHANNEL_CTOR.newInstance(key, MOLANG_FACTORY);
                }
                catch (ReflectiveOperationException e)
                {
                    throw new IllegalStateException("Cannot stage AnimationPart." + key, e);
                }
            });
        }

        void flush(AnimationPart part, String a, String b, String c, Field vectorField, MolangExpression fallback)
                throws ReflectiveOperationException
        {
            if (vectorField == null || (!channels.containsKey(a) && !channels.containsKey(b) && !channels.containsKey(c)))
            {
                return;
            }

            /* BBS 2.1 fillVectorFromAxes interpolates missing axes and NPEs
             * when those keyframes have a null factory (Prism / production).
             * Merge on the union of ticks instead, holding the previous value. */
            KeyframeChannel<MolangExpression> x = channels.get(a);
            KeyframeChannel<MolangExpression> y = channels.get(b);
            KeyframeChannel<MolangExpression> z = channels.get(c);
            Object vectorChannel = vectorField.get(part);

            if (vectorChannel == null)
            {
                return;
            }

            TreeSet<Float> ticks = new TreeSet<>();

            collectTicks(ticks, x);
            collectTicks(ticks, y);
            collectTicks(ticks, z);

            Method insert = vectorChannel.getClass().getMethod("insert", float.class, Object.class);
            Method get = vectorChannel.getClass().getMethod("get", int.class);

            for (float tick : ticks)
            {
                Object vector = MOLANG_VECTOR_CTOR.newInstance(
                        sampleAxis(x, tick, fallback),
                        sampleAxis(y, tick, fallback),
                        sampleAxis(z, tick, fallback));
                int index = (Integer) insert.invoke(vectorChannel, tick, vector);
                Keyframe<?> dest = (Keyframe<?>) get.invoke(vectorChannel, index);
                Keyframe<?> source = keyframeAt(x, tick);

                if (source == null)
                {
                    source = keyframeAt(y, tick);
                }

                if (source == null)
                {
                    source = keyframeAt(z, tick);
                }

                if (dest != null && source != null)
                {
                    dest.getInterpolation().copy(source.getInterpolation());
                }
            }
        }
    }

    private static void collectTicks(TreeSet<Float> ticks, KeyframeChannel<MolangExpression> channel)
    {
        if (channel == null || channel.isEmpty())
        {
            return;
        }

        for (Keyframe<MolangExpression> keyframe : channel.getKeyframes())
        {
            ticks.add(keyframe.getTick());
        }
    }

    private static MolangExpression sampleAxis(
            KeyframeChannel<MolangExpression> channel,
            float tick,
            MolangExpression fallback)
    {
        if (channel == null || channel.isEmpty())
        {
            return fallback;
        }

        Keyframe<MolangExpression> previous = null;

        for (Keyframe<MolangExpression> keyframe : channel.getKeyframes())
        {
            float at = keyframe.getTick();

            if (at == tick)
            {
                return keyframe.getValue();
            }

            if (at < tick)
            {
                previous = keyframe;
            }
            else
            {
                return previous != null ? previous.getValue() : keyframe.getValue();
            }
        }

        return previous != null ? previous.getValue() : fallback;
    }

    private static Keyframe<MolangExpression> keyframeAt(KeyframeChannel<MolangExpression> channel, float tick)
    {
        if (channel == null || channel.isEmpty())
        {
            return null;
        }

        for (Keyframe<MolangExpression> keyframe : channel.getKeyframes())
        {
            if (keyframe.getTick() == tick)
            {
                return keyframe;
            }
        }

        return null;
    }
}
