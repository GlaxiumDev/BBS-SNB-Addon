package glaxium.snb.mixin.basecml;

import glaxium.snb.model.fbx.loaders.FBXModelLoader;
import glaxium.snb.model.fbx.loaders.FBXTextureResolverCML;
import glaxium.snb.model.fbx.loaders.IModelMaterialTextures;
import glaxium.snb.model.fbx.loaders.IModelMeshMaterial;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.data.model.ModelMesh;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.loaders.CubicModelLoader;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Connects the addon's per-material multi-texture support to pure legacy
 * {@code .bbs.json} models on Base and CML -- the same support the OBJ
 * loader ({@link CubicModelLoaderMixinBaseCML}) and the FBX/glTF loaders
 * already provide. Meshes carry their {@code material} name via
 * {@link ModelMeshMaterialMixinBaseCML}; after the native load we extract
 * the model's embedded textures (the BBS S&B exporter stores them in the
 * file, mirroring the armature path), collect the distinct materials and
 * bind each to its {@code textures/<material>/default.png} texture, exactly
 * the folder convention the OBJ path resolves. A material without a texture
 * gets its folder created on disk and falls back to a solid white color
 * (never left unbound, matching the OBJ path).
 *
 * <p>{@code model.png} is only extracted when something actually needs a
 * default (a mesh without a material, or any cube) -- all-material models
 * follow the {@code textures/<material>/} only convention, like the
 * FBX/glTF models.</p>
 *
 * <p>Gated to Base/CML by {@code BBSFbxMixinPlugin}; FS carries its own
 * native per-material machinery ({@code ModelInstance.materials}/
 * {@code materialTextures}) and its mixins handle this there.</p>
 */
@Mixin(value = CubicModelLoader.class, remap = false)
public abstract class CubicModelLoaderLegacyMixinBaseCML
{
    @Inject(method = "load", at = @At("RETURN"), remap = false)
    private void bbsFbx$loadLegacyMaterials(String id, ModelManager models, Link model, Collection<Link> links, MapType config, CallbackInfoReturnable<ModelInstance> cir)
    {
        ModelInstance instance = cir.getReturnValue();

        if (instance == null || !(instance.getModel() instanceof Model cubic))
        {
            return;
        }

        /* Only legacy models (a .bbs.json skeleton present) - pure OBJ
         * models were taken over by CubicModelLoaderMixinBaseCML and
         * already carry their material data. */
        boolean legacy = false;

        for (Link link : links)
        {
            if (link.path.endsWith(".bbs.json"))
            {
                legacy = true;
                break;
            }
        }

        if (!legacy)
        {
            return;
        }

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
                String material = ((IModelMeshMaterial) mesh).bbsFbx$getMaterial();

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

        if (materials.isEmpty())
        {
            return;
        }

        Map<String, Link> materialTextures = new LinkedHashMap<>();

        for (String name : materials)
        {
            Link resolved = FBXTextureResolverCML.resolveMaterialTexture(name, model, links);

            if (resolved == null)
            {
                FBXModelLoader.ensureMaterialFolder(models.provider, model, name);
                resolved = FBXTextureResolverCML.colorLink(new float[] { 1.0F, 1.0F, 1.0F });
            }

            materialTextures.put(name, resolved);
        }

        ((IModelMaterialTextures) cubic).bbsFbx$setMaterialTextures(new ArrayList<>(materials), materialTextures);
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
     */
    private static void extractEmbeddedLegacyTextures(AssetProvider provider, Link model, boolean needDefaultTexture, Set<String> materials)
    {
        /* Fast path for reloads: extraction only ever creates files (it
         * never overwrites), so when every file it could produce is already
         * on disk there is nothing to do - the embedded JSON, its base64
         * blobs and the PNG decodes are all skipped. */
        if (bbsFbx$allExtracted(provider, model, needDefaultTexture, materials))
        {
            return;
        }

        try (InputStream stream = provider.getAsset(model.combine("model.bbs.json")))
        {
            if (stream == null)
            {
                return;
            }

            MapType root = DataToString.mapFromString(IOUtils.readText(stream));
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
            System.err.println("[BBS S&B] Failed to read embedded textures from " + model + ": " + e.getMessage());
        }
    }

    /**
     * Whether every file the extraction could create is already on disk.
     * Extraction never overwrites, so when this is true a reload has nothing
     * to extract and the embedded JSON can be skipped entirely.
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