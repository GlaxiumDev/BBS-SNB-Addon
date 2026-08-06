package glaxium.snb.importers;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.importers.ImporterContext;
import mchorse.bbs_mod.importers.ImporterUtils;
import mchorse.bbs_mod.importers.types.IImporter;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.net.URLDecoder;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports {@code .gltf} / {@code .glb} models into their own folder under
 * {@code models/}, the same way {@link FBXImporter} does for FBX.
 *
 * <p>The reason this isn't just another extension on {@code FBXImporter}: a
 * {@code .glb} is self-contained, but the far more common "separate" glTF
 * export is a {@code .gltf} JSON that references its geometry buffer
 * ({@code .bin}) and images by relative URI. Copying only the file the user
 * picked would produce a model folder the glTF parser can't read at all, so this
 * importer also copies whatever that JSON points at, preserving the relative
 * subfolders the URIs use ({@code textures/foo.png} stays under
 * {@code textures/}) so the URIs keep resolving after the copy.</p>
 */
public class GLTFImporter implements IImporter
{
    /**
     * Matches glTF's {@code "uri"} properties, which is where every external
     * reference in the file lives -- buffers and images alike both use it, so
     * one scan covers both without needing to understand the schema.
     */
    private static final Pattern URI = Pattern.compile("\"uri\"\\s*:\\s*\"([^\"]+)\"");

    @Override
    public IKey getName()
    {
        return L10n.lang("bbs_fbx.importer.gltf.name");
    }

    @Override
    public File getDefaultFolder()
    {
        return new File(BBSMod.getAssetsFolder(), ModelManager.MODELS_PREFIX);
    }

    @Override
    public boolean canImport(ImporterContext context)
    {
        return ImporterUtils.checkFileExtension(context.files, ".gltf", ".glb");
    }

    @Override
    public void importFiles(ImporterContext context)
    {
        File destinationRoot = context.getDestination(this);
        destinationRoot.mkdirs();

        for (File file : context.files)
        {
            try
            {
                File targetFolder = findNonExistingFolder(destinationRoot, stripExtension(file.getName()));
                targetFolder.mkdirs();

                Files.copy(file.toPath(), new File(targetFolder, file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);

                if (file.getName().toLowerCase().endsWith(".gltf"))
                {
                    copyReferencedFiles(file, targetFolder);
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    /**
     * Copies every sibling file the {@code .gltf} references by relative URI.
     * Skips {@code data:} URIs (already inline in the JSON), absolute URLs
     * (nothing local to copy) and any path that climbs out of the source
     * folder, and tolerates a missing reference rather than aborting the whole
     * import -- a glTF missing one image still loads, just untextured.
     */
    private static void copyReferencedFiles(File gltf, File targetFolder) throws IOException
    {
        String json = Files.readString(gltf.toPath(), StandardCharsets.UTF_8);
        File sourceFolder = gltf.getParentFile();

        if (sourceFolder == null)
        {
            return;
        }

        Set<String> uris = new LinkedHashSet<>();
        Matcher matcher = URI.matcher(json);

        while (matcher.find())
        {
            uris.add(matcher.group(1));
        }

        for (String uri : uris)
        {
            String relative = normalizeUri(uri);

            if (relative == null)
            {
                continue;
            }

            File source = new File(sourceFolder, relative);

            if (!source.isFile())
            {
                System.err.println("[BBS FBX] glTF references a file that isn't there, skipping: " + uri);
                continue;
            }

            File target = new File(targetFolder, relative);

            target.getParentFile().mkdirs();
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The URI as a safe relative path, or null if it isn't one to copy. */
    private static String normalizeUri(String uri)
    {
        if (uri.isEmpty() || uri.startsWith("data:") || uri.contains("://"))
        {
            return null;
        }

        String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8).replace('\\', '/');

        if (decoded.startsWith("/") || decoded.contains("../"))
        {
            return null;
        }

        return decoded;
    }

    private static String stripExtension(String fileName)
    {
        int dot = fileName.lastIndexOf('.');

        return dot == -1 ? fileName : fileName.substring(0, dot);
    }

    private static File findNonExistingFolder(File parent, String baseName)
    {
        File folder = new File(parent, baseName);
        int i = 1;

        while (folder.exists())
        {
            folder = new File(parent, baseName + "_" + i);
            i += 1;
        }

        return folder;
    }
}
