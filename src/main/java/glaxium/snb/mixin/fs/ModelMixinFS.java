package glaxium.snb.mixin.fs;

import glaxium.snb.model.fbx.loaders.IModelMaterialTextures;

import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.resources.Link;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Mixin(value = Model.class, remap = false)
public abstract class ModelMixinFS implements IModelMaterialTextures
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
