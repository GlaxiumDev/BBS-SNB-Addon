package glaxium.snb.mixin;

import glaxium.snb.anim.HeadLookAt;

import mchorse.bbs_mod.cubic.IModelInstance;
import mchorse.bbs_mod.cubic.animation.Animator;
import mchorse.bbs_mod.forms.entities.IEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Head look-at for non-procedural models (clip {@link Animator}).
 *
 * <p>BBS only rotates a bone named {@code head} inside
 * {@code ProceduralAnimator}. This injects the same look after clip actions
 * so renaming a bone to {@code head} is enough — no {@code procedural: true}.</p>
 */
@Mixin(value = Animator.class, remap = false)
public abstract class AnimatorMixin
{
    @Inject(method = "applyActions", at = @At("RETURN"), remap = false)
    private void bbsFbx$applyHeadLookAt(IEntity entity, IModelInstance model, float transition, CallbackInfo info)
    {
        HeadLookAt.apply(entity, model, transition);
    }
}
