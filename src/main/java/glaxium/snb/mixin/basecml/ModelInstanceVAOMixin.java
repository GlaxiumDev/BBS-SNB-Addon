package glaxium.snb.mixin.basecml;

import glaxium.snb.render.CubicVAOBucketingBuilder;
import glaxium.snb.render.IModelInstanceMaterialVaos;
import glaxium.snb.render.MaterialTextureDelegate;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.CubicRenderer;
import mchorse.bbs_mod.cubic.render.vao.ModelVAO;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/**
 * Gives Base/CML's {@code ModelInstance} the per-material VAO storage its
 * {@code Map<ModelGroup, ModelVAO> vaos} field can't hold. FS's native
 * {@code ModelInstance} uses {@code Map<ModelGroup, Map<String, ModelVAO>>}
 * (one VAO per material per group); Base/CML use one merged VAO per group.
 * OBJ models loaded by {@code CubicModelLoaderMixinBaseCML} keep one
 * {@code ModelGroup} per OBJ object with one mesh per material, so they need
 * the per-material split to render each material with its own texture.
 *
 * <p>{@code setup()} is replaced for those models: instead of the native
 * merged-VAO bake, it schedules {@link CubicVAOBucketingBuilder}, which
 * writes the per-material VAOs into {@link #bbsFbx$materialVaos}. The native
 * {@code vaos} map stays empty for them (the VAO renderer mixins draw from
 * the per-material map instead), and {@code isVAORendered()} /
 * {@code delete()} are extended so the model is still treated as VAO-rendered
 * and its per-material VAOs are cleaned up. Only models whose cubic
 * {@code Model} carries material data ({@code IModelMaterialTextures}, set
 * by the OBJ loader) are touched -- regular BBS cubic models, FBX BOBJ
 * models and FS keep their native paths.</p>
 *
 * <p>{@code @Unique} fields and {@code @Inject}s on identical method
 * signatures only -- Base and CML's {@code ModelInstance} match here
 * (verified via javap), so this single mixin class covers both. Gated to
 * Base/CML by {@code BBSFbxMixinPlugin}.</p>
 */
@Mixin(value = ModelInstance.class, remap = false)
public abstract class ModelInstanceVAOMixin implements IModelInstanceMaterialVaos
{
    @Shadow public IModel model;
    @Shadow public boolean onCpu;

    @Unique private Map<ModelGroup, Map<String, ModelVAO>> bbsFbx$materialVaos = new HashMap<>();
    @Unique private boolean bbsFbx$materialVaosReady = false;

    @Override
    public Map<ModelGroup, Map<String, ModelVAO>> bbsFbx$getMaterialVaos()
    {
        return this.bbsFbx$materialVaos;
    }

    @Inject(method = "setup", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$setupPerMaterialVaos(CallbackInfo ci)
    {
        if (!(this.model instanceof Model cubic) || this.onCpu)
        {
            return;
        }

        if (!cubic.getShapeKeys().isEmpty())
        {
            return;
        }

        if (MaterialTextureDelegate.getMaterials(this.model).isEmpty())
        {
            return;
        }

        this.bbsFbx$materialVaos.clear();
        this.bbsFbx$materialVaosReady = true;

        MinecraftClient.getInstance().execute(() ->
        {
            CubicRenderer.processRenderModel(
                    new CubicVAOBucketingBuilder(this.bbsFbx$materialVaos), null, new MatrixStack(), cubic);
        });

        ci.cancel();
    }

    @Inject(method = "isVAORendered", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$materialVaosRendered(CallbackInfoReturnable<Boolean> cir)
    {
        if (this.bbsFbx$materialVaosReady && !this.bbsFbx$materialVaos.isEmpty())
        {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "delete", at = @At("HEAD"), remap = false)
    private void bbsFbx$deleteMaterialVaos(CallbackInfo ci)
    {
        for (Map<String, ModelVAO> groupVaos : this.bbsFbx$materialVaos.values())
        {
            for (ModelVAO vao : groupVaos.values())
            {
                vao.delete();
            }
        }

        this.bbsFbx$materialVaos.clear();
        this.bbsFbx$materialVaosReady = false;
    }
}
