package elgatopro300.bbsfbx.model.fbx.loaders;

import mchorse.bbs_mod.resources.Link;

import java.util.List;

/**
 * Implemented by {@code ModelInstanceMixinBase}, {@code ModelInstanceMixinFS}
 * and {@code ModelInstanceMixinCML} (one per fork, same as the other
 * {@code ModelInstanceMixin*} variants). Lets the UI layer
 * ({@code UIModelFormPanelMixin}) ask "does this model have more than one
 * material, and if so what are they" without depending on
 * {@link FBXCompiledData} directly.
 *
 * <p>Only reports material NAMES now - genuinely model-structural (every
 * Form using a given model file has the same material slots), unlike which
 * TEXTURE is assigned to each, which is per-Form and lives on
 * {@link IFormMaterialTextureHolder} instead. This interface originally also
 * had {@code bbsFbx$getMaterialTexture}/{@code bbsFbx$setMaterialTexture}
 * methods that read/wrote the texture choice onto the shared
 * {@code FBXCompiledData} backing the model's mesh - which is exactly what
 * caused choosing a texture for one Form's material to leak onto every
 * other Form (and every morph) using the same model file, since that data
 * is cached globally by file path, not per-Form. Removed rather than left
 * in place unused, to avoid a future reader assuming they still do
 * something.</p>
 */
public interface IMaterialTextureHolder
{
    /** Material names for this model, in a stable index order - empty if there's zero or one. */
    List<String> bbsFbx$getMaterials();

    /**
     * The SHARED default texture for one material (e.g. resolved from its
     * own {@code textures/<material>/} folder at load time) - same for
     * every Form using this model file, unlike the per-Form override on
     * {@link IFormMaterialTextureHolder}. Fine to share (it's read-only,
     * structural to the model file itself, exactly like the material name
     * list above) - reintroduced after the first cut of the leak fix
     * accidentally removed the only way to read this at all, which is what
     * caused every un-overridden material to fall through straight to the
     * whole-model default texture (often null on a multi-material model)
     * instead of its own resolved one.
     */
    Link bbsFbx$getDefaultMaterialTexture(String material);
}
