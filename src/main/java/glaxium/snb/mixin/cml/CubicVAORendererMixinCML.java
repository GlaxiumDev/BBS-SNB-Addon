package glaxium.snb.mixin.cml;

import glaxium.snb.mixin.CubicCubeRendererAccessor;
import glaxium.snb.render.CMLRenderCompat;
import glaxium.snb.render.CurrentMaterialPbrOverrides;
import glaxium.snb.render.IModelInstanceMaterialVaos;
import glaxium.snb.render.MaterialPbrIntensity;
import glaxium.snb.render.MaterialTextureDelegate;

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
import mchorse.bbs_mod.utils.colors.Color;
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
 * CML variant of per-material rendering on the cubic VAO path. CML's native
 * {@code CubicVAORenderer.renderGroup} binds one texture per group
 * ({@code group.textureOverride}, else {@code defaultTexture}, else
 * {@code model.texture}) and draws one merged VAO, so an OBJ model loaded
 * with one {@code ModelGroup} per object (many materials inside) could only
 * ever draw with a single texture.
 *
 * <p>OBJ models get per-material VAOs instead ({@code CubicVAOBucketingBuilder},
 * stored by {@code ModelInstanceVAOMixin}); for those this replaces
 * {@code renderGroup}: it draws one VAO per material, binding each material's
 * resolved texture first ({@link MaterialTextureDelegate#resolveMaterialTexture}:
 * current Form's per-material override, else the material's loaded default),
 * with the same per-group color/light/glow/paint/color-grade as the native
 * draw ({@link CMLRenderCompat}, resolved reflectively so this still compiles
 * against the released CML 2.0-beta-1 jar and Base, which predate those
 * members -- on such forks the glow/paint/grade calls are no-ops and the
 * historical behavior is preserved). Non-OBJ groups keep the native path
 * (their merged VAO draw + native texture binding) untouched. Gated to CML by
 * {@code BBSFbxMixinPlugin}.</p>
 *
 * <p>Note: CML's native {@code renderGroup} also culls non-hovered groups in
 * the pose editor via {@code StencilMap.isBoneAllowed} -- a CML-only API
 * that isn't present on the other forks this addon compiles against, so the
 * per-material path intentionally skips it (Base has no such cull either);
 * pose-editor picking of OBJ models on CML therefore behaves like Base.</p>
 */
@Mixin(value = CubicVAORenderer.class, remap = false)
public abstract class CubicVAORendererMixinCML
{
    @Shadow private ModelInstance model;
    @Shadow private ShaderProgram program;

    @Inject(
            method = "renderGroup",
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

        if (!group.visible)
        {
            cir.cancel();

            return;
        }

        IModel iModel = this.model.model;

        if (iModel == null)
        {
            cir.cancel();

            return;
        }

        CubicCubeRendererAccessor accessor = (CubicCubeRendererAccessor) (Object) this;

        float r;
        float g;
        float b;
        float a;

        if (CMLRenderCompat.hasActiveTransform(group.color))
        {
            r = accessor.bbsFbx$getR();
            g = accessor.bbsFbx$getG();
            b = accessor.bbsFbx$getB();
            a = accessor.bbsFbx$getA();
        }
        else
        {
            r = accessor.bbsFbx$getR() * group.color.r;
            g = accessor.bbsFbx$getG() * group.color.g;
            b = accessor.bbsFbx$getB() * group.color.b;
            a = accessor.bbsFbx$getA() * group.color.a;
        }

        float effectiveGlowStrength = CMLRenderCompat.resolveGlowStrength(group);
        float effectiveGlowR = CMLRenderCompat.resolveGlowR(group);
        float effectiveGlowG = CMLRenderCompat.resolveGlowG(group);
        float effectiveGlowB = CMLRenderCompat.resolveGlowB(group);
        float effectivePaintStrength = CMLRenderCompat.resolvePaintStrength(group);
        float effectivePaintR = CMLRenderCompat.resolvePaintR(group);
        float effectivePaintG = CMLRenderCompat.resolvePaintG(group);
        float effectivePaintB = CMLRenderCompat.resolvePaintB(group);

        if (!CMLRenderCompat.isGlowingUniformActive())
        {
            if (effectiveGlowStrength != 0F)
            {
                Color groupColor = new Color().set(r, g, b, a);
                Color glowColor = new Color().set(effectiveGlowR, effectiveGlowG, effectiveGlowB, 1F);

                CMLRenderCompat.blendBrighten(groupColor, glowColor, effectiveGlowStrength);

                r = groupColor.r;
                g = groupColor.g;
                b = groupColor.b;
                a = groupColor.a;
            }
        }

        int light = accessor.bbsFbx$getLight();

        if (effectiveGlowStrength != 0F && !CMLRenderCompat.isGlowingUniformActive() && !CMLRenderCompat.isPaintOverlayPass())
        {
            float glowLightT = MathUtils.clamp(Math.abs(effectiveGlowStrength), 0F, 1F);
            int baseU = light & '\uffff';
            int u = (int) Lerps.lerp(baseU, LightmapTextureManager.MAX_BLOCK_LIGHT_COORDINATE, glowLightT);
            int v = light >> 16 & '\uffff';

            light = u | v << 16;
        }

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
            String material = entry.getKey();
            Link resolved = MaterialTextureDelegate.resolveMaterialTexture(iModel, material);

            /* Stage this material's own PBR intensity (CML film-editor channel) so the bindTexture
             * below snapshots it against the texture for Iris' _n/_s loaders, mirroring the BOBJ
             * multi-material loop. With no override the whole-model intensity (staged by the native
             * ModelFormRenderer around this render) stays active. */
            MaterialPbrIntensity pbr = CurrentMaterialPbrOverrides.current().get(material);

            if (pbr != null)
            {
                MaterialPbrIntensity base = CurrentMaterialPbrOverrides.currentBase();

                CMLRenderCompat.stagePbrIntensity(
                        pbr.normal != null ? pbr.normal : (base.normal != null ? base.normal : 1.0F),
                        pbr.specular != null ? pbr.specular : (base.specular != null ? base.specular : 1.0F));
            }

            if (resolved != null)
            {
                BBSModClient.getTextures().bindTexture(resolved);
            }

            CMLRenderCompat.setGroupPaint(effectivePaintR, effectivePaintG, effectivePaintB, effectivePaintStrength);
            CMLRenderCompat.setGroupPaintEffectTransform(CMLRenderCompat.paintColorTransform(group));
            CMLRenderCompat.setGroupGlowing(effectiveGlowR, effectiveGlowG, effectiveGlowB, effectiveGlowStrength);
            CMLRenderCompat.setGroupGlowEffectTransform(CMLRenderCompat.glowingColorTransform(group));
            CMLRenderCompat.setGroupFormColorGrade(group.color);
            CMLRenderCompat.setGroupColorEffectTransform(CMLRenderCompat.colorTransform(group.color));
            CMLRenderCompat.setGroupFormColorTint(group.color);

            ModelVAORenderer.render(this.program, entry.getValue(), stack, r, g, b, a, light, accessor.bbsFbx$getOverlay());

            /* Restore the whole-model intensity so the next material (or a non-overridden one) never
             * inherits this override. */
            if (pbr != null)
            {
                MaterialPbrIntensity base = CurrentMaterialPbrOverrides.currentBase();

                CMLRenderCompat.stagePbrIntensity(
                        base.normal != null ? base.normal : 1.0F,
                        base.specular != null ? base.specular : 1.0F);
            }
        }

        CMLRenderCompat.clearTextureBlend();

        cir.cancel();
    }
}
