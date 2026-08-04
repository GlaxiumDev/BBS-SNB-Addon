package elgatopro300.bbsfbx.render;

import java.lang.reflect.Field;

import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;

/**
 * Reads the protected {@code r/g/b/a}, {@code light}, {@code overlay} and
 * {@code stencilMap} fields that {@code CubicCubeRenderer} (the superclass of
 * every fork's {@code CubicVAORenderer}) declares. Mixins applied to
 * {@code CubicVAORenderer} cannot {@code @Shadow} those fields because Mixin
 * only resolves shadow fields declared directly on the target class (or added
 * by super-mixins), not fields inherited from a Java superclass. Values are
 * read via reflection on the render thread only and cached per frame; the
 * {@code Field} handles are resolved once and reused.
 */
public final class CubicCubeRendererFields
{
    private static final Field R = field("r");
    private static final Field G = field("g");
    private static final Field B = field("b");
    private static final Field A = field("a");
    private static final Field LIGHT = field("light");
    private static final Field OVERLAY = field("overlay");
    private static final Field STENCIL_MAP = field("stencilMap");

    private CubicCubeRendererFields()
    {
    }

    public static float getR(Object renderer)
    {
        return getFloat(R, renderer);
    }

    public static float getG(Object renderer)
    {
        return getFloat(G, renderer);
    }

    public static float getB(Object renderer)
    {
        return getFloat(B, renderer);
    }

    public static float getA(Object renderer)
    {
        return getFloat(A, renderer);
    }

    public static int getLight(Object renderer)
    {
        return getInt(LIGHT, renderer);
    }

    public static int getOverlay(Object renderer)
    {
        return getInt(OVERLAY, renderer);
    }

    public static StencilMap getStencilMap(Object renderer)
    {
        try
        {
            return (StencilMap) STENCIL_MAP.get(renderer);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException("Failed to read stencilMap from " + renderer.getClass().getName(), e);
        }
    }

    private static float getFloat(Field field, Object renderer)
    {
        try
        {
            return field.getFloat(renderer);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException("Failed to read " + field.getName() + " from " + renderer.getClass().getName(), e);
        }
    }

    private static int getInt(Field field, Object renderer)
    {
        try
        {
            return field.getInt(renderer);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException("Failed to read " + field.getName() + " from " + renderer.getClass().getName(), e);
        }
    }

    private static Field field(String name)
    {
        try
        {
            Field field = Class.forName("mchorse.bbs_mod.cubic.render.CubicCubeRenderer").getDeclaredField(name);
            field.setAccessible(true);

            return field;
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Failed to resolve CubicCubeRenderer field " + name, e);
        }
    }
}
