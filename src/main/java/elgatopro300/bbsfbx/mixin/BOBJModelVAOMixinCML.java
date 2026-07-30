package elgatopro300.bbsfbx.mixin;

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
 * Gives Base/CML real multi-texture support for FBX models by splitting
 * {@code BOBJModelVAO.render}'s single {@code glDrawArrays} call into one
 * sub-call per material, rebinding that material's texture in between.
 *
 * <p>Neither Base nor CML's engine has any native concept of "more than one
 * texture per model" (confirmed directly: {@code ModelInstance} carries
 * exactly one {@code texture} field, {@code BOBJModel} manages exactly one
 * VAO/one {@code CompiledData}) - unlike BBS FS, which was extended upstream
 * to support this via a per-mesh VAO list and a
 * {@code Function<String, Link>} texture resolver baked into its own
 * {@code ModelInstance.render()} signature. There's nothing to hook into for
 * that on Base/CML, so this reimplements the equivalent at the one place
 * that's actually reachable: the merged mesh compiled by
 * {@code FBXMeshCompiler#compileMergedWithMaterials} keeps every vertex
 * grouped contiguously by the mesh (material) it came from, plus a per-vertex
 * material index ({@code FBXCompiledData#materialIndexData}) and a resolved
 * texture per material ({@code FBXCompiledData#materialTextures}, filled in
 * by {@code FBXTextureResolverCML#resolveMaterialTextures}). That's enough to
 * walk the single VAO's vertex buffer, find where the material index changes,
 * and issue one {@code glDrawArrays} sub-range per run - binding that
 * material's texture immediately before its sub-range, via the same
 * {@code BBSModClient.getTextures().bindTexture(Link)} call the host's own
 * form renderer already uses to bind a model's single texture before calling
 * {@code render()} in the first place (confirmed directly against
 * {@code ModelFormRenderer}).
 *
 * <p>Only replaces the draw call itself - vertex attribute setup/teardown,
 * uniform upload, and VAO/shader bind stay exactly as the host's own
 * {@code render()} does them, once for the whole batch. A material with no
 * resolved texture (index out of range, or {@code materialTextures} itself
 * null) simply isn't rebound for - its sub-range draws with whatever texture
 * was bound most recently, same graceful fallback a single-texture model
 * already gets from the host's default-texture bind before {@code render()}
 * is even called.
 *
 * <p>Single-material FBX models (or any other model using this VAO class at
 * all - {@code Model}/OBJ/BOBJ forms never hit {@code FBXCompiledData}) fall
 * straight through to the host's own unmodified {@code render()}; only
 * {@code FBXCompiledData} with {@code hasMultipleMaterials()} true takes this
 * path. Doesn't apply to the FS-targeted sibling addon: FS already renders
 * multi-material FBX models through its own native pipeline, and this mixin
 * targets a Base/CML-only render() signature.
 *
 * <p>Fork-agnostic between Base and CML the same way {@code BOBJModelVAOMixin}
 * already is for {@code updateMesh} - every {@code @Shadow}'d field and the
 * {@code render} signature below are byte-for-byte identical across the Base
 * 1.7.7-1.20.4 and BBS CML EDITION 2.0-beta-1-1.20.4 jars (checked directly).
 */
@Mixin(value = BOBJModelVAO.class, remap = false)
public abstract class BOBJModelVAOMixinCML
{
    @Shadow public BOBJLoader.CompiledData data;
    @Shadow private int vao;
    @Shadow private int count;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$renderMultiMaterial(
            ShaderProgram shader, MatrixStack stack, float r, float g, float b, float a,
            StencilMap stencilMap, int light, int overlay, CallbackInfo info)
    {
        if (!(this.data instanceof FBXCompiledData fbxData) || !fbxData.hasMultipleMaterials() || this.count <= 0)
        {
            return;
        }

        info.cancel();

        boolean hasShaders = BBSRendering.isIrisShadersEnabled();

        GL30.glVertexAttrib4f(Attributes.COLOR, r, g, b, a);
        GL30.glVertexAttribI2i(Attributes.OVERLAY_UV, overlay & 0xFFFF, overlay >> 16 & 0xFFFF);
        GL30.glVertexAttribI2i(Attributes.LIGHTMAP_UV, light & 0xFFFF, light >> 16 & 0xFFFF);

        int currentVAO = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int currentElementArrayBuffer = GL30.glGetInteger(GL30.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        ModelVAORenderer.setupUniforms(stack, shader);

        shader.bind();

        GL30.glBindVertexArray(this.vao);

        GL30.glEnableVertexAttribArray(Attributes.POSITION);
        GL30.glEnableVertexAttribArray(Attributes.TEXTURE_UV);
        GL30.glEnableVertexAttribArray(Attributes.NORMAL);

        if (stencilMap != null) GL30.glEnableVertexAttribArray(Attributes.LIGHTMAP_UV);
        if (hasShaders) GL30.glEnableVertexAttribArray(Attributes.TANGENTS);
        if (hasShaders) GL30.glEnableVertexAttribArray(Attributes.MID_TEXTURE_UV);

        int[] materialIndexData = fbxData.materialIndexData;
        Link[] materialTextures = fbxData.materialTextures;

        int start = 0;
        int currentMaterial = materialIndexData[0];

        for (int i = 1; i <= this.count; i++)
        {
            int material = i < this.count ? materialIndexData[i] : Integer.MIN_VALUE;

            if (material != currentMaterial)
            {
                bbsFbx$drawMaterialRange(start, i - start, currentMaterial, materialTextures);
                start = i;
                currentMaterial = material;
            }
        }

        GL30.glDisableVertexAttribArray(Attributes.POSITION);
        GL30.glDisableVertexAttribArray(Attributes.TEXTURE_UV);
        GL30.glDisableVertexAttribArray(Attributes.NORMAL);

        if (stencilMap != null) GL30.glDisableVertexAttribArray(Attributes.LIGHTMAP_UV);
        if (hasShaders) GL30.glDisableVertexAttribArray(Attributes.TANGENTS);
        if (hasShaders) GL30.glDisableVertexAttribArray(Attributes.MID_TEXTURE_UV);

        shader.unbind();

        GL30.glBindVertexArray(currentVAO);
        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, currentElementArrayBuffer);
    }

    private static void bbsFbx$drawMaterialRange(int start, int length, int materialIndex, Link[] materialTextures)
    {
        if (length <= 0)
        {
            return;
        }

        Link texture = materialIndex >= 0 && materialTextures != null && materialIndex < materialTextures.length
                ? materialTextures[materialIndex]
                : null;

        if (texture != null)
        {
            BBSModClient.getTextures().bindTexture(texture);
        }

        GL30.glDrawArrays(GL30.GL_TRIANGLES, start, length);
    }
}
