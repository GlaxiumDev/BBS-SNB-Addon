package glaxium.snb.mixin.fs;

import glaxium.snb.render.MaterialTextureDelegate;

import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * FS counterpart of {@code basecml.FormUtilsMixin}'s texture-track hiding.
 * Base/CML drop the whole-model {@code texture} track from
 * {@code FormUtils.collectPropertyPaths} for multi-material FBX models (each
 * material already has its own track, so the whole-model one is redundant)
 * while adding the per-material paths themselves. FS already exposes
 * per-material tracks natively (its {@code UIReplaysEditor} adds material
 * sheets via {@code UIReplaysEditorUtils.addMaterialTextureSheets}), so this
 * only drops the redundant {@code texture} track and never adds anything --
 * FS's native material sheets stay exactly as they are.
 */
@Mixin(value = FormUtils.class, remap = false)
public abstract class FormUtilsMixinFS
{
    @Inject(
            method = "collectPropertyPaths(Lmchorse/bbs_mod/forms/forms/Form;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true, remap = false
    )
    private static void bbsFbx$hideTextureTrackOnMultiMaterial(Form form, CallbackInfoReturnable<List<String>> info)
    {
        List<String> paths = info.getReturnValue();

        if (paths == null || form == null || !MaterialTextureDelegate.hasMultipleMaterials(form))
        {
            return;
        }

        paths.removeIf(path -> path != null && path.equals("texture"));
    }
}
