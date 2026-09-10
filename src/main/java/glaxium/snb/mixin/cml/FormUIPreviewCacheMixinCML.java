package glaxium.snb.mixin.cml;

import glaxium.snb.render.PreviewFrameBudget;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.framework.UIContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps CML morph-menu thumbnails from hard-freezing the render thread.
 *
 * <p>CML's own fill budget is 96/28&nbsp;ms. Opening the morph menu with many
 * FBX/glTF cards was priority-queuing every model and baking several full
 * FBO previews per frame — long enough that the mouse and Alt+F4 appear
 * dead. This gates fills to one per frame, stops priority queuing from
 * {@code isPreviewReady}, and never live-draws uncached ModelForms.</p>
 */
@Mixin(targets = "mchorse.bbs_mod.forms.FormUIPreviewCache", remap = false)
public abstract class FormUIPreviewCacheMixinCML
{
    @Inject(method = "thisBeginFill", at = @At("HEAD"), cancellable = true, remap = false)
    private static void bbsFbx$limitPreviewFills(CallbackInfoReturnable<Boolean> ci)
    {
        ci.setReturnValue(PreviewFrameBudget.tryFill());
    }

    /**
     * Do not {@code addFirst} every visible morph onto the loader queue —
     * opening the menu was re-prioritizing dozens of FBX/glTF loads at once.
     */
    @ModifyArg(method = "isPreviewReady",
            at = @At(value = "INVOKE",
                    target = "Lmchorse/bbs_mod/cubic/model/ModelManager;getModel(Ljava/lang/String;Z)Lmchorse/bbs_mod/cubic/ModelInstance;"),
            index = 1,
            remap = false)
    private static boolean bbsFbx$noPriorityQueue(boolean request)
    {
        return false;
    }

    /**
     * Live fallback while a cache entry is missing: never draw ModelForms
     * inline (that bypasses the fill budget and freezes on heavy imports).
     */
    @Redirect(method = "render(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/ui/framework/UIContext;IIIIZ)V",
            at = @At(value = "INVOKE",
                    target = "Lmchorse/bbs_mod/forms/FormUtilsClient;renderUI(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/ui/framework/UIContext;IIIIZ)V"),
            remap = false)
    private static void bbsFbx$deferUncachedModels(Form form, UIContext context,
            int x1, int y1, int x2, int y2, boolean animate)
    {
        if (!(form instanceof ModelForm))
        {
            FormUtilsClient.renderUI(form, context, x1, y1, x2, y2, animate);
        }
    }
}
