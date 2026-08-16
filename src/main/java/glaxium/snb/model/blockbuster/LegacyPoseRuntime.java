package glaxium.snb.model.blockbuster;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.IModelInstance;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.interps.Lerps;

import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EquipmentSlot;

import java.util.Map;

public final class LegacyPoseRuntime
{
    private LegacyPoseRuntime() {}

    public static void apply(IEntity entity, IModelInstance instance, IModel model)
    {
        if (!(instance instanceof LegacyPoseHolder holder))
        {
            return;
        }

        Map<String, Pose> poses = holder.bbsFbx$getLegacyPoses();

        if (poses.isEmpty())
        {
            return;
        }

        String state = resolveState(entity, poses);
        Pose pose = state == null ? null : poses.get(state);

        if (pose != null && !pose.isEmpty())
        {
            model.applyPose(pose);
        }
    }

    /**
     * Apply Blockbuster's old per-limb swipe algorithm. BBS's procedural
     * animator uses a vanilla-style torso plus two-arm swipe, which is not
     * compatible with legacy model.json's swiping flag.
     */
    public static void applySwipe(IEntity entity, IModelInstance instance, IModel model, float tickDelta)
    {
        if (entity == null || !(instance instanceof LegacyPoseHolder holder) || !holder.bbsFbx$isLegacyModel()
                || !(model instanceof LegacyBBModel legacy))
        {
            return;
        }

        float progress = entity.getHandSwingProgress(tickDelta);

        if (progress <= 0F)
        {
            return;
        }

        float bodyY = (float) Math.sin(Math.sqrt(progress) * Math.PI * 2D) * 0.2F;
        float eased = 1F - progress;
        eased = 1F - eased * eased * eased;
        float sinSwing = (float) Math.sin(eased * Math.PI);
        float sinSwing2 = (float) Math.sin(progress * Math.PI) * 0.7F * 0.75F;
        float swipeZ = (float) Math.sin(progress * Math.PI) * -0.4F;

        for (Map.Entry<String, Float> entry : holder.bbsFbx$getLegacySwipeFactors().entrySet())
        {
            ModelGroup group = legacy.group(entry.getKey());

            if (group == null)
            {
                continue;
            }

            float factor = entry.getValue();

            /* Legacy ModelRenderer rotations are converted as (-X, Y, -Z).
             * Apply the same axis conversion to the procedural swipe delta. */
            group.current.rotate.x += (float) Math.toDegrees(sinSwing * 1.2F + sinSwing2);
            group.current.rotate.y -= (float) Math.toDegrees(bodyY * 2F * factor);
            group.current.rotate.z += (float) Math.toDegrees(swipeZ * factor);
        }
    }

    /** Apply BB's flag-driven look, walk, idle, hold, wheel, wing and roll pass. */
    public static void applyIdle(IEntity entity, IModelInstance instance, IModel model, float tickDelta)
    {
        if (entity == null || !(instance instanceof LegacyPoseHolder holder) || !holder.bbsFbx$isLegacyModel()
                || !(model instanceof LegacyBBModel legacy))
        {
            return;
        }

        float age = entity.getAge() + tickDelta;
        float bodyYaw = Lerps.lerp(entity.getPrevBodyYaw(), entity.getBodyYaw(), tickDelta);
        float headYaw = Lerps.lerp(entity.getPrevHeadYaw(), entity.getHeadYaw(), tickDelta) - bodyYaw;
        float pitch = Lerps.lerp(entity.getPrevPitch(), entity.getPitch(), tickDelta);
        float limbSwing = entity.getLimbPos(tickDelta);
        float limbAmount = entity.getLimbSpeed(tickDelta);

        for (Map.Entry<String, BlockbusterModelLoader.LegacyLimb> entry : legacy.data.limbs.entrySet())
        {
            BlockbusterModelLoader.LegacyLimb limb = entry.getValue();
            ModelGroup group = legacy.group(entry.getKey());

            if (group == null)
            {
                continue;
            }

            float factor = limb.mirror ^ limb.invert ? -1F : 1F;

            if ((limb.lookX || limb.lookY) && !limb.wheel)
            {
                if (limb.lookX) group.current.rotate.x -= pitch;
                if (limb.lookY)
                {
                    if (limb.invert) group.current.rotate.z += headYaw;
                    else group.current.rotate.y -= headYaw;
                }
            }

            if (limb.swinging)
            {
                float phase = limb.mirror ^ limb.invert ? (float) Math.PI : 0F;
                float strength = limb.holding == null ? 1.4F : 1F;
                float delta = (float) Math.cos(limbSwing * 0.6662F + phase) * strength * limbAmount;
                group.current.rotate.x -= (float) Math.toDegrees(delta);
            }

            if (limb.idle)
            {
                float z = ((float) Math.cos(age * 0.09F) * 0.05F + 0.05F) * factor;
                float x = (float) Math.sin(age * 0.067F) * 0.05F * factor;
                group.current.rotate.z += (float) Math.toDegrees(z);
                group.current.rotate.x -= (float) Math.toDegrees(x);
            }

            if (limb.holding != null && limb.hold)
            {
                boolean occupied = "right".equals(limb.holding)
                        ? !entity.getEquipmentStack(EquipmentSlot.MAINHAND).isEmpty()
                        : !entity.getEquipmentStack(EquipmentSlot.OFFHAND).isEmpty();

                if (occupied) group.current.rotate.x = group.current.rotate.x * 0.5F + 18F;
            }

            if (limb.wheel)
            {
                group.current.rotate.x -= (float) Math.toDegrees(limbSwing * factor);
                if (limb.lookY) group.current.rotate.y = -headYaw;
            }

            if (limb.wing)
            {
                float wing = (float) Math.cos(age * 1.3F) * (float) Math.PI * 0.25F * (0.5F + limbAmount) * factor;
                if (limb.swiping) group.current.rotate.z = (float) Math.toDegrees(wing);
                else group.current.rotate.y = -(float) Math.toDegrees(wing);
            }

            if (limb.roll) group.current.rotate.z -= entity.getRoll();
        }
    }

    private static String resolveState(IEntity entity, Map<String, Pose> poses)
    {
        if (entity.getEntityPose() == EntityPose.SLEEPING && poses.containsKey("sleeping"))
        {
            return "sleeping";
        }

        if (entity instanceof MCEntity mcEntity && mcEntity.getMcEntity().hasVehicle() && poses.containsKey("riding"))
        {
            return "riding";
        }

        if (entity.isFallFlying() && poses.containsKey("flying"))
        {
            return "flying";
        }

        return null;
    }
}
