package glaxium.snb.mixin.fs;

import glaxium.snb.model.bbssnb.CubicParseCapture;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.data.model.ModelMesh;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.loaders.CubicModelLoader;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.IOUtils;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers per-material textures for pure legacy .bbs.json models. Stock
 * {@link CubicModelLoader#load} only populates {@link ModelInstance#materials}
 * and {@link ModelInstance#materialTextures} on the OBJ branch; mesh
 * {@code material} fields parsed from a legacy file are otherwise ignored, so
 * every mesh would render with the model's default texture. After the load we
 * extract the model's embedded textures (the BBS S&B exporter stores them in
 * the file, mirroring the armature path), then collect the distinct materials
 * from the cubic model's meshes and bind them to their
 * {@code textures/<material>/} textures (same convention as OBJ and BOBJ
 * models), surfacing an empty folder when the texture is missing.
 *
 * <p>The FS-only APIs ({@code ModelMesh.material},
 * {@code ModelInstance.materials}/{@code materialTextures}, and
 * {@code IModelLoader}'s material helpers) don't exist in the Base and CML
 * jars, and gradle compiles against whichever fork is enabled in libs/, so
 * they are reached through reflection. The mixin is gated to the FS fork by
 * {@code BBSFbxMixinPlugin} anyway, so the reflective lookups only ever run
 * where those members exist.</p>
 *
 * <p>Gated to the FS fork: FS is the only 1.20.4 jar whose
 * {@code ModelInstance} exposes {@code materials}/{@code materialTextures}
 * and whose cubic renderer resolves per-material textures (the CML edition
 * 2.0 jar lacks both; Base lacks the whole material system).</p>
 */
@Mixin(value = CubicModelLoader.class, remap = false)
public abstract class CubicModelLoaderMixinFS
{
    @Inject(method = "load", at = @At("RETURN"), remap = false)
    private void bbsFbx$loadLegacyMaterials(String id, ModelManager models, Link model, Collection<Link> links, MapType config, CallbackInfoReturnable<ModelInstance> cir)
    {
        ModelInstance instance = cir.getReturnValue();

        if (instance == null || !(instance.getModel() instanceof Model cubic))
        {
            return;
        }

        /* model.png is the fallback default for meshes without a material
         * and for cubes. Models where every mesh carries a material (the
         * FBX/glTF convention: only textures/<material>/ folders) don't
         * need it, so it's only extracted when something uses it. */
        boolean needDefaultTexture = false;
        Set<String> materials = new LinkedHashSet<>();

        for (ModelGroup group : cubic.getAllGroups())
        {
            if (!group.cubes.isEmpty())
            {
                needDefaultTexture = true;
            }

            for (ModelMesh mesh : group.meshes)
            {
                String material = bbsFbx$meshMaterial(mesh);

                if (material.isEmpty())
                {
                    needDefaultTexture = true;
                }
                else
                {
                    materials.add(material);
                }
            }
        }

        extractEmbeddedLegacyTextures(models.provider, model, needDefaultTexture, materials);

        List<String> instanceMaterials = bbsFbx$materials(instance);
        Map<String, Link> instanceMaterialTextures = bbsFbx$materialTextures(instance);

        if (instanceMaterials == null || instanceMaterialTextures == null)
        {
            return;
        }

        for (String material : materials)
        {
            if (instanceMaterials.contains(material))
            {
                continue;
            }

            instanceMaterials.add(material);

            Link texture = bbsFbx$findMaterialTexture(links, model, material);

            if (texture != null)
            {
                instanceMaterialTextures.put(material, texture);
            }
            else
            {
                bbsFbx$ensureMaterialFolder(models.provider, model, material);
            }
        }
    }

    /* --- reflective access to FS-only APIs (absent in Base/CML jars) --- */

    /** FS {@code ModelMesh.material}; meshes on Base/CML never reach this mixin (fork-gated). */
    private static String bbsFbx$meshMaterial(ModelMesh mesh)
    {
        try
        {
            Object value = mesh.getClass().getField("material").get(mesh);

            return value == null ? "" : (String) value;
        }
        catch (Exception e)
        {
            return "";
        }
    }

    /** FS {@code ModelInstance.materials} ({@code List<String>}). */
    @SuppressWarnings("unchecked")
    private static List<String> bbsFbx$materials(ModelInstance instance)
    {
        try
        {
            return (List<String>) instance.getClass().getField("materials").get(instance);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** FS {@code ModelInstance.materialTextures} ({@code Map<String, Link>}). */
    @SuppressWarnings("unchecked")
    private static Map<String, Link> bbsFbx$materialTextures(ModelInstance instance)
    {
        try
        {
            return (Map<String, Link>) instance.getClass().getField("materialTextures").get(instance);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** FS {@code IModelLoader.findMaterialTexture(links, model, material)}. */
    private static Link bbsFbx$findMaterialTexture(Collection<Link> links, Link model, String material)
    {
        try
        {
            return (Link) IModelLoader.class
                .getMethod("findMaterialTexture", Collection.class, Link.class, String.class)
                .invoke(null, links, model, material);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** FS {@code IModelLoader.ensureMaterialFolder(provider, model, material)}. */
    private static void bbsFbx$ensureMaterialFolder(AssetProvider provider, Link model, String material)
    {
        try
        {
            IModelLoader.class
                .getMethod("ensureMaterialFolder", AssetProvider.class, Link.class, String.class)
                .invoke(null, provider, model, material);
        }
        catch (Exception e)
        {
            /* Folder creation is best-effort; a missing texture surfaces an
             * empty material folder on FS anyway. */
        }
    }

    /**
     * Writes the embedded textures of a legacy .bbs.json into the model
     * folder. Every entry whose name matches one of the model's mesh
     * materials lands in {@code textures/<name>/default.png} (index 0
     * included - the primary texture is often a material too); the first
     * entry additionally becomes {@code model.png}, but only when something
     * actually needs a default (a mesh without a material, or any cube).
     * Entries that no mesh references are skipped. Never overwrites a file
     * that is already there, matching the FBX extractor.
     *
     * <p>Reloads stay cheap: when every target file already exists the
     * embedded JSON is not touched at all, and otherwise the parsed
     * {@code MapType} captured by {@link CubicLoaderParseMixinFS} is reused
     * instead of reading and parsing the file a second time.</p>
     */
    private static void extractEmbeddedLegacyTextures(AssetProvider provider, Link model, boolean needDefaultTexture, Set<String> materials)
    {
        if (bbsFbx$allExtracted(provider, model, needDefaultTexture, materials))
        {
            return;
        }

        MapType root = CubicParseCapture.takeRoot();

        if (root == null)
        {
            try (InputStream stream = provider.getAsset(model.combine("model.bbs.json")))
            {
                if (stream == null)
                {
                    return;
                }

                root = DataToString.mapFromString(IOUtils.readText(stream));
            }
            catch (Exception e)
            {
                System.err.println("[BBS S&B] Could not read embedded textures from " + model + ": " + e.getMessage());

                return;
            }
        }

        try
        {
            ListType textures = root.getList("textures");
            int extracted = 0;

            for (int i = 0; i < textures.size(); i++)
            {
                BaseType element = textures.get(i);

                if (!element.isMap())
                {
                    continue;
                }

                MapType entry = element.asMap();
                String name = entry.getString("name", "");
                String source = entry.getString("source", "");
                int comma = source.indexOf(',');

                if (name.isEmpty() || !source.startsWith("data:image/") || comma < 0)
                {
                    continue;
                }

                try
                {
                    byte[] bytes = Base64.getDecoder().decode(source.substring(comma + 1));
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));

                    if (image == null)
                    {
                        continue;
                    }

                    if (materials.contains(name))
                    {
                        File folder = provider.getFile(model.combine("textures/" + name));
                        File target = new File(folder, "default.png");

                        if (folder != null && !target.exists())
                        {
                            folder.mkdirs();
                            ImageIO.write(image, "png", target);
                            extracted++;
                        }
                    }

                    if (i == 0 && needDefaultTexture)
                    {
                        File folder = provider.getFile(model);
                        File target = new File(folder, "model.png");

                        if (folder != null && !target.exists())
                        {
                            folder.mkdirs();
                            ImageIO.write(image, "png", target);
                            extracted++;
                        }
                    }
                }
                catch (Exception e)
                {
                    System.err.println("[BBS S&B] Failed to extract embedded texture " + name + ": " + e.getMessage());
                }
            }

            if (extracted > 0)
            {
                System.out.println("[BBS S&B] Extracted " + extracted + " embedded texture(s) for " + model);
            }
        }
        catch (Exception e)
        {
            System.err.println("[BBS S&B] Could not extract embedded textures: " + e.getMessage());
        }
    }

    /**
     * Fast path for reloads: extraction only ever creates files (it never
     * overwrites), so when every file it could produce is already on disk
     * there is nothing to do - the embedded JSON, its base64 blobs and the
     * PNG decodes are all skipped.
     */
    private static boolean bbsFbx$allExtracted(AssetProvider provider, Link model, boolean needDefaultTexture, Set<String> materials)
    {
        for (String material : materials)
        {
            File folder = provider.getFile(model.combine("textures/" + material));

            if (folder == null || !new File(folder, "default.png").exists())
            {
                return false;
            }
        }

        if (needDefaultTexture)
        {
            File folder = provider.getFile(model);

            if (folder == null || !new File(folder, "model.png").exists())
            {
                return false;
            }
        }

        return true;
    }
}
