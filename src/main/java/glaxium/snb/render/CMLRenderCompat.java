package glaxium.snb.render;

import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import mchorse.bbs_mod.utils.colors.Color;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * CML-only helpers for the per-material draw path ({@code CubicVAORendererMixinCML}).
 *
 * <p>CML EDITION's dev build carries group-level glow/paint/color-grade state on
 * {@link ModelGroup} and matching uniforms on {@link ModelVAORenderer}, but the
 * released CML 2.0-beta-1 jar and the Base fork predate all of it. This addon
 * compiles against whichever BBS jar sits in {@code libs/}, so none of those
 * members can be referenced directly from the mixin; they are resolved
 * reflectively here and every call degrades to a safe no-op on forks that do
 * not have them (in which case the per-material path simply falls back to the
 * historical behavior: no glow, no paint, no color grade).</p>
 */
public final class CMLRenderCompat
{
    private static final boolean SUPPORTED = detect();

    private static Method isGlowingUniformActive;
    private static Method isPaintOverlayPass;
    private static Method getBaseGlowingStrength;
    private static Method getBaseGlowingR;
    private static Method getBaseGlowingG;
    private static Method getBaseGlowingB;
    private static Method getBasePaintStrength;
    private static Method getBasePaintR;
    private static Method getBasePaintG;
    private static Method getBasePaintB;
    private static Method setGroupPaint;
    private static Method setGroupPaintEffectTransform;
    private static Method setGroupGlowing;
    private static Method setGroupGlowEffectTransform;
    private static Method setGroupFormColorGrade;
    private static Method setGroupColorEffectTransform;
    private static Method setGroupFormColorTint;
    private static Method clearTextureBlend;

    private static Field groupGlowIntensity;
    private static Field groupGlowingColor;
    private static Field groupPaintColor;

    private static Field colorTransform;
    private static Method colorHasActiveTransform;

    private static Method blendBrighten;

    private static Method setPBRTextureIntensity;
    private static boolean pbrIntensityFailed;

    private CMLRenderCompat()
    {}

