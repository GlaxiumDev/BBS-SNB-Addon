package elgatopro300.bbsfbx.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;

/**
 * Which BBS this addon is currently running on top of.
 *
 * <p>Only used for one thing: telling {@link elgatopro300.bbsfbx.BBSFbxMixinPlugin}
 * which of the three {@code ModelInstanceMixin} variants to apply --
 * {@code ModelInstance.render(...)}'s final parameter is the one place Base,
 * FS and CML genuinely disagree (see the mixin/base, mixin/fs, mixin/cml
 * doc comments). Every other mixin in this addon is fork-agnostic and
 * doesn't need this class at all.</p>
 *
 * <p>Detection is loader-metadata only ({@link #fromLoadedMods()}), which
 * makes it safe to call from a mixin config plugin during mixin bootstrap --
 * touching an actual BBS class that early force-loads it before mixins have
 * been applied to it, silently breaking every mixin targeting it. This
 * pattern (and the CML-name-vs-mod-id caveat below) is carried over from
 * {@code BBS-Minema-Addon}'s own {@code compat/BBSFork.java}, written for
 * its BBS Addon Engine port.</p>
 */
public enum BBSFork
{
    BASE("BBS Base"),
    FS("BBS FS"),
    CML("BBS CML Edition");

    /** Present in FS, absent in Base. Fully qualified. */
    private static final String FS_ONLY_CLASS = "mchorse.bbs_mod.ui.film.replays.ReplayListEntry";

    private static final String[] FS_MOD_IDS = {"bbs_fs", "bbs-fs", "bbsfs", "bbs_mod_fs"};
    private static final String[] CML_MOD_IDS = {"bbs_cml", "bbs-cml", "bbscml", "bbs_cml_edition", "bbs_mod_cml"};

    /** The mod id every known fork -- Base, FS, and CML -- actually publishes under. */
    private static final String BBS_MOD_ID = "bbs";

    private static BBSFork cached;

    private final String label;

    BBSFork(String label)
    {
        this.label = label;
    }

    public String label()
    {
        return this.label;
    }

    /** Loader-metadata only. Safe during mixin bootstrap. */
    public static BBSFork fromLoadedMods()
    {
        if (cached != null)
        {
            return cached;
        }

        BBSFork fork = BASE;

        if (isCmlByName() || anyLoaded(CML_MOD_IDS))
        {
            fork = CML;
        }
        else if (anyLoaded(FS_MOD_IDS))
        {
            fork = FS;
        }
        else if (classPresent(FS_ONLY_CLASS))
        {
            // Some FS builds ship under the shared "bbs" id with no
            // distinguishing name either -- the class probe is the last
            // resort. Safe to run here too: by the time anything actually
            // calls fromLoadedMods() for real gating decisions, mixin
            // bootstrap for THIS addon's own targets has already happened;
            // it's only unsafe to probe a class that one of our OWN mixins
            // also targets, and ReplayListEntry isn't one of them.
            fork = FS;
        }

        cached = fork;

        return fork;
    }

    /**
     * CML ships under the same mod id as everything else ("bbs"), so the
     * only loader-metadata-level way to tell it apart is its mod NAME --
     * "BBS CML" in ElGatoPro300/bbs-mod-cml-edition's own fabric.mod.json.
     * Reading getMetadata().getName() never loads or touches a BBS class.
     */
    private static boolean isCmlByName()
    {
        try
        {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(BBS_MOD_ID);

            if (container.isEmpty())
            {
                return false;
            }

            String name = container.get().getMetadata().getName();

            return name != null && name.toUpperCase().contains("CML");
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static boolean anyLoaded(String[] ids)
    {
        for (String id : ids)
        {
            if (isLoaded(id))
            {
                return true;
            }
        }

        return false;
    }

    private static boolean isLoaded(String id)
    {
        try
        {
            return FabricLoader.getInstance().isModLoaded(id);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static boolean classPresent(String name)
    {
        try
        {
            Class.forName(name, false, BBSFork.class.getClassLoader());

            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
