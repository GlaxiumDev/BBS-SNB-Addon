package elgatopro300.bbsfbx.render;

import elgatopro300.bbsfbx.model.fbx.FBXShapeKeyModelCML;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXCompiledData;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.resources.Link;

import java.util.Collections;
import java.util.List;

/**
 * The {@code IMaterialTextureHolder} logic -- material names, and the
 * shared per-material DEFAULT texture -- factored out so
 * {@code ModelInstanceMixinBase} and {@code ModelInstanceMixinFS} don't
 * each need their own copy. {@code mixin.cml.ModelInstanceMixinCML} keeps
 * its own separate, already-shipped copy of this same logic rather than
 * being refactored onto this helper, matching the same "don't touch what's
 * already working" call made for {@code BOBJModelVAOMixinCML}.
 *
 * <p>Originally also had {@code getMaterialTexture}/{@code setMaterialTexture}
 * methods here that read/wrote the CHOSEN (per-Form) texture onto the
 * shared {@code FBXCompiledData} backing the model's mesh. That's the part
 * that was genuinely broken and got removed: that data is cached globally
 * by BBS keyed by model file path, shared by every Form (and every morph)
 * using the same model, so writing a per-Form choice there leaked it onto
 * every other Form/morph too. That choice now lives on {@code ModelForm}
 * instead (see {@code ModelFormMixin}, {@code IFormMaterialTextureHolder}),
 * resolved fresh per render call via {@link CurrentMaterialTextureOverrides}
 * rather than stored on anything shared.</p>
 *
 * <p><b>{@link #getDefaultMaterialTexture} is a DIFFERENT thing, and was
 * cut from this class by mistake in that same pass</b> -- it's a pure
 * read, of the material's own resolved {@code textures/<material>/} folder
 * default computed once at load time by {@code FBXModelLoaderCML} and
 * stored on {@code FBXCompiledData.materialTextures}. That data is exactly
 * as safe to share as the material NAME list already was (every Form using
 * this model file sees the same resolved default, same as they'd see the
 * same whole-model default texture) - it just isn't where a per-Form
 * CHOICE should ever be written. Removing read access to it entirely was
 * an over-correction: with nowhere left to read it from, every
 * un-overridden material fell through straight to the whole-model default
 * texture (frequently null on a genuinely multi-material FBX, which has no
 * single texture that makes sense for the whole mesh) instead of its own
 * resolved one -- the flat, textureless/grey rendering this reintroduces
 * the fix for.</p>
 *
 * <p>Checks against {@link FBXShapeKeyModelCML} specifically, which -- despite
 * the name -- is confirmed shared between Base and CML (see that class's own
 * doc comment: {@code BOBJModel}'s constructor is identical on both). It is
 * NOT what BBS FS's own FBX models actually load as, since FS needs its own
 * separate {@code List<CompiledData>}-shaped model loader that doesn't exist
 * in this addon yet (tracked in {@code MIGRATION.md}). So calling this from
 * {@code ModelInstanceMixinFS} is technically wired up and harmless, but
 * won't actually show multi-material buttons on FS until that loader gap is
 * closed -- the model will just never be an {@code FBXShapeKeyModelCML}
 * there yet, so {@link #getMaterials} returns empty and the picker falls
 * back to the ordinary single-texture button, same as any single-material
 * model.</p>
 */
public final class MaterialTextureDelegate
{
    private MaterialTextureDelegate() {}

    private static FBXCompiledData materialData(IModel model)
    {
        if (model instanceof FBXShapeKeyModelCML fbxModel
                && fbxModel.getMeshData() instanceof FBXCompiledData data
                && data.hasMultipleMaterials())
        {
            return data;
        }

        return null;
    }

    public static List<String> getMaterials(IModel model)
    {
        FBXCompiledData data = materialData(model);

        return data == null ? Collections.emptyList() : List.of(data.materialNames);
    }

    /** The shared, resolved-at-load-time default texture for one material - see class doc. Null if nothing was resolved for it. */
    public static Link getDefaultMaterialTexture(IModel model, String material)
    {
        FBXCompiledData data = materialData(model);

        if (data == null)
        {
            return null;
        }

        int index = indexOf(data.materialNames, material);

        return index >= 0 && data.materialTextures != null && index < data.materialTextures.length
                ? data.materialTextures[index]
                : null;
    }

    private static int indexOf(String[] names, String name)
    {
        for (int i = 0; i < names.length; i++)
        {
            if (names[i].equals(name))
            {
                return i;
            }
        }

        return -1;
    }
}
