package elgatopro300.bbsfbx.mixin.base;

import elgatopro300.bbsfbx.render.CurrentMaterialTextureOverrides;
import elgatopro300.bbsfbx.render.MultiMaterialTriangleDraw;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXCompiledData;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.cubic.render.vao.Attributes;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.util.math.MatrixStack;

import org.lwjgl.opengl.GL30;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Base variant of multi-material FBX rendering. See
 * {@link elgatopro300.bbsfbx.mixin.fs.BOBJModelVAOMixinFS} for the FS
 * variant, and {@link elgatopro300.bbsfbx.mixin.cml.BOBJModelVAOMixinCML}
 * for CML's.
 *
 * <p>Base's compiled 1.7.7-1.20.4 jar only has ONE
 * {@code render(ShaderProgram, MatrixStack, ...)} overload on
 * {@code BOBJModelVAO} (confirmed: unlike FS's real source, there's no
 * second no-{@code MatrixStack} overload it delegates to), and its
 * descriptor is confirmed byte-for-byte below. {@code ModelVAORenderer} on
 * Base was also checked directly: it only has
 * {@code setupUniforms(MatrixStack, ShaderProgram)} -- no
 * {@code captureModelView}, no 3-arg {@code setupUniforms(ShaderProgram,
 * Matrix4f, Matrix3f)} the way FS has -- which is also exactly what CML's
 * still-separate {@code render(..., Link)} overload already calls, so this
 * uses the same confirmed call.</p>
 *
 * <p>What's <em>not</em> independently decompiled is the rest of the method
 * body -- the exact vertex-attribute enable/disable sequence below is
 * carried over from FS's real (confirmed) source and CML's already-working
 * mixin, both of which follow the identical sequence, rather than from a
 * decompile of Base's own bytecode. If FBX multi-material rendering
 * silently doesn't work on Base (model draws, but always with one texture,
 * never per-material) rather than crashing outright, that sequence not
 * matching Base's real one is the first thing to check -- ideally by
 * decompiling {@code BOBJModelVAO.class} straight out of your own Base jar
 * with a tool like Vineflower/CFR.</p>
 *
 * <p>The "no override for this material" fallback deliberately uses raw GL
 * ({@code glGetInteger(GL_TEXTURE_BINDING_2D)} captured up front, plain
 * {@code glBindTexture} to restore it) instead of
 * {@code BBSModClient.getTextures().getLastBound()} -- that method doesn't
 * exist at all in the actual 1.7.7-1.20.4 jar (confirmed directly; it's
 * only in {@code Wemppy4/bbs-fs}'s current source, same version-drift
 * pattern as {@code BOBJArmature.copy()}/{@code ModelInstance.color}
 * elsewhere in this addon), so this avoids depending on it.</p>
 */
@Mixin(value = BOBJModelVAO.class, remap = false)
public abstract class BOBJModelVAOMixinBase
{
    @Shadow public BOBJLoader.CompiledData data;
    @Shadow private int vao;

    @Inject(
            method = "render(Lnet/minecraft/client/gl/ShaderProgram;Lnet/minecraft/client/util/math/MatrixStack;FFFFLmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;II)V",
            at = @At("HEAD"), cancellable = true, remap = false
    )
    private void bbsFbx$renderPerMaterial(
            ShaderProgram shader, MatrixStack stack, float r, float g, float b, float a,
            StencilMap stencilMap, int light, int overlay,
            CallbackInfo info)
    {
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

        int previousTexture = GL30.glGetInteger(GL30.GL_TEXTURE_BINDING_2D);
        int[] dominant = MultiMaterialTriangleDraw.computeDominantMaterialPerTriangle(fbxData.materialIndexData);
        boolean hasShaders = BBSRendering.isIrisShadersEnabled();

        GL30.glVertexAttrib4f(Attributes.COLOR, r, g, b, a);
        GL30.glVertexAttribI2i(Attributes.OVERLAY_UV, overlay & '\uffff', overlay >> 16 & '\uffff');
        GL30.glVertexAttribI2i(Attributes.LIGHTMAP_UV, light & '\uffff', light >> 16 & '\uffff');

        int currentVAO = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int currentElementArrayBuffer = GL30.glGetInteger(GL30.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        ModelVAORenderer.setupUniforms(stack, shader);

        shader.bind();

        GL30.glBindVertexArray(this.vao);

        GL30.glEnableVertexAttribArray(Attributes.POSITION);
        GL30.glEnableVertexAttribArray(Attributes.TEXTURE_UV);
        GL30.glEnableVertexAttribArray(Attributes.NORMAL);

        if (hasShaders) GL30.glEnableVertexAttribArray(Attributes.TANGENTS);
        if (hasShaders) GL30.glEnableVertexAttribArray(Attributes.MID_TEXTURE_UV);

        String[] materialNames = fbxData.materialNames;
        java.util.Map<String, Link> overrides = CurrentMaterialTextureOverrides.current();

        for (int m = 0; m < materialNames.length; m++)
        {
            Link texture = overrides.get(materialNames[m]);

            if (texture != null)
            {
                // .bind(), not .bindTexture() -- the latter only calls RenderSystem.setShaderTexture,
                // which is for vanilla's deferred render pipeline. This custom raw-GL draw loop needs
                // an actual immediate glBindTexture per material, which only .bind() does. This was
                // the actual bug behind "buttons work, texture picks correctly, but the model still
                // shows one texture" -- confirmed by reading TextureManager's real source directly:
                // bindTexture(Link) just stores state for later, .bind(Link) calls Texture.bind(),
                // which does GL11.glBindTexture(...) right then. Same bug and same fix applied to
                // mixin.cml.BOBJModelVAOMixinCML, which had the identical mistake.
                BBSModClient.getTextures().bind(texture);
            }
            else
            {
                GL30.glBindTexture(GL30.GL_TEXTURE_2D, previousTexture);
            }

            MultiMaterialTriangleDraw.drawTrianglesForMaterial(dominant, m);
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
}
