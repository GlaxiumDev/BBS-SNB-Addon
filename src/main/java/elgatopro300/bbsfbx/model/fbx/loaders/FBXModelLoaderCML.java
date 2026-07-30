package elgatopro300.bbsfbx.model.fbx.loaders;

import elgatopro300.bbsfbx.model.fbx.FBXConverter;
import elgatopro300.bbsfbx.model.fbx.FBXShapeKeyModelCML;
import elgatopro300.bbsfbx.model.fbx.FBXShapeKeyNames;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;

import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Set;

/**
 * Registers as the FBX model loader (see {@code ModelManagerMixin}, which
 * installs this into {@code ModelManager.loaders} on every fork).
 *
 * <p><b>Despite the "CML" name, this loader also covers BBS Base.</b>
 * {@code mchorse.bbs_mod.cubic.model.bobj.BOBJModel}'s constructor -- the
 * thing this class's "one merged {@code CompiledData}" design depends on --
 * is byte-for-byte identical between {@code bbs-1.7.7-1.20.4.jar} and
 * {@code bbs-cml-edition-2.0-beta-1-1.20.4.jar}, confirmed directly:
 * {@code (BOBJArmature, BOBJLoader.CompiledData, boolean)} on both. BBS FS is
 * the one that actually diverged, to a {@code (BOBJArmature,
 * List<CompiledData> meshes, boolean)} per-mesh model shape -- confirmed
 * directly against {@code Wemppy4/bbs-fs}'s real source -- which is why FS
 * needs its own separate loader (the one in the FS-targeted sibling addon,
 * not present in this repository) rather than a mixin variant of this
 * class. This class was left un-renamed rather than risk a wide,
 * unverifiable rename across every file that references it.</p>
 *
 * <p>This is the CML twin of {@code FBXModelLoader} (the BBS FS loader). The
 * pipeline up to "we have a BOBJData" is 100% shared - both loaders call the
 * same {@link FBXAssimpImporter}, {@link FBXConverter}, and
 * {@link FBXModelLoadCache} from the {@code common} module. Everything after
 * that differs because BBS CML EDITION's model/render API diverged from BBS
 * FS's:
 *
 * <ul>
 *   <li>CML's {@code BOBJModel} constructor takes ONE {@code CompiledData}
 *       for the whole model, not a {@code List<CompiledData>} (one per
 *       mesh) like FS's does - so every mesh gets flattened into a single
 *       merged mesh via {@link FBXMeshCompiler#compileMergedWithMaterials}
 *       (used purely for its shape-key merging; the per-material tagging it
 *       also produces isn't consumed - this addon only supports one texture
 *       or one flat color for the whole model, same as FS).</li>
 *   <li>Texture/color: {@link FBXTextureResolverCML} resolves ONE texture
 *       for the whole model, exactly like FS's {@code ModelInstance.texture}
 *       default. If no texture is found anywhere, any flat Base Color
 *       captured off the material is applied straight to
 *       {@code ModelInstance.color} - no PNG or folder is ever generated for
 *       it.</li>
 *   <li>Shape keys: {@link FBXShapeKeyModelCML} overrides
 *       {@code getShapeKeys()} (CML's own {@code BOBJModel} hardcodes it
 *       empty), and {@code BOBJModelVAOMixinCML} does the actual per-vertex
 *       blending, mirroring the FS addon's own {@code BOBJModelVAOMixin}.</li>
 * </ul>
 */
public class FBXModelLoaderCML implements IModelLoader
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
                Link[] materialTextures = FBXTextureResolverCML.resolveMaterialTextures(data, merged, model, links, models.provider);

                merged.setMaterialTextures(materialTextures);
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

            FBXShapeKeyModelCML bobjModel = new FBXShapeKeyModelCML(armature, merged, false, shapeKeyNames);

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
     * {@code ModelInstance.color} only exists on CML - Base's
     * {@code ModelInstance} has no stored per-model tint at all (confirmed
     * directly against {@code mchorse/bbs-mod}; there color only ever comes
     * in as a render-time parameter from the caller). Reflection lets this
     * one loader class compile and run against both targets: it applies the
     * flat Base Color on CML, and is a silent, harmless no-op on Base,
     * which currently has no equivalent hook for it.
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
            // No "color" field on this fork (e.g. Base) - nothing to apply it to.
        }
    }

    /**
     * Same re-extraction safety net as the FS loader: if a cached load's
     * known textured materials are missing their PNG on disk (folder
     * deleted by the user), re-import purely to rerun texture extraction.
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