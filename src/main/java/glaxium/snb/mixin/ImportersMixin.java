package glaxium.snb.mixin;

import glaxium.snb.importers.FBXImporter;

import mchorse.bbs_mod.importers.Importers;
import mchorse.bbs_mod.importers.types.IImporter;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Registers {@link FBXImporter} with BBS's importer list.
 *
 * <p>Fork-agnostic: BBS Base, BBS FS and BBS CML EDITION all declare the same
 * {@code private final static List<IImporter> importers} field, populated in
 * a static initializer, so the same {@code <clinit>} TAIL injection applies
 * unchanged on all three (verified against the Base and CML jars directly).</p>
 */
@Mixin(value = Importers.class, remap = false)
public class ImportersMixin
{
    @Shadow
    @Final
    private static List<IImporter> importers;

    @Inject(method = "<clinit>", at = @At("TAIL"), remap = false)
    private static void bbsFbx$registerFbxImporter(CallbackInfo info)
    {
        importers.add(new FBXImporter());
    }
}
