package glaxium.snb.model.mixamo;

import glaxium.snb.compat.AnimationPartCompat;
import glaxium.snb.model.fbx.FBXConverter;
import glaxium.snb.model.fbx.loaders.java.JavaFbxImporter;
import glaxium.snb.model.fbx.scene.JavaScene;
import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJChannel;
import mchorse.bbs_mod.bobj.BOBJGroup;
import mchorse.bbs_mod.bobj.BOBJKeyframe;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts Mixamo FBX animations to Emoticons rig animations.
 *
 * <p>Uses {@link FBXConverter} to bake skinned bone delta rotations, then retargets
 * them onto the BBS Emoticons rig:
 * - Upper arms are mapped from Mixamo's horizontal T-pose to BBS's arms-down rest pose,
 *   preserving forward pitch (rotation.x), axial twist (rotation.y), and outward abduction (rotation.z).
 * - Forearm hinge flexion is mapped from Mixamo's rotation.z to BBS's pitch (rotation.x >= 0).
 * - Legs maintain natural forward/backward flexion without coordinate mirroring.
 * - Body root translation and forward tilt are converted to Minecraft coordinate space.</p>
 */
public final class MixamoAnimationConverter
{
    private static final String[] TARGET_BONES = new String[] {
            "body",
            "low_body",
            "head",
            "left_arm",
            "low_left_arm",
            "low_left_arm.end",
            "right_arm",
            "low_right_arm",
            "low_right_arm.end",
            "left_leg",
            "low_left_leg",
            "right_leg",
            "low_leg_right"
    };

    private MixamoAnimationConverter() {}

    /**
     * Converts a binary Mixamo FBX file into a BBS {@link Animation}.
     */
    public static Animation convert(String animationName, byte[] fbxBytes, MolangParser parser) throws IOException
    {
        JavaScene scene = JavaFbxImporter.read(fbxBytes);

        if (scene.animations.isEmpty())
        {
            return null;
        }

        BOBJData data = FBXConverter.convert(scene, 1.0F);
        if (data == null || data.actions.isEmpty())
        {
            return null;
        }

        BOBJAction action = data.actions.values().iterator().next();
        Map<String, BOBJGroup> groups = action.groups;

        float maxFrame = action.getDuration();
        if (maxFrame <= 0.0F)
        {
            for (BOBJGroup g : groups.values())
            {
                for (BOBJChannel c : g.channels)
                {
                    for (BOBJKeyframe kf : c.keyframes)
                    {
                        if (kf.frame > maxFrame)
                        {
                            maxFrame = kf.frame;
                        }
                    }
                }
            }
        }

        int totalFrames = Math.max(1, (int) Math.round(maxFrame));

        Animation result = new Animation(animationName, parser);
        result.setLength(totalFrames / 20.0);

        BOBJGroup hipsGroup = findMixamoGroup(groups, "Hips");

        for (String bone : TARGET_BONES)
        {
            AnimationPart part = new AnimationPart(parser);
            KeyframeChannel<MolangExpression> rx = AnimationPartCompat.axisChannel(part, "rotation.x");
            KeyframeChannel<MolangExpression> ry = AnimationPartCompat.axisChannel(part, "rotation.y");
            KeyframeChannel<MolangExpression> rz = AnimationPartCompat.axisChannel(part, "rotation.z");

            boolean isBody = bone.equals("body");
            KeyframeChannel<MolangExpression> tx = isBody ? AnimationPartCompat.axisChannel(part, "location.x") : null;
            KeyframeChannel<MolangExpression> ty = isBody ? AnimationPartCompat.axisChannel(part, "location.y") : null;
            KeyframeChannel<MolangExpression> tz = isBody ? AnimationPartCompat.axisChannel(part, "location.z") : null;

            Vector3f prevEuler = null;

            for (int f = 0; f <= totalFrames; f++)
            {
                float frame = (float) f;

                Vector3f euler = computeBoneEuler(bone, groups, frame);

                if (prevEuler != null)
                {
                    euler.x = unwrapAngle(euler.x, prevEuler.x);
                    euler.y = unwrapAngle(euler.y, prevEuler.y);
                    euler.z = unwrapAngle(euler.z, prevEuler.z);
                }
                prevEuler = new Vector3f(euler);

                if (rx != null) rx.insert(frame, new MolangValue(parser, new Constant(euler.x)));
                if (ry != null) ry.insert(frame, new MolangValue(parser, new Constant(euler.y)));
                if (rz != null) rz.insert(frame, new MolangValue(parser, new Constant(euler.z)));

                if (isBody && tx != null && ty != null && tz != null && hipsGroup != null)
                {
                    float x = evaluateChannel(hipsGroup, "location.x", frame);
                    float y = evaluateChannel(hipsGroup, "location.y", frame);
                    float z = evaluateChannel(hipsGroup, "location.z", frame);

                    // Mixamo -Y forward to Minecraft +Y forward, preserving standing height
                    tx.insert(frame, new MolangValue(parser, new Constant(-x)));
                    ty.insert(frame, new MolangValue(parser, new Constant(y)));
                    tz.insert(frame, new MolangValue(parser, new Constant(-z)));
                }
            }

            AnimationPartCompat.commit(part);
            result.parts.put(bone, part);
        }

        return result;
    }

