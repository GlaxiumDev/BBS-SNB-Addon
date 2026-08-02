package elgatopro300.bbsfbx.render;

import elgatopro300.bbsfbx.model.fbx.loaders.FBXCompiledData;
import elgatopro300.bbsfbx.model.fbx.loaders.IFbxModel;

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
 * default computed once at load time by {@code FBXModelLoader} and
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
 * <p>Checks against {@link IFbxModel} specifically -- the mixin interface
 * {@code BOBJModelMixin} adds to {@code BOBJModel} on every fork, so this
 * works identically for Base, FS and CML now that the FBX model is a plain
 * {@code BOBJModel} (the old per-fork {@code FBXShapeKeyModel} subclasses
 * are gone -- see {@code BOBJModelMixin}'s doc comment).</p>
 */
public final class MaterialTextureDelegate
{
    private MaterialTextureDelegate() {}

    private static FBXCompiledData materialData(IModel model)
    {
        if (model instanceof IFbxModel fbxModel)
        {
            FBXCompiledData data = fbxModel.bbsFbx$getFbxData();

            if (data != null && data.hasMultipleMaterials())
            {
                return data;
            }
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
