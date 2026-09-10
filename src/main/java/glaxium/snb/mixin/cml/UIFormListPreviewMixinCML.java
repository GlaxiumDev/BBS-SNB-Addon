package glaxium.snb.mixin.cml;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import glaxium.snb.render.MaterialTextureDelegate;
import mchorse.bbs_mod.forms.forms.Form;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Uses CML's static thumbnail cache for imported multi-material models. */
@Mixin(targets = "mchorse.bbs_mod.ui.forms.UIFormList", remap = false)
public abstract class UIFormListPreviewMixinCML
{
    @ModifyExpressionValue(method = "renderFormThumbnail(Lmchorse/bbs_mod/ui/framework/UIContext;Lmchorse/bbs_mod/forms/forms/Form;IIIIZ)V",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/settings/values/numeric/ValueBoolean;get()Ljava/lang/Boolean;"),
            remap = false)
    private Boolean bbsFbx$cacheMaterialThumbnails(Boolean enabled, @Local(argsOnly = true) Form form)
    {
        /* The host still renders selected/hovered cards live. Do not change
         * the saved setting or the behavior of unrelated form types. */
        return enabled || MaterialTextureDelegate.hasMultipleMaterials(form);
    }
}
