package glaxium.snb.model.mixamo;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages loading and caching of Mixamo FBX animations from
 * {@code config/bbs/assets/models/emoticons/mixamo/} and injecting them
 * into all Emoticons models.
 */
public final class MixamoAnimationManager
{
    private static final String MIXAMO_PATH = "models/emoticons/mixamo";

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private record CacheEntry(long lastModified, Animation animation) {}

    private MixamoAnimationManager() {}

    /**
     * Ensures the global mixamo folder exists on disk.
     */
    public static void ensureDirectory(AssetProvider provider)
    {
        File folder = resolveFolder(provider);
        if (folder != null && !folder.exists())
        {
            folder.mkdirs();
        }
    }

    /**
     * Resolves the {@code mixamo} folder on disk.
     */
    public static File resolveFolder(AssetProvider provider)
    {
        if (provider != null)
        {
            try
            {
                File file = provider.getFile(Link.assets(MIXAMO_PATH));
                if (file != null)
                {
                    return file;
                }
            }
            catch (Exception ignored)
            {}
        }

        try
        {
            File runDir = MinecraftClient.getInstance().runDirectory;
            if (runDir != null)
            {
                return new File(runDir, "config/bbs/assets/" + MIXAMO_PATH);
            }
        }
        catch (Throwable ignored)
        {}

        return new File("config/bbs/assets/" + MIXAMO_PATH);
    }

    /**
     * Injects all detected Mixamo animations into any Emoticons model instance.
     */
    public static void injectMixamoAnimations(
            String modelId, ModelInstance instance, AssetProvider provider, MolangParser parser)
    {
        if (modelId == null || !modelId.startsWith("emoticons/") ||
            modelId.equals("emoticons/mixamo") || modelId.startsWith("emoticons/mixamo/"))
        {
            return;
        }

        if (instance == null || instance.animations == null || parser == null)
        {
            return;
        }

        ensureDirectory(provider);
        File folder = resolveFolder(provider);

        // 1. Scan disk folder
        if (folder != null && folder.isDirectory())
        {
            File[] fbxFiles = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".fbx"));
            if (fbxFiles != null)
            {
                for (File file : fbxFiles)
                {
                    String fileName = file.getName();
                    String animName = fileName.substring(0, fileName.length() - 4);
                    long lastModified = file.lastModified();

                    CacheEntry entry = CACHE.get(file.getAbsolutePath());
                    Animation anim;

                    if (entry != null && entry.lastModified == lastModified)
                    {
                        anim = entry.animation;
                    }
                    else
                    {
                        try
                        {
                            byte[] bytes = Files.readAllBytes(file.toPath());
                            anim = MixamoAnimationConverter.convert(animName, bytes, parser);
                            if (anim != null)
                            {
                                CACHE.put(file.getAbsolutePath(), new CacheEntry(lastModified, anim));
                            }
                        }
                        catch (Exception e)
                        {
                            System.err.println("[BBS FBX] Failed to convert Mixamo animation: " + file.getName());
                            e.printStackTrace();
                            anim = null;
                        }
                    }

                    if (anim != null)
                    {
                        instance.animations.add(anim);
                    }
                }
            }
        }

        // 2. Scan pack links if available
        if (provider != null)
        {
            try
            {
                for (Link link : provider.getLinksFromPath(Link.assets(MIXAMO_PATH)))
                {
                    if (link.path.toLowerCase(Locale.ROOT).endsWith(".fbx"))
                    {
                        String name = link.path.substring(link.path.lastIndexOf('/') + 1);
                        String animName = name.substring(0, name.length() - 4);

                        if (instance.animations.animations.containsKey(animName))
                        {
                            continue;
                        }

                        CacheEntry entry = CACHE.get(link.toString());
                        Animation anim;

                        if (entry != null)
                        {
                            anim = entry.animation;
                        }
                        else
                        {
                            try (InputStream stream = provider.getAsset(link))
                            {
                                if (stream != null)
                                {
                                    byte[] bytes = stream.readAllBytes();
                                    anim = MixamoAnimationConverter.convert(animName, bytes, parser);
                                    if (anim != null)
                                    {
                                        CACHE.put(link.toString(), new CacheEntry(0, anim));
                                    }
                                }
                                else
                                {
                                    anim = null;
                                }
                            }
                            catch (Exception e)
                            {
                                System.err.println("[BBS FBX] Failed to convert Mixamo link: " + link);
                                e.printStackTrace();
                                anim = null;
                            }
                        }

                        if (anim != null)
                        {
                            instance.animations.add(anim);
                        }
                    }
                }
            }
            catch (Exception ignored)
            {}
        }
    }
}
