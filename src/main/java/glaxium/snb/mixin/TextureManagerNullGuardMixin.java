package glaxium.snb.mixin;

import mchorse.bbs_mod.graphics.texture.TextureManager;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.resources.Pixels;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TextureManager.class, remap = false)
public abstract class TextureManagerNullGuardMixin
{
    @Inject(method = "bindTexture(Lmchorse/bbs_mod/resources/Link;I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$guardNullBindTexture(Link link, int i, CallbackInfo info)
    {
        if (link == null)
        {
            info.cancel();
        }
    }

    @Inject(method = "getPixels(Lmchorse/bbs_mod/resources/Link;)Lmchorse/bbs_mod/utils/resources/Pixels;", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$guardNullGetPixels(Link link, CallbackInfoReturnable<Pixels> info)
    {
        if (link == null)
        {
            info.setReturnValue(Pixels.fromSize(1, 1));
        }
    }
}
