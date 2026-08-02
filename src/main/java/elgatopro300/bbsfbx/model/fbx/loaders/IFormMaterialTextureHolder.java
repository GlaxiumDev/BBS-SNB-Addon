package elgatopro300.bbsfbx.model.fbx.loaders;

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
}
