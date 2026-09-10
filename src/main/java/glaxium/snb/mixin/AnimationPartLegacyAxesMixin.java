package glaxium.snb.mixin;

import glaxium.snb.compat.AnimationPartCompat;

import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BBS&nbsp;2.1 {@link AnimationPart} only reads {@code translate}/{@code scale}/
 * {@code rotate} vector channels. Older cubic {@code model.json} /
 * {@code .bbs.json} animations still store per-axis {@code x}/{@code rx}/…
 * channels; convert those after stock fromData so OBJ+JSON animations play.
 *
 * <p>No-op on Base/FS/CML where {@code AnimationPart} is still axis-native.</p>
 */
@Mixin(value = AnimationPart.class, remap = false)
public abstract class AnimationPartLegacyAxesMixin
{
    @Inject(method = "fromData(Lmchorse/bbs_mod/data/types/MapType;)V", at = @At("RETURN"), remap = false)
    private void bbsFbx$importLegacyAxisChannels(MapType data, CallbackInfo info)
    {
        if (AnimationPartCompat.isAxisMode() || data == null)
        {
            return;
        }

        if (!hasAnyAxis(data))
        {
            return;
        }

        AnimationPart part = (AnimationPart) (Object) this;
        boolean wrote = false;

        wrote |= readAxis(part, data, "x", "location.x");
        wrote |= readAxis(part, data, "y", "location.y");
        wrote |= readAxis(part, data, "z", "location.z");
        wrote |= readAxis(part, data, "sx", "scale.x");
        wrote |= readAxis(part, data, "sy", "scale.y");
        wrote |= readAxis(part, data, "sz", "scale.z");
        wrote |= readAxis(part, data, "rx", "rotation.x");
        wrote |= readAxis(part, data, "ry", "rotation.y");
        wrote |= readAxis(part, data, "rz", "rotation.z");

        if (wrote)
        {
            AnimationPartCompat.commit(part);
        }
    }

    private static boolean hasAnyAxis(MapType data)
    {
        return data.has("x") || data.has("y") || data.has("z")
                || data.has("sx") || data.has("sy") || data.has("sz")
                || data.has("rx") || data.has("ry") || data.has("rz");
    }

    private static boolean readAxis(AnimationPart part, MapType data, String key, String path)
    {
        if (!data.has(key))
        {
            return false;
        }

        BaseType channelData = data.get(key);

        if (channelData == null)
        {
            return false;
        }

        KeyframeChannel<MolangExpression> channel = AnimationPartCompat.axisChannel(part, path);

        if (channel == null)
        {
            return false;
        }

        channel.fromData(channelData);
        return true;
    }
}
