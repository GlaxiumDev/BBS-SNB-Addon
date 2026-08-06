package glaxium.snb.model.fbx.loaders;

import glaxium.snb.model.fbx.FBXConverter;
import glaxium.snb.model.fbx.FBXMesh;
import glaxium.snb.model.fbx.FBXShapeKeyNames;
import glaxium.snb.model.scene.Scene;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.CompiledData;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers as this addon's model loader (see {@code ModelManagerMixin}),
 * covering every format in {@link SceneFormat} -- FBX, glTF and GLB -- via
 * pure-Java parsers into a shared {@link Scene} IR.
 */
public class FBXModelLoader implements IModelLoader
{
    @Override
    public ModelInstance load(String id, ModelManager models, Link model, Collection<Link> links, MapType config)
    {
        Link fbxLink = null;
        SceneFormat format = null;

        for (SceneFormat candidate : SceneFormat.values())
        {
            for (Link link : links)
            {
                if (candidate.matches(link.path))
                {
                    fbxLink = link;
                    format = candidate;
                    break;
                }
            }

            if (fbxLink != null)
            {
                break;
            }
        }

        if (fbxLink == null)
        {
            return null;
        }

        try
        {
            byte[] bytes;
            try (InputStream stream = models.provider.getAsset(fbxLink))
            {
                if (stream == null)
                {
                    return null;
                }
                bytes = stream.readAllBytes();
            }

            long contentHash = FBXModelLoadCache.hash(bytes);
            FBXModelLoadCache.Cached cached = FBXModelLoadCache.get(fbxLink.path, contentHash);

            BOBJData data;
            Set<String> shapeKeyNames;

            if (cached != null)
            {
                data = cached.data;
                shapeKeyNames = cached.shapeKeyNames;

                boolean texturesReextracted = ensureTexturesPresent(fbxLink, format, bytes, cached.texturedMaterials, models, model);

                if (texturesReextracted)
                {
                    FBXModelLoadCache.invalidate(fbxLink.path);
                }
            }
            else
            {
                Scene scene = importScene(fbxLink, format, bytes, models.provider);

                if (scene == null)
                {
                    return null;
                }

                shapeKeyNames = FBXShapeKeyNames.collectShapeKeyNames(scene);
                data = FBXConverter.convert(scene, format.unitScale());
                Set<String> texturedMaterials = FBXConverter.extractEmbeddedTextures(scene, models.provider, model);

                FBXModelLoadCache.put(fbxLink.path, contentHash, data, shapeKeyNames, texturedMaterials);
            }

            data.initiateArmatures();

            FBXCompiledData merged = FBXMeshCompiler.compileMergedWithMaterials(data);

            if (merged.materialNames != null && merged.materialNames.length > 0)
            {
                resolveMaterialTextures(merged, data, model, links, models.provider);
            }

            BOBJArmature armature = null;
            if (!data.armatures.isEmpty())
            {
                armature = data.armatures.values().iterator().next();
            }

            if (armature == null)
            {
                armature = new BOBJArmature("Armature");
                armature.initArmature();
            }

            BOBJModel bobjModel = createModel(armature, merged);

            IFbxModel fbxModel = (IFbxModel) bobjModel;
            fbxModel.bbsFbx$setFbxData(merged);
            fbxModel.bbsFbx$setShapeKeyNames(shapeKeyNames);

            Animations animations = FBXAnimationConverter.convert(data.actions, models.parser);

            Link textureLink = FBXTextureResolverCML.resolveTexture(data, model, links, models.provider);

            if (textureLink == null)
            {
                float[] solidColor = FBXTextureResolverCML.detectSolidColor(data);

                if (solidColor != null)
                {
                    textureLink = FBXTextureResolverCML.colorLink(solidColor);
                }
            }

            ModelInstance modelInstance = new ModelInstance(id, bobjModel, animations, textureLink);

            modelInstance.applyConfig(config);
            return modelInstance;
        }
        catch (Throwable e)
        {
            System.err.println("Failed to load " + format.name() + " model for " + id + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    public static BOBJModel createModel(BOBJArmature armature, FBXCompiledData merged)
    {
        try
        {
            Constructor<BOBJModel> listCtor = BOBJModel.class.getConstructor(BOBJArmature.class, List.class, boolean.class);

            return listCtor.newInstance(armature, List.of(merged), false);
        }
        catch (NoSuchMethodException e)
        {
            return singleCompiledDataModel(armature, merged);
        }
        catch (ReflectiveOperationException e)
        {
            return singleCompiledDataModel(armature, merged);
        }
    }

    private static BOBJModel singleCompiledDataModel(BOBJArmature armature, FBXCompiledData merged)
    {
        try
        {
            Constructor<BOBJModel> singleCtor = BOBJModel.class.getConstructor(BOBJArmature.class, CompiledData.class, boolean.class);

            return singleCtor.newInstance(armature, merged, false);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("No BOBJModel constructor for this BBS fork", e);
        }
    }

    public static void resolveMaterialTextures(FBXCompiledData merged, BOBJData data, Link model, Collection<Link> links, AssetProvider provider)
    {
        Map<String, Link> saved = FBXMaterialTextureConfig.load(provider, model);
        Link[] textures = new Link[merged.materialNames.length];

        for (int i = 0; i < merged.materialNames.length; i++)
        {
            String materialName = merged.materialNames[i];

            if (materialName == null || materialName.isEmpty())
            {
                System.err.println("[BBS FBX] WARNING: Mesh has null/empty material name! "
                        + "This usually means FBXModelLoadCache returned stale BOBJData. "
                        + "Press F6 to clear the cache.");
                continue;
            }

            Link chosen = saved.get(materialName);
            FBXMesh mesh = findMesh(data, materialName);
            Link resolved = chosen != null ? chosen : FBXTextureResolverCML.resolveMaterialTexture(materialName, mesh, model, links);

            if (resolved == null && mesh != null && mesh.color != null)
            {
                resolved = FBXTextureResolverCML.colorLink(mesh.color);
            }

            if (resolved != null)
            {
                textures[i] = resolved;
            }
            else
            {
                ensureMaterialFolder(provider, model, materialName);
            }
        }

        merged.setMaterialTextures(textures);
    }

    public static void ensureMaterialFolder(AssetProvider provider, Link model, String material)
    {
        if (material == null || material.isEmpty())
        {
            return;
        }

        File folder = provider.getFile(model.combine("textures/" + material));

        if (folder != null)
        {
            folder.mkdirs();
        }
    }

    private static FBXMesh findMesh(BOBJData data, String materialName)
    {
        for (BOBJMesh mesh : data.meshes)
        {
            if (mesh instanceof FBXMesh fbxMesh && materialName.equals(mesh.name))
            {
                return fbxMesh;
            }
        }

        return null;
    }

    /**
     * Prefer a real filesystem path so separate glTF exports can resolve
     * relative {@code .bin} / image URIs; fall back to in-memory bytes.
     */
    private static Scene importScene(Link link, SceneFormat format, byte[] bytes, AssetProvider provider) throws Exception
    {
        File file = provider.getFile(link);

        if (file != null && file.isFile())
        {
            return SceneImporter.importScene(file, format);
        }

        File baseDir = file != null ? file.getParentFile() : null;
        return SceneImporter.importScene(bytes, format, baseDir);
    }

    private static boolean ensureTexturesPresent(Link link, SceneFormat format, byte[] bytes, Set<String> texturedMaterials, ModelManager models, Link model)
    {
        if (texturedMaterials == null || texturedMaterials.isEmpty())
        {
            return false;
        }

        boolean missing = false;

        for (String materialName : texturedMaterials)
        {
            File folder = models.provider.getFile(model.combine("textures/" + materialName));
            File target = folder == null ? null : new File(folder, "default.png");

            if (target == null || !target.exists())
            {
                missing = true;
                break;
            }
        }

        if (!missing)
        {
            return false;
        }

        try
        {
            Scene scene = importScene(link, format, bytes, models.provider);

            if (scene != null)
            {
                FBXConverter.extractEmbeddedTextures(scene, models.provider, model);
            }
        }
        catch (Exception e)
        {
            System.err.println("Failed to re-extract textures: " + e.getMessage());
        }

        return true;
    }
}
