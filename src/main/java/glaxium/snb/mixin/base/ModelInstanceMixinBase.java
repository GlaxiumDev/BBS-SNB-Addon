package glaxium.snb.mixin.base;

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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Supplier;

/**
 * Base variant of the shape-key hook-up redirect. See
 * {@link glaxium.snb.mixin.cml.ModelInstanceMixinCML} for the full
 * explanation of why three copies exist.
 *
 * <p>Confirmed directly against {@code bbs-1.7.7-1.20.4.jar}:
 * {@code ModelInstance.render(...)} on Base has NO trailing texture
 * parameter at all -- just
 * {@code (MatrixStack, Supplier<ShaderProgram>, Color, int, int, StencilMap, ShapeKeys)}.
 * That is one parameter shorter than both FS and CML, which is why this is
 * its own class rather than a shared one with an optional last argument.</p>
 *
 * <p>Also implements {@link IMaterialTextureHolder} -- this was missing from
 * the first version of this class, which is why the multi-material "pick
 * texture" menu never appeared on Base even after
 * {@code UIModelFormPanelMixin} was un-gated to run there: that menu checks
 * {@code model instanceof IMaterialTextureHolder}, and without this,
 * {@code ModelInstance} never was one on Base, so the check always failed
 * and it silently fell back to the plain single-texture picker. Delegates
 * to {@link MaterialTextureDelegate} rather than duplicating
 * {@code ModelInstanceMixinCML}'s own copy of this logic.</p>
 */
@Mixin(value = ModelInstance.class, remap = false)
public abstract class ModelInstanceMixinBase implements IMaterialTextureHolder
{
    @Shadow public IModel model;
    @Shadow public String id;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$renderLegacyBB(
            MatrixStack stack, Supplier<ShaderProgram> program, Color color,
            int light, int overlay, StencilMap stencilMap, ShapeKeys keys,
            CallbackInfo ci)
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
            int light, int overlay, StencilMap stencilMap2, ShapeKeys keys)
    {
        if (vao instanceof IShapeKeyHolder holder)
        {
            holder.bbsFbx$setShapeKeys(keys);
        }
        vao.updateMesh(stencilMap);
    }

    @Override
    public List<String> bbsFbx$getMaterials()
    {
        return MaterialTextureDelegate.getMaterials(this.model);
    }

    @Override
    public Link bbsFbx$getDefaultMaterialTexture(String material)
    {
        return MaterialTextureDelegate.getDefaultMaterialTexture(this.model, material);
    }

}
