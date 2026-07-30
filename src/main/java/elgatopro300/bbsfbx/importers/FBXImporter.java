package elgatopro300.bbsfbx.importers;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.importers.ImporterContext;
import mchorse.bbs_mod.importers.ImporterUtils;
import mchorse.bbs_mod.importers.types.IImporter;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class FBXImporter implements IImporter
{
    @Override
    public IKey getName()
    {
        return L10n.lang("bbs_fbx.importer.name");
    }

    @Override
    public File getDefaultFolder()
    {
        return new File(BBSMod.getAssetsFolder(), ModelManager.MODELS_PREFIX);
    }

    @Override
    public boolean canImport(ImporterContext context)
    {
        return ImporterUtils.checkFileExtension(context.files, ".fbx");
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
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
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