package glaxium.snb.mixin;

import glaxium.snb.model.bobj.FastBOBJParser;

import mchorse.bbs_mod.bobj.BOBJLoader;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;

/** Routes native BOBJ, including armor sidecars, through the streaming parser. */
@Mixin(value = BOBJLoader.class, remap = false)
public class BOBJLoaderFastParserMixin
{
    @Inject(method = "readData", at = @At("HEAD"), cancellable = true, remap = false)
    private static void bbsFbx$readStreaming(
            InputStream stream,
            CallbackInfoReturnable<BOBJLoader.BOBJData> info) throws Exception
    {
        info.setReturnValue(FastBOBJParser.read(stream));
    }
}
