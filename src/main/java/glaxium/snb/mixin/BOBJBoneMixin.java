package glaxium.snb.mixin;

import mchorse.bbs_mod.bobj.BOBJBone;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reuses each bone's output matrix instead of allocating one every frame. */
@Mixin(value = BOBJBone.class, remap = false)
public abstract class BOBJBoneMixin
{
    @Shadow public Matrix4f mat;
    @Shadow public Matrix4f invBoneMat;
    @Shadow public Vector3f offset;

    @Shadow public abstract Matrix4f computeMatrix(Matrix4f matrix);

    @Unique
    private final Matrix4f bbsFbx$skinMatrix = new Matrix4f();

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$reuseComputedMatrix(CallbackInfoReturnable<Matrix4f> info)
    {
        Matrix4f result = this.computeMatrix(this.bbsFbx$skinMatrix);

        /* mat is the current global bone transform used by child bones;
         * result then becomes the inverse-bind skinning transform. */
        this.mat.set(result);
        result.mul(this.invBoneMat);

        if (this.offset != null)
        {
            result.translateLocal(this.offset);
        }

        info.setReturnValue(result);
    }
}
