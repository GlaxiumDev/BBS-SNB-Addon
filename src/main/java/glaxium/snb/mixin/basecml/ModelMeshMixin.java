package glaxium.snb.mixin.basecml;

import glaxium.snb.model.fbx.loaders.IModelMeshMaterial;

import mchorse.bbs_mod.cubic.data.model.ModelMesh;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * <p>Also lets legacy .bbs.json meshes carry per-vertex normals: stock
 * {@code ModelMesh#fromData} always derives flat per-triangle normals; when
 * the file has an optional "normals" list (same length as "vertices",
 * written by the BBS S&B exporter for smooth-shaded meshes), the computed
 * flat normals are replaced so the cubic renderer shades them smoothly.
 * Cubes never get normals and stay flat-shaded.</p>
 *
 * <p>The material field is {@code @Unique} (no existing method touched);
 * the normals hook injects into {@code ModelMesh#fromData}, which has the
 * identical signature in both jars (confirmed via javap). Gated to
 * Base/CML by {@code BBSFbxMixinPlugin}; FS has the native field and its
 * own {@code ModelMeshMixinFS} for normals.</p>
 */
@Mixin(value = ModelMesh.class, remap = false)
public abstract class ModelMeshMixin implements IModelMeshMaterial
{
    @Unique private String bbsFbx$material = "";

    @Inject(method = "fromData", at = @At("RETURN"), remap = false)
    private void bbsFbx$applyMeshNormals(MapType data, CallbackInfo ci)
    {
        ModelMesh mesh = (ModelMesh) (Object) this;
        ListType normals = data.getList("normals");

        /* The list holds three floats per vertex; baseData.vertices counts
         * Vector3f entries, so the list must be exactly three times as long. */
        if (normals.size() == mesh.baseData.vertices.size() * 3)
        {
            mesh.baseData.normals.clear();

            for (int i = 0, c = normals.size() / 3; i < c; i++)
            {
                mesh.baseData.normals.add(new Vector3f(
                        normals.getFloat(i * 3),
                        normals.getFloat(i * 3 + 1),
                        normals.getFloat(i * 3 + 2)));
            }
        }
    }

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
