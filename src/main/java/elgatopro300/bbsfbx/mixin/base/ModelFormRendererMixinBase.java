package elgatopro300.bbsfbx.mixin.base;

import elgatopro300.bbsfbx.model.fbx.loaders.IFormMaterialTextureHolder;
import elgatopro300.bbsfbx.mixin.FormRendererAccessor;
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
 * Base-fork variant of the material-texture override push/pop. The actual
 * fix for "editing one Form's material texture leaks onto every other Form
 * (and morph) sharing the same model file". See
 * {@link CurrentMaterialTextureOverrides}'s doc comment for the full
 * root-cause explanation and why this specific method is the right hook
 * point.
 *
 * <p>This is gated to the stock BBS fork by
 * {@code elgatopro300.bbsfbx.BBSFbxMixinPlugin}: {@code renderModel} has a
 * trailing {@code boolean renderEquipment} parameter only in the CML fork
 * (see {@code ModelFormRendererMixinCML}), so the two variants can't share
 * one mixin -- a descriptor-carrying {@code @Inject} on a method whose
 * signature differs between forks makes Mixin's permissive selector fail on
 * whichever fork doesn't match (an {@code InvalidInjectionException}).
 * Keeping one mixin per fork, gated the same way the existing
 * {@code ModelInstanceMixin} trio is, sidesteps that entirely.</p>
 */
@Mixin(value = ModelFormRenderer.class, remap = false)
public abstract class ModelFormRendererMixinBase
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
