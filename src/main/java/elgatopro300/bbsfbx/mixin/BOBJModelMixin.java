package elgatopro300.bbsfbx.mixin;

import elgatopro300.bbsfbx.model.fbx.loaders.FBXCompiledData;
import elgatopro300.bbsfbx.model.fbx.loaders.IFbxModel;

import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Fork-agnostic FBX-data carrier for {@code BOBJModel}.
 *
 * <p>Replaces the old per-fork {@code FBXShapeKeyModel} subclasses. Base and
 * CML's {@code BOBJModel} constructor takes a single {@code CompiledData},
 * FS's takes a {@code List<CompiledData>} -- no common supertype constructor
 * exists, so a single shared subclass can't be written (and per-fork
 * subclasses can't coexist in one source tree that compiles against one jar
 * at a time). Instead the loader builds a plain {@code BOBJModel} via the
 * active fork's constructor (picked reflectively in {@code FBXModelLoader})
 * and stores the FBX-specific pieces here, on the model itself.</p>
 *
 * <p>Two things ride on this mixin:
 * <ul>
 *   <li>{@link FBXCompiledData}: read back by the multi-material picker UI
 *       ({@code MaterialTextureDelegate}, {@code ModelInstanceMixinCML}) and,
 *       through the same object reference, by the per-material VAO render
 *       split ({@code BOBJModelVAOMixin} trio).</li>
 *   <li>The shape-key name list: {@code BOBJModel.getShapeKeys()} is
 *       hardcoded to an empty set on every fork, and overriding it is what
 *       turns shape keys on for an FBX model (feeds
 *       {@code ModelInstance.hasShapeKeys()} / the shape-key UI). This mixin
 *       returns the stored names when the loader set any, and falls through
 *       to the host's own empty-set behavior otherwise -- non-FBX models are
 *       completely unaffected.</li>
 * </ul>
 */
@Mixin(value = BOBJModel.class, remap = false)
public abstract class BOBJModelMixin implements IFbxModel
{
    @Unique
    private FBXCompiledData bbsFbx$fbxData;

    @Unique
    private Set<String> bbsFbx$shapeKeyNames;

    @Override
    @Unique
    public FBXCompiledData bbsFbx$getFbxData()
    {
        return this.bbsFbx$fbxData;
    }

    @Override
    @Unique
    public void bbsFbx$setFbxData(FBXCompiledData data)
    {
        this.bbsFbx$fbxData = data;
    }

    @Override
    @Unique
    public Set<String> bbsFbx$getShapeKeyNames()
    {
        return this.bbsFbx$shapeKeyNames;
    }

    @Override
    @Unique
    public void bbsFbx$setShapeKeyNames(Set<String> names)
    {
        this.bbsFbx$shapeKeyNames = names == null ? null : Set.copyOf(names);
    }

    @Inject(method = "getShapeKeys", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$shapeKeysFromFbxData(CallbackInfoReturnable<Set<String>> info)
    {
        if (this.bbsFbx$shapeKeyNames != null)
        {
            info.setReturnValue(this.bbsFbx$shapeKeyNames);
        }
    }
}
