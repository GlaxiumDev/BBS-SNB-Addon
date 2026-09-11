package glaxium.snb.mixin.fs;

import glaxium.snb.render.MaterialTextureDelegate;

import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;

/**
 * BBS&nbsp;2.1 counterpart of {@link FormUtilsMixinFS}: hide the whole-model
 * {@code texture} track when the form already has per-material texture tracks.
 *
 * <p>FS21 has no {@code FormUtils.collectPropertyPaths}; tracks come from
 * {@code UIReplaysEditorUtils.collectFormTrackCandidates}, which always adds
 * {@code ModelForm.texture} and (when {@code materials.size() > 1}) also adds
 * per-material candidates. Drop only this form's {@code texture} path so
 * nested body-part forms are unaffected.</p>
 *
 * <p>{@code TrackCandidate} exists only on BBS&nbsp;2.1, so this mixin never
 * names that type -- the list is untyped and {@code id} is read reflectively
 * so the class still compiles against the CML jar.</p>
 */
@Mixin(value = UIReplaysEditorUtils.class, remap = false)
public abstract class UIReplaysEditorUtilsMixinFS21
{
    @Inject(
            method = "collectFormTrackCandidates(Lmchorse/bbs_mod/forms/forms/Form;Ljava/util/List;Z)V",
            at = @At("RETURN"),
            remap = false
    )
    private static void bbsFbx$hideTextureTrackOnMultiMaterial(
            Form form,
            List<?> candidates,
            boolean deep,
            CallbackInfo info)
    {
        if (candidates == null || !(form instanceof ModelForm modelForm)
                || !MaterialTextureDelegate.hasMultipleMaterials(form))
        {
            return;
        }

        String texturePath = FormUtils.getPropertyPath(modelForm.texture);

        if (texturePath == null || texturePath.isEmpty())
        {
            return;
        }

        Iterator<?> it = candidates.iterator();

        while (it.hasNext())
        {
            Object candidate = it.next();

            if (candidate == null)
            {
                continue;
            }

            try
            {
                Field idField = candidate.getClass().getField("id");
                Object id = idField.get(candidate);

                if (texturePath.equals(id))
                {
                    it.remove();
                }
            }
            catch (ReflectiveOperationException ignored)
            {
                // Unexpected candidate shape - leave it alone.
            }
        }
    }
}
