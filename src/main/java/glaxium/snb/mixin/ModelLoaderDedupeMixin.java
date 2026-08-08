package glaxium.snb.mixin;

import glaxium.snb.model.fbx.loaders.ModelLoadInFlight;

import mchorse.bbs_mod.cubic.model.ModelLoader;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents duplicate {@link ModelLoader#add} entries for an id that is already
 * queued or loading. Keeps stock's single loader thread — only closes the
 * re-queue hole that turns a model replace / F6 into a reload storm.
 */
@Mixin(value = ModelLoader.class, remap = false)
public abstract class ModelLoaderDedupeMixin
{
    @Inject(method = "add", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$dedupeAdd(String key, CallbackInfo info)
    {
        if (!ModelLoadInFlight.tryBegin(key))
        {
            info.cancel();
        }
    }
}
