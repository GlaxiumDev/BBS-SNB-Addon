package glaxium.snb.model.bobj;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJChannel;
import mchorse.bbs_mod.bobj.BOBJGroup;
import mchorse.bbs_mod.bobj.BOBJKeyframe;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import org.joml.Vector3f;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stock Emoticons action loader for non-native BOBJ model loaders. */
public final class EmoticonDefaultAnimations
{
    private EmoticonDefaultAnimations() {}

    public static Animations load(AssetProvider provider, MolangParser parser)
    {
        return load(provider, parser, null);
    }

    public static Animations load(AssetProvider provider, MolangParser parser, BOBJArmature targetArmature)
    {
        Animations animations = new Animations(parser);
        List<Link> actions = new ArrayList<>();
        BOBJArmature sourceArmature = targetArmature == null ? null : loadSourceArmature(provider);

        actions.add(Link.assets("actions.bobj"));

        for (Link link : provider.getLinksFromPath(Link.assets("emotes")))
        {
            if (link.path.endsWith(".bobj"))
            {
                actions.add(link);
            }
        }

        for (Link link : actions)
        {
            try (InputStream stream = provider.getAsset(link))
            {
                if (stream != null)
                {
                    BOBJLoader.BOBJData data = BOBJLoader.readData(stream);

                    retarget(data, sourceArmature, targetArmature);
                    convert(data, animations);
                }
            }
            catch (Exception e)
            {
                System.err.println("Failed to load Emoticons " + link + "!");
                e.printStackTrace();
            }
        }

        return animations;
    }

    private static BOBJArmature loadSourceArmature(AssetProvider provider)
    {
        Link[] candidates = {
                Link.assets("models/emoticons/steve/default.bobj"),
                Link.assets("models/emoticons/alex/slim.bobj")
        };

        for (Link link : candidates)
        {
            try (InputStream stream = provider.getAsset(link))
            {
                if (stream == null)
                {
                    continue;
                }

                BOBJLoader.BOBJData data = BOBJLoader.readData(stream);

                if (!data.armatures.isEmpty())
                {
                    BOBJArmature armature = data.armatures.values().iterator().next();

                    armature.initArmature();

                    return armature;
                }
            }
            catch (Exception e)
            {}
        }

        return null;
    }

    public static Animations convert(BOBJLoader.BOBJData bobjData, Animations animations)
    {
        for (Map.Entry<String, BOBJAction> entry : bobjData.actions.entrySet())
        {
            Animation animation = new Animation(entry.getKey(), animations.parser);

            fillAnimation(animation, entry.getValue());
            animations.add(animation);
        }

        return animations;
    }

    private static void retarget(BOBJLoader.BOBJData data, BOBJArmature sourceArmature, BOBJArmature targetArmature)
    {
        if (data == null || sourceArmature == null || targetArmature == null)
        {
            return;
        }

        for (BOBJAction action : data.actions.values())
        {
            for (BOBJGroup group : action.groups.values())
            {
                BOBJBone source = sourceArmature.bones.get(group.name);
                BOBJBone target = targetArmature.bones.get(group.name);

                if (source == null || target == null)
                {
                    continue;
                }

                AxisMap map = AxisMap.between(source, target);

                if (map.identity)
                {
                    continue;
                }

                List<BOBJChannel> remapped = new ArrayList<>();

                for (BOBJChannel channel : group.channels)
                {
                    ChannelInfo info = ChannelInfo.parse(channel);

                    if (info == null)
                    {
                        remapped.add(channel);
                        continue;
                    }

                    int targetAxis = map.axis[info.axis];
                    float sign = info.kind == ChannelKind.SCALE ? 1F : map.sign[info.axis];

                    remapped.add(copyChannel(channel, info.kind.path, targetAxis, sign));
                }

                group.channels.clear();
                group.channels.addAll(remapped);
            }
        }
    }

    private static BOBJChannel copyChannel(BOBJChannel source, String path, int index, float sign)
    {
        BOBJChannel channel = new BOBJChannel(path, index);

        for (BOBJKeyframe keyframe : source.keyframes)
        {
            BOBJKeyframe copy = new BOBJKeyframe(keyframe.frame, keyframe.value * sign);

            copy.interpolation = keyframe.interpolation;
            copy.leftX = keyframe.leftX;
            copy.leftY = keyframe.leftY * sign;
            copy.rightX = keyframe.rightX;
            copy.rightY = keyframe.rightY * sign;
            channel.keyframes.add(copy);
        }

        return channel;
    }

    private static void fillAnimation(Animation animation, BOBJAction value)
    {
        MolangParser parser = animation.parser;

        for (Map.Entry<String, BOBJGroup> entry : value.groups.entrySet())
        {
            AnimationPart part = new AnimationPart(parser);

            for (BOBJChannel channel : entry.getValue().channels)
            {
                KeyframeChannel<MolangExpression> target = getTargetChannel(part, channel);

                if (target != null)
                {
                    copyKeyframes(parser, target, channel);
                }
            }

            animation.parts.put(entry.getKey(), part);
        }

        AnimationPart head = animation.parts.get("head");

        if (head == null)
        {
            head = new AnimationPart(parser);

            animation.parts.put("head", head);
            fillHeadVariables(parser, head);
        }
        else if (head.rx.isEmpty())
        {
            fillHeadVariables(parser, head);
        }

        animation.setLength(value.getDuration() / 20F);
    }

