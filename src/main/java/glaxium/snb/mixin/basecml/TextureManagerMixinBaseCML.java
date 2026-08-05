package glaxium.snb.mixin.basecml;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.resources.Pixels;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives Base and CML the synthetic solid-color texture handling that FS's
 * {@code TextureManager.getPixels} has natively: a {@code Link("color",
 * <hex>)} (the exact kind the original BBS-FS-only addon built via
 * {@code LinkUtils.color}, and that {@code FBXTextureResolverCML#colorLink}
 * produces here) resolves to a single-pixel in-memory texture, byte-for-byte
 * the same algorithm FS uses - no PNG is ever written to disk.
 *
 * <p>On Base and CML the original {@code getPixels} knows no such source
 * (it goes straight to {@code provider.getAsset}, which can't serve a
 * non-file link), so without this mixin a solid-color material would fall
 * back to the error texture. FS does not need it and this mixin is gated to
 * Base/CML by {@code glaxium.snb.BBSFbxMixinPlugin}.</p>
 */
@Mixin(value = mchorse.bbs_mod.graphics.texture.TextureManager.class, remap = false)
public abstract class TextureManagerMixinBaseCML
{
    @Inject(
            method = "getPixels(Lmchorse/bbs_mod/resources/Link;)Lmchorse/bbs_mod/utils/resources/Pixels;",
            at = @At("HEAD"), cancellable = true, remap = false
    )
    private void bbsFbx$colorLinkPixels(Link link, CallbackInfoReturnable<Pixels> info)
    {
        if (link != null && "color".equals(link.source))
        {
            Pixels pixels = Pixels.fromSize(1, 1);

            pixels.setColor(0, 0, new Color().set((int) Long.parseLong(link.path, 16)));
            pixels.rewindBuffer();
            info.setReturnValue(pixels);
        }
    }
}
