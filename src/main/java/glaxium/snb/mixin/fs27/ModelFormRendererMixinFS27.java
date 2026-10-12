package glaxium.snb.mixin.fs27;

import glaxium.snb.model.fbx.loaders.IFormMaterialTextureHolder;
import glaxium.snb.mixin.FormRendererAccessor;
import glaxium.snb.render.CurrentMaterialTextureOverrides;
import glaxium.snb.render.CurrentEmoticonArmor;
import glaxium.snb.render.CurrentModelTexture;
import glaxium.snb.compat.ModelInstanceCompat;

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
 * BBS FS 2.7+ variant of the material-texture override push/pop.
 * BBS 2.7 dropped the unused 'boolean ui' parameter from renderModel,
 * reducing the argument count from 13 to 12.
 */
@Mixin(value = ModelFormRenderer.class, remap = false)
public abstract class ModelFormRendererMixinFS27
{
    @Inject(method = "renderModel", at = @At("HEAD"), remap = false)
    private void bbsFbx$pushMaterialOverrides(
            IEntity target, Supplier<ShaderProgram> program, MatrixStack stack, ModelInstance model,
            int light, int overlay, Color color, Color ambient, boolean additive,
            StencilMap stencilMap, float transition, MatrixStack poseStack,
            CallbackInfo info)
    {
        CurrentEmoticonArmor.push(target, model, true);
        Form form = ((FormRendererAccessor) (Object) this).bbsFbx$getForm();

        if (form instanceof ModelForm modelForm)
        {
            CurrentMaterialTextureOverrides.push(((IFormMaterialTextureHolder) modelForm).bbsFbx$getMaterialTextureOverrides());
            CurrentModelTexture.push(modelForm.texture.get() == null ? ModelInstanceCompat.getTexture(model) : modelForm.texture.get());
        }
        else
        {
            CurrentMaterialTextureOverrides.push(null);
            CurrentModelTexture.push(ModelInstanceCompat.getTexture(model));
        }
    }

    @Inject(method = "renderModel", at = @At("RETURN"), remap = false)
    private void bbsFbx$popMaterialOverrides(
            IEntity target, Supplier<ShaderProgram> program, MatrixStack stack, ModelInstance model,
            int light, int overlay, Color color, Color ambient, boolean additive,
            StencilMap stencilMap, float transition, MatrixStack poseStack,
            CallbackInfo info)
    {
        CurrentMaterialTextureOverrides.pop();
        CurrentModelTexture.pop();
        CurrentEmoticonArmor.pop();
    }
}
