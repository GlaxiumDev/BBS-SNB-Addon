package elgatopro300.bbsfbx.mixin.cml;

import elgatopro300.bbsfbx.model.fbx.FBXShapeKeyModelCML;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXCompiledData;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXMaterialTextureConfig;
import elgatopro300.bbsfbx.model.fbx.loaders.IMaterialTextureHolder;
import elgatopro300.bbsfbx.model.fbx.loaders.IShapeKeyHolder;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.colors.Color;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.util.math.MatrixStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * CML variant of the shape-key hook-up redirect. This is the ONE place Base,
 * FS and CML genuinely disagree: {@code ModelInstance.render(...)} takes a
 * different final parameter on each --
 * {@link elgatopro300.bbsfbx.mixin.base.ModelInstanceMixinBase Base has no
 * texture parameter at all}, FS takes a
 * {@code Function<String, Link> textureResolver}
 * ({@link elgatopro300.bbsfbx.mixin.fs.ModelInstanceMixinFS}), and CML takes
 * a plain {@code Link defaultTexture} (this class). Everything past the
 * signature -- the redirected call and its target,
 * {@code BOBJModelVAO.updateMesh} -- is identical across all three, hence the
 * three copies changing only their {@code method} descriptor rather than
 * duplicating logic. {@link elgatopro300.bbsfbx.BBSFbxMixinPlugin} makes sure
 * only the variant matching the running fork is ever applied, so exactly one
 * of these three loads at a time.
 *
 * <p>Also implements {@link IMaterialTextureHolder} for the multi-material
 * FBX picker UI ({@code UIModelFormPanelMixin}). This is a pure
 * delegation layer -- it holds no state of its own, every method reads or
 * writes straight through to the {@link FBXCompiledData} backing this
 * instance's model, which is the SAME object reference
 * {@code BOBJModelVAOMixinCML} reads at render time, so a write here is
 * visible next frame with no extra plumbing needed.</p>
 */
@Mixin(value = ModelInstance.class, remap = false)
public abstract class ModelInstanceMixinCML implements IMaterialTextureHolder
{
    @Shadow public IModel model;
    @Shadow public String id;

    @Redirect(
            method = "render(Lnet/minecraft/client/util/math/MatrixStack;Ljava/util/function/Supplier;Lmchorse/bbs_mod/utils/colors/Color;IILmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;Lmchorse/bbs_mod/obj/shapes/ShapeKeys;Lmchorse/bbs_mod/resources/Link;)V",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/cubic/render/vao/BOBJModelVAO;updateMesh(Lmchorse/bbs_mod/ui/framework/elements/utils/StencilMap;)V"),
            remap = false
    )
    private void bbsFbx$redirectUpdateMesh(
            BOBJModelVAO vao, StencilMap stencilMap,
            MatrixStack stack, Supplier<ShaderProgram> program, Color color,
            int light, int overlay, StencilMap stencilMap2, ShapeKeys keys,
            Link defaultTexture)
    {
        if (vao instanceof IShapeKeyHolder holder)
        {
            holder.bbsFbx$setShapeKeys(keys);
        }
        vao.updateMesh(stencilMap);
    }

    @Unique
    private FBXCompiledData bbsFbx$materialData()
    {
        if (this.model instanceof FBXShapeKeyModelCML fbxModel
                && fbxModel.getMeshData() instanceof FBXCompiledData data
                && data.hasMultipleMaterials())
        {
            return data;
        }

        return null;
    }

    @Override
    public List<String> bbsFbx$getMaterials()
    {
        FBXCompiledData data = this.bbsFbx$materialData();

        return data == null ? Collections.emptyList() : List.of(data.materialNames);
    }

    @Override
    public Link bbsFbx$getMaterialTexture(String material)
    {
        FBXCompiledData data = this.bbsFbx$materialData();

        if (data == null)
        {
            return null;
        }

        int index = bbsFbx$indexOf(data.materialNames, material);

        return index >= 0 && data.materialTextures != null ? data.materialTextures[index] : null;
    }

    @Override
    public void bbsFbx$setMaterialTexture(String material, Link link)
    {
        FBXCompiledData data = this.bbsFbx$materialData();

        if (data == null)
        {
            return;
        }

        int index = bbsFbx$indexOf(data.materialNames, material);

        if (index < 0)
        {
            return;
        }

        if (data.materialTextures == null)
        {
            data.materialTextures = new Link[data.materialNames.length];
        }

        data.materialTextures[index] = link;

        bbsFbx$persist(data);
    }

    /**
     * {@code this.id} is the model's own asset key (same string
     * {@code ModelManager.loadModel(id)} was called with) - {@code
     * ModelManager} itself builds the actual model {@code Link} loaders
     * receive as {@code Link.assets(MODELS_PREFIX + id)}
     * ({@code ModelManager.loadModel}, confirmed directly), which is
     * reproduced here so the sidecar file ends up at the exact same path
     * {@code FBXModelLoaderCML} reads/writes it at.
     */
    @Unique
    private void bbsFbx$persist(FBXCompiledData data)
    {
        AssetProvider provider = mchorse.bbs_mod.BBSModClient.getModels().provider;
        Link model = Link.assets(mchorse.bbs_mod.cubic.model.ModelManager.MODELS_PREFIX + this.id);

        Map<String, Link> all = new LinkedHashMap<>();

        for (int i = 0; i < data.materialNames.length; i++)
        {
            all.put(data.materialNames[i], data.materialTextures != null ? data.materialTextures[i] : null);
        }

        FBXMaterialTextureConfig.save(provider, model, all);
    }

    @Unique
    private static int bbsFbx$indexOf(String[] names, String name)
    {
        for (int i = 0; i < names.length; i++)
        {
            if (names[i].equals(name))
            {
                return i;
            }
        }

        return -1;
    }
}
