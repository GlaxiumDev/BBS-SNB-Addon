package elgatopro300.bbsfbx.mixin.fs;

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
 * FS-fork variant of the material-texture override push/pop, identical to
 * {@code ModelFormRendererMixinBase} and {@code ModelFormRendererMixinCML}
 * except for {@code renderModel}'s signature, which differs on every fork:
 * FS's version (confirmed directly against {@code bbs-2.4-1.20.4.jar}) is
 * {@code (IEntity, Supplier<ShaderProgram>, MatrixStack, ModelInstance, int,
 * int, Color, Color, boolean, boolean, StencilMap, float, MatrixStack)} --
 * two colors, two booleans and a trailing MatrixStack that the other forks
 * don't have.
 *
 * <p>Gated to the FS fork by {@code elgatopro300.bbsfbx.BBSFbxMixinPlugin}.
 * Keeping one mixin per fork, gated the same way the existing
 * {@code ModelInstanceMixin} trio is, is what lets each fork's variant carry
 * its own descriptor without Mixin's permissive selector tripping over the
 * fork-divergent {@code renderModel} signature.</p>
 */
@Mixin(value = ModelFormRenderer.class, remap = false)
public abstract class ModelFormRendererMixinFS
{
    @Inject(
            method = "renderModel(Lmchorse/bbs_mod/forms/entities/IEntity;Ljava/util/function/Supplier;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/cubic/ModelInstance;IILmchorse/bbs_mod/utils/colors/Color;Lmchorse/bbs_mod/utils/colors/Color;ZZLmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;FLnet/minecraft/client/util/math/MatrixStack;)V",
            at = @At("HEAD"), remap = false
    )
    private void bbsFbx$pushMaterialOverrides(
            IEntity target, Supplier<ShaderProgram> program, MatrixStack stack, ModelInstance model,
            int light, int overlay, Color color, Color ambient, boolean ui, boolean renderEquipment,
            StencilMap stencilMap, float transition, MatrixStack poseStack,
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
            method = "renderModel(Lmchorse/bbs_mod/forms/entities/IEntity;Ljava/util/function/Supplier;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/cubic/ModelInstance;IILmchorse/bbs_mod/utils/colors/Color;Lmchorse/bbs_mod/utils/colors/Color;ZZLmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;FLnet/minecraft/client/util/math/MatrixStack;)V",
            at = @At("RETURN"), remap = false
    )
    private void bbsFbx$popMaterialOverrides(
            IEntity target, Supplier<ShaderProgram> program, MatrixStack stack, ModelInstance model,
            int light, int overlay, Color color, Color ambient, boolean ui, boolean renderEquipment,
            StencilMap stencilMap, float transition, MatrixStack poseStack,
            CallbackInfo info)
    {
        CurrentMaterialTextureOverrides.pop();
    }
}
