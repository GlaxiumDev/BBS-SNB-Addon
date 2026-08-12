package glaxium.snb.mixin;

import mchorse.bbs_mod.cubic.CubicModelAnimator;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolation;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.BezierUtils;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import org.joml.Vector3d;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Removes the temporary segment object and linear {@code indexOf} scan from
 * every model animation channel sample. This benefits native cubic/OBJ,
 * BOBJ, FBX and GLB because they all pass through CubicModelAnimator.
 */
@Mixin(value = CubicModelAnimator.class, remap = false)
public class CubicModelAnimatorMixin
{
    @Inject(method = "interpolateList", at = @At("HEAD"), cancellable = true, remap = false)
    private static void bbsFbx$interpolateWithoutSegments(
            Vector3d output,
            KeyframeChannel<MolangExpression> x,
            KeyframeChannel<MolangExpression> y,
            KeyframeChannel<MolangExpression> z,
            float tick,
            double fallback,
            CallbackInfoReturnable<Vector3d> info)
    {
        output.x = bbsFbx$sample(x, tick, fallback);
        output.y = bbsFbx$sample(y, tick, fallback);
        output.z = bbsFbx$sample(z, tick, fallback);

        info.setReturnValue(output);
    }

    @Unique
    private static double bbsFbx$sample(
            KeyframeChannel<MolangExpression> channel,
            float tick,
            double fallback)
    {
        List<Keyframe<MolangExpression>> keys = channel.getAllTyped();
        int size = keys.size();

        if (size == 0)
        {
            return fallback;
        }

        int aIndex;
        int bIndex;
        Keyframe<MolangExpression> first = keys.get(0);

        if (size == 1 || tick < first.getTick())
        {
            aIndex = 0;
            bIndex = 0;
        }
        else
        {
            int lastIndex = size - 1;
            Keyframe<MolangExpression> last = keys.get(lastIndex);

            if (tick >= last.getTick())
            {
                aIndex = lastIndex;
                bIndex = lastIndex;
            }
            else
            {
                int low = 0;
                int high = lastIndex;

                /* Same lower-bound search as KeyframeChannel#findSegment:
                 * find the first key whose tick is >= the sample tick. */
                while (low <= high)
                {
                    int middle = low + (high - low) / 2;

                    if (keys.get(middle).getTick() < tick)
                    {
                        low = middle + 1;
                    }
                    else
                    {
                        high = middle - 1;
                    }
                }

                bIndex = low;

                /* Preserve BBS's integer-tick boundary rule exactly. */
                if ((double) keys.get(bIndex).getTick() == Math.floor((double) tick) && bIndex < lastIndex)
                {
                    bIndex++;
                }

                aIndex = Math.max(0, bIndex - 1);
            }
        }

        Keyframe<MolangExpression> a = keys.get(aIndex);
        Keyframe<MolangExpression> b = keys.get(bIndex);
        int preIndex = aIndex > 0 ? aIndex - 1 : aIndex;
        int postIndex = aIndex + 2 < size ? aIndex + 2 : bIndex;

        double aValue = a.getValue().get();
        double bValue = b.getValue().get();
        float duration = a.getDuration() > 0F ? a.getDuration() : b.getTick() - a.getTick();
        float factor = duration == 0F ? 0F : (tick - a.getTick()) / duration;

        if (factor < 0F)
        {
            factor = 0F;
        }
        else if (factor > 1F)
        {
            factor = 1F;
        }

        Interpolation interpolation = b.getInterpolation();

        if (interpolation.getInterp() == Interpolations.BEZIER)
        {
            return BezierUtils.get(
                    aValue, bValue,
                    a.getTick(), b.getTick(),
                    a.rx, a.ry, b.lx, b.ly,
                    factor);
        }

        double preA = keys.get(preIndex).getValue().get();
        double postB = keys.get(postIndex).getValue().get();

        return interpolation.interpolate(IInterp.context.set(preA, aValue, bValue, postB, factor));
    }
}
