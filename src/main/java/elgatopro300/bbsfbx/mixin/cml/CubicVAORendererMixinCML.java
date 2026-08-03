package elgatopro300.bbsfbx.mixin.cml;

import elgatopro300.bbsfbx.render.MaterialTextureDelegate;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.CubicVAORenderer;
import mchorse.bbs_mod.graphics.texture.TextureManager;
import mchorse.bbs_mod.resources.Link;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CML variant of per-material rendering on the cubic VAO path. Unlike Base's
 * {@code CubicVAORenderer}, CML's natively binds a texture per group
 * ({@code group.textureOverride}, else {@code defaultTexture}, else
 * {@code model.texture}) via {@code TextureManager.bindTexture}. For OBJ
 * models loaded with one {@code ModelGroup} per material
 * ({@code CubicModelLoaderMixinBaseCML}) every material would otherwise draw
 * with that one group-level texture.
 *
 * <p>This redirects those native {@code bindTexture} calls so that, for a
 * material group, the bound texture is the group's own material texture
 * resolved like the FBX renderers do ({@link MaterialTextureDelegate#resolveMaterialTexture}:
 * current Form's override, else the material's loaded default) -- or nothing
 * when there is nothing to bind, leaving the base texture the caller bound
 * before the model draw, matching BBS FS's native OBJ loader. Non-material
 * groups keep the native binding untouched. Like the Base variant, models
 * with at most one material ignore the material system entirely.</p>
 *
 * <p>{@code renderGroup} is not reentrant, so a single {@code @Unique}
 * "current group" field (set by the HEAD inject, read by the redirect) is
 * safe. Gated to CML by {@code BBSFbxMixinPlugin}.</p>
 */
@Mixin(value = CubicVAORenderer.class, remap = false)
public abstract class CubicVAORendererMixinCML
{
    @Shadow private ModelInstance model;

    @Unique private ModelGroup bbsFbx$currentGroup;

    @Inject(
            method = "renderGroup(Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/cubic/data/model/ModelGroup;Lmchorse/bbs_mod/cubic/data/model/Model;)Z",
            at = @At("HEAD"), remap = false
    )
    private void bbsFbx$captureGroup(
            BufferBuilder builder, MatrixStack stack, ModelGroup group, Model model,
            CallbackInfoReturnable<Boolean> cir)
    {
        this.bbsFbx$currentGroup = group;
    }

    @Redirect(
            method = "renderGroup(Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/cubic/data/model/ModelGroup;Lmchorse/bbs_mod/cubic/data/model/Model;)Z",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/graphics/texture/TextureManager;bindTexture(Lmchorse/bbs_mod/resources/Link;)V"),
            remap = false
    )
    private void bbsFbx$bindMaterialTexture(TextureManager manager, Link link)
    {
        ModelGroup group = this.bbsFbx$currentGroup;

        if (group == null || this.model == null)
        {
            manager.bindTexture(link);

            return;
        }

        IModel iModel = this.model.model;

        if (iModel != null && MaterialTextureDelegate.getMaterials(iModel).size() > 1
                && MaterialTextureDelegate.isMaterial(iModel, group.id))
        {
            Link resolved = MaterialTextureDelegate.resolveMaterialTexture(iModel, group.id);

            if (resolved != null)
            {
                manager.bindTexture(resolved);
            }

            return;
        }

        manager.bindTexture(link);
    }
}
