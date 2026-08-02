package elgatopro300.bbsfbx.mixin;

import elgatopro300.bbsfbx.model.fbx.loaders.IFormMaterialTextureHolder;
import elgatopro300.bbsfbx.render.CurrentMaterialTextureOverrides;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.colors.Color;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * The actual fix for "editing one Form's material texture leaks onto every
 * other Form (and morph) sharing the same model file". See
 * {@link CurrentMaterialTextureOverrides}'s doc comment for the full
 * root-cause explanation and why this specific method is the right hook
 * point.
 *
 * <p>Signature was first taken from a decompile of this project's actual
 * {@code ModelFormRenderer.class} (Fernflower, via IntelliJ) -- but that
 * decompile showed an 11th trailing {@code boolean renderEquipment}
 * parameter that Mixin's own error at runtime said doesn't actually exist:
 * the real bytecode target has exactly 10 parameters, ending at
 * {@code float transition}. Fixed using the descriptor Mixin's
 * {@code InvalidInjectionException} reported directly (it computes
 * "expected" from the real target method it already resolved by name, so
 * that message is more authoritative than any decompiler output). Corrected
 * signature:</p>
 *
 * <pre>
 * private void renderModel(IEntity target, Supplier&lt;ShaderProgram&gt; program,
 *         MatrixStack stack, ModelInstance model, int light, int overlay,
 *         Color color, boolean ui, StencilMap stencilMap, float transition)
 * </pre>
 *
 * <p>This is the single private method every public render entry point on
 * {@code ModelFormRenderer} (world rendering, the form-editor UI preview,
 * the first-person arm) funnels through on the way to actually calling
 * {@code model.render(...)}, confirmed directly from the same decompile --
 * so hooking it once here covers every rendering context this addon's
 * multi-material feature needs to work in, not just the editor preview.</p>
 */
@Mixin(value = ModelFormRenderer.class, remap = false)
public abstract class ModelFormRendererMixin
{
    @Inject(
            method = "renderModel(Lmchorse/bbs_mod/forms/entities/IEntity;Ljava/util/function/Supplier;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/cubic/ModelInstance;IILmchorse/bbs_mod/utils/colors/Color;ZLmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;F)V",
            at = @At("HEAD"), remap = false
    )
    private void bbsFbx$pushMaterialOverrides(
            IEntity target, Supplier<ShaderProgram> program, MatrixStack stack, ModelInstance model,
            int light, int overlay, Color color, boolean ui, StencilMap stencilMap, float transition,
            CallbackInfo info)
    {
        Form form = ((FormRendererAccessor) (Object) this).bbsFbx$getForm();

        if (form instanceof ModelForm modelForm)
        {
            CurrentMaterialTextureOverrides.push(((IFormMaterialTextureHolder) modelForm).bbsFbx$getMaterialTextureOverrides());
        }
        else
        {
            CurrentMaterialTextureOverrides.push(null);
        }
    }

    @Inject(
            method = "renderModel(Lmchorse/bbs_mod/forms/entities/IEntity;Ljava/util/function/Supplier;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/cubic/ModelInstance;IILmchorse/bbs_mod/utils/colors/Color;ZLmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;F)V",
            at = @At("RETURN"), remap = false
    )
    private void bbsFbx$popMaterialOverrides(
            IEntity target, Supplier<ShaderProgram> program, MatrixStack stack, ModelInstance model,
            int light, int overlay, Color color, boolean ui, StencilMap stencilMap, float transition,
            CallbackInfo info)
    {
        CurrentMaterialTextureOverrides.pop();
    }
}
