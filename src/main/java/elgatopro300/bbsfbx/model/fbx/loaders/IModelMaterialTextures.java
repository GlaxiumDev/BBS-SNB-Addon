package elgatopro300.bbsfbx.model.fbx.loaders;

import mchorse.bbs_mod.resources.Link;

import java.util.List;
import java.util.Map;

/**
 * Per-material texture data on a cubic {@code Model}. Added to Base/CML's
 * cubic {@code Model} via {@code ModelMixin} ({@code mixin.basecml}, gated
 * off FS which has native per-material support in its own loader).
 *
 * <p>This is the cubic-path analogue of the {@code FBXCompiledData}
 * material data ({@code materialNames}/{@code materialTextures}) the FBX
 * path stores on a {@code BOBJModel}: material names and their shared
 * per-material DEFAULT textures, resolved once at load time. Like that data
 * it is structural to the model FILE (every Form using the model sees the
 * same defaults), so it's safe to live on the globally-cached
 * {@code Model} -- the per-Form CHOICE still lives on the form
 * ({@link IFormMaterialTextureHolder}), resolved fresh at render time via
 * {@link elgatopro300.bbsfbx.render.CurrentMaterialTextureOverrides}.
 *
 * <p>{@link MaterialTextureDelegate} reads this so the whole addon layer --
 * picker UI, film-editor material sheets, form properties -- works unchanged
 * for OBJ models now that they stay native cubic models instead of being
 * converted into a bone-equipped {@code BOBJModel}.</p>
 */
public interface IModelMaterialTextures
{
    /** Replace the whole per-material texture set (names + defaults). */
    void bbsFbx$setMaterialTextures(List<String> materials, Map<String, Link> materialTextures);

    /** Material names, in a stable index order - empty when the model has no per-material data. */
    List<String> bbsFbx$getMaterials();

    /** The shared default texture for one material, or null. */
    Link bbsFbx$getDefaultMaterialTexture(String material);
}
