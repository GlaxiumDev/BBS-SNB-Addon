package glaxium.snb.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.Optional;

/**
 * Which BBS this addon is currently running on top of.
 *
 * <p>Used by {@link glaxium.snb.BBSFbxMixinPlugin} to pick fork-gated mixins
 * where Base / Wemppy FS / CML / BBS 2.1 (FS fork) disagree on method
 * signatures. Detection is loader-metadata only ({@link #fromLoadedMods()}),
 * which is safe during mixin bootstrap.</p>
 */
public enum BBSFork
{
    BASE("BBS Base"),
    FS("BBS FS"),
    /** McHorse/community BBS 2.1 line — FS-shaped render APIs, older SimpleVAO/FormUtils. */
    FS21("BBS 2.1 (FS fork)"),
    CML("BBS CML Edition");

    /** Present in FS / FS21, absent in Base. Fully qualified. */
    private static final String FS_ONLY_CLASS = "mchorse.bbs_mod.ui.film.replays.ReplayListEntry";

    private static final String[] FS_MOD_IDS = {"bbs_fs", "bbs-fs", "bbsfs", "bbs_mod_fs"};
    private static final String[] CML_MOD_IDS = {"bbs_cml", "bbs-cml", "bbscml", "bbs_cml_edition", "bbs_mod_cml"};

    /** The mod id every known fork -- Base, FS, FS21, and CML -- actually publishes under. */
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

    /** True for Wemppy FS and the BBS 2.1 FS fork (shared FS-shaped render hooks). */
    public boolean isFsFamily()
    {
        return this == FS || this == FS21;
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
        else if (isWemppyFsByName() || anyLoaded(FS_MOD_IDS))
        {
            fork = FS;
        }
        else if (isFs21ByMetadata())
        {
            fork = FS21;
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
        String name = bbsName();

        return name != null && name.toUpperCase().contains("CML");
    }

    /**
     * Wemppy FS publishes name "BBS FS mod". Do not match description text
     * here — BBS 2.1 uses description "BBS FS fork" with name "BBS mod".
     */
    private static boolean isWemppyFsByName()
    {
        String name = bbsName();

        return name != null && name.toUpperCase().contains("FS");
    }

    /**
     * BBS 2.1 (FS fork): shared id/name with Base, distinguished by
     * description, custom {@code bbs:git_commit}, or version {@code 2.1-*}
     * (not CML's {@code 2.1.1-*}).
     */
    private static boolean isFs21ByMetadata()
    {
        try
        {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(BBS_MOD_ID);

            if (container.isEmpty())
            {
                return false;
            }

            ModMetadata meta = container.get().getMetadata();
            String description = meta.getDescription();

            if (description != null && description.toUpperCase().contains("BBS FS FORK"))
            {
                return true;
            }

            CustomValue git = meta.getCustomValue("bbs:git_commit");

            if (git != null && git.getType() == CustomValue.CvType.STRING)
            {
                return true;
            }

            String version = meta.getVersion().getFriendlyString();

            return version != null && version.startsWith("2.1-");
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static String bbsName()
    {
        try
        {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(BBS_MOD_ID);

            if (container.isEmpty())
            {
                return null;
            }

            return container.get().getMetadata().getName();
        }
        catch (Throwable t)
        {
            return null;
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
