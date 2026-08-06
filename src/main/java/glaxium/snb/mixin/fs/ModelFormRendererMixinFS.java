package glaxium.snb.mixin.fs;

import glaxium.snb.model.fbx.loaders.IFormMaterialTextureHolder;
import glaxium.snb.mixin.FormRendererAccessor;
import glaxium.snb.render.CurrentMaterialTextureOverrides;

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
 * FS-fork variant of the material-texture override push/pop. Handler
 * parameter lists differ per fork because {@code renderModel}'s signature
 * does; the {@code @Inject} target is the bare name {@code renderModel}
 * (unique on each fork) so Yarn {@code MatrixStack} descriptors never end
 * up in a {@code remap = false} selector -- those break under intermediary
 * on real launchers (Prism / production).
 *
 * <p>Gated to the FS fork by {@code glaxium.snb.BBSFbxMixinPlugin}.</p>
 */
@Mixin(value = ModelFormRenderer.class, remap = false)
public abstract class ModelFormRendererMixinFS
{
    @Inject(method = "renderModel", at = @At("HEAD"), remap = false)
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

    @Inject(method = "renderModel", at = @At("RETURN"), remap = false)
    private void bbsFbx$popMaterialOverrides(
            IEntity target, Supplier<ShaderProgram> program, MatrixStack stack, ModelInstance model,
            int light, int overlay, Color color, Color ambient, boolean ui, boolean renderEquipment,
            StencilMap stencilMap, float transition, MatrixStack poseStack,
            CallbackInfo info)
    {
        CurrentMaterialTextureOverrides.pop();
    }
}
