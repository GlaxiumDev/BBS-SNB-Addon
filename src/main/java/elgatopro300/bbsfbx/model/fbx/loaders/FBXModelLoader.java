package elgatopro300.bbsfbx.model.fbx.loaders;

import elgatopro300.bbsfbx.model.fbx.FBXConverter;
import elgatopro300.bbsfbx.model.fbx.FBXShapeKeyNames;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.CompiledData;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers as the FBX model loader (see {@code ModelManagerMixin}, which
 * installs this into {@code ModelManager.loaders} on every fork).
 *
 * <p>One loader for all three forks -- Base, FS and CML. Everything upstream
 * of "we have a {@code BOBJData}" is shared with the FS-targeted sibling
 * addon's loader ({@link FBXAssimpImporter}, {@link FBXConverter},
 * {@link FBXModelLoadCache}), and everything downstream used to fork apart
 * only because {@code BOBJModel}'s constructor -- the single thing a model
 * class can't inherit -- genuinely diverges:
 *
 * <ul>
 *   <li><b>Base / CML:</b> {@code BOBJModel(BOBJArmature, CompiledData,
 *       boolean)} -- one {@code CompiledData} for the whole model.</li>
 *   <li><b>FS:</b> {@code BOBJModel(BOBJArmature, List<CompiledData>,
 *       boolean)} -- one per mesh.</li>
 * </ul>
 *
 * <p>Rather than keep per-fork model/loader subclasses (which can't coexist
 * in a single source tree that compiles against one jar at a time), this
 * loader always compiles the whole model into ONE merged
 * {@link FBXCompiledData} via
 * {@link FBXMeshCompiler#compileMergedWithMaterials} -- used for its
 * shape-key merging AND its per-material tagging -- and constructs the model
 * by reflecting over the active fork's constructor ({@link #createModel}):
 * FS gets {@code List.of(merged)} (a single-VAO list, identical layout to the
 * single-VAO Base/CML case), Base/CML get {@code merged} directly. The
 * FBX-specific pieces (the merged data + shape key names) are then handed to
 * the model through {@link IFbxModel} ({@code BOBJModelMixin}), which is how
 * the material-name UI and the per-material VAO render split read them back
 * on every fork.</p>
 *
 * <p>Texture/color: {@link FBXTextureResolverCML} resolves ONE texture for
 * the whole model, exactly like every fork's {@code ModelInstance} texture
 * default. If no texture is found anywhere, any flat Base Color captured off
 * the material is applied straight to {@code ModelInstance.color} (CML only;
 * reflection makes this a no-op on the forks without the field) -- no PNG or
 * folder is ever generated for it.</p>
 */
public class FBXModelLoader implements IModelLoader
{
    @Override
    public ModelInstance load(String id, ModelManager models, Link model, Collection<Link> links, MapType config)
    {
        Link fbxLink = null;

        for (Link link : links)
        {
            if (link.path.toLowerCase().endsWith(".fbx"))
            {
                fbxLink = link;
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

                boolean texturesReextracted = ensureTexturesPresent(bytes, cached.texturedMaterials, models, model);

                if (texturesReextracted)
                {
                    FBXModelLoadCache.invalidate(fbxLink.path);
                }
            }
            else
            {
                AIScene scene = null;
                Set<String> texturedMaterials;

                try
                {
                    scene = FBXAssimpImporter.importScene(bytes);

                    if (scene == null)
                    {
                        return null;
                    }

                    shapeKeyNames = FBXShapeKeyNames.collectShapeKeyNames(scene);
                    data = FBXConverter.convert(scene);
                    texturedMaterials = FBXConverter.extractEmbeddedTextures(scene, models.provider, model);
                }
                finally
                {
                    if (scene != null)
                    {
                        Assimp.aiReleaseImport(scene);
                    }
                }

                FBXModelLoadCache.put(fbxLink.path, contentHash, data, shapeKeyNames, texturedMaterials);
            }

            data.initiateArmatures();

            FBXCompiledData merged = FBXMeshCompiler.compileMergedWithMaterials(data);

            if (merged.hasMultipleMaterials())
            {
                resolveMaterialTextures(merged, model, links, models.provider);
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

            ModelInstance modelInstance = new ModelInstance(id, bobjModel, animations, textureLink);

            if (textureLink == null)
            {
                float[] solidColor = FBXTextureResolverCML.detectSolidColor(data);

                if (solidColor != null)
                {
                    applySolidColor(modelInstance, FBXTextureResolverCML.packColor(solidColor));
                }
            }

            modelInstance.applyConfig(config);
            return modelInstance;
        }
        catch (Throwable e)
        {
            System.err.println("Failed to load FBX model for " + id + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * Builds a {@code BOBJModel} through whichever constructor the ACTIVE
     * fork's jar actually has. FS's {@code (BOBJArmature, List<CompiledData>,
     * boolean)} is tried first (the list wraps the single merged
     * {@code CompiledData} -- one VAO, byte-for-byte the same layout as
     * Base/CML's single-VAO model); Base/CML's {@code (BOBJArmature,
     * CompiledData, boolean)} is the fallback. Both constructors are found
     * by erased types, so this never needs to know which fork it's on and
     * compiles against any one jar.
     */
    private static BOBJModel createModel(BOBJArmature armature, FBXCompiledData merged)
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

    /**
     * Fills in {@link FBXCompiledData#materialTextures} for a multi-material
     * model: a saved user choice (from {@link FBXMaterialTextureConfig})
     * wins if there is one, otherwise falls back to the same
     * {@code textures/<material>/} folder convention the single-texture path
     * already uses. Leaves an entry null (falls back to the model's default
     * texture at render time) when neither source has anything for that
     * material - same "no texture yet" outcome as the single-texture path.
     */
    private static void resolveMaterialTextures(FBXCompiledData merged, Link model, Collection<Link> links, AssetProvider provider)
    {
        Map<String, Link> saved = FBXMaterialTextureConfig.load(provider, model);
        Link[] textures = new Link[merged.materialNames.length];

        for (int i = 0; i < merged.materialNames.length; i++)
        {
            String materialName = merged.materialNames[i];
            Link chosen = saved.get(materialName);

            textures[i] = chosen != null ? chosen : FBXTextureResolverCML.resolveMaterialTexture(materialName, model, links);
        }

        merged.setMaterialTextures(textures);
    }

    /**
     * {@code ModelInstance.color} only exists on CML - Base's and FS's
     * {@code ModelInstance} have no stored per-model tint at all (confirmed
     * directly against the real jars; FS instead passes color in at render
     * time from the caller, Base likewise). Reflection lets this one loader
     * class compile and run against all three targets: it applies the flat
     * Base Color on CML, and is a silent, harmless no-op on the others.
     */
    private static void applySolidColor(ModelInstance modelInstance, int packedColor)
    {
        try
        {
            Field colorField = modelInstance.getClass().getField("color");

            colorField.setInt(modelInstance, packedColor);
        }
        catch (ReflectiveOperationException ignored)
        {
            // No "color" field on this fork (e.g. Base or FS) - nothing to apply it to.
        }
    }

    /**
     * Same re-extraction safety net as the FS-targeted sibling loader: if a
     * cached load's known textured materials are missing their PNG on disk
     * (folder deleted by the user), re-import purely to rerun texture
     * extraction.
     */
    private static boolean ensureTexturesPresent(byte[] bytes, Set<String> texturedMaterials, ModelManager models, Link model)
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

        AIScene scene = null;

        try
        {
            scene = FBXAssimpImporter.importScene(bytes);

            if (scene != null)
            {
                FBXConverter.extractEmbeddedTextures(scene, models.provider, model);
            }
        }
        finally
        {
            if (scene != null)
            {
                Assimp.aiReleaseImport(scene);
            }
        }

        return true;
    }
}
