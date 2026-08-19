package glaxium.snb.mixin;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.resources.packs.InternalAssetsSourcePack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Replaces {@code InternalAssetsSourcePack#getLinksFromPath} with a jar
 * listing that never calls {@code Path.toFile()}.
 *
 * <p>The stock implementation converts the class's code source location to a
 * {@code Path} ({@code Paths.get(location.toURI())}) and then calls
 * {@code Path.toFile()}. Under NeoForge with Sinytra Connector the BBS jar
 * is served through a {@code jar:} URI, so {@code Paths.get} returns a
 * zip-filesystem path and {@code toFile()} throws
 * {@code UnsupportedOperationException: Path not associated with default
 * file system}. The stock catch only prints the trace and then falls back
 * to {@code stupidWorkaround}, which scans {@code FabricLoader} metadata and
 * the game dir's {@code mods/} folder — also unreliable on NeoForge. The
 * net effect: every {@code ModelManager.loadModel} call spams an exception
 * and models that live inside the BBS jar (built-ins) never get listed.</p>
 *
 * <p>This mixin opens the code source location as a stream and enumerates it
 * with {@link JarInputStream}, which works for plain {@code file:} jars and
 * nested {@code jar:} entries alike (the stream of a {@code jar:} URL is the
 * inner archive itself). In a dev environment where the code source is a
 * directory, it falls back to a plain file walk. The link filtering mirrors
 * the stock {@code handleLinksFromZipFile} exactly: entries under
 * {@code internalPrefix + "/" + link.path}, direct children only when the
 * call is non-recursive, folder links suffixed with {@code /}.</p>
 *
 * <p>Registered fork-agnostically: every BBS fork ships this class with the
 * same fields ({@code prefix}, {@code internalPrefix}, {@code clazz}), and
 * the injector targets the same method shape on all of them.</p>
 */
@Mixin(value = InternalAssetsSourcePack.class, remap = false)
public abstract class InternalAssetsSourcePackMixin
{
    @Shadow private String prefix;

    @Shadow private String internalPrefix;

    @Shadow private Class clazz;

    @Unique
    private List<String> bbsFbx$zipEntries;

    @Inject(method = "getLinksFromPath", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$listInternalAssetsSafe(Collection<Link> links, Link path, boolean recursive, CallbackInfo info)
    {
        List<String> entries = this.bbsFbx$entries();

        if (!entries.isEmpty())
        {
            String[] wanted = path.path == null || path.path.isEmpty() ? new String[0] : path.path.split("/", -1);

            for (String entry : entries)
            {
                /* Relativize against internalPrefix; skip the prefix itself
                 * and anything not strictly beneath it. */
                if (entry.length() <= this.internalPrefix.length() + 1)
                {
                    continue;
                }

                String rel = entry.substring(this.internalPrefix.length() + 1);
                String[] relSegments = rel.split("/", -1);

                if (relSegments.length <= wanted.length)
                {
                    continue;
                }

                boolean under = true;

                for (int i = 0; i < wanted.length; i++)
                {
                    if (!wanted[i].equals(relSegments[i]))
                    {
                        under = false;
                        break;
                    }
                }

                if (!under)
                {
                    continue;
                }

                if (!recursive && relSegments.length != wanted.length + 1)
                {
                    continue;
                }

                boolean folder = entry.endsWith("/");

                links.add(new Link(this.prefix, rel + (folder ? "/" : "")));
            }
        }

        /* Always cancel: the stock body is exactly the Path.toFile() crash
         * (or the unreliable Forge/fallback workaround) we are replacing. */
        info.cancel();
    }

    /**
     * Enumerates this pack's jar once: every entry under {@code internalPrefix}.
     * Empty when the code source is not enumerable, which degrades to "no
     * built-in assets listed" instead of crashing model loading.
     */
    @Unique
    private List<String> bbsFbx$entries()
    {
        if (this.bbsFbx$zipEntries != null)
        {
            return this.bbsFbx$zipEntries;
        }

        List<String> names = new ArrayList<>();

        try
        {
            URL location = this.clazz.getProtectionDomain().getCodeSource().getLocation();

            if (location != null)
            {
                try (InputStream stream = location.openStream(); JarInputStream jar = new JarInputStream(stream))
                {
                    JarEntry entry;

                    while ((entry = jar.getNextJarEntry()) != null)
                    {
                        String name = entry.getName();

                        if (name.startsWith(this.internalPrefix))
                        {
                            names.add(name);
                        }
                    }
                }
            }
        }
        catch (Exception jarFailure)
        {
            /* Dev environment: the code source may be an exploded classes
             * folder rather than an archive. */
            names.clear();

            try
            {
                URL location = this.clazz.getProtectionDomain().getCodeSource().getLocation();
                File root = location == null ? null : new File(location.toURI());
                File assets = root == null ? null : new File(root, this.internalPrefix);

                if (assets != null && assets.isDirectory())
                {
                    this.bbsFbx$walk(assets, assets.getPath().length() + 1, names);
                }
            }
            catch (Exception ignored)
            {}
        }

        this.bbsFbx$zipEntries = names;

        return names;
    }

    @Unique
    private void bbsFbx$walk(File folder, int prefixLength, List<String> names)
    {
        File[] children = folder.listFiles();

        if (children == null)
        {
            return;
        }

        for (File child : children)
        {
            if (child.isDirectory())
            {
                names.add(this.internalPrefix + "/" + child.getPath().substring(prefixLength) + "/");

                this.bbsFbx$walk(child, prefixLength, names);
            }
            else
            {
                names.add(this.internalPrefix + "/" + child.getPath().substring(prefixLength));
            }
        }
    }
}
