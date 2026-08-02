package elgatopro300.bbsfbx.mixin.fs;

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
 * FS variant of multi-material FBX rendering. See
 * {@link elgatopro300.bbsfbx.mixin.base.BOBJModelVAOMixinBase} for the Base
 * variant and {@link elgatopro300.bbsfbx.mixin.cml.BOBJModelVAOMixinCML} for
 * CML's.
 *
 * <p><b>Revised from the first version of this class</b> -- that one
 * targeted FS's inner {@code render(ShaderProgram, Matrix4f, Matrix3f, ...)}
 * overload and called the 3-arg {@code ModelVAORenderer.setupUniforms(
 * ShaderProgram, Matrix4f, Matrix3f)}, both confirmed present in
 * {@code Wemppy4/bbs-fs}'s real source -- but a Fabric/Loom project only has
 * ONE host jar on its compile classpath at a time. Mixin's fork gating
 * ({@code BBSFbxMixinPlugin}) only controls what applies at RUNTIME; it
 * can't make a real Java method call to an FS-only API compile against a
 * classpath whose jar doesn't have that API, regardless of which fork the
 * class is meant for. Since the project builds against Base's jar, that
 * call failed outright at {@code javac} time, before gating ever got a
 * chance to matter.</p>
 *
 * <p>Fixed by targeting FS's OUTER {@code render(ShaderProgram, MatrixStack,
 * ...)} wrapper instead -- confirmed in the same real FS source to have the
 * exact same descriptor, parameter order, and body as this addon's Base
 * mixin (a plain delegating one-liner) -- and using only the 2-arg
 * {@code ModelVAORenderer.setupUniforms(MatrixStack, ShaderProgram)}, also
 * confirmed present in FS's real source (as a wrapper that internally does
 * the same {@code captureModelView}/{@code getNormalMatrix} work the 3-arg
 * one needs) AND in the actual Base jar this project compiles against. The
 * result is that this class and {@code BOBJModelVAOMixinBase} are now
 * functionally identical -- same target descriptor, same body -- kept as
 * two separate mixin classes only because {@code BBSFbxMixinPlugin} gates
 * by mixin package (base/fs/cml, one applied per fork), matching the
 * existing pattern the rest of this addon already uses rather than adding a
 * new "not CML" gating category for just this one case.</p>
 */
@Mixin(value = BOBJModelVAO.class, remap = false)
public abstract class BOBJModelVAOMixinFS
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
        Link[] materialTextures = fbxData.materialTextures;
        java.util.Map<String, Link> overrides = CurrentMaterialTextureOverrides.current();

        for (int m = 0; m < materialNames.length; m++)
        {
            Link override = overrides.get(materialNames[m]);
            Link sharedDefault = materialTextures != null && m < materialTextures.length ? materialTextures[m] : null;
            Link texture = override != null ? override : sharedDefault;

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
