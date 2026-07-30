package elgatopro300.bbsfbx.model.fbx.loaders;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJChannel;
import mchorse.bbs_mod.bobj.BOBJGroup;
import mchorse.bbs_mod.bobj.BOBJKeyframe;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.Map;

/**
 * Converts the BOBJAction/BOBJGroup/BOBJChannel data FBXConverter produces
 * into BBS FS's own Animations/Animation/AnimationPart/KeyframeChannel
 * representation.
 */
public final class FBXAnimationConverter
{
    private FBXAnimationConverter() {}

    public static Animations convert(Map<String, BOBJAction> actions, MolangParser parser)
    {
        Animations animations = new Animations(parser);

        for (BOBJAction action : actions.values())
        {
            Animation animation = new Animation(action.name, parser);
            /* +epsilon so BBS's floor(getLength()*20) loop length never rounds
             * down and drops the final baked frame. */
            animation.setLength(action.getDuration() / 20.0 + 1e-3);

            for (BOBJGroup group : action.groups.values())
            {
                AnimationPart part = new AnimationPart(parser);

                for (BOBJChannel channel : group.channels)
                {
                    KeyframeChannel<MolangExpression> targetChannel = switch (channel.path) {
                        case "location.x" -> part.x;
                        case "location.y" -> part.y;
                        case "location.z" -> part.z;
                        case "rotation.x" -> part.rx;
                        case "rotation.y" -> part.ry;
                        case "rotation.z" -> part.rz;
                        case "scale.x" -> part.sx;
                        case "scale.y" -> part.sy;
                        case "scale.z" -> part.sz;
                        default -> null;
                    };

                    if (targetChannel != null)
                    {
                        for (BOBJKeyframe kf : channel.keyframes)
                        {
                            targetChannel.insert(kf.frame, new MolangValue(parser, new Constant(kf.value)));
                        }
                    }
                }

                animation.parts.put(group.name, part);
            }

            animations.add(animation);
        }

        return animations;
    }
}