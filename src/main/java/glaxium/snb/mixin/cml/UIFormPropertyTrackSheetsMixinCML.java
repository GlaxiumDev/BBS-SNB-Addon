package glaxium.snb.mixin.cml;

import glaxium.snb.render.MaterialTextureDelegate;

import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * CML 2.1.1 moved animation-state sheet construction into this helper.
 * Resolve synthetic material/PBR properties both when filtering paths and
 * when creating sheets, so channels retain their correct default values.
 */
@Mixin(targets = "mchorse.bbs_mod.ui.forms.editors.utils.UIFormPropertyTrackSheets", remap = false)
public abstract class UIFormPropertyTrackSheetsMixinCML
{
    @Redirect(
            method = {"createSheets", "isCompatiblePropertyPath"},
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/forms/FormUtils;getProperty(Lmchorse/bbs_mod/forms/forms/Form;Ljava/lang/String;)Lmchorse/bbs_mod/settings/values/base/BaseValueBasic;"),
            remap = false
    )
    private static BaseValueBasic bbsFbx$materialSheetProperty(Form form, String key)
    {
        BaseValueBasic property = FormUtils.getProperty(form, key);

        if (property != null)
        {
            return property;
        }

        if (MaterialTextureDelegate.isMaterial(form, key))
        {
            return MaterialTextureDelegate.materialSheetProperty(form, key);
        }

        return MaterialTextureDelegate.materialPbrSheetProperty(form, key);
    }
}
