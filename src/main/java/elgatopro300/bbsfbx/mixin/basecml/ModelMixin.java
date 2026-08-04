package elgatopro300.bbsfbx.mixin.basecml;

import elgatopro300.bbsfbx.model.fbx.loaders.IModelMaterialTextures;

import mchorse.bbs_mod.resources.Link;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Gives Base/CML's cubic {@code Model} the per-material texture data
 * described by {@link IModelMaterialTextures}. OBJ models loaded by
 * {@link CubicModelLoaderMixinBaseCML} (see its class doc) keep one
 * {@code ModelGroup} per OBJ object with one mesh per OBJ material, and the
 * materials list here is what lets the renderers
 * ({@code CubicVAORendererMixinBase} / {@code CubicVAORendererMixinCML})
 * resolve each material's texture off the model (per-Form override first,
 * else the material's loaded default).
 *
 * <p>{@code @Unique} fields only -- nothing here injects into an existing
 * method, so this single mixin class works against both Base's and CML's
 * {@code Model} bytecode (identical API). Gated to Base/CML by
 * {@code BBSFbxMixinPlugin} because FS carries its own native per-material
 * model data and doesn't need this.</p>
 *
 * <p>Empty by default: a regular BBS cubic model (from a {@code .bbs.json})
 * has no material data and is untouched. Only a pure-OBJ model loaded by
 * {@code CubicModelLoaderMixinBaseCML} ever gets the lists filled in.</p>
 */
@Mixin(value = mchorse.bbs_mod.cubic.data.model.Model.class, remap = false)
public abstract class ModelMixin implements IModelMaterialTextures
{
    @Unique private List<String> bbsFbx$materials = Collections.emptyList();
    @Unique private Map<String, Link> bbsFbx$materialTextures = Collections.emptyMap();

    @Override
    public void bbsFbx$setMaterialTextures(List<String> materials, Map<String, Link> materialTextures)
    {
        this.bbsFbx$materials = materials == null ? Collections.emptyList() : List.copyOf(materials);
        this.bbsFbx$materialTextures = materialTextures == null ? Collections.emptyMap() : Map.copyOf(materialTextures);
    }

    @Override
    public List<String> bbsFbx$getMaterials()
    {
        return this.bbsFbx$materials;
    }

    @Override
    public Link bbsFbx$getDefaultMaterialTexture(String material)
    {
        return material == null ? null : this.bbsFbx$materialTextures.get(material);
    }
}
