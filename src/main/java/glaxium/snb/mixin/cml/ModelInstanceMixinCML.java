package glaxium.snb.mixin.cml;

import glaxium.snb.model.fbx.loaders.FBXCompiledData;
import glaxium.snb.model.fbx.loaders.IFbxModel;
import glaxium.snb.model.fbx.loaders.IMaterialTextureHolder;
import glaxium.snb.model.fbx.loaders.IShapeKeyHolder;
import glaxium.snb.render.MaterialTextureDelegate;
import glaxium.snb.model.blockbuster.LegacyBBModel;
import glaxium.snb.model.blockbuster.LegacyBBRenderer;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.colors.Color;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Supplier;

/**
 * CML variant of the shape-key hook-up redirect. This is the ONE place Base,
 * FS and CML genuinely disagree: {@code ModelInstance.render(...)} takes a
 * different final parameter on each --
 * {@link glaxium.snb.mixin.base.ModelInstanceMixinBase Base has no
 * texture parameter at all}, FS takes a
 * {@code Function<String, Link> textureResolver}
 * ({@link glaxium.snb.mixin.fs.ModelInstanceMixinFS}), and CML takes
 * a plain {@code Link defaultTexture} (this class). Everything past the
 * signature -- the redirected call and its target,
 * {@code BOBJModelVAO.updateMesh} -- is identical across all three, hence the
 * three copies changing only their {@code method} descriptor rather than
 * duplicating logic. {@link glaxium.snb.BBSFbxMixinPlugin} makes sure
 * only the variant matching the running fork is ever applied, so exactly one
 * of these three loads at a time.
 *
 * <p>Also implements {@link IMaterialTextureHolder} for the multi-material
 * FBX picker UI ({@code UIModelFormPanelMixin}). This is a pure
 * delegation layer -- it holds no state of its own, every method reads or
 * writes straight through to the {@link FBXCompiledData} backing this
 * instance's model, which is the SAME object reference
 * {@code BOBJModelVAOMixinCML} reads at render time, so a write here is
 * visible next frame with no extra plumbing needed.</p>
 */
@Mixin(value = ModelInstance.class, remap = false)
public abstract class ModelInstanceMixinCML implements IMaterialTextureHolder
{
    @Shadow public IModel model;
    @Shadow public String id;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$renderLegacyBB(
            MatrixStack stack, Supplier<ShaderProgram> program, Color color,
            int light, int overlay, StencilMap stencilMap, ShapeKeys keys,
            Link defaultTexture, CallbackInfo ci)
    {
        if (this.model instanceof LegacyBBModel legacy)
        {
            LegacyBBRenderer.render(legacy, stack, program, color, light, overlay, stencilMap);
            ci.cancel();
        }
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/cubic/render/vao/BOBJModelVAO;updateMesh(Lmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;)V"),
            remap = false
    )
    private void bbsFbx$redirectUpdateMesh(
            BOBJModelVAO vao, StencilMap stencilMap,
            MatrixStack stack, Supplier<ShaderProgram> program, Color color,
            int light, int overlay, StencilMap stencilMap2, ShapeKeys keys,
            Link defaultTexture)
    {
        if (vao instanceof IShapeKeyHolder holder)
        {
            holder.bbsFbx$setShapeKeys(keys);
        }
        vao.updateMesh(stencilMap);
    }

    @Unique
    private FBXCompiledData bbsFbx$materialData()
    {
        if (this.model instanceof IFbxModel fbxModel)
        {
            FBXCompiledData data = fbxModel.bbsFbx$getFbxData();

            if (data != null && data.hasMultipleMaterials())
            {
                return data;
            }
        }

        return null;
    }

    @Override
    public List<String> bbsFbx$getMaterials()
    {
        /* Delegate, not the raw materialNames list: the delegate excludes the
         * armor sidecar shells, which must never appear in the picker menu. */
        return MaterialTextureDelegate.getMaterials(this.model);
    }

    @Override
    public Link bbsFbx$getDefaultMaterialTexture(String material)
    {
        FBXCompiledData data = this.bbsFbx$materialData();

        if (data != null)
        {
            String[] names = data.materialNames;

            for (int i = 0; i < names.length; i++)
            {
                if (names[i].equals(material))
                {
                    return data.materialTextures != null && i < data.materialTextures.length ? data.materialTextures[i] : null;
                }
            }

            return null;
        }

        return MaterialTextureDelegate.getDefaultMaterialTexture(this.model, material);
    }

}