    private static boolean detect()
    {
        try
        {
            Class<?> renderer = ModelVAORenderer.class;
            Class<?> groupType = ModelGroup.class;
            Class<?> colorType = Color.class;

            isGlowingUniformActive = renderer.getMethod("isGlowingUniformActive");
            isPaintOverlayPass = renderer.getMethod("isPaintOverlayPass");
            getBaseGlowingStrength = renderer.getMethod("getBaseGlowingStrength");
            getBaseGlowingR = renderer.getMethod("getBaseGlowingR");
            getBaseGlowingG = renderer.getMethod("getBaseGlowingG");
            getBaseGlowingB = renderer.getMethod("getBaseGlowingB");
            getBasePaintStrength = renderer.getMethod("getBasePaintStrength");
            getBasePaintR = renderer.getMethod("getBasePaintR");
            getBasePaintG = renderer.getMethod("getBasePaintG");
            getBasePaintB = renderer.getMethod("getBasePaintB");

            groupGlowIntensity = groupType.getField("glowIntensity");
            groupGlowingColor = groupType.getField("glowingColor");
            groupPaintColor = groupType.getField("paintColor");

            colorTransform = colorType.getField("transform");

            Class<?> transformType = colorTransform.getType();

            setGroupPaint = renderer.getMethod("setGroupPaint", float.class, float.class, float.class, float.class);
            setGroupPaintEffectTransform = renderer.getMethod("setGroupPaintEffectTransform", transformType);
            setGroupGlowing = renderer.getMethod("setGroupGlowing", float.class, float.class, float.class, float.class);
            setGroupGlowEffectTransform = renderer.getMethod("setGroupGlowEffectTransform", transformType);
            setGroupFormColorGrade = renderer.getMethod("setGroupFormColorGrade", colorType);
            setGroupColorEffectTransform = renderer.getMethod("setGroupColorEffectTransform", transformType);
            setGroupFormColorTint = renderer.getMethod("setGroupFormColorTint", colorType);
            clearTextureBlend = renderer.getMethod("clearTextureBlend");

            colorHasActiveTransform = colorType.getMethod("hasActiveTransform");

            blendBrighten = Class.forName("mchorse.bbs_mod.forms.renderers.utils.FormColorEffects")
                .getMethod("blendBrighten", colorType, colorType, float.class);

            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    public static boolean isSupported()
    {
        return SUPPORTED;
    }

    public static float resolveGlowStrength(ModelGroup group)
    {
        if (ownGlowIntensity(group) != 0F)
        {
            return ownGlowIntensity(group);
        }

        return invokeFloat(getBaseGlowingStrength);
    }

    public static float resolveGlowR(ModelGroup group)
    {
        if (ownGlowIntensity(group) != 0F)
        {
            Color color = ownGlowingColor(group);

            if (color != null)
            {
                return color.r;
            }
        }

        return invokeFloat(getBaseGlowingR);
    }

    public static float resolveGlowG(ModelGroup group)
    {
        if (ownGlowIntensity(group) != 0F)
        {
            Color color = ownGlowingColor(group);

            if (color != null)
            {
                return color.g;
            }
        }

        return invokeFloat(getBaseGlowingG);
    }

    public static float resolveGlowB(ModelGroup group)
    {
        if (ownGlowIntensity(group) != 0F)
        {
            Color color = ownGlowingColor(group);

            if (color != null)
            {
                return color.b;
            }
        }

        return invokeFloat(getBaseGlowingB);
    }

    public static float resolvePaintStrength(ModelGroup group)
    {
        Color color = ownPaintColor(group);

        if (color != null && color.a != 0F)
        {
            return color.a;
        }

        return invokeFloat(getBasePaintStrength);
    }

    public static float resolvePaintR(ModelGroup group)
    {
        Color color = ownPaintColor(group);

        if (color != null && color.a != 0F)
        {
            return color.r;
        }

        return invokeFloat(getBasePaintR);
    }

    public static float resolvePaintG(ModelGroup group)
    {
        Color color = ownPaintColor(group);

        if (color != null && color.a != 0F)
        {
            return color.g;
        }

        return invokeFloat(getBasePaintG);
    }

    public static float resolvePaintB(ModelGroup group)
    {
        Color color = ownPaintColor(group);

        if (color != null && color.a != 0F)
        {
            return color.b;
        }

        return invokeFloat(getBasePaintB);
    }

    public static boolean hasActiveTransform(Color color)
    {
        if (SUPPORTED && colorHasActiveTransform != null)
        {
            try
            {
                return (Boolean) colorHasActiveTransform.invoke(color);
            }
            catch (Throwable t)
            {
                return false;
            }
        }

        return false;
    }

    public static Object colorTransform(Color color)
    {
        if (SUPPORTED && colorTransform != null)
        {
            try
            {
                return colorTransform.get(color);
            }
            catch (Throwable t)
            {
                return null;
            }
        }

        return null;
    }

    public static Object glowingColorTransform(ModelGroup group)
    {
        Color color = ownGlowingColor(group);

        return color == null ? null : colorTransform(color);
    }

    public static Object paintColorTransform(ModelGroup group)
    {
        Color color = ownPaintColor(group);

        return color == null ? null : colorTransform(color);
    }

    public static boolean isGlowingUniformActive()
    {
        return SUPPORTED && invokeBoolean(isGlowingUniformActive);
    }

    public static boolean isPaintOverlayPass()
    {
        return SUPPORTED && invokeBoolean(isPaintOverlayPass);
    }

    public static void blendBrighten(Color base, Color glow, float strength)
    {
        if (SUPPORTED && blendBrighten != null)
        {
            try
            {
                blendBrighten.invoke(null, base, glow, strength);
            }
            catch (Throwable t)
            {
                /* fall through: keep the un-brightened color */
            }
        }
    }

    public static void setGroupPaint(float r, float g, float b, float strength)
    {
        invokeVoid(setGroupPaint, r, g, b, strength);
    }

    public static void setGroupPaintEffectTransform(Object transform)
    {
        invokeVoid(setGroupPaintEffectTransform, transform);
    }

    public static void setGroupGlowing(float r, float g, float b, float strength)
    {
        invokeVoid(setGroupGlowing, r, g, b, strength);
    }

    public static void setGroupGlowEffectTransform(Object transform)
    {
        invokeVoid(setGroupGlowEffectTransform, transform);
    }

    public static void setGroupFormColorGrade(Color color)
    {
        invokeVoid(setGroupFormColorGrade, color);
    }

    public static void setGroupColorEffectTransform(Object transform)
    {
        invokeVoid(setGroupColorEffectTransform, transform);
    }

    public static void setGroupFormColorTint(Color color)
    {
        invokeVoid(setGroupFormColorTint, color);
    }

    public static void clearTextureBlend()
    {
        invokeVoid(clearTextureBlend);
    }

    /**
     * Stages the Iris thread-local active PBR intensity (normal + specular)
     * for the next {@code trackTexture} snapshot, mirroring what CML's own
     * {@code ModelFormRenderer.applyPBRTextureIntensity} does. Resolved
     * reflectively so this still compiles against forks whose
     * {@code BBSRendering} predates {@code setPBRTextureIntensity}; on those
     * the call is a no-op and the per-material loop simply leaves the
     * whole-model intensity (already staged by the native renderer) active.
     */
    public static void stagePbrIntensity(float normal, float specular)
    {
        if (pbrIntensityFailed)
        {
            return;
        }

        try
        {
            if (setPBRTextureIntensity == null)
            {
                setPBRTextureIntensity = BBSRendering.class.getMethod("setPBRTextureIntensity", float.class, float.class);
            }

            setPBRTextureIntensity.invoke(null, normal, specular);
        }
        catch (Throwable t)
        {
            pbrIntensityFailed = true;
        }
    }

    private static float ownGlowIntensity(ModelGroup group)
    {
        if (SUPPORTED && groupGlowIntensity != null)
        {
            try
            {
                return groupGlowIntensity.getFloat(group);
            }
            catch (Throwable t)
            {
                return 0F;
            }
        }

        return 0F;
    }

    private static Color ownGlowingColor(ModelGroup group)
    {
        if (SUPPORTED && groupGlowingColor != null)
        {
            try
            {
                return (Color) groupGlowingColor.get(group);
            }
            catch (Throwable t)
            {
                return null;
            }
        }

        return null;
    }

    private static Color ownPaintColor(ModelGroup group)
    {
        if (SUPPORTED && groupPaintColor != null)
        {
            try
            {
                return (Color) groupPaintColor.get(group);
            }
            catch (Throwable t)
            {
                return null;
            }
        }

        return null;
    }

    private static float invokeFloat(Method method)
    {
        if (!SUPPORTED || method == null)
        {
            return 0F;
        }

        try
        {
            return ((Number) method.invoke(null)).floatValue();
        }
        catch (Throwable t)
        {
            return 0F;
        }
    }

    private static boolean invokeBoolean(Method method)
    {
        if (!SUPPORTED || method == null)
        {
            return false;
        }

        try
        {
            return (Boolean) method.invoke(null);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static void invokeVoid(Method method, Object... args)
    {
        if (!SUPPORTED || method == null)
        {
            return;
        }

        try
        {
            method.invoke(null, args);
        }
        catch (Throwable t)
        {
            /* ignore */
        }
    }
}