    private static Vector3f computeBoneEuler(String eBone, Map<String, BOBJGroup> groups, float frame)
    {
        Quaternionf rot;

        if (eBone.equals("body"))
        {
            rot = getRot(findMixamoGroup(groups, "Hips"), frame);
            mirror(rot, "Y");
        }
        else if (eBone.equals("low_body"))
        {
            Quaternionf spine = getRot(findMixamoGroup(groups, "Spine"), frame);
            Quaternionf spine1 = getRot(findMixamoGroup(groups, "Spine1"), frame);
            Quaternionf spine2 = getRot(findMixamoGroup(groups, "Spine2"), frame);
            rot = new Quaternionf(spine).mul(spine1).mul(spine2);
            mirror(rot, "Y");
        }
        else if (eBone.equals("head"))
        {
            Quaternionf neck = getRot(findMixamoGroup(groups, "Neck"), frame);
            Quaternionf head = getRot(findMixamoGroup(groups, "Head"), frame);
            rot = new Quaternionf(neck).mul(head);
        }
        else if (eBone.equals("left_arm"))
        {
            return computeUpperArmEuler(groups, "Left", frame);
        }
        else if (eBone.equals("right_arm"))
        {
            return computeUpperArmEuler(groups, "Right", frame);
        }
        else if (eBone.equals("low_left_arm"))
        {
            return computeForearmEuler(groups, "Left", frame);
        }
        else if (eBone.equals("low_right_arm"))
        {
            return computeForearmEuler(groups, "Right", frame);
        }
        else if (eBone.equals("low_left_arm.end"))
        {
            return computeHandEuler(groups, "Left", frame);
        }
        else if (eBone.equals("low_right_arm.end"))
        {
            return computeHandEuler(groups, "Right", frame);
        }
        else if (eBone.equals("left_leg"))
        {
            rot = getRot(findMixamoGroup(groups, "LeftUpLeg"), frame);
        }
        else if (eBone.equals("low_left_leg"))
        {
            rot = getRot(findMixamoGroup(groups, "LeftLeg"), frame);
        }
        else if (eBone.equals("right_leg"))
        {
            rot = getRot(findMixamoGroup(groups, "RightUpLeg"), frame);
        }
        else if (eBone.equals("low_leg_right"))
        {
            rot = getRot(findMixamoGroup(groups, "RightLeg"), frame);
        }
        else
        {
            rot = new Quaternionf();
        }

        return decomposeEuler(rot.normalize());
    }

