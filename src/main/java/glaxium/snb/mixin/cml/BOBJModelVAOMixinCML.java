package glaxium.snb.mixin.cml;

import glaxium.snb.model.fbx.loaders.FBXCompiledData;
import glaxium.snb.render.CurrentMaterialPbrOverrides;
import glaxium.snb.render.MaterialPbrIntensity;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.cubic.render.vao.Attributes;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.util.math.MatrixStack;

import com.mojang.blaze3d.systems.RenderSystem;

import org.lwjgl.opengl.GL30;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws multi-material FBX models (see {@link FBXCompiledData#hasMultipleMaterials()})
 * with one texture bind + draw call per material instead of a single texture
 * for the whole mesh. Reuses {@code BOBJModelVAO}'s own private
 * {@code bindDrawTexture}/{@code rebindShaderSamplers} methods via
 * {@code @Invoker} - the same ones CML's native per-bone texture override
 * already uses (see the {@code fullOverrides} loop in the real
 * {@code render()}) - but NOT its private {@code drawTriangles}, since that
 * method reads the host's own bone-keyed array internally rather than
 * taking one as a parameter; {@link glaxium.snb.render.MultiMaterialTriangleDraw#drawRuns}
 * draws from {@link glaxium.snb.model.fbx.loaders.FBXCompiledData
 * #getMaterialDrawRuns()}'s precomputed material-keyed run ranges instead.
 *
 * <p>The @Inject below cancels the original method entirely and re-runs its
 * setup/teardown by hand, copied line-for-line from BBS CML EDITION
 * 2.0-beta-1-1.20.4's real {@code render()} (confirmed directly against that
 * source) - only the single {@code GL30.glDrawArrays(...)} draw call in the
 * no-override branch is replaced with a per-material loop. This only
 * triggers for the stencil-pick-free branch on models this addon flagged as
 * multi-material; every other model (including single-material FBX models,
 * and anything on Base/FS) goes through the host's completely untouched
 * {@code render()} exactly as before.</p>
 *
 * <p>CML-only: this reuses private internals confirmed only against BBS CML
 * EDITION's real {@code BOBJModelVAO.java} - Base and FS were never checked
 * for this exact structure, and aren't touched by this class at all.</p>
 */
@Mixin(value = BOBJModelVAO.class, remap = false)
public abstract class BOBJModelVAOMixinCML
{
    @Shadow public BOBJLoader.CompiledData data;
    @Shadow public BOBJArmature armature;
    @Shadow private int vao;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$renderPerMaterial(
            ShaderProgram shader, MatrixStack stack, float r, float g, float b, float a,
            StencilMap stencilMap, int light, int overlay, Link defaultTexture,
            CallbackInfo info)
    {
        /* Stencil-pick passes need every triangle drawn regardless of texture (same as the
         * host's own bone-override branch already special-cases by skipping straight to a
         * full-mesh draw) - leave those, and every non-multi-material model, to the original
         * method untouched. */
        if (stencilMap != null || !(this.data instanceof FBXCompiledData fbxData) || !fbxData.hasMultipleMaterials())
        {
            return;
        }

        if (this.vao == 0 || !GL30.glIsVertexArray(this.vao))
        {
            info.cancel();
            return;
        }

        info.cancel();

        // Precomputed once and cached on FBXCompiledData -- not the per-VAO
        // cache of the raw per-triangle array this used to keep locally (see
        // git history): that still left an O(triangles) scan per material
        // PER RENDER CALL to find contiguous runs, which testing showed was
        // the actual remaining per-frame cost (FPS scaled with material
        // count, not texture count). getMaterialDrawRuns() precomputes the
        // run boundaries themselves in one pass, once, so rendering is just
        // replaying fixed ranges -- shared with the Base/FS mixins' fix too.
        int[][] materialRuns = fbxData.getMaterialDrawRuns();

        boolean hasShaders = BBSRendering.isIrisShadersEnabled();

        int currentVAO = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int currentElementArrayBuffer = GL30.glGetInteger(GL30.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        if (defaultTexture != null)
        {
            BBSModClient.getTextures().bindTexture(defaultTexture);
        }

        ModelVAORenderer.setupUniforms(stack, shader);

        RenderSystem.setShader(() -> shader);
        shader.bind();
        bbsFbx$uploadColorGrade();

        GL30.glBindVertexArray(this.vao);

        GL30.glDisableVertexAttribArray(Attributes.COLOR);
        GL30.glDisableVertexAttribArray(Attributes.OVERLAY_UV);
        GL30.glDisableVertexAttribArray(Attributes.LIGHTMAP_UV);

        GL30.glVertexAttrib4f(Attributes.COLOR, r, g, b, a);
        GL30.glVertexAttribI2i(Attributes.OVERLAY_UV, overlay & '\uffff', overlay >> 16 & '\uffff');
        GL30.glVertexAttribI2i(Attributes.LIGHTMAP_UV, light & '\uffff', light >> 16 & '\uffff');

        GL30.glEnableVertexAttribArray(Attributes.POSITION);
        GL30.glEnableVertexAttribArray(Attributes.TEXTURE_UV);
        GL30.glEnableVertexAttribArray(Attributes.NORMAL);

        if (hasShaders) GL30.glEnableVertexAttribArray(Attributes.TANGENTS);
        if (hasShaders) GL30.glEnableVertexAttribArray(Attributes.MID_TEXTURE_UV);

        String[] materialNames = fbxData.materialNames;
        Link[] materialTextures = fbxData.materialTextures;
        java.util.Map<String, Link> overrides = glaxium.snb.render.CurrentMaterialTextureOverrides.current();
        java.util.Map<String, MaterialPbrIntensity> pbrOverrides = CurrentMaterialPbrOverrides.current();
        MaterialPbrIntensity base = CurrentMaterialPbrOverrides.currentBase();

        for (int m = 0; m < materialNames.length; m++)
        {
            Link override = overrides.get(materialNames[m]);
            Link sharedDefault = materialTextures != null && m < materialTextures.length ? materialTextures[m] : null;
            Link toUse = override != null ? override : (sharedDefault != null ? sharedDefault : defaultTexture);

            MaterialPbrIntensity pbr = pbrOverrides.get(materialNames[m]);

            if (pbr != null)
            {
                bbsFbx$setPbrIntensity(
                        pbr.normal != null ? pbr.normal : (base.normal != null ? base.normal : 1.0F),
                        pbr.specular != null ? pbr.specular : (base.specular != null ? base.specular : 1.0F));
            }

            // bindTexture(), not .bind(): the raw .bind() only calls glBindTexture, which bypasses
            // Iris's PBR hook -- Iris applies the _n/_s companion maps as a side effect of
            // RenderSystem.setShaderTexture (its onSetShaderTexture mixin), so a material bound
            // only through raw GL never gets its _s specular map. bindTexture() also snapshots the
            // active PBR intensity against the texture (BBSRendering.trackTexture, with the whole-
            // model intensity from ModelFormRenderer.applyPBRTextureIntensity active when no per-
            // material override was staged above) -- the same mechanism the native bindDrawTexture
            // uses, which is why single-texture models get _s.
            BBSModClient.getTextures().bindTexture(toUse);

            // Assert active unit 0 before the raw bind: setShaderTexture only records the tracked
            // value, so the actual GL bind is Texture.bind() -> glBindTexture on the CURRENTLY
            // ACTIVE unit. shader.bind() leaves a non-zero unit active when Sodium/Iris is present,
            // stranding the material texture on unit 1 while Sampler0 samples unit 0 (still the
            // caller's default/body texture) -- hair rendered black. Same fix as
            // mixin.base.BOBJModelVAOMixinBase.
            GL30.glActiveTexture(GL30.GL_TEXTURE0);
            BBSModClient.getTextures().bind(toUse);

            glaxium.snb.render.MultiMaterialTriangleDraw.drawRuns(materialRuns[m]);

            // Restore the whole-model intensity right after each override-staged material, so a
            // material without its own override (pbr == null above, where the caller's staged
            // whole-model intensity applies) never inherits a neighbour material's override.
            bbsFbx$setPbrIntensity(base.normal != null ? base.normal : 1.0F, base.specular != null ? base.specular : 1.0F);
        }

        GL30.glDisableVertexAttribArray(Attributes.POSITION);
        GL30.glDisableVertexAttribArray(Attributes.TEXTURE_UV);
        GL30.glDisableVertexAttribArray(Attributes.NORMAL);

        if (hasShaders) GL30.glDisableVertexAttribArray(Attributes.TANGENTS);
        if (hasShaders) GL30.glDisableVertexAttribArray(Attributes.MID_TEXTURE_UV);

        shader.unbind();

        GL30.glBindVertexArray(currentVAO);
        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, currentElementArrayBuffer);
    }

    /**
     * {@code FormColorGradePatch} exists on the {@code 1.20.4} branch of BBS
     * CML EDITION's source (confirmed directly, called unconditionally right
     * after {@code shader.bind()} in the real {@code render()}) but
     * apparently isn't present in every distributed CML jar build - same
     * kind of version drift as {@code BOBJArmature.copy()}/
     * {@code ModelInstance.color} earlier, just for a newer/more obscure
     * corner of the API (an Iris shader color-grading patch). Routed
     * through reflection so this compiles regardless of which specific CML
     * jar build is on the classpath; if it's missing, Iris color grading
     * simply won't apply to multi-material draws on that build - same
     * outcome as any other custom render path that doesn't call it either.
     */
    private static void bbsFbx$uploadColorGrade()
    {
        try
        {
            Class<?> patch = Class.forName("mchorse.bbs_mod.utils.iris.FormColorGradePatch");

            patch.getMethod("uploadToCurrentProgram").invoke(null);
        }
        catch (ReflectiveOperationException ignored)
        {
            // Not present on this CML build - nothing to do.
        }
    }

    /**
     * Stages the Iris thread-local active PBR intensity (normal + specular)
     * for the next trackTexture snapshot. CML-only API, invoked reflectively
     * so this class still compiles against Base/FS. Goes through
     * {@code BBSRendering.setPBRTextureIntensity} rather than loading
     * {@code IrisUtils} directly: the call chain mirrors what CML's own
     * {@code ModelFormRenderer.applyPBRTextureIntensity} does every render
     * and works fine without the Iris mod installed, whereas
     * {@code Class.forName("IrisUtils")} would force initialization of a
     * class that references Iris classes absent from the classpath.
     */
    private static void bbsFbx$setPbrIntensity(float normal, float specular)
    {
        try
        {
            BBSRendering.class.getMethod("setPBRTextureIntensity", float.class, float.class)
                    .invoke(null, normal, specular);
        }
        catch (ReflectiveOperationException ignored)
        {
            // Not present on this CML build - nothing to do.
        }
    }
}