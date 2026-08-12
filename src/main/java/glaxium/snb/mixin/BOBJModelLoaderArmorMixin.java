package glaxium.snb.mixin;

import glaxium.snb.model.bobj.EmoticonArmorSidecar;

import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.loaders.BOBJModelLoader;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.Collection;

/** Adds armor.bobj immediately after the host parses the untouched main BOBJ. */
@Mixin(value = BOBJModelLoader.class, remap = false)
public abstract class BOBJModelLoaderArmorMixin
{
    @Unique
    private static final ThreadLocal<String> bbsFbx$armorModelId = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<ModelManager> bbsFbx$armorModelManager = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<Link> bbsFbx$armorModelFolder = new ThreadLocal<>();

    @Inject(method = "load", at = @At("HEAD"), remap = false)
    private void bbsFbx$rememberArmorLoad(
            String id, ModelManager manager, Link folder, Collection<Link> links, MapType config,
            CallbackInfoReturnable<ModelInstance> info)
    {
        bbsFbx$armorModelId.set(id);
        bbsFbx$armorModelManager.set(manager);
        bbsFbx$armorModelFolder.set(folder);
    }

    @Redirect(
            method = "load",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/bobj/BOBJLoader;readData(Ljava/io/InputStream;)Lmchorse/bbs_mod/bobj/BOBJLoader$BOBJData;"),
            remap = false
    )
    private BOBJLoader.BOBJData bbsFbx$readWithArmorSidecar(InputStream stream) throws Exception
    {
        BOBJLoader.BOBJData data = BOBJLoader.readData(stream);
        String id = bbsFbx$armorModelId.get();
        ModelManager manager = bbsFbx$armorModelManager.get();
        Link folder = bbsFbx$armorModelFolder.get();

        if (id != null && manager != null && folder != null)
        {
            EmoticonArmorSidecar.tryMerge(id, manager.provider, folder, data);
        }

        return data;
    }

    @Inject(method = "load", at = @At("RETURN"), remap = false)
    private void bbsFbx$forgetArmorLoad(
            String id, ModelManager manager, Link folder, Collection<Link> links, MapType config,
            CallbackInfoReturnable<ModelInstance> info)
    {
        bbsFbx$armorModelId.remove();
        bbsFbx$armorModelManager.remove();
        bbsFbx$armorModelFolder.remove();
    }
}
