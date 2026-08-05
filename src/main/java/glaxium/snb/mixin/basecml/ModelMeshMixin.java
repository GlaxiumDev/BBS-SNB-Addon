package glaxium.snb.mixin.basecml;

import glaxium.snb.model.fbx.loaders.IModelMeshMaterial;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Adds the per-mesh material name Base/CML's {@code ModelMesh} is missing
 * (BBS FS declares {@code public String material = ""} natively; Base/CML
 * don't -- verified against both jars). Only the OBJ loader
 * ({@code CubicModelLoaderMixinBaseCML}) ever sets it: one {@code ModelMesh}
 * per OBJ {@code usemtl} group inside one {@code ModelGroup} per OBJ object.
 * {@code CubicVAOBucketingBuilder} reads it to split the group's baked VAO
 * data per material, and the VAO renderer mixins draw each material with its
 * own resolved texture, so an OBJ object with several materials renders as
 * ONE group (matching BBS FS's native group-per-object + mesh-per-material
 * structure) instead of one group per material.
 *
 * <p>{@code @Unique} fields only -- nothing injects into an existing method,
 * so this single mixin class works against both Base's and CML's
 * {@code ModelMesh} bytecode (identical API, confirmed via javap). Gated to
 * Base/CML by {@code BBSFbxMixinPlugin}; FS has the native field and doesn't
 * need this.</p>
 */
@Mixin(value = mchorse.bbs_mod.cubic.data.model.ModelMesh.class, remap = false)
public abstract class ModelMeshMixin implements IModelMeshMaterial
{
    @Unique private String bbsFbx$material = "";

    @Override
    public String bbsFbx$getMaterial()
    {
        return this.bbsFbx$material;
    }

    @Override
    public void bbsFbx$setMaterial(String material)
    {
        this.bbsFbx$material = material == null ? "" : material;
    }
}
