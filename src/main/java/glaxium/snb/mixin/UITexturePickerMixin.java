package glaxium.snb.mixin;

import glaxium.snb.model.bobj.EmoticonArmorSidecar;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.input.UITexturePicker;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIFileLinkList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/**
 * Keeps the emoticon armor sidecar textures out of the texture picker.
 * The per-material picker menus and film tracks already exclude the armor
 * materials (see {@code MaterialTextureDelegate}), but the picker itself
 * lists every file/folder under {@code models/} -- including the sidecar's
 * {@code textures/armor_helmet/}, {@code textures/armor_chest/}, etc. --
 * which made unequipped armor textures directly choosable. Wraps the
 * picker's native filter (public {@code UIFileLinkList.filter}) so its
 * folder/image logic is preserved exactly, on every fork.
 */
@Mixin(value = UITexturePicker.class, remap = false)
public abstract class UITexturePickerMixin
{
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void bbsFbx$hideArmorTextures(CallbackInfo info)
    {
        UIFileLinkList list = ((UITexturePicker) (Object) this).picker;

        if (list == null)
        {
            return;
        }

        Predicate<Link> nativeFilter = list.filter;

        list.filter((link) ->
        {
            if (nativeFilter != null && !nativeFilter.test(link))
            {
                return false;
            }

            return !bbsFbx$isArmorTextureLink(link);
        });
    }

    /** True when the link lives under a {@code textures/<armor-part>/} folder. */
    @Unique
    private static boolean bbsFbx$isArmorTextureLink(Link link)
    {
        String path = link == null ? null : link.path;

        if (path == null)
        {
            return false;
        }

        int marker = path.indexOf("/textures/");

        if (marker < 0)
        {
            return false;
        }

        String after = path.substring(marker + "/textures/".length());
        int slash = after.indexOf('/');
        String folder = slash >= 0 ? after.substring(0, slash) : after;

        return EmoticonArmorSidecar.isArmorMesh(folder);
    }
}
