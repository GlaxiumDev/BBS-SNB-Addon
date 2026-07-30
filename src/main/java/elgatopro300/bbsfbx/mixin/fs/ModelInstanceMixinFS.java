package elgatopro300.bbsfbx.mixin.fs;

import elgatopro300.bbsfbx.model.fbx.loaders.IShapeKeyHolder;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.colors.Color;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * FS variant of the shape-key hook-up redirect. See
 * {@link elgatopro300.bbsfbx.mixin.cml.ModelInstanceMixinCML} for the full
 * explanation of why three copies exist.
 *
 * <p>Confirmed directly against {@code Wemppy4/bbs-fs}'s real source:
 * {@code ModelInstance.render(...)} (java line 654) takes
 * {@code Function<String, Link> textureResolver} as its final parameter, and
 * its one call to {@code BOBJModelVAO.updateMesh(stencilMap)} (line 720)
 * sits directly in {@code render()}'s own body -- inside a
 * {@code for (BOBJModelVAO vao : vaos)} loop, but not delegated to a helper
 * method -- so this {@code @Redirect} reaches it correctly.</p>
 */
@Mixin(value = ModelInstance.class, remap = false)
public class ModelInstanceMixinFS
{
    @Redirect(
            method = "render(Lnet/minecraft/client/util/math/MatrixStack;Ljava/util/function/Supplier;Lmchorse/bbs_mod/utils/colors/Color;IILmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;Lmchorse/bbs_mod/obj/shapes/ShapeKeys;Ljava/util/function/Function;)V",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/cubic/render/vao/BOBJModelVAO;updateMesh(Lmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;)V"),
            remap = false
    )
    private void bbsFbx$redirectUpdateMesh(
            BOBJModelVAO vao, StencilMap stencilMap,
            MatrixStack stack, Supplier<ShaderProgram> program, Color color,
            int light, int overlay, StencilMap stencilMap2, ShapeKeys keys,
            Function<String, Link> textureResolver)
    {
        if (vao instanceof IShapeKeyHolder holder)
        {
            holder.bbsFbx$setShapeKeys(keys);
        }
        vao.updateMesh(stencilMap);
    }
}
