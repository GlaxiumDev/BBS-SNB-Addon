package glaxium.snb.mixin.fs;

import glaxium.snb.model.bbssnb.CubicParseCapture;

import mchorse.bbs_mod.cubic.CubicLoader;
import mchorse.bbs_mod.data.types.MapType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.InputStream;

/**
 * Captures the {@code MapType} the stock {@link CubicLoader#load} already
 * parsed for a legacy .bbs.json, so {@link CubicModelLoaderMixinFS} can run
 * its embedded-texture extraction against it instead of reading and parsing
 * the multi-megabyte JSON a second time. The parsed root travels through
 * {@link CubicParseCapture} (a ThreadLocal) because {@code CubicLoader.load}
 * and {@code CubicModelLoader.load} run back-to-back on the same model
 * loader thread.
 *
 * <p>Gated to the FS fork; Base/CML get the plain existence-check fast path
 * in their loader mixin.</p>
 */
@Mixin(value = CubicLoader.class, remap = false)
public class CubicLoaderParseMixinFS
{
    @Redirect(method = "load", at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/cubic/CubicLoader;loadFile(Ljava/io/InputStream;)Lmchorse/bbs_mod/data/types/MapType;"), remap = false)
    private MapType bbsFbx$captureRoot(InputStream stream)
    {
        MapType root = CubicLoader.loadFile(stream);

        CubicParseCapture.setRoot(root);

        return root;
    }
}
