package glaxium.snb.mixin;

import glaxium.snb.importers.FBXImporter;
import glaxium.snb.importers.GLTFImporter;

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
 * Registers {@link FBXImporter} and {@link GLTFImporter} with BBS's importer
 * list. Two importers rather than one so the picker's label matches the file
 * type the user is actually dropping in, and because glTF needs its own
 * sidecar-copying import step.
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
        importers.add(new GLTFImporter());
    }
}
