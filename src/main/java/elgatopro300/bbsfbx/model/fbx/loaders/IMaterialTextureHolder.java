package elgatopro300.bbsfbx.model.fbx.loaders;

import mchorse.bbs_mod.resources.Link;

import java.util.List;

/**
 * Implemented by {@code ModelInstanceMixinCML} (CML target only). Lets the
 * UI layer ({@code UIModelFormPanelMixinCML}) ask "does this model have more
 * than one material, and if so what are they and what's currently assigned
 * to each" without depending on {@link FBXCompiledData} directly - every
 * implementation is expected to delegate straight through to the
 * {@code FBXCompiledData} backing the model's mesh, so nothing here holds
 * its own separate copy of the data.
 */
public interface IMaterialTextureHolder
{
    /** Material names for this model, in a stable index order - empty if there's zero or one. */
    List<String> bbsFbx$getMaterials();

    /** Currently assigned texture for one material, or null if it's using the model's default texture. */
    Link bbsFbx$getMaterialTexture(String material);

    /** Assigns (or clears, with a null link) a texture override for one material, persisting the choice. */
    void bbsFbx$setMaterialTexture(String material, Link link);
}
