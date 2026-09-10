package glaxium.snb.mixin.cml;

import glaxium.snb.render.PreviewFrameBudget;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.framework.UIContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents uncached model thumbnails from defeating the cache's frame budget. */
@Mixin(targets = "mchorse.bbs_mod.forms.FormUIPreviewCache", remap = false)
public abstract class FormUIPreviewCacheMixinCML
{
    @Inject(method = "thisBeginFill", at = @At("HEAD"), cancellable = true, remap = false)
    private static void bbsFbx$limitPreviewFills(CallbackInfoReturnable<Boolean> ci)
    {
        ci.setReturnValue(PreviewFrameBudget.tryFill());
    }

    @Redirect(method = "render(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/ui/framework/UIContext;IIIIZ)V",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/forms/FormUtilsClient;renderUI(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/ui/framework/UIContext;IIIIZ)V"),
            remap = false)
    private static void bbsFbx$deferUncachedModels(Form form, UIContext context,
            int x1, int y1, int x2, int y2, boolean animate)
    {
        /* isPreviewReady has already requested pending models. A new model
         * image can wait for the next frame; rendering it live here bypasses
         * the allowance and makes fast scrolling upload a whole row at once. */
        if (!(form instanceof ModelForm))
        {
            FormUtilsClient.renderUI(form, context, x1, y1, x2, y2, animate);
        }
    }
}
