package elgatopro300.bbsfbx.mixin.cml;

import elgatopro300.bbsfbx.model.fbx.loaders.IFormMaterialTextureHolder;
import elgatopro300.bbsfbx.mixin.FormRendererAccessor;
import elgatopro300.bbsfbx.render.CurrentMaterialPbrOverrides;
import elgatopro300.bbsfbx.render.CurrentMaterialTextureOverrides;
import elgatopro300.bbsfbx.render.MaterialPbrIntensity;

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
 * CML-fork variant of the material-texture override push/pop, identical to
 * {@code ModelFormRendererMixinBase} except for {@code renderModel}'s extra
 * trailing {@code boolean renderEquipment} parameter, which only exists in
 * the CML fork's bytecode.
 *
 * <p>Gated to the CML fork by {@code elgatopro300.bbsfbx.BBSFbxMixinPlugin}.
 * Keeping the base and CML variants in separate fork-gated mixins (the same
 * pattern the {@code ModelInstanceMixin} trio uses) is what lets both forks
 * run from the same build without Mixin's permissive selector tripping over
 * the fork-divergent {@code renderModel} signature.</p>
 */
@Mixin(value = ModelFormRenderer.class, remap = false)
public abstract class ModelFormRendererMixinCML
{
    @Inject(
            method = "renderModel(Lmchorse/bbs_mod/forms/entities/IEntity;Ljava/util/function/Supplier;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/cubic/ModelInstance;IILmchorse/bbs_mod/utils/colors/Color;ZLmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;FZ)V",
            at = @At("HEAD"), remap = false
    )
    private void bbsFbx$pushMaterialOverrides(
            IEntity target, Supplier<ShaderProgram> program, MatrixStack stack, ModelInstance model,
            int light, int overlay, Color color, boolean ui, StencilMap stencilMap, float transition,
            boolean renderEquipment, CallbackInfo info)
    {
        Form form = ((FormRendererAccessor) (Object) this).bbsFbx$getForm();

        if (form instanceof ModelForm modelForm)
        {
            IFormMaterialTextureHolder holder = (IFormMaterialTextureHolder) modelForm;

            CurrentMaterialTextureOverrides.push(holder.bbsFbx$getMaterialTextureOverrides());
            CurrentMaterialPbrOverrides.push(holder.bbsFbx$getMaterialPbrOverrides(), bbsFbx$formPbrIntensity(modelForm));
        }
        else
        {
            CurrentMaterialTextureOverrides.push(null);
            CurrentMaterialPbrOverrides.push(null, null);
        }
    }

    /**
     * Reads the whole-model PBR intensity off the {@code ModelForm}'s
     * CML-only {@code pbrNormalIntensity}/{@code pbrSpecularIntensity}
     * {@code ValueFloat}s (via reflection so this class still compiles
     * against Base/FS, and so IrisUtils never has to be re-read from the
     * render path). Returns {@code null} when the fields aren't there.
     */
    private static MaterialPbrIntensity bbsFbx$formPbrIntensity(ModelForm modelForm)
    {
        try
        {
            Object normal = ModelForm.class.getField("pbrNormalIntensity").get(modelForm);
            Object specular = ModelForm.class.getField("pbrSpecularIntensity").get(modelForm);

            MaterialPbrIntensity intensity = new MaterialPbrIntensity();

            intensity.normal = ((Number) normal.getClass().getMethod("get").invoke(normal)).floatValue();
            intensity.specular = ((Number) specular.getClass().getMethod("get").invoke(specular)).floatValue();

            return intensity;
        }
        catch (ReflectiveOperationException ignored)
        {
            return null;
        }
    }

    @Inject(
            method = "renderModel(Lmchorse/bbs_mod/forms/entities/IEntity;Ljava/util/function/Supplier;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/cubic/ModelInstance;IILmchorse/bbs_mod/utils/colors/Color;ZLmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;FZ)V",
            at = @At("RETURN"), remap = false
    )
    private void bbsFbx$popMaterialOverrides(
            IEntity target, Supplier<ShaderProgram> program, MatrixStack stack, ModelInstance model,
            int light, int overlay, Color color, boolean ui, StencilMap stencilMap, float transition,
            boolean renderEquipment, CallbackInfo info)
    {
        CurrentMaterialTextureOverrides.pop();
        CurrentMaterialPbrOverrides.pop();
    }
}
