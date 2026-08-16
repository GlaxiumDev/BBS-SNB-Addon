package glaxium.snb.mixin.basecml;

import glaxium.snb.model.fbx.loaders.FBXCompiledData;
import glaxium.snb.model.fbx.loaders.FBXMaterialTextureConfig;
import glaxium.snb.model.fbx.loaders.FBXMeshCompiler;
import glaxium.snb.model.fbx.loaders.FBXModelLoader;
import glaxium.snb.model.fbx.loaders.FBXTextureResolverCML;
import glaxium.snb.model.fbx.loaders.IFbxModel;
import glaxium.snb.model.bobj.EmoticonArmorSidecar;

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
import mchorse.bbs_mod.utils.resources.LinkUtils;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
        /* FBX/glTF folders have no .bobj — bail before getAsset so every scene
         * model does not pay a FileNotFoundException + stack dump on the BOBJ
         * loader that runs first in ModelManager.loadModel. */
        if (!bbsFbx$hasBobj(links))
        {
            cir.setReturnValue(null);
            return;
        }

        Link modelBOBJ = IModelLoader.getLink(model.combine("model.bobj"), links, ".bobj");

        /* getLink() returns its constructed argument even when no .png exists,
         * so a bare getLink(model.combine("model.png")) would hand the renderer
         * a fabricated model.png link (missing file, blue/purple). Resolve the
         * true model default instead: config.json's "texture" (what applyConfig
         * will set on the ModelInstance), then a model.png that really exists,
         * else null so material-less entries fall back to the model texture. */
        Link modelTexture = bbsFbx$defaultTexture(config, model, links);

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

                EmoticonArmorSidecar.tryMerge(id, models.provider, model, bobjData);

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

                /* Simple+ models (emoticons/*_simple) must carry the native
                 * "simple" constructor flag or the model builds a plain VAO
                 * whose processData is a no-op -- the sharp 90-degree hinge
                 * the SimpleVAO applies would silently disappear. Same
                 * condition as the native loaders. */
                boolean simple = id.startsWith("emoticons") && id.endsWith("_simple");
                BOBJModel bobjModel = FBXModelLoader.createModel(armature, merged, simple);

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
        catch (java.io.FileNotFoundException e)
        {
            cir.setReturnValue(null);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            cir.setReturnValue(null);
        }
    }

    @Unique
    private static boolean bbsFbx$hasBobj(Collection<Link> links)
    {
        if (links == null)
        {
            return false;
        }
        for (Link link : links)
        {
            if (link != null && link.path != null && link.path.endsWith(".bobj"))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * True default texture for a native BOBJ model: the {@code texture} entry
     * of {@code config.json} (what {@code applyConfig} sets on the
     * {@code ModelInstance}), else the folder's own {@code model.png} if it
     * really exists, else {@code null}. Never a fabricated link -- native
     * {@code IModelLoader.getLink} returns its constructed argument even when
     * no matching file exists, which is what used to send the renderer looking
     * for {@code models/emoticons/steve/model.png} (blue/purple) on models
     * whose only texture is declared in {@code config.json}.
     */
    @Unique
    private static Link bbsFbx$defaultTexture(MapType config, Link model, Collection<Link> links)
    {
        if (config != null && config.has("texture") && config.get("texture") != null)
        {
            Link texture = LinkUtils.create(config.get("texture"));

            if (texture != null)
            {
                return texture;
            }
        }

        Link modelPng = model.combine("model.png");

        return links.contains(modelPng) ? modelPng : null;
    }

    /**
     * Per-object texture resolution for native BOBJ models. A saved pick wins,
     * then the {@code textures/<object>/} folder convention, then the model's
     * true default texture ({@code config.json} texture or real {@code model.png}).
     * A {@code null} fallback (no config texture, no model.png) is fine: the VAO
     * render mixins bind the model-level texture the caller already bound.
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
