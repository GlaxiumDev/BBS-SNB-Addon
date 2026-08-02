package elgatopro300.bbsfbx.mixin.cml;

import elgatopro300.bbsfbx.model.fbx.loaders.IFbxModel;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CML-fork-only companion to {@code BOBJModelMixin}.
 *
 * <p>{@code BOBJModel.copy()} only exists on CML (not Base or FS -- verified
 * directly against the real jars). It returns a plain {@code new BOBJModel(
 * armature.copy(), meshData, simple)} -- it carries the same
 * {@code FBXCompiledData} reference but none of the {@code @Unique} state the
 * mixin stores on the original, so without this the copied model would lose
 * its shape keys (and this addon's material data) and silently render as a
 * plain empty-shape-key model in whatever uses copies (e.g. pose-editor
 * preview). This re-stamps the FBX data and shape-key names onto the copy
 * right after it's built.</p>
 *
 * <p>Gated to the CML fork by {@code BBSFbxMixinPlugin}: the {@code copy()}
 * method it targets doesn't exist on Base or FS, so it must never be applied
 * there.</p>
 */
@Mixin(value = BOBJModel.class, remap = false)
public abstract class BOBJModelCopyMixin
{
    @Inject(method = "copy", at = @At("RETURN"), remap = false)
    private void bbsFbx$copyCarriesFbxData(CallbackInfoReturnable<IModel> info)
    {
        IModel result = info.getReturnValue();

        if (result instanceof BOBJModel copy && copy instanceof IFbxModel target)
        {
            IFbxModel source = (IFbxModel) (Object) this;

            target.bbsFbx$setFbxData(source.bbsFbx$getFbxData());
            target.bbsFbx$setShapeKeyNames(source.bbsFbx$getShapeKeyNames());
        }
    }
}
