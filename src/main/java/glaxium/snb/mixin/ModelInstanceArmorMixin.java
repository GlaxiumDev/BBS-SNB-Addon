package glaxium.snb.mixin;

import glaxium.snb.render.CurrentEmoticonArmor;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.resources.Link;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies the equipped armor texture to BBS's existing per-BOBJ-mesh resolver. */
@Mixin(value = ModelInstance.class, remap = false)
public abstract class ModelInstanceArmorMixin
{
    @Inject(method = "getMaterialTexture", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$resolveArmorTexture(String material, Link fallback, CallbackInfoReturnable<Link> info)
    {
        Link armorTexture = CurrentEmoticonArmor.texture(material);

        if (armorTexture != null)
        {
            info.setReturnValue(armorTexture);
        }
    }
}
