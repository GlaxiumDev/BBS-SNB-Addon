package elgatopro300.bbsfbx.render;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;

/**
 * CML-only counterpart to {@link CurrentMaterialTextureOverrides}: the
 * currently-rendering Form's per-material PBR intensity overrides, pushed
 * and popped around {@code ModelFormRenderer.renderModel} by
 * {@code mixin.cml.ModelFormRendererMixinCML} and read by the per-material
 * draw loop in {@code mixin.cml.BOBJModelVAOMixinCML}. Same stack rationale
 * as the texture overrides (nested Form renders must not clobber each
 * other); harmless and empty on Base/FS where the CML-gated consumer never
 * runs.
 *
 * <p>The whole-model PBR intensity (the {@code ModelForm} values that
 * {@code applyPBRTextureIntensity} already staged into Iris) is tracked in
 * parallel via {@link #currentBase()} so the per-material loop can fall back
 * to it for any channel a material override leaves null - reading it back
 * out of Iris directly would force {@code Class.forName} initialization of
 * {@code IrisUtils}, which blows up when the Iris mod isn't loaded.</p>
 */
public final class CurrentMaterialPbrOverrides
{
    private static final Deque<Map<String, MaterialPbrIntensity>> STACK = new ArrayDeque<>();
    private static final Deque<MaterialPbrIntensity> BASE_STACK = new ArrayDeque<>();

    private CurrentMaterialPbrOverrides() {}

    public static void push(Map<String, MaterialPbrIntensity> overrides, MaterialPbrIntensity base)
    {
        STACK.push(overrides == null ? Collections.emptyMap() : overrides);
        BASE_STACK.push(base == null ? MaterialPbrIntensity.neutral() : base);
    }

    public static void pop()
    {
        if (!STACK.isEmpty())
        {
            STACK.pop();
        }

        if (!BASE_STACK.isEmpty())
        {
            BASE_STACK.pop();
        }
    }

    /** The innermost currently-rendering Form's per-material PBR overrides, or empty if none is set. */
    public static Map<String, MaterialPbrIntensity> current()
    {
        return STACK.isEmpty() ? Collections.emptyMap() : STACK.peek();
    }

    /**
     * The innermost currently-rendering Form's whole-model PBR intensity
     * (its {@code pbrNormalIntensity}/{@code pbrSpecularIntensity} values),
     * or neutral ({@code 1.0/1.0}) if no Form is on the stack.
     */
    public static MaterialPbrIntensity currentBase()
    {
        return BASE_STACK.isEmpty() ? MaterialPbrIntensity.neutral() : BASE_STACK.peek();
    }
}