    private static KeyframeChannel<MolangExpression> getTargetChannel(AnimationPart part, BOBJChannel channel)
    {
        return switch (channel.path)
        {
            case "location.x" -> part.x;
            case "location.y" -> part.y;
            case "location.z" -> part.z;
            case "rotation.x" -> part.rx;
            case "rotation.y" -> part.ry;
            case "rotation.z" -> part.rz;
            case "scale.x" -> part.sx;
            case "scale.y" -> part.sy;
            case "scale.z" -> part.sz;
            case "location" -> axis(part.x, part.y, part.z, channel.index);
            case "scale" -> axis(part.sx, part.sy, part.sz, channel.index);
            default -> axis(part.rx, part.ry, part.rz, channel.index);
        };
    }

    private enum ChannelKind
    {
        LOCATION("location"),
        ROTATION("rotation"),
        SCALE("scale");

        public final String path;

        ChannelKind(String path)
        {
            this.path = path;
        }
    }

    private static final class ChannelInfo
    {
        public final ChannelKind kind;
        public final int axis;

        private ChannelInfo(ChannelKind kind, int axis)
        {
            this.kind = kind;
            this.axis = axis;
        }

        public static ChannelInfo parse(BOBJChannel channel)
        {
            String path = channel.path;
            int dot = path.indexOf('.');
            String base = dot >= 0 ? path.substring(0, dot) : path;
            int axis = dot >= 0 ? axisFromSuffix(path.substring(dot + 1)) : channel.index;
            ChannelKind kind = switch (base)
            {
                case "location" -> ChannelKind.LOCATION;
                case "rotation" -> ChannelKind.ROTATION;
                case "scale" -> ChannelKind.SCALE;
                default -> null;
            };

            return kind == null || axis < 0 || axis > 2 ? null : new ChannelInfo(kind, axis);
        }

        private static int axisFromSuffix(String suffix)
        {
            return switch (suffix)
            {
                case "x" -> 0;
                case "y" -> 1;
                case "z" -> 2;
                default -> -1;
            };
        }
    }

    private static final class AxisMap
    {
        public final int[] axis = new int[3];
        public final float[] sign = new float[3];
        public boolean identity;

        private AxisMap() {}

        public static AxisMap between(BOBJBone source, BOBJBone target)
        {
            AxisMap map = new AxisMap();
            Vector3f[] sourceAxes = axes(source);
            Vector3f[] targetAxes = axes(target);
            boolean identity = true;

            for (int sourceAxis = 0; sourceAxis < 3; sourceAxis++)
            {
                float best = -1F;
                int bestAxis = sourceAxis;
                float bestSign = 1F;

                for (int targetAxis = 0; targetAxis < 3; targetAxis++)
                {
                    float dot = sourceAxes[sourceAxis].dot(targetAxes[targetAxis]);
                    float abs = Math.abs(dot);

                    if (abs > best)
                    {
                        best = abs;
                        bestAxis = targetAxis;
                        bestSign = dot < 0F ? -1F : 1F;
                    }
                }

                map.axis[sourceAxis] = bestAxis;
                map.sign[sourceAxis] = bestSign;

                if (bestAxis != sourceAxis || bestSign < 0F)
                {
                    identity = false;
                }
            }

            map.identity = identity;

            return map;
        }

        private static Vector3f[] axes(BOBJBone bone)
        {
            return new Vector3f[] {
                    new Vector3f(1, 0, 0).mulDirection(bone.boneMat).normalize(),
                    new Vector3f(0, 1, 0).mulDirection(bone.boneMat).normalize(),
                    new Vector3f(0, 0, 1).mulDirection(bone.boneMat).normalize()
            };
        }
    }

    private static KeyframeChannel<MolangExpression> axis(
            KeyframeChannel<MolangExpression> x,
            KeyframeChannel<MolangExpression> y,
            KeyframeChannel<MolangExpression> z,
            int index)
    {
        return switch (index)
        {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> null;
        };
    }

    private static void copyKeyframes(
            MolangParser parser,
            KeyframeChannel<MolangExpression> keyframeChannel,
            BOBJChannel channel)
    {
        for (int i = 0, c = channel.keyframes.size(); i < c; i++)
        {
            BOBJKeyframe current = channel.keyframes.get(i);
            BOBJKeyframe previous = i - 1 >= 0 ? channel.keyframes.get(i - 1) : current;
            MolangValue value = new MolangValue(parser, new Constant(current.value));
            int index = keyframeChannel.insert(current.frame, value);
            Keyframe<MolangExpression> keyframe = keyframeChannel.get(index);

            keyframe.getInterpolation().setInterp(previous.interpolation.interp);
            keyframe.lx = current.frame - current.leftX;
            keyframe.ly = current.leftY - current.value;
            keyframe.rx = current.rightX - current.frame;
            keyframe.ry = current.rightY - current.value;
        }

        keyframeChannel.sort();
    }

    private static void fillHeadVariables(MolangParser parser, AnimationPart head)
    {
        head.rx.insert(0F, parseExpression(parser, "query.head_pitch / 180 * " + Math.PI));
        head.ry.insert(0F, parseExpression(parser, "-query.head_yaw / 180 * " + Math.PI));
    }

    private static MolangExpression parseExpression(MolangParser parser, String expression)
    {
        try
        {
            return new MolangValue(parser, parser.parse(expression));
        }
        catch (Exception e)
        {}

        return MolangParser.ZERO;
    }
}
