package glaxium.snb.mixin;

import glaxium.snb.model.blockbuster.LegacyPoseRuntime;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.IModelInstance;
import mchorse.bbs_mod.cubic.animation.ProceduralAnimator;
import mchorse.bbs_mod.forms.entities.IEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ProceduralAnimator.class, remap = false)
public abstract class ProceduralAnimatorLegacyMixin
{
    @Shadow private IModelInstance model;

    @Redirect(
            method = "applyActions",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/cubic/IModel;resetPose()V"),
            remap = false
    )
    private void bbsFbx$applyLegacyStatePose(IModel model, IEntity entity, IModelInstance instance, float tickDelta)
    {
        model.resetPose();
        LegacyPoseRuntime.apply(entity, instance, model);
    }

    @Redirect(
            method = "applyActions",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/forms/entities/IEntity;getHandSwingProgress(F)F"),
            remap = false
    )
    private float bbsFbx$suppressBbsSwipeForLegacyModel(IEntity entity, float tickDelta)
    {
        if (this.model instanceof glaxium.snb.model.blockbuster.LegacyPoseHolder holder
                && holder.bbsFbx$isLegacyModel())
        {
            return 0F;
        }

        return entity.getHandSwingProgress(tickDelta);
    }

    @Inject(method = "applyActions", at = @At("RETURN"), remap = false)
    private void bbsFbx$applyLegacySwipe(IEntity entity, IModelInstance instance, float tickDelta, CallbackInfo ci)
    {
        LegacyPoseRuntime.applyIdle(entity, instance, instance.getModel(), tickDelta);
        LegacyPoseRuntime.applySwipe(entity, instance, instance.getModel(), tickDelta);
    }
}
