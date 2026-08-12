package glaxium.snb.mixin.fs;

import glaxium.snb.model.bobj.EmoticonArmorSidecar;
import glaxium.snb.model.bobj.fs.BOBJModelArmorSimpleVAOFS;

import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelSimpleVAO;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Gives FS Bend armor the body hinge without using player-skin UV ranges. */
@Mixin(value = BOBJModel.class, remap = false)
public abstract class BOBJModelArmorMixinFS
{
    @Shadow private List<BOBJModelVAO> vaos;

    /**
     * FS's *_simple models use a UV-driven sharp hinge authored for the player
     * skin atlas. Armor needs the same hinge, but its atlas requires geometric
     * joint detection. Keep the original VAO for the body and replace only
     * armor VAOs with the armor-aware implementation.
     */
    @Inject(method = "setup", at = @At("RETURN"), remap = false)
    private void bbsFbx$useRegularSkinningForArmor(CallbackInfo info)
    {
        for (int i = 0; i < this.vaos.size(); i++)
        {
            BOBJModelVAO vao = this.vaos.get(i);
            String mesh = vao.data == null || vao.data.mesh == null ? null : vao.data.mesh.name;

            if (vao instanceof BOBJModelSimpleVAO && EmoticonArmorSidecar.isArmorMesh(mesh))
            {
                vao.delete();
                this.vaos.set(i, new BOBJModelArmorSimpleVAOFS(vao.data));
            }
        }
    }
}
