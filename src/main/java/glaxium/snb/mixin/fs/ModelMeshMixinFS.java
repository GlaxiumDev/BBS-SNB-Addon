package glaxium.snb.mixin.fs;

import mchorse.bbs_mod.cubic.data.model.ModelMesh;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets legacy .bbs.json meshes carry per-vertex normals. The stock
 * {@link ModelMesh#fromData} always derives flat per-triangle normals; when
 * the file has an optional "normals" list (same length as "vertices",
 * written by the BBS S&B exporter for smooth-shaded Blockbench meshes), the
 * computed flat normals are replaced so the cubic renderer shades them
 * smoothly. Blockbench cubes never get normals and stay flat-shaded.
 *
 * <p>Gated to the FS fork: FS is the only 1.20.4 jar whose cubic path
 * renders per-material VAOs and whose {@code ModelMesh} parses the
 * {@code material} field natively (Base lacks the whole material system,
 * and the CML edition 2.0 jar lacks {@code ModelInstance.materialTextures}).</p>
 */
@Mixin(value = ModelMesh.class, remap = false)
public abstract class ModelMeshMixinFS
{
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
}