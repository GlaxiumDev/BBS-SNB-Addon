package glaxium.snb.model.fbx.loaders;

import glaxium.snb.model.fbx.FBXConverter;
import glaxium.snb.model.fbx.FBXMesh;
import glaxium.snb.model.fbx.FBXShapeKeyNames;
import glaxium.snb.model.fbx.loaders.java.JavaSceneImporter;
import glaxium.snb.model.fbx.scene.JavaScene;

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
 * Registers as this addon's model loader (see {@code
 * ModelManagerMixin}, which installs this into {@code ModelManager.loaders}
 * on every fork), covering every format in {@link SceneFormat} -- FBX, glTF
 * and GLB. The formats share this one loader because only the import call
 * itself differs between them (unit scale and external-file resolution);
 * every format is converted through the shared {@link JavaScene} pipeline,
 * hence the historical FBX-prefixed class names.
 *
 * <p>One loader for all three forks -- Base, FS and CML. Everything upstream
 * of "we have a {@code BOBJData}" is shared ({@link JavaSceneImporter},
 * {@link FBXConverter}, {@link FBXModelLoadCache}), and everything downstream used to fork apart
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
 * the material becomes a synthetic solid-color texture {@code Link}
 * ({@code Link("color", hex)}) -- the exact mechanism the original
 * BBS-FS-only addon used via {@code LinkUtils.color}. BBS's TextureManager
 * generates the 1x1 color texture in memory; no PNG or folder is ever
 * written. FS handles that Link source natively;
 * {@code TextureManagerMixinBaseCML} gives Base and CML the same handling.
 * Every material of the model gets one such Link per flat-color material
 * through {@link #resolveMaterialTextures} (single-material models included,
 * matching the FS-targeted sibling addon); a material with neither a texture
 * nor a flat color gets its {@code textures/<material>/} folder created
 * instead, and a stale/empty material name is skipped with a warning.</p>
 */
public class FBXModelLoader implements IModelLoader
{
    @Override
    public ModelInstance load(String id, ModelManager models, Link model, Collection<Link> links, MapType config)
    {
        Link fbxLink = null;
        SceneFormat format = null;

        /* Outer loop over formats, not links: links is an unordered set, so
         * scanning it per format in SceneFormat's declaration order keeps the
         * choice deterministic when a folder holds more than one importable
         * file (FBX wins, so models that already loaded from one keep to it). */
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
            /* Reload fast path: unchanged files (same size + mtime) reuse the
             * cached BOBJData without reading or hashing the file at all. */
            File sceneFile = models.provider.getFile(fbxLink);
            FBXModelLoadCache.Cached cached = FBXModelLoadCache.get(fbxLink.path, sceneFile);

            BOBJData data;
            Set<String> shapeKeyNames;

            if (cached != null)
            {
                data = cached.data;
                shapeKeyNames = cached.shapeKeyNames;
            }
            else
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

                /* Second-chance lookup by content hash: covers jar-served
                 * assets and files whose mtime changed (or is unreliable)
                 * but whose content didn't. */
                cached = FBXModelLoadCache.get(fbxLink.path, contentHash);

                if (cached != null)
                {
                    data = cached.data;
                    shapeKeyNames = cached.shapeKeyNames;
                }
                else
                {
                    Set<String> texturedMaterials;
                    JavaScene scene = importScene(fbxLink, format, bytes, models.provider);

                    if (scene == null)
                    {
                        return null;
                    }

                    shapeKeyNames = FBXShapeKeyNames.collectShapeKeyNames(scene);
                    data = FBXConverter.convert(scene, format.unitScale());
                    texturedMaterials = FBXConverter.extractEmbeddedTextures(scene, models.provider, model);

                    FBXModelLoadCache.put(fbxLink.path, contentHash, data, shapeKeyNames, texturedMaterials, sceneFile);
                }
            }

            boolean texturesReextracted = ensureTexturesPresent(fbxLink, format, cached != null ? cached.texturedMaterials : null, models, model);

            if (texturesReextracted)
            {
                FBXModelLoadCache.invalidate(fbxLink.path);
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
    public static BOBJModel createModel(BOBJArmature armature, FBXCompiledData merged)
    {
        return createModel(armature, merged, false);
    }

    /**
     * {@code simple} is the {@code BOBJModel} constructor's "Simple+ model"
     * flag: true makes the model's setup build a
     * {@code BOBJModelSimpleVAO}, whose {@code processData} applies the
     * sharp 90-degree UV-based hinge to Simple+ body parts. The native
     * loaders pass {@code id.startsWith("emoticons") && id.endsWith("_simple")},
     * so the addon's Base/CML loader must pass the same or those models
     * silently lose their hinge (they get a plain VAO with a no-op
     * {@code processData} instead).
     */
    public static BOBJModel createModel(BOBJArmature armature, FBXCompiledData merged, boolean simple)
    {
        try
        {
            Constructor<BOBJModel> listCtor = BOBJModel.class.getConstructor(BOBJArmature.class, List.class, boolean.class);

            return listCtor.newInstance(armature, List.of(merged), simple);
        }
        catch (NoSuchMethodException e)
        {
            return singleCompiledDataModel(armature, merged, simple);
        }
        catch (ReflectiveOperationException e)
        {
            return singleCompiledDataModel(armature, merged, simple);
        }
    }

    private static BOBJModel singleCompiledDataModel(BOBJArmature armature, FBXCompiledData merged, boolean simple)
    {
        try
        {
            Constructor<BOBJModel> singleCtor = BOBJModel.class.getConstructor(BOBJArmature.class, CompiledData.class, boolean.class);

            return singleCtor.newInstance(armature, merged, simple);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("No BOBJModel constructor for this BBS fork", e);
        }
    }

    /**
     * Fills in {@link FBXCompiledData#materialTextures} for the model -- any
     * model, single- or multi-material (the FS film editor's per-material
     * sheets iterate {@code ModelInstance.materials}, which the FS mixin
     * seeds from this data even for one-material models). A saved user choice
     * (from {@link FBXMaterialTextureConfig}) wins if there is one, otherwise
     * falls back to the same {@code textures/<material>/} folder convention
     * the single-texture path already uses. A material that has neither
     * becomes a synthetic solid-color texture {@code Link} ({@link
     * FBXTextureResolverCML#colorLink}) when its FBX material captured a
     * flat Base Color (the exact mechanism the original BBS-FS-only addon
     * used via {@code LinkUtils.color}); one that has neither gets its
     * {@code textures/<material>/} folder created on disk (matching the
     * sibling addon's {@code IModelLoader.ensureMaterialFolder}); and a
     * stale null/empty material name is skipped with a cache-corruption
     * warning.
     */
    public static void resolveMaterialTextures(FBXCompiledData merged, BOBJData data, Link model, Collection<Link> links, AssetProvider provider)
    {
        Map<String, Link> saved = FBXMaterialTextureConfig.load(provider, model);
        Link[] textures = new Link[merged.materialNames.length];

        for (int i = 0; i < merged.materialNames.length; i++)
        {
            String materialName = merged.materialNames[i];

            /* DEFENSIVE: If the cached BOBJData was corrupted (the bug this
             * addon was built around), mesh names may be null or empty. Log it
             * so the user knows the cache is returning stale data. */
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

    /**
     * Fork-agnostic copy of BBS FS's
     * {@code IModelLoader.ensureMaterialFolder}: creates the model's
     * {@code textures/<material>/} folder on disk so a texture can be dropped
     * in for a material that had neither a real texture nor a flat color.
     * Only FS's {@code IModelLoader} declares the original, so this addon
     * can't call it directly against Base/CML.
     */
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

    /** The mesh carrying the given material name, for its texture reference and flat Base Color. */
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
     * Passes the real source path to the Java reader when available. The path
     * matters for a "separate" glTF export,
     * whose {@code .bin} buffer and loose image files are referenced by
     * relative URI and are simply unreachable from a bare byte buffer.
     */
    private static JavaScene importScene(Link link, SceneFormat format, byte[] bytes, AssetProvider provider) throws java.io.IOException
    {
        File file = provider.getFile(link);
        return JavaSceneImporter.importScene(bytes, format, file != null && file.isFile() ? file : null);
    }

    /**
     * Same re-extraction safety net as the FS-targeted sibling loader: if a
     * cached load's known textured materials are missing their PNG on disk
     * (folder deleted by the user), re-import purely to rerun texture
     * extraction.
     */
    private static boolean ensureTexturesPresent(Link link, SceneFormat format, Set<String> texturedMaterials, ModelManager models, Link model)
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
            byte[] bytes;

            try (InputStream stream = models.provider.getAsset(link))
            {
                bytes = stream == null ? null : stream.readAllBytes();
            }

            if (bytes == null)
            {
                return false;
            }

            JavaScene scene = importScene(link, format, bytes, models.provider);

            if (scene != null)
            {
                FBXConverter.extractEmbeddedTextures(scene, models.provider, model);
            }
        }
        catch (java.io.IOException e)
        {
            System.err.println("[BBS FBX] Failed to re-extract embedded textures: " + e.getMessage());
        }

        return true;
    }
}
