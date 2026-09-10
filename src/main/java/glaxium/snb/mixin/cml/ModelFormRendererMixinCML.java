package glaxium.snb.mixin.cml;

import glaxium.snb.model.fbx.loaders.IFormMaterialTextureHolder;
import glaxium.snb.mixin.FormRendererAccessor;
import glaxium.snb.render.CurrentMaterialPbrOverrides;
import glaxium.snb.render.CurrentMaterialTextureOverrides;
import glaxium.snb.render.CurrentEmoticonArmor;
import glaxium.snb.render.CurrentModelTexture;
import glaxium.snb.compat.ModelInstanceCompat;
import glaxium.snb.render.MaterialPbrIntensity;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * CML-fork variant of the material-texture override push/pop, identical to
 * {@code ModelFormRendererMixinBase} except for {@code renderModel}'s extra
 * {@code boolean renderEquipment} parameter and, since CML 2.1.1, trailing
 * equipment matrices and rendering context. Argument capture reads only the
 * fields needed here, accepting both signatures without CML-only context types.
 *
 * <p>Gated to the CML fork by {@code glaxium.snb.BBSFbxMixinPlugin}.
 * Keeping the base and CML variants in separate fork-gated mixins (the same
 * pattern the {@code ModelInstanceMixin} trio uses) is what lets both forks
 * run from the same build without Mixin's permissive selector tripping over
 * the fork-divergent {@code renderModel} signature.</p>
 */
@Mixin(value = ModelFormRenderer.class, remap = false)
public abstract class ModelFormRendererMixinCML
{
    @Inject(method = "renderModel", at = @At("HEAD"), remap = false)
    private void bbsFbx$pushMaterialOverrides(CallbackInfo info,
            @Local(argsOnly = true) IEntity target,
            @Local(argsOnly = true) ModelInstance model,
            @Local(argsOnly = true, ordinal = 0) boolean ui,
            @Local(argsOnly = true, ordinal = 1) boolean renderEquipment)
    {
        CurrentEmoticonArmor.push(target, model, renderEquipment && !ui);
        Form form = ((FormRendererAccessor) (Object) this).bbsFbx$getForm();

        if (form instanceof ModelForm modelForm)
        {
            IFormMaterialTextureHolder holder = (IFormMaterialTextureHolder) modelForm;

            CurrentMaterialTextureOverrides.push(holder.bbsFbx$getMaterialTextureOverrides());
            CurrentMaterialPbrOverrides.push(holder.bbsFbx$getMaterialPbrOverrides(), bbsFbx$formPbrIntensity(modelForm));
            CurrentModelTexture.push(modelForm.texture.get() == null ? ModelInstanceCompat.getTexture(model) : modelForm.texture.get());
        }
        else
        {
            CurrentMaterialTextureOverrides.push(null);
            CurrentMaterialPbrOverrides.push(null, null);
            CurrentModelTexture.push(ModelInstanceCompat.getTexture(model));
        }
    }

    @Unique
    private static Field bbsFbx$pbrNormalField;
    @Unique
    private static Field bbsFbx$pbrSpecularField;
    @Unique
    private static Method bbsFbx$valueFloatGet;
    @Unique
    private static boolean bbsFbx$pbrReflectionFailed;

    /**
     * Reads the whole-model PBR intensity off the {@code ModelForm}'s
     * CML-only {@code pbrNormalIntensity}/{@code pbrSpecularIntensity}
     * {@code ValueFloat}s (via reflection so this class still compiles
     * against Base/FS, and so IrisUtils never has to be re-read from the
     * render path). Returns {@code null} when the fields aren't there.
     *
     * <p>The {@code Field}/{@code Method} objects are resolved once and
     * cached in static fields, not re-looked-up on every call - this runs
     * once per rendered CML model per frame, and {@code Class.getField}/
     * {@code getMethod} both walk the class's reflection data on every call
     * if not cached, which is real, easily-avoidable overhead on a hot path
     * that isn't free (unlike, say, {@code FormPropertiesMixin}'s
     * equivalent {@code bbsFbx$addMethod} caching, which this mirrors).
     * {@code bbsFbx$pbrReflectionFailed} latches to true after the first
     * failed attempt so a permanently-absent API doesn't retry the (failed)
     * lookup every frame forever either.</p>
     */
    private static MaterialPbrIntensity bbsFbx$formPbrIntensity(ModelForm modelForm)
    {
        if (bbsFbx$pbrReflectionFailed)
        {
            return null;
        }

        try
        {
            if (bbsFbx$pbrNormalField == null)
            {
                bbsFbx$pbrNormalField = ModelForm.class.getField("pbrNormalIntensity");
                bbsFbx$pbrSpecularField = ModelForm.class.getField("pbrSpecularIntensity");
                bbsFbx$valueFloatGet = bbsFbx$pbrNormalField.getType().getMethod("get");
            }

            Object normal = bbsFbx$pbrNormalField.get(modelForm);
            Object specular = bbsFbx$pbrSpecularField.get(modelForm);

            MaterialPbrIntensity intensity = new MaterialPbrIntensity();

            intensity.normal = ((Number) bbsFbx$valueFloatGet.invoke(normal)).floatValue();
            intensity.specular = ((Number) bbsFbx$valueFloatGet.invoke(specular)).floatValue();

            return intensity;
        }
        catch (ReflectiveOperationException error)
        {
            bbsFbx$pbrReflectionFailed = true;

            return null;
        }
    }

    @Inject(method = "renderModel", at = @At("RETURN"), remap = false)
    private void bbsFbx$popMaterialOverrides(CallbackInfo info)
    {
        CurrentMaterialTextureOverrides.pop();
        CurrentMaterialPbrOverrides.pop();
        CurrentModelTexture.pop();
        CurrentEmoticonArmor.pop();
    }
}
