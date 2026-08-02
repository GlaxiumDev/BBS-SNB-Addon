package elgatopro300.bbsfbx.mixin.cml;

import elgatopro300.bbsfbx.model.fbx.loaders.FBXCompiledData;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.cubic.render.vao.Attributes;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
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
 * taking one as a parameter; {@link #bbsFbx$drawTrianglesForMaterial} is a
 * faithful copy of its contiguous-run algorithm reading a material-keyed
 * array instead.
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

    private int[] bbsFbx$dominantMaterialPerTriangle;
    private FBXCompiledData bbsFbx$dominantMaterialSource;

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

        this.bbsFbx$ensureDominantMaterial(fbxData);

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
        java.util.Map<String, Link> overrides = elgatopro300.bbsfbx.render.CurrentMaterialTextureOverrides.current();

        for (int m = 0; m < materialNames.length; m++)
        {
            Link override = overrides.get(materialNames[m]);
            Link sharedDefault = materialTextures != null && m < materialTextures.length ? materialTextures[m] : null;
            Link toUse = override != null ? override : (sharedDefault != null ? sharedDefault : defaultTexture);

            // .bind(), not .bindTexture() -- the latter only calls RenderSystem.setShaderTexture,
            // which is for vanilla's deferred render pipeline. This custom raw-GL draw loop needs
            // an actual immediate glBindTexture per material, which only .bind() does.
            BBSModClient.getTextures().bind(toUse);

            this.bbsFbx$drawTrianglesForMaterial(m);
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
     * Faithful copy of the host's own {@code drawTriangles(IntPredicate)}
     * contiguous-run algorithm, reading {@link #bbsFbx$dominantMaterialPerTriangle}
     * instead of the host's private (bone-keyed) array - {@code @Invoker}
     * can't be used for this since the real method reads its own field
     * internally rather than taking an array as a parameter, so it has no
     * way to substitute a material-keyed one in.
     */
    private void bbsFbx$drawTrianglesForMaterial(int materialIndex)
    {
        int[] dominant = this.bbsFbx$dominantMaterialPerTriangle;
        int start = -1;

        for (int i = 0; i < dominant.length; i++)
        {
            boolean draw = dominant[i] == materialIndex;

            if (draw && start == -1)
            {
                start = i;
            }
            else if (!draw && start != -1)
            {
                GL30.glDrawArrays(GL30.GL_TRIANGLES, start * 3, (i - start) * 3);
                start = -1;
            }
        }

        if (start != -1)
        {
            GL30.glDrawArrays(GL30.GL_TRIANGLES, start * 3, (dominant.length - start) * 3);
        }
    }

    /**
     * Builds the per-triangle material lookup {@link #bbsFbx$drawTrianglesForMaterial}
     * reads from, using each triangle's first vertex's material index - safe
     * because every vertex belonging to one triangle came from the same
     * originating BOBJMesh/material in
     * {@code FBXMeshCompiler#compileMergedWithMaterials}. Cached per VAO
     * instance since neither the mesh data nor its material split change
     * after this VAO is constructed.
     */
    private void bbsFbx$ensureDominantMaterial(FBXCompiledData fbxData)
    {
        if (this.bbsFbx$dominantMaterialSource == fbxData && this.bbsFbx$dominantMaterialPerTriangle != null)
        {
            return;
        }

        int[] materialIndexData = fbxData.materialIndexData;
        int triangleCount = materialIndexData.length / 3;
        int[] dominant = new int[triangleCount];

        for (int triangle = 0; triangle < triangleCount; triangle++)
        {
            dominant[triangle] = materialIndexData[triangle * 3];
        }

        this.bbsFbx$dominantMaterialPerTriangle = dominant;
        this.bbsFbx$dominantMaterialSource = fbxData;
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
}