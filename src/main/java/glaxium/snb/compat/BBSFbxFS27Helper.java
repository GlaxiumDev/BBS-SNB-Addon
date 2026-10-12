package glaxium.snb.compat;

import glaxium.snb.BBSFbxAddon;
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
    private static boolean registered = false;

    private BBSFbxFS27Helper()
    {
    }

    public static synchronized void register()
    {
        if (registered)
        {
            return;
        }

        registered = true;

        try
        {
            BBSMod.getProvider().register(new InternalAssetsSourcePack("bbs_fbx", "assets/bbs_fbx", BBSFbxAddon.class));
            BBSMod.getProvider().register(new MinecraftTextureSourcePack());
            LOGGER.info("[BBS Fbx Addon] Registered source packs for BBS FS 2.7+");
        }
        catch (Throwable t)
        {
            LOGGER.error("[BBS Fbx Addon] Failed to register source packs on BBS FS 2.7: " + t.getMessage(), t);
        }

        try
        {
            BBSModClient.getL10n().registerOne((lang) -> new Link("bbs_fbx", "lang/" + lang + ".json"));
            LOGGER.info("[BBS Fbx Addon] Registered L10n for BBS FS 2.7+");
        }
        catch (Throwable t)
        {
            LOGGER.error("[BBS Fbx Addon] Failed to register L10n on BBS FS 2.7: " + t.getMessage(), t);
        }
    }
}