    private static Vector3f computeUpperArmEuler(Map<String, BOBJGroup> groups, String side, float frame)
    {
        BOBJGroup shG = findMixamoGroup(groups, side + "Shoulder");
        BOBJGroup armG = findMixamoGroup(groups, side + "Arm");
        BOBJGroup foreG = findMixamoGroup(groups, side + "ForeArm");

        Quaternionf sh = getRot(shG, frame);
        Quaternionf arm = getRot(armG, frame);
        Quaternionf qCombined = new Quaternionf(sh).mul(arm);

        // In Mixamo bone local space, bone points along (0, 1, 0)
        Vector3f vLocal = qCombined.transform(new Vector3f(0.0F, 1.0F, 0.0F));

        // Map to Steve's bone local coordinate frame:
        Vector3f vSteve;
        if (side.equals("Left"))
        {
            vSteve = new Vector3f(-vLocal.y, vLocal.z, -vLocal.x);
        }
        else
        {
            vSteve = new Vector3f(vLocal.y, vLocal.z, vLocal.x);
        }

        if (foreG != null)
        {
            Quaternionf fore = getRot(foreG, frame);
            Quaternionf qForeCombined = new Quaternionf(qCombined).mul(fore);
            Vector3f vForeLocal = qForeCombined.transform(new Vector3f(0.0F, 1.0F, 0.0F));
            Vector3f vForeSteve;
            if (side.equals("Left"))
            {
                vForeSteve = new Vector3f(-vForeLocal.y, vForeLocal.z, -vForeLocal.x);
                if (vForeSteve.x > 0.0F)
                {
                    vSteve.x += vForeSteve.x * 0.75F;
                }
            }
            else
            {
                vForeSteve = new Vector3f(vForeLocal.y, vForeLocal.z, vForeLocal.x);
                if (vForeSteve.x < 0.0F)
                {
                    vSteve.x += vForeSteve.x * 0.75F;
                }
            }
        }
        vSteve.normalize();

        Quaternionf q = new Quaternionf().rotateTo(0.0F, 1.0F, 0.0F, vSteve.x, vSteve.y, vSteve.z);
        return decomposeEuler(q);
    }

    private static Vector3f computeForearmEuler(Map<String, BOBJGroup> groups, String side, float frame)
    {
        BOBJGroup shG = findMixamoGroup(groups, side + "Shoulder");
        BOBJGroup armG = findMixamoGroup(groups, side + "Arm");
        BOBJGroup foreG = findMixamoGroup(groups, side + "ForeArm");
        if (foreG == null)
        {
            return new Vector3f();
        }

        Quaternionf sh = getRot(shG, frame);
        Quaternionf arm = getRot(armG, frame);
        Quaternionf fore = getRot(foreG, frame);

        Quaternionf qArmCombined = new Quaternionf(sh).mul(arm);
        Quaternionf qForeCombined = new Quaternionf(qArmCombined).mul(fore);

        Vector3f vArmLocal = qArmCombined.transform(new Vector3f(0.0F, 1.0F, 0.0F));
        Vector3f vForeLocal = qForeCombined.transform(new Vector3f(0.0F, 1.0F, 0.0F));

        Vector3f vArmSteve;
        Vector3f vForeSteve;
        if (side.equals("Left"))
        {
            vArmSteve = new Vector3f(-vArmLocal.y, vArmLocal.z, -vArmLocal.x);
            vForeSteve = new Vector3f(-vForeLocal.y, vForeLocal.z, -vForeLocal.x);
            if (vForeSteve.x > 0.0F)
            {
                float offset = vForeSteve.x * 0.75F;
                vArmSteve.x += offset;
                vForeSteve.x += offset;
            }
        }
        else
        {
            vArmSteve = new Vector3f(vArmLocal.y, vArmLocal.z, vArmLocal.x);
            vForeSteve = new Vector3f(vForeLocal.y, vForeLocal.z, vForeLocal.x);
            if (vForeSteve.x < 0.0F)
            {
                float offset = vForeSteve.x * 0.75F;
                vArmSteve.x += offset;
                vForeSteve.x += offset;
            }
        }
        vArmSteve.normalize();
        vForeSteve.normalize();

        Quaternionf qArmSteve = new Quaternionf().rotateTo(0.0F, 1.0F, 0.0F, vArmSteve.x, vArmSteve.y, vArmSteve.z);

        Vector3f vForeInArm = new Quaternionf(qArmSteve).conjugate().transform(vForeSteve).normalize();

        Quaternionf qForeSteve = new Quaternionf().rotateTo(0.0F, 1.0F, 0.0F, vForeInArm.x, vForeInArm.y, vForeInArm.z);
        return decomposeEuler(qForeSteve);
    }

    private static Vector3f computeHandEuler(Map<String, BOBJGroup> groups, String side, float frame)
    {
        BOBJGroup handG = findMixamoGroup(groups, side + "Hand");
        Quaternionf hand = getRot(handG, frame);

        Quaternionf rot = new Quaternionf(hand);
        mirror(rot, "X");
        swapComponents(rot, 1, 2);
        if (side.equals("Left"))
        {
            mirror(rot, "YZ");
        }
        else
        {
            mirror(rot, "XZ");
        }

        return decomposeEuler(rot.normalize());
    }

