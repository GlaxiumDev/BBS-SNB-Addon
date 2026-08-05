package glaxium.snb.mixin.basecml;

import glaxium.snb.model.fbx.loaders.FBXCompiledData;
import glaxium.snb.model.fbx.loaders.FBXMaterialTextureConfig;
import glaxium.snb.model.fbx.loaders.FBXMeshCompiler;
import glaxium.snb.model.fbx.loaders.FBXModelLoader;
import glaxium.snb.model.fbx.loaders.FBXTextureResolverCML;
import glaxium.snb.model.fbx.loaders.IFbxModel;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.model.loaders.BOBJModelLoader;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.Collection;
import java.util.Map;

/**
 * Base/CML fix for native {@code BOBJModelLoader.load}, which only compiles
 * the FIRST mesh of the armature into a single {@code CompiledData} -- every
 * other object in the BOBJ file never renders, and there's no per-object
 * material/name data for multi-texture.
 *
 * <p>This re-implements {@code load} (HEAD + cancel) the same way the FBX
 * loader does: flatten EVERY mesh of the chosen armature into one
 * {@code FBXCompiledData} via
 * {@link FBXMeshCompiler#compileMergedWithMaterials(BOBJData, boolean)} (with
 * V-flip on -- native {@code processFaceVertex} writes {@code 1 - y}, which
 * BOBJ files' top-left-origin UVs need), populate the per-object material
 * names and resolved textures, and let the already-present Base/CML VAO split
 * ({@code BOBJModelVAOMixinBase} / {@code ...CML}) issue one draw call per
 * object. Each object's texture resolves as: a saved per-material pick
 * ({@link FBXMaterialTextureConfig}), else the {@code textures/<object>/}
 * folder convention, else the model's own {@code model.png} -- so a plain
 * BOBJ with one texture renders exactly as it always did, and a BOBJ with
 * per-object texture folders gets multi-texture.
 *
 * <p>The native private animation-conversion is reached through
 * {@code @Invoker} accessors, so emoticon default animations and BOBJ action
 * conversion behave exactly as stock. FS is unaffected: this mixin is gated to
 * Base/CML by {@code BBSFbxMixinPlugin} (FS's own loader already does
 * per-mesh materials natively).</p>
 */
@Mixin(value = BOBJModelLoader.class, remap = false)
public abstract class BOBJModelLoaderMixinBaseCML
{
    @Shadow private Animations defaultAnimations;

    @Invoker("loadDefaultAnimations")
    protected abstract void invokeLoadDefaultAnimations(AssetProvider provider, MolangParser parser);

    @Invoker("convertAnimations")
    protected abstract Animations invokeConvertAnimations(BOBJData bobjData, Animations animations);

    @Inject(method = "load", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$loadMultiObject(
            String id, ModelManager models, Link model, Collection<Link> links, MapType config,
            CallbackInfoReturnable<ModelInstance> cir)
    {
        Link modelBOBJ = IModelLoader.getLink(model.combine("model.bobj"), links, ".bobj");
        Link modelTexture = IModelLoader.getLink(model.combine("model.png"), links, ".png");

        try
        {
            try (InputStream stream = models.provider.getAsset(modelBOBJ))
            {
                if (stream == null)
                {
                    cir.setReturnValue(null);
                    return;
                }

                BOBJData bobjData = BOBJLoader.readData(stream);

                if (bobjData.armatures.isEmpty())
                {
                    System.err.println("Model \"" + model + "\" doesn't have an armature!");
                    cir.setReturnValue(null);
                    return;
                }

                BOBJArmature armature = bobjData.armatures.values().iterator().next();
                boolean hasMeshOnArmature = false;

                for (BOBJMesh mesh : bobjData.meshes)
                {
                    if (mesh.armature == armature)
                    {
                        hasMeshOnArmature = true;
                        break;
                    }
                }

                if (!hasMeshOnArmature)
                {
                    System.err.println("Model \"" + model + "\" doesn't have a mesh connected to one of the armatures!");
                    cir.setReturnValue(null);
                    return;
                }

                /* The renderer drives the whole VAO from ONE armature (and the
                 * VAO reads it back off data.mesh.armature), so meshes bound
                 * to any other armature must not be merged in -- native drops
                 * them too. */
                bobjData.meshes.removeIf(mesh -> mesh.armature != armature);

                bobjData.initiateArmatures();

                FBXCompiledData merged = FBXMeshCompiler.compileMergedWithMaterials(bobjData, true);

                if (merged.materialNames != null && merged.materialNames.length > 0)
                {
                    resolveObjectTextures(merged, modelTexture, model, links, models);
                }

                BOBJModel bobjModel = FBXModelLoader.createModel(armature, merged);

                IFbxModel fbxModel = (IFbxModel) bobjModel;
                fbxModel.bbsFbx$setFbxData(merged);
                fbxModel.bbsFbx$setShapeKeyNames(null);

                ModelInstance instance = new ModelInstance(
                        id, bobjModel,
                        this.invokeConvertAnimations(bobjData, new Animations(models.parser)),
                        modelTexture);

                if (id.startsWith("emoticons/"))
                {
                    if (this.defaultAnimations == null)
                    {
                        this.invokeLoadDefaultAnimations(models.provider, models.parser);
                    }

                    if (this.defaultAnimations != null)
                    {
                        for (Animation value : this.defaultAnimations.animations.values())
                        {
                            instance.animations.add(value);
                        }
                    }
                }

                instance.applyConfig(config);
                cir.setReturnValue(instance);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            cir.setReturnValue(null);
        }
    }

    /**
     * Per-object texture resolution for native BOBJ models. A saved pick wins,
     * then the {@code textures/<object>/} folder convention, then the model's
     * own {@code model.png} (never null-ing out to the error texture for a
     * BOBJ that simply has no per-object folders -- those render as stock).
     */
    private static void resolveObjectTextures(
            FBXCompiledData merged, Link modelTexture, Link model, Collection<Link> links, ModelManager models)
    {
        Map<String, Link> saved = FBXMaterialTextureConfig.load(models.provider, model);
        Link[] textures = new Link[merged.materialNames.length];

        for (int i = 0; i < merged.materialNames.length; i++)
        {
            String name = merged.materialNames[i];

            if (name == null || name.isEmpty())
            {
                textures[i] = modelTexture;
                continue;
            }

            Link chosen = saved.get(name);
            Link resolved = chosen != null ? chosen : FBXTextureResolverCML.resolveMaterialTexture(name, model, links);

            textures[i] = resolved != null ? resolved : modelTexture;
        }

        merged.setMaterialTextures(textures);
    }
}
