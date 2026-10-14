package glaxium.snb.mixin.fs;

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

import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FS variant of the material-texture override push/pop targeting BBS FS 2.7+ and BBS 2.1/2.3.2.
 * Uses MixinExtras @Local so varying parameter counts (12, 13, 14 args) are handled cleanly.
 */
@Mixin(value = ModelFormRenderer.class, remap = false)
public abstract class ModelFormRendererMixinFS
{
    @Shadow public abstract ModelInstance getModel();

    @Inject(method = "render3D", at = @At("HEAD"), remap = false)
    private void bbsFbx$pushMaterialOverrides3D(
            mchorse.bbs_mod.forms.renderers.FormRenderingContext context,
            CallbackInfo info)
    {
        IEntity target = context == null ? null : context.entity;
        ModelInstance model = this.getModel();
        boolean renderEquipment = context == null || (!context.ui && !context.isPicking());

        bbsFbx$push(target, model, renderEquipment);
    }

    @Inject(method = "render3D", at = @At("RETURN"), remap = false)
    private void bbsFbx$popMaterialOverrides3D(
            mchorse.bbs_mod.forms.renderers.FormRenderingContext context,
            CallbackInfo info)
    {
        bbsFbx$pop();
    }

    @Inject(method = "renderModel", at = @At("HEAD"), remap = false)
    private void bbsFbx$pushMaterialOverrides(
            CallbackInfo info,
            @Local(argsOnly = true) IEntity target,
            @Local(argsOnly = true) ModelInstance model)
    {
        bbsFbx$push(target, model, true);
    }

    @Inject(method = "renderModel", at = @At("RETURN"), remap = false)
    private void bbsFbx$popMaterialOverrides(CallbackInfo info)
    {
        bbsFbx$pop();
    }

    private void bbsFbx$push(IEntity target, ModelInstance model, boolean renderEquipment)
    {
        CurrentEmoticonArmor.push(target, model, renderEquipment);
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

    private void bbsFbx$pop()
    {
        CurrentMaterialTextureOverrides.pop();
        CurrentModelTexture.pop();
        CurrentEmoticonArmor.pop();
    }
}
