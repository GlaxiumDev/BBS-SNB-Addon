package glaxium.snb.anim;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJChannel;
import mchorse.bbs_mod.bobj.BOBJGroup;
import mchorse.bbs_mod.bobj.BOBJKeyframe;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts selected pose keyframes from the film replay editor into a
 * standalone animation-only {@code .bobj} file, saved under
 * {@code config/bbs/assets/<modelId>/animations/<name>.bobj}.
 *
 * <p>The saved animation is merged into the model's {@link Animations} on the
 * next {@link ModelManager#loadModel} call, so it appears in the animation
 * keyframe dropdown like any embedded animation — scoped to that model only.
 *
 * <p>Pose transform values map directly to animation channel values:
 * both are additive deltas on the bone's rest pose in local space, with
 * rotations in radians. The conversion composes {@code rotate} and
 * {@code rotate2} into a single ZYX euler triple via quaternion multiplication,
 * matching the {@code rotateZ/rotateY/rotateX} application order in
 * {@code BOBJBone#applyTransformations}.
 */
public final class PoseToAnimation
{
    private static final String ANIMATIONS_FOLDER = "animations";
    private static final String BOBJ_EXTENSION = ".bobj";
    private static final String BACKUP_EXTENSION = ".bobj.backup";

    private PoseToAnimation() {}

    /**
     * Converts selected pose keyframes into BOBJ animation text and writes it
     * to {@code <assetsFolder>/models/<modelId>/animations/<name>.bobj}.
     * If a file with the same name already exists, it is renamed to
     * {@code <name>.bobj.backup} (with a numeric suffix if that also exists).
     *
     * @return the written file, or null on failure
     */
    public static File save(List<Keyframe<Pose>> keyframes, String modelId, String name)
    {
        if (keyframes == null || keyframes.isEmpty() || modelId == null || name == null)
        {
            return null;
        }

        name = sanitizeFileName(name);

        if (name.isEmpty())
        {
            return null;
        }

        String bobjText = convertToBobj(keyframes, name);

        if (bobjText == null)
        {
            return null;
        }

        File folder = new File(BBSMod.getAssetsFolder(),
                ModelManager.MODELS_PREFIX + modelId + "/" + ANIMATIONS_FOLDER);

        folder.mkdirs();

        File file = new File(folder, name + BOBJ_EXTENSION);

        if (file.exists())
        {
            backupExisting(file);
        }

        try (FileWriter writer = new FileWriter(file))
        {
            writer.write(bobjText);
        }
        catch (IOException e)
        {
            e.printStackTrace();

            return null;
        }

        return file;
    }

    /**
     * Merges all animation-only {@code .bobj} files from the model's
     * {@code animations/} subfolder into the given model instance's
     * {@link Animations}. Called from the {@code ModelManager.loadModel}
     * mixin after the loader returns a non-null instance.
     */
    public static void merge(ModelInstance instance, ModelManager manager)
    {
        if (instance == null || instance.animations == null)
        {
            return;
        }

        Link animFolder = Link.assets(ModelManager.MODELS_PREFIX + instance.id + "/" + ANIMATIONS_FOLDER);
        Collection<Link> links = manager.provider.getLinksFromPath(animFolder, true);

        if (links == null || links.isEmpty())
        {
            return;
        }

        for (Link link : links)
        {
            if (link == null || link.path == null || !link.path.endsWith(BOBJ_EXTENSION))
            {
                continue;
            }

            try (InputStream stream = manager.provider.getAsset(link))
            {
                if (stream == null)
                {
                    continue;
                }

                BOBJLoader.BOBJData data = BOBJLoader.readData(stream);

                convertAndMerge(data, instance.animations);
            }
            catch (Exception e)
            {
                System.err.println("Failed to load pose animation: " + link.path);
                e.printStackTrace();
            }
        }
    }

    /**
     * Converts parsed BOBJ data (actions only, no geometry) into
     * {@link Animation} objects and merges them into the target
     * {@link Animations} map. Handles both the standard indexed channel
     * format ({@code location 0}) and the dotted format ({@code location.x}).
     */
    private static void convertAndMerge(BOBJLoader.BOBJData data, Animations animations)
    {
        MolangParser parser = animations.parser;

        for (Map.Entry<String, BOBJAction> entry : data.actions.entrySet())
        {
            Animation animation = new Animation(entry.getKey(), parser);

            fillAnimation(animation, entry.getValue(), parser);

            animations.add(animation);
        }
    }

    private static void fillAnimation(Animation animation, BOBJAction action, MolangParser parser)
    {
        for (Map.Entry<String, BOBJGroup> entry : action.groups.entrySet())
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

        animation.setLength(action.getDuration() / 20F + 1e-3);
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

    /**
     * Converts selected pose keyframes into BOBJ animation text.
     * Each selected keyframe becomes one animation keyframe per channel.
     * Bones with identity transforms are skipped.
     *
     * <p>Frames are normalized so the FIRST selected keyframe becomes frame 0
     * and the last becomes the animation's end — the animation plays from its
     * own start, not from the absolute film tick the poses were captured at.
     */
    private static String convertToBobj(List<Keyframe<Pose>> keyframes, String name)
    {
        List<Keyframe<Pose>> sorted = new ArrayList<>(keyframes);

        sorted.sort((a, b) -> Float.compare(a.getTick(), b.getTick()));

        /* Normalize: first selected keyframe = frame 0 */
        float startTick = sorted.get(0).getTick();

        Set<String> boneNames = new LinkedHashSet<>();

        for (Keyframe<Pose> kf : sorted)
        {
            Pose pose = kf.getValue();

            if (pose != null)
            {
                boneNames.addAll(pose.transforms.keySet());
            }
        }

        if (boneNames.isEmpty())
        {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        sb.append("an ").append(name).append('\n');

        for (String bone : boneNames)
        {
            boolean hasAnyValue = false;

            for (Keyframe<Pose> kf : sorted)
            {
                Pose pose = kf.getValue();
                PoseTransform transform = pose != null ? pose.transforms.get(bone) : null;

                if (transform != null && !isIdentity(transform))
                {
                    hasAnyValue = true;
                    break;
                }
            }

            if (!hasAnyValue)
            {
                continue;
            }

            sb.append("ao ").append(bone).append('\n');

            writeChannel(sb, sorted, bone, ChannelKind.LOCATION, 0, startTick);
            writeChannel(sb, sorted, bone, ChannelKind.LOCATION, 1, startTick);
            writeChannel(sb, sorted, bone, ChannelKind.LOCATION, 2, startTick);
            writeChannel(sb, sorted, bone, ChannelKind.ROTATION, 0, startTick);
            writeChannel(sb, sorted, bone, ChannelKind.ROTATION, 1, startTick);
            writeChannel(sb, sorted, bone, ChannelKind.ROTATION, 2, startTick);
            writeChannel(sb, sorted, bone, ChannelKind.SCALE, 0, startTick);
            writeChannel(sb, sorted, bone, ChannelKind.SCALE, 1, startTick);
            writeChannel(sb, sorted, bone, ChannelKind.SCALE, 2, startTick);
        }

        return sb.toString();
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

    private static void writeChannel(StringBuilder sb, List<Keyframe<Pose>> keyframes,
            String bone, ChannelKind kind, int axis, float startTick)
    {
        sb.append("ag ").append(kind.path).append(' ').append(axis).append('\n');

        for (Keyframe<Pose> kf : keyframes)
        {
            float tick = kf.getTick() - startTick;
            Pose pose = kf.getValue();
            PoseTransform transform = pose != null ? pose.transforms.get(bone) : null;

            float value = getChannelValue(transform, kind, axis);

            sb.append("kf ").append(formatFloat(tick)).append(' ')
              .append(formatFloat(value)).append('\n');
        }
    }

    private static float getChannelValue(PoseTransform transform, ChannelKind kind, int axis)
    {
        if (transform == null)
        {
            return kind == ChannelKind.SCALE ? 1F : 0F;
        }

        switch (kind)
        {
            case LOCATION:
                return axis == 0 ? transform.translate.x
                     : axis == 1 ? transform.translate.y
                     : transform.translate.z;

            case ROTATION:
            {
                Vector3f euler = composeRotation(transform);

                return axis == 0 ? euler.x
                     : axis == 1 ? euler.y
                     : euler.z;
            }

            case SCALE:
                return axis == 0 ? transform.scale.x
                     : axis == 1 ? transform.scale.y
                     : transform.scale.z;

            default:
                return 0F;
        }
    }

    /**
     * Composes {@code rotate} and {@code rotate2} into a single ZYX euler
     * triple via quaternion multiplication, matching the application order in
     * {@code BOBJBone#applyTransformations}: Rz * Ry * Rx for rotate, then
     * Rz * Ry * Rx for rotate2.
     */
    private static Vector3f composeRotation(Transform transform)
    {
        Quaternionf q1 = new Quaternionf()
                .rotateZ(transform.rotate.z)
                .rotateY(transform.rotate.y)
                .rotateX(transform.rotate.x);

        Quaternionf q2 = new Quaternionf()
                .rotateZ(transform.rotate2.z)
                .rotateY(transform.rotate2.y)
                .rotateX(transform.rotate2.x);

        q1.mul(q2);

        return quatToEulerZYX(q1);
    }

    /**
     * Converts a quaternion to ZYX euler angles (radians), matching the
     * rotateZ -> rotateY -> rotateX application order used by
     * {@code BOBJBone#applyTransformations}. Returns (rotX, rotY, rotZ).
     */
    private static Vector3f quatToEulerZYX(Quaternionf q)
    {
        float x = q.x;
        float y = q.y;
        float z = q.z;
        float w = q.w;

        double sinrCosp = 2.0 * (w * x + y * z);
        double cosrCosp = 1.0 - 2.0 * (x * x + y * y);
        double roll = Math.atan2(sinrCosp, cosrCosp);

        double sinp = 2.0 * (w * y - z * x);
        double pitch;

        if (Math.abs(sinp) >= 1.0)
        {
            pitch = Math.copySign(Math.PI / 2.0, sinp);
        }
        else
        {
            pitch = Math.asin(sinp);
        }

        double sinyCosp = 2.0 * (w * z + x * y);
        double cosyCosp = 1.0 - 2.0 * (y * y + z * z);
        double yaw = Math.atan2(sinyCosp, cosyCosp);

        return new Vector3f((float) roll, (float) pitch, (float) yaw);
    }

    private static boolean isIdentity(Transform transform)
    {
        return transform.translate.x == 0F && transform.translate.y == 0F && transform.translate.z == 0F
            && transform.rotate.x == 0F && transform.rotate.y == 0F && transform.rotate.z == 0F
            && transform.rotate2.x == 0F && transform.rotate2.y == 0F && transform.rotate2.z == 0F
            && transform.scale.x == 1F && transform.scale.y == 1F && transform.scale.z == 1F;
    }

    /**
     * Renames an existing file to {@code .bobj.backup}, appending a numeric
     * suffix if that name is also taken.
     */
    private static void backupExisting(File file)
    {
        String baseName = file.getName();
        int dot = baseName.lastIndexOf('.');
        String stem = dot > 0 ? baseName.substring(0, dot) : baseName;

        File backup = new File(file.getParentFile(), stem + BACKUP_EXTENSION);
        int counter = 1;

        while (backup.exists())
        {
            backup = new File(file.getParentFile(), stem + BACKUP_EXTENSION + "." + counter);
            counter++;
        }

        file.renameTo(backup);
    }

    /**
     * Strips filesystem-reserved characters and path separators from a
     * user-supplied file name.
     */
    public static String sanitizeFileName(String name)
    {
        if (name == null)
        {
            return "";
        }

        name = name.trim()
                .replaceAll("[/\\\\:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");

        if (name.toLowerCase(Locale.ROOT).endsWith(BOBJ_EXTENSION))
        {
            name = name.substring(0, name.length() - BOBJ_EXTENSION.length());
        }

        return name;
    }

    private static String formatFloat(float value)
    {
        if (value == (int) value)
        {
            return String.valueOf((int) value);
        }

        return String.valueOf(value);
    }
}
