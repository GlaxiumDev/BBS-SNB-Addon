package elgatopro300.bbsfbx.mixin.basecml;

import elgatopro300.bbsfbx.compat.BBSFork;
import elgatopro300.bbsfbx.render.MaterialTextureDelegate;

import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Half of the Base/CML film-editor per-material-texture-keyframe feature:
 * append the FBX model's material names to
 * {@code FormUtils.collectPropertyPaths(Form)} so the film editor (and the
 * forms editor's animation-state editor, which collects tracks the same
 * way) offers one track per material for multi-material FBX models.
 *
 * <p>The other half lives in {@code FormPropertiesMixin} (create/apply/
 * reset those synthetic channels). FS is not touched: its film editor
 * already has a native per-material implementation, so registering this
 * here would only create duplicate tracks there -- hence this mixin lives
 * under {@code mixin.basecml.} and is gated to Base/CML by
 * {@code elgatopro300.bbsfbx.BBSFbxMixinPlugin}.</p>
 *
 * <p>This mixin also enforces the material-track layout the film editor
 * should show: on a multi-material model the whole-model {@code texture}
 * track is redundant (each material already has its own), so it's dropped
 * from the collected paths, and the material paths are inserted immediately
 * before {@code model} instead of being appended at the end. On CML, each
 * material path is followed by its two per-material PBR sub-tracks
 * ({@code <material>:pbr_normal_intensity} /
 * {@code <material>:pbr_specular_intensity}) -- Base has no PBR system, so
 * those are added only when the CML fork is detected.</p>
 */
@Mixin(value = FormUtils.class, remap = false)
public abstract class FormUtilsMixin
{
    /**
     * {@code collectPropertyPaths} builds a fresh {@code ArrayList} per call,
     * so editing the returned list in place is safe. A path that would
     * collide with a real form property is left alone so the real property
     * wins.
     */
    @Inject(
            method = "collectPropertyPaths(Lmchorse/bbs_mod/forms/forms/Form;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true, remap = false
    )
    private static void bbsFbx$reworkMaterialPaths(Form form, CallbackInfoReturnable<List<String>> info)
    {
        List<String> paths = info.getReturnValue();

        if (paths == null || form == null)
        {
            return;
        }

        boolean multi = MaterialTextureDelegate.hasMultipleMaterials(form);

        if (!multi)
        {
            return;
        }

        List<String> materials = new ArrayList<>();

        for (Iterator<String> it = paths.iterator(); it.hasNext();)
        {
            String path = it.next();

            if (multi && path.equals("texture"))
            {
                it.remove();
            }
            else if (FormUtils.getProperty(form, path) == null && MaterialTextureDelegate.isMaterial(form, path))
            {
                it.remove();
                materials.add(path);
            }
        }

        for (String material : MaterialTextureDelegate.getMaterials(form))
        {
            if (FormUtils.getProperty(form, material) == null && !materials.contains(material))
            {
                materials.add(material);
            }
        }

        if (BBSFork.fromLoadedMods() == BBSFork.CML)
        {
            List<String> withPbr = new ArrayList<>(materials.size() * 3);

            for (String material : materials)
            {
                withPbr.add(material);
                withPbr.add(material + ":pbr_normal_intensity");
                withPbr.add(material + ":pbr_specular_intensity");
            }

            materials = withPbr;
        }

        int modelIndex = -1;

        for (int i = 0; i < paths.size(); i++)
        {
            if (paths.get(i).equals("model"))
            {
                modelIndex = i;

                break;
            }
        }

        if (modelIndex == -1)
        {
            paths.addAll(materials);
        }
        else
        {
            paths.addAll(modelIndex, materials);
        }
    }
}
