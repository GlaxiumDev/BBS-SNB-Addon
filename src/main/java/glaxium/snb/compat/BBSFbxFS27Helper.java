package glaxium.snb.compat;

import glaxium.snb.model.bobj.MinecraftTextureSourcePack;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.resources.packs.InternalAssetsSourcePack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers asset source packs and language packs for BBS FS 2.7+ directly
 * without depending on older BBSAddonMod event signatures.
 */
public final class BBSFbxFS27Helper
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BBS Fbx Addon");
    private static boolean sourcePacksRegistered = false;
    private static boolean l10nRegistered = false;

    private BBSFbxFS27Helper()
    {
    }

    public static synchronized void register()
    {
        registerSourcePacks();
        registerL10n();
    }

    public static synchronized void registerSourcePacks()
    {
        if (sourcePacksRegistered)
        {
            return;
        }

        try
        {
            BBSMod.getProvider().register(new InternalAssetsSourcePack("bbs_fbx", "assets/bbs_fbx", BBSFbxFS27Helper.class));
            BBSMod.getProvider().register(new MinecraftTextureSourcePack());
            sourcePacksRegistered = true;
            LOGGER.info("[BBS Fbx Addon] Registered source packs");
        }
        catch (Throwable t)
        {
            LOGGER.error("[BBS Fbx Addon] Failed to register source packs: " + t.getMessage(), t);
        }
    }

    public static synchronized void registerL10n()
    {
        if (l10nRegistered)
        {
            return;
        }

        try
        {
            if (BBSModClient.getL10n() != null)
            {
                BBSModClient.getL10n().registerOne((lang) -> new Link("bbs_fbx", "lang/" + lang + ".json"));
                l10nRegistered = true;
                LOGGER.info("[BBS Fbx Addon] Registered L10n");
            }
        }
        catch (Throwable t)
        {
            LOGGER.error("[BBS Fbx Addon] Failed to register L10n: " + t.getMessage(), t);
        }
    }
}
