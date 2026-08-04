package elgatopro300.bbsfbx.mixin.base;

import elgatopro300.bbsfbx.mixin.CubicCubeRendererAccessor;
import elgatopro300.bbsfbx.render.IModelInstanceMaterialVaos;
import elgatopro300.bbsfbx.render.MaterialTextureDelegate;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.CubicVAORenderer;
import mchorse.bbs_mod.cubic.render.vao.ModelVAO;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Lerps;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Base variant of per-material rendering on the cubic VAO path. Base's
 * {@code CubicVAORenderer.renderGroup} binds no texture of its own and draws
 * one merged VAO per group, so an OBJ model loaded with one
 * {@code ModelGroup} per object (many materials inside) could only ever draw
 * with a single texture.
 *
 * <p>OBJ models get per-material VAOs instead ({@code CubicVAOBucketingBuilder},
 * stored by {@code ModelInstanceVAOMixin}); for those this replaces
 * {@code renderGroup}: it draws one VAO per material, binding each material's
 * resolved texture first ({@link MaterialTextureDelegate#resolveMaterialTexture}:
 * current Form's per-material override, else the material's loaded default),
 * with the same per-group color/light as the native draw. Non-OBJ groups keep
 * the native path untouched. Gated to Base by {@code BBSFbxMixinPlugin}; CML
 * gets its own mixin ({@code CubicVAORendererMixinCML}).</p>
 */
@Mixin(value = CubicVAORenderer.class, remap = false)
public abstract class CubicVAORendererMixinBase
{
    @Shadow private ModelInstance model;
    @Shadow private ShaderProgram program;

    @Inject(
            method = "renderGroup(Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/client/util/math/MatrixStack;Lmchorse/bbs_mod/cubic/data/model/ModelGroup;Lmchorse/bbs_mod/cubic/data/model/Model;)Z",
            at = @At("HEAD"), cancellable = true, remap = false
    )
    private void bbsFbx$renderPerMaterial(
            BufferBuilder builder, MatrixStack stack, ModelGroup group, Model model,
            CallbackInfoReturnable<Boolean> cir)
    {
        if (this.model == null || group == null)
        {
            return;
        }

        Map<String, ModelVAO> groupVaos = ((IModelInstanceMaterialVaos) this.model).bbsFbx$getMaterialVaos().get(group);

        if (groupVaos == null || groupVaos.isEmpty())
        {
            return;
        }

        IModel iModel = this.model.model;

        if (iModel == null)
        {
            cir.cancel();

            return;
        }

        CubicCubeRendererAccessor accessor = (CubicCubeRendererAccessor) (Object) this;

        float r = accessor.bbsFbx$getR() * group.color.r;
        float g = accessor.bbsFbx$getG() * group.color.g;
        float b = accessor.bbsFbx$getB() * group.color.b;
        float a = accessor.bbsFbx$getA() * group.color.a;
        int light = accessor.bbsFbx$getLight();
        StencilMap stencilMap = accessor.bbsFbx$getStencilMap();

        if (stencilMap != null)
        {
            light = stencilMap.increment ? group.index : 0;
        }
        else
        {
            int u = (int) Lerps.lerp(light & '\uffff', LightmapTextureManager.MAX_BLOCK_LIGHT_COORDINATE, MathUtils.clamp(group.lighting, 0F, 1F));
            int v = light >> 16 & '\uffff';

            light = u | v << 16;
        }

        /* One draw per material, each with its material's texture bound. */
        for (Map.Entry<String, ModelVAO> entry : groupVaos.entrySet())
        {
            Link resolved = MaterialTextureDelegate.resolveMaterialTexture(iModel, entry.getKey());

            if (resolved != null)
            {
                BBSModClient.getTextures().bindTexture(resolved);
            }

            ModelVAORenderer.render(this.program, entry.getValue(), stack, r, g, b, a, light, accessor.bbsFbx$getOverlay());
        }

        cir.cancel();
    }
}
