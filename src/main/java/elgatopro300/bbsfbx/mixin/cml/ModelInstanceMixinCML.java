package elgatopro300.bbsfbx.mixin.cml;

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

import java.util.function.Supplier;

/**
 * CML variant of the shape-key hook-up redirect. This is the ONE place Base,
 * FS and CML genuinely disagree: {@code ModelInstance.render(...)} takes a
 * different final parameter on each --
 * {@link elgatopro300.bbsfbx.mixin.base.ModelInstanceMixinBase Base has no
 * texture parameter at all}, FS takes a
 * {@code Function<String, Link> textureResolver}
 * ({@link elgatopro300.bbsfbx.mixin.fs.ModelInstanceMixinFS}), and CML takes
 * a plain {@code Link defaultTexture} (this class). Everything past the
 * signature -- the redirected call and its target,
 * {@code BOBJModelVAO.updateMesh} -- is identical across all three, hence the
 * three copies changing only their {@code method} descriptor rather than
 * duplicating logic. {@link elgatopro300.bbsfbx.BBSFbxMixinPlugin} makes sure
 * only the variant matching the running fork is ever applied, so exactly one
 * of these three loads at a time.
 */
@Mixin(value = ModelInstance.class, remap = false)
public class ModelInstanceMixinCML
{
    @Redirect(
            method = "render(Lnet/minecraft/client/util/math/MatrixStack;Ljava/util/function/Supplier;Lmchorse/bbs_mod/utils/colors/Color;IILmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;Lmchorse/bbs_mod/obj/shapes/ShapeKeys;Lmchorse/bbs_mod/resources/Link;)V",
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
}
