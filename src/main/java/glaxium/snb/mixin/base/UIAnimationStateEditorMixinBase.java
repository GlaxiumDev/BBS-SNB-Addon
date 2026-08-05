package glaxium.snb.mixin.base;

import glaxium.snb.render.MaterialTextureDelegate;

import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.ui.forms.editors.states.keyframes.UIAnimationStateEditor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Base-only counterpart of the CML fix: give every material sheet a
 * synthetic {@code BaseValueBasic} (see
 * {@link MaterialTextureDelegate#materialSheetProperty}) so new keyframes
 * default to the material's texture instead of the link factory's
 * {@code error.png} fallback. {@code UIAnimationStateEditor.setState} reads
 * the sheet property via {@code FormUtils.getProperty(form, key)}, which is
 * null for a material key -- this swaps in the synthetic one.
 */
@Mixin(value = UIAnimationStateEditor.class, remap = false)
public abstract class UIAnimationStateEditorMixinBase
{
    @Redirect(
            method = "setState(Lmchorse/bbs_mod/forms/states/AnimationState;)V",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/forms/FormUtils;getProperty(Lmchorse/bbs_mod/forms/forms/Form;Ljava/lang/String;)Lmchorse/bbs_mod/settings/values/base/BaseValueBasic;"),
            remap = false
    )
    private static BaseValueBasic bbsFbx$materialSheetProperty(Form form, String key)
    {
        BaseValueBasic property = FormUtils.getProperty(form, key);

        if (property == null && MaterialTextureDelegate.isMaterial(form, key))
        {
            return MaterialTextureDelegate.materialSheetProperty(form, key);
        }

        return property;
    }
}
