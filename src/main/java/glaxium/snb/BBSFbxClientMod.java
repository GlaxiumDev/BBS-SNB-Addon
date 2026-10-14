package glaxium.snb;

import glaxium.snb.compat.BBSFbxFS27Helper;
import glaxium.snb.compat.BBSFork;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standard Fabric ClientModInitializer for BBS FBX Ultimate.
 * Runs on every Fabric launcher and initializes BBS FS 2.7+ assets
 * when running under BBS FS 2.7+.
 */
public class BBSFbxClientMod implements ClientModInitializer
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BBS Fbx Addon");

    @Override
    public void onInitializeClient()
    {
        BBSFork fork = BBSFork.fromLoadedMods();
        LOGGER.info("[BBS Fbx Addon] Initializing client for " + (fork != null ? fork.label() : "BBS"));
        BBSFbxFS27Helper.register();
        glaxium.snb.model.mixamo.MixamoAnimationManager.ensureDirectory(null);
    }
}