    private static BOBJGroup findMixamoGroup(Map<String, BOBJGroup> groups, String baseName)
    {
        if (baseName == null || baseName.isEmpty())
        {
            return null;
        }

        BOBJGroup g = groups.get(baseName);
        if (g != null) return g;

        g = groups.get("mixamorig:" + baseName);
        if (g != null) return g;

        for (Map.Entry<String, BOBJGroup> entry : groups.entrySet())
        {
            String key = entry.getKey();
            if (key.equalsIgnoreCase(baseName)
                    || key.equalsIgnoreCase("mixamorig:" + baseName)
                    || key.endsWith(":" + baseName)
                    || key.endsWith("_" + baseName)
                    || key.toLowerCase(Locale.ROOT).endsWith(baseName.toLowerCase(Locale.ROOT)))
            {
                return entry.getValue();
            }
        }

        return null;
    }

    private static Quaternionf getRot(BOBJGroup g, float frame)
    {
        if (g == null)
        {
            return new Quaternionf();
        }

        float rx = evaluateChannel(g, "rotation.x", frame);
        float ry = evaluateChannel(g, "rotation.y", frame);
        float rz = evaluateChannel(g, "rotation.z", frame);

        return new Quaternionf().rotateZ(rz).rotateY(ry).rotateX(rx);
    }

    private static float evaluateChannel(BOBJGroup g, String path, float frame)
    {
        if (g == null || g.channels == null)
        {
            return 0.0F;
        }

        for (BOBJChannel c : g.channels)
        {
            if (c.path.equals(path))
            {
                List<BOBJKeyframe> keys = c.keyframes;
                if (keys.isEmpty())
                {
                    return 0.0F;
                }
                if (frame <= keys.get(0).frame)
                {
                    return keys.get(0).value;
                }
                if (frame >= keys.get(keys.size() - 1).frame)
                {
                    return keys.get(keys.size() - 1).value;
                }
                for (int i = 0; i < keys.size() - 1; i++)
                {
                    BOBJKeyframe k0 = keys.get(i);
                    BOBJKeyframe k1 = keys.get(i + 1);
                    if (frame >= k0.frame && frame <= k1.frame)
                    {
                        float span = k1.frame - k0.frame;
                        if (span <= 1e-4F)
                        {
                            return k0.value;
                        }
                        float t = (frame - k0.frame) / span;
                        return k0.value + (k1.value - k0.value) * t;
                    }
                }
                return keys.get(keys.size() - 1).value;
            }
        }

        return 0.0F;
    }

    private static void swapComponents(Quaternionf q, int i, int j)
    {
        if ((i == 1 && j == 2) || (i == 2 && j == 1))
        {
            float tmp = q.x;
            q.x = q.y;
            q.y = tmp;
        }
    }

    private static void mirror(Quaternionf q, String ax)
    {
        boolean nx = true;
        boolean ny = true;
        boolean nz = true;

        for (int i = 0; i < ax.length(); i++)
        {
            char c = ax.charAt(i);
            if (c == 'X') nx = false;
            if (c == 'Y') ny = false;
            if (c == 'Z') nz = false;
        }

        if (nx) q.x = -q.x;
        if (ny) q.y = -q.y;
        if (nz) q.z = -q.z;
    }

    private static Vector3f decomposeEuler(Quaternionf q)
    {
        Matrix3f m = new Matrix3f().set(q);
        float sy = -m.m02;
        float cy = (float) Math.sqrt(Math.max(0.0F, 1.0F - sy * sy));

        float x;
        float y;
        float z;

        if (cy > 1e-6F)
        {
            y = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, sy)));
            x = (float) Math.atan2(m.m12, m.m22);
            z = (float) Math.atan2(m.m01, m.m00);
        }
        else
        {
            y = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, sy)));
            x = (float) Math.atan2(-m.m21, m.m11);
            z = 0.0F;
        }

        return new Vector3f(x, y, z);
    }

    private static float unwrapAngle(float angle, float prevAngle)
    {
        while (angle - prevAngle > Math.PI)
        {
            angle -= (float) (2 * Math.PI);
        }
        while (angle - prevAngle < -Math.PI)
        {
            angle += (float) (2 * Math.PI);
        }
        return angle;
    }
}

