package glaxium.snb.mixin.basecml;

import glaxium.snb.model.fbx.loaders.IModelMeshMaterial;

import mchorse.bbs_mod.cubic.data.model.ModelMesh;
import mchorse.bbs_mod.data.types.MapType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Parses the legacy {@code material} field of a {@code .bbs.json} mesh into
 * the per-mesh material name Base/CML's {@code ModelMesh} is missing (FS
 * declares {@code public String material} natively and reads it in
 * {@code fromData}). The OBJ loader path never calls {@code fromData} (it
 * fills {@code baseData} directly and sets its own materials via
 * {@code IModelMeshMaterial}), so this only affects native legacy models.
 *
 * <p>Together with {@link CubicModelLoaderLegacyMixinBaseCML}, this connects
 * the addon's per-material multi-texture support (one material per mesh,
 * textures in {@code textures/<material>/}) to pure legacy models, exactly
 * like it already works for OBJ and FBX/glTF models on Base and CML.</p>
 *
 * <p>Gated to Base/CML by {@code BBSFbxMixinPlugin}.</p>
 */
@Mixin(value = ModelMesh.class, remap = false)
public abstract class ModelMeshMaterialMixinBaseCML
{
    @Inject(method = "fromData(Lmchorse/bbs_mod/data/types/MapType;)V", at = @At("RETURN"), remap = false)
    private void bbsFbx$parseLegacyMaterial(MapType data, CallbackInfo ci)
    {
        ((IModelMeshMaterial) this).bbsFbx$setMaterial(data.getString("material", ""));
    }
}