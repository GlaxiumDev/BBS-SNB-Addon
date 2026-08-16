package glaxium.snb.mixin.fs;

import glaxium.snb.model.fbx.loaders.FBXCompiledData;
import glaxium.snb.model.fbx.loaders.IFbxModel;
import glaxium.snb.model.fbx.loaders.IMaterialTextureHolder;
import glaxium.snb.model.fbx.loaders.IModelMaterialTextures;
import glaxium.snb.model.fbx.loaders.IShapeKeyHolder;
import glaxium.snb.render.MaterialTextureDelegate;
import glaxium.snb.model.blockbuster.LegacyBBModel;
import glaxium.snb.model.blockbuster.LegacyBBRenderer;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.colors.Color;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * FS variant of the shape-key hook-up redirect. See
 * {@link glaxium.snb.mixin.cml.ModelInstanceMixinCML} for the full
 * explanation of why three copies exist.
 *
 * <p>Confirmed directly against {@code Wemppy4/bbs-fs}'s real source:
 * {@code ModelInstance.render(...)} (java line 654) takes
 * {@code Function<String, Link> textureResolver} as its final parameter, and
 * its one call to {@code BOBJModelVAO.updateMesh(stencilMap)} (line 720)
 * sits directly in {@code render()}'s own body -- inside a
 * {@code for (BOBJModelVAO vao : vaos)} loop, but not delegated to a helper
 * method -- so this {@code @Redirect} reaches it correctly.</p>
 *
 * <p>Also implements {@link IMaterialTextureHolder}, same as
 * {@code ModelInstanceMixinBase} and for the same reason (see its doc
 * comment).</p>
 *
 * <p><b>Seed for the film editor's per-material texture keyframes.</b> FS's
 * film editor creates one keyframe sheet per material via its native
 * {@code UIReplaysEditorUtils.addMaterialTextureSheets}, which iterates
 * {@code ModelInstance.materials} (native BOBJ models get theirs filled in
 * by {@code BOBJModelLoader}). {@code BOBJModel} doesn't do that, so the
 * constructor injection below fills both {@code materials} and
 * {@code materialTextures} straight from the FBX data instead, for every
 * FBX model -- single-material ones included, matching the FS-targeted
 * sibling addon's {@code FBXTextureResolver.registerMaterials}. Those two
 * fields are public on FS's {@code ModelInstance} but absent on Base/CML --
 * hence the {@code @Shadow} declarations (which compile against any one
 * fork's jar) plus this mixin being gated to FS by
 * {@code BBSFbxMixinPlugin}.</p>
 */
@Mixin(value = ModelInstance.class, remap = false)
public abstract class ModelInstanceMixinFS implements IMaterialTextureHolder
{
    @Shadow public IModel model;
    @Shadow public String id;
    @Shadow public List<String> materials;
    @Shadow public Map<String, Link> materialTextures;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$renderLegacyBB(
            MatrixStack stack, Supplier<ShaderProgram> program, Color color,
            int light, int overlay, StencilMap stencilMap, ShapeKeys keys,
            Function<String, Link> textureResolver, CallbackInfo ci)
    {
        if (this.model instanceof LegacyBBModel legacy)
        {
            LegacyBBRenderer.render(legacy, stack, program, color, light, overlay, stencilMap);
            ci.cancel();
        }
    }

    /**
     * Fills {@code materials}/{@code materialTextures} from the FBX data the
     * way {@code BOBJModelLoader} fills them from {@code BOBJData.meshes}.
     * Runs after the constructor already allocated the (empty) collections.
     * Every FBX model is touched -- single-material models get their one
     * material seeded too (the sibling addon's {@code registerMaterials}
     * behavior); non-FBX models keep whatever the constructor left.
     */
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void bbsFbx$seedFbxMaterials(CallbackInfo info)
    {
        List<String> names = null;
        Link[] defaults = null;

        if (this.model instanceof IFbxModel fbxModel)
        {
            FBXCompiledData data = fbxModel.bbsFbx$getFbxData();

            if (data != null && data.materialNames != null && data.materialNames.length > 0)
            {
                names = List.of(data.materialNames);
                defaults = data.materialTextures;
            }
        }
        else if (this.model instanceof IModelMaterialTextures cubic)
        {
            names = cubic.bbsFbx$getMaterials();
        }

        if (names == null || names.isEmpty())
        {
            return;
        }

        for (String name : names)
        {
            if (!this.materials.contains(name))
            {
                this.materials.add(name);
            }
        }

        for (int i = 0; i < names.size(); i++)
        {
            Link texture = defaults != null && i < defaults.length
                    ? defaults[i]
                    : MaterialTextureDelegate.getDefaultMaterialTexture(this.model, names.get(i));

            if (texture != null)
            {
                this.materialTextures.put(names.get(i), texture);
            }
        }
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/cubic/render/vao/BOBJModelVAO;updateMesh(Lmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;)V"),
            remap = false
    )
    private void bbsFbx$redirectUpdateMesh(
            BOBJModelVAO vao, StencilMap stencilMap,
            MatrixStack stack, Supplier<ShaderProgram> program, Color color,
            int light, int overlay, StencilMap stencilMap2, ShapeKeys keys,
            Function<String, Link> textureResolver)
    {
        if (vao instanceof IShapeKeyHolder holder)
        {
            holder.bbsFbx$setShapeKeys(keys);
        }
        vao.updateMesh(stencilMap);
    }

    @Override
    public List<String> bbsFbx$getMaterials()
    {
        return MaterialTextureDelegate.getMaterials(this.model);
    }

    @Override
    public Link bbsFbx$getDefaultMaterialTexture(String material)
    {
        return MaterialTextureDelegate.getDefaultMaterialTexture(this.model, material);
    }

}
