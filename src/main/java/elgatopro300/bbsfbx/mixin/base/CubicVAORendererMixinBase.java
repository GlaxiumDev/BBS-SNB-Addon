package elgatopro300.bbsfbx.mixin.base;

import elgatopro300.bbsfbx.render.MaterialTextureDelegate;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.CubicVAORenderer;
import mchorse.bbs_mod.resources.Link;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Base variant of per-material rendering on the cubic VAO path. Base's
 * {@code CubicVAORenderer.renderGroup} binds no texture of its own -- the
 * caller ({@code ModelFormRenderer.renderModel}) binds one base texture for
 * the whole model and every group's VAO draw uses whatever is bound. For OBJ
 * models loaded with one {@code ModelGroup} per material
 * ({@code CubicModelLoaderMixinBaseCML}) that means every material draws
 * with the same base texture.
 *
 * <p>This binds the group's own material texture right before the draw: the
 * current Form's per-material override first, else the material's loaded
 * default ({@link MaterialTextureDelegate#resolveMaterialTexture}), matching
 * the multi-material FBX renderers ({@code BOBJModelVAOMixinBase} etc.) and
 * BBS FS's native OBJ loader (which also ignores the material system for
 * models with at most one material -- those keep the base texture).</p>
 *
 * <p>Gated to Base by {@code BBSFbxMixinPlugin}; CML's own
 * {@code CubicVAORenderer} already binds a texture natively and gets its own
 * mixin ({@code elgatopro300.bbsfbx.mixin.cml.CubicVAORendererMixinCML}).</p>
 */
@Mixin(value = CubicVAORenderer.class, remap = false)
public abstract class CubicVAORendererMixinBase
{
    @Shadow private ModelInstance model;

    @Inject(
            method = "renderGroup(Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/cubic/data/model/ModelGroup;Lmchorse/bbs_mod/cubic/data/model/Model;)Z",
            at = @At("HEAD"), remap = false
    )
    private void bbsFbx$bindMaterialTexture(
            BufferBuilder builder, MatrixStack stack, ModelGroup group, Model model,
            CallbackInfoReturnable<Boolean> cir)
    {
        if (group == null || this.model == null)
        {
            return;
        }

        IModel iModel = this.model.model;

        if (iModel == null || MaterialTextureDelegate.getMaterials(iModel).size() <= 1)
        {
            return;
        }

        if (!MaterialTextureDelegate.isMaterial(iModel, group.id))
        {
            return;
        }

        Link resolved = MaterialTextureDelegate.resolveMaterialTexture(iModel, group.id);

        if (resolved != null)
        {
            BBSModClient.getTextures().bindTexture(resolved);
        }
    }
}
