package glaxium.snb.mixin.cml;

import glaxium.snb.render.PreviewFrameBudget;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class PreviewFrameMixinCML
{
    @Inject(method = "render", at = @At("HEAD"))
    private void bbsFbx$beginPreviewFrame(CallbackInfo ci)
    {
        PreviewFrameBudget.beginFrame();
    }
}
