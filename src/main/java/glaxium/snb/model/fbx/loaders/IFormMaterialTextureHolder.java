package glaxium.snb.model.fbx.loaders;

import glaxium.snb.render.MaterialPbrIntensity;

import mchorse.bbs_mod.resources.Link;

import java.util.Map;

/**
 * Implemented by {@code ModelFormMixin} (mixed into {@code ModelForm}
 * itself). Exists as an interface -- not a plain method call to the mixin
 * class directly -- because only interface-based mixins leave a real
 * implemented-type relationship on the target class after Sponge Mixin
 * applies them; an abstract-class mixin like {@code ModelFormMixin} gets
 * merged into {@code ModelForm}'s bytecode and effectively stops existing
 * as its own loadable type, so casting a {@code ModelForm} instance
 * directly to {@code ModelFormMixin} from another mixin class would throw
 * {@code ClassCastException} at runtime despite compiling fine. Casting to
 * this interface instead (which {@code ModelFormMixin} declares
 * {@code implements}) is the correct, already-proven pattern -- same as
 * {@link IMaterialTextureHolder} on the {@code ModelInstance} side.
 *
 * <p>Used by {@code ModelFormRendererMixin} (to read the current Form's
 * overrides right before rendering) and {@code UIModelFormPanelMixin} (to
 * read/write the user's choice from the picker menu).</p>
 */
public interface IFormMaterialTextureHolder
{
    Map<String, Link> bbsFbx$getMaterialTextureOverrides();

    /** Assigns (or clears, with a null link) this Form's texture override for one material. */
    void bbsFbx$setMaterialTextureOverride(String material, Link link);

    /**
     * Assigns (or clears, with a null link) a runtime-only material texture
     * override, used by the film editor on Base/CML: those forks' film
     * system has no native per-material API, so the film's keyframes write
     * here instead of {@link #bbsFbx$setMaterialTextureOverride}. Unlike the
     * persisted variant this never touches the form's saved data, mirroring
     * how {@code setRuntimeValue} works for ordinary form properties and
     * how FS's film writes into {@code ModelForm.materialTextureOverrides}.
     */
    void bbsFbx$setRuntimeMaterialTextureOverride(String material, Link link);

    /**
     * Runtime-only per-material PBR intensity overrides (CML fork only).
     * Written by the film editor's per-material PBR sub-tracks
     * ({@code <material>:pbr_normal_intensity} / {@code :pbr_specular_intensity})
     * and read by the per-material draw loop. A null argument clears that
     * channel's override (the material falls back to the whole-model value);
     * passing null for both removes the material's entry entirely.
     */
    Map<String, MaterialPbrIntensity> bbsFbx$getMaterialPbrOverrides();

    void bbsFbx$setRuntimeMaterialPbr(String material, Float normal, Float specular);
}
