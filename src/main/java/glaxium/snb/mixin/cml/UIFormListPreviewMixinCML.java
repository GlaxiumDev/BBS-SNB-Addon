package glaxium.snb.mixin.cml;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.framework.UIContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Forces CML's cached thumbnail path for every {@link ModelForm} card.
 *
 * <p>Selected/hovered cards normally live-render every frame; with heavy
 * FBX/glTF that alone freezes the client. Route them through the static
 * cache (still filled at most once per frame by {@code PreviewFrameBudget}).</p>
 */
@Mixin(targets = "mchorse.bbs_mod.ui.forms.UIFormList", remap = false)
public abstract class UIFormListPreviewMixinCML
{
    @ModifyExpressionValue(method = "renderFormThumbnail(Lmchorse/bbs_mod/ui/framework/UIContext;Lmchorse/bbs_mod/forms/forms/Form;IIIIZ)V",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/settings/values/numeric/ValueBoolean;get()Ljava/lang/Boolean;"),
            remap = false)
    private Boolean bbsFbx$forceCachedModelThumbnails(Boolean enabled, @Local(argsOnly = true) Form form)
    {
        return enabled || form instanceof ModelForm;
    }

    @ModifyExpressionValue(method = "renderFormThumbnailStatic",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/settings/values/numeric/ValueBoolean;get()Ljava/lang/Boolean;"),
            remap = false)
    private Boolean bbsFbx$forceCachedModelThumbnailsStatic(Boolean enabled, @Local(argsOnly = true) Form form)
    {
        return enabled || form instanceof ModelForm;
    }

    @Redirect(method = "renderFormThumbnail(Lmchorse/bbs_mod/ui/framework/UIContext;Lmchorse/bbs_mod/forms/forms/Form;IIIIZ)V",
            at = @At(value = "INVOKE",
                    target = "Lmchorse/bbs_mod/forms/FormUtilsClient;renderUI(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/ui/framework/UIContext;IIIIZ)V"),
            remap = false)
    private void bbsFbx$noLiveModelCards(Form form, UIContext context,
            int x1, int y1, int x2, int y2, boolean animate)
    {
        if (form instanceof ModelForm)
        {
            FormUtilsClient.renderUICachedStatic(form, context, x1, y1, x2, y2);
            return;
        }

        FormUtilsClient.renderUI(form, context, x1, y1, x2, y2, animate);
    }
}
