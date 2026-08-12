package glaxium.snb.animation;

import mchorse.bbs_mod.bobj.BOBJChannel;
import mchorse.bbs_mod.bobj.BOBJKeyframe;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.List;

/**
 * Copies imported BOBJ animation keys without calling
 * {@link KeyframeChannel#insert(float, Object)} once per key.
 *
 * <p>The stock loader's repeated insert/sort/notification sequence becomes
 * quadratic on dense baked animation. Some Blender exports contain hundreds
 * of thousands of BOBJ keys, so channel conversion alone can stall model
 * loading for seconds and create a very large amount of temporary garbage.
 * This class builds the already-sorted channel directly and synchronizes it
 * once while preserving the stock loader's duplicate-frame semantics.</p>
 */
public final class AnimationKeyframeBulkLoader
{
    private AnimationKeyframeBulkLoader()
    {}

    public static void copy(BOBJChannel source, KeyframeChannel<MolangExpression> target, MolangParser parser)
    {
        copy(source, target, parser, true);
    }

    /** Bulk equivalent of the addon's historical value-only FBX conversion. */
    public static void copyValues(BOBJChannel source, KeyframeChannel<MolangExpression> target, MolangParser parser)
    {
        copy(source, target, parser, false);
    }

    private static void copy(
            BOBJChannel source,
            KeyframeChannel<MolangExpression> target,
            MolangParser parser,
            boolean preserveBobjCurves)
    {
        List<BOBJKeyframe> keys = source.keyframes;

        if (keys == null || keys.isEmpty())
        {
            return;
        }

        if (isSorted(keys))
        {
            copySorted(keys, target, parser, preserveBobjCurves);
        }
        else
        {
            /* BOBJ exporters normally emit channels in chronological order.
             * Hand-written legacy files need the host's unusual insert/sort
             * behavior exactly, so retain that slower path only for them. */
            copyUnsortedCompat(keys, target, parser, preserveBobjCurves);
        }

        target.sort();
    }

    private static boolean isSorted(List<BOBJKeyframe> keys)
    {
        float previous = keys.get(0).frame;

        for (int i = 1; i < keys.size(); i++)
        {
            float frame = keys.get(i).frame;

            if (frame < previous)
            {
                return false;
            }

            previous = frame;
        }

        return true;
    }

    private static void copySorted(
            List<BOBJKeyframe> keys,
            KeyframeChannel<MolangExpression> target,
            MolangParser parser,
            boolean preserveBobjCurves)
    {
        int i = 0;

        while (i < keys.size())
        {
            int last = i;
            float frame = keys.get(i).frame;

            while (last + 1 < keys.size() && keys.get(last + 1).frame == frame)
            {
                last++;
            }

            BOBJKeyframe current = keys.get(last);
            BOBJKeyframe interpolation = last == 0 ? current : keys.get(last - 1);

            add(target, parser, current, interpolation, preserveBobjCurves);
            i = last + 1;
        }
    }

    private static void copyUnsortedCompat(
            List<BOBJKeyframe> keys,
            KeyframeChannel<MolangExpression> target,
            MolangParser parser,
            boolean preserveBobjCurves)
    {
        for (int i = 0; i < keys.size(); i++)
        {
            BOBJKeyframe source = keys.get(i);
            BOBJKeyframe interpolation = i == 0 ? source : keys.get(i - 1);
            MolangExpression value = new MolangValue(parser, new Constant(source.value));
            int index = target.insert(source.frame, value);

            if (preserveBobjCurves)
            {
                applyBobjCurve(target.get(index), source, interpolation);
            }
        }
    }

    private static void add(
            KeyframeChannel<MolangExpression> target,
            MolangParser parser,
            BOBJKeyframe source,
            BOBJKeyframe interpolation,
            boolean preserveBobjCurves)
    {
        MolangExpression value = new MolangValue(parser, new Constant(source.value));
        Keyframe<MolangExpression> keyframe = new Keyframe<>("", target.getFactory(), source.frame, value);

        if (preserveBobjCurves)
        {
            applyBobjCurve(keyframe, source, interpolation);
        }

        target.add(keyframe);
    }

    private static void applyBobjCurve(
            Keyframe<MolangExpression> keyframe,
            BOBJKeyframe source,
            BOBJKeyframe interpolation)
    {
        keyframe.getInterpolation().setInterp(interpolation.interpolation.interp);
        keyframe.lx = source.frame - source.leftX;
        keyframe.ly = source.leftY - source.value;
        keyframe.rx = source.rightX - source.frame;
        keyframe.ry = source.rightY - source.value;
    }
}
