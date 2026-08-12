package glaxium.snb.mixin;

import glaxium.snb.animation.AnimationKeyframeBulkLoader;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJChannel;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.model.loaders.BOBJModelLoader;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optimizes native BOBJ animation conversion without changing its format. */
@Mixin(value = BOBJModelLoader.class, remap = false)
public class BOBJModelLoaderOptimizationMixin
{
    @Inject(method = "copyKeyframes", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$copyKeyframesInBulk(
            MolangParser parser,
            KeyframeChannel<MolangExpression> target,
            BOBJChannel source,
            CallbackInfo info)
    {
        AnimationKeyframeBulkLoader.copy(source, target, parser);
        info.cancel();
    }

    @Inject(method = "fillAnimation", at = @At("RETURN"), remap = false)
    private void bbsFbx$preserveFinalBobjFrame(Animation animation, BOBJAction action, CallbackInfo info)
    {
        /* Animation#getLengthInTicks floors seconds * 20. Float division can
         * land just below the final integer tick, making the loop jump a
         * frame early and visibly hitch. FBX/GLB already use this epsilon. */
        animation.setLength(action.getDuration() / 20.0 + 1e-3);
    }
}
