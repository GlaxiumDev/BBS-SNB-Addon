package glaxium.snb.model.bbssnb;

import mchorse.bbs_mod.data.types.MapType;

/**
 * Thread-safe side channel for the FS cubic extraction path: the parsed
 * {@code MapType} of a legacy .bbs.json is handed from the {@code
 * CubicLoader.load} redirect ({@code glaxium.snb.mixin.fs.CubicLoaderParseMixinFS})
 * to {@code CubicModelLoaderMixinFS}'s extraction on the same model loader
 * thread, so the multi-megabyte JSON isn't read and parsed a second time.
 *
 * <p>Deliberately a plain class OUTSIDE the {@code glaxium.snb.mixin}
 * package (which {@code bbs_snb_addon.mixins.json} declares as its mixin
 * package): the mixin transformer forbids directly referencing classes from
 * a defined mixin package ("... is in a defined mixin package ... and cannot
 * be referenced directly"), and a public static accessor on a mixin itself
 * is rejected as a non-private static method.</p>
 */
public final class CubicParseCapture
{
    private static final ThreadLocal<MapType> LAST_ROOT = new ThreadLocal<>();

    private CubicParseCapture() {}

    public static void setRoot(MapType root)
    {
        LAST_ROOT.set(root);
    }

    /** Returns the root parsed by the current thread's most recent load, or null. */
    public static MapType takeRoot()
    {
        MapType root = LAST_ROOT.get();

        LAST_ROOT.remove();

        return root;
    }
}
