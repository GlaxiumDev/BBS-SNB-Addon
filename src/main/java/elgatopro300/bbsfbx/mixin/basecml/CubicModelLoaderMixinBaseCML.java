package elgatopro300.bbsfbx.mixin.basecml;

import elgatopro300.bbsfbx.model.fbx.loaders.FBXCompiledData;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXMaterialTextureConfig;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXMeshCompiler;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXModelLoader;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXTextureResolverCML;
import elgatopro300.bbsfbx.model.fbx.loaders.IFbxModel;
import elgatopro300.bbsfbx.model.obj.OBJToBOBJConverter;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.model.loaders.CubicModelLoader;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.obj.MeshOBJ;
import mchorse.bbs_mod.obj.MeshesOBJ;
import mchorse.bbs_mod.obj.OBJMaterial;
import mchorse.bbs_mod.obj.OBJParser;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.StringUtils;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base/CML fix for native {@code CubicModelLoader.load}, which bakes every
 * OBJ material into a single {@code baked.png} atlas (one texture for the
 * whole model). This intercepts pure-OBJ models (no {@code model.bbs.json}
 * skeleton to merge) and routes them through the addon's per-material
 * pipeline instead: {@code OBJParser} -> {@link OBJToBOBJConverter} (one
 * {@code BOBJMesh} per OBJ material) -> the same {@code FBXCompiledData} +
 * {@code BOBJModel} path FBX models use, so the Base/CML VAO split issues one
 * draw call per material and the per-material texture picker / film-editor
 * sheets apply to OBJ models too.
 *
 * <p>Texture resolution per material, matching BBS FS's native OBJ loader: a
 * saved per-material pick ({@link FBXMaterialTextureConfig}), else the
 * material's own MTL {@code map_Kd} texture link, else the
 * {@code textures/<material>/} folder convention, else the material's flat Kd
 * color (the folder is created on disk for a PNG to be dropped in later).</p>
 *
 * <p>Models that combine a {@code .obj} with a {@code .bbs.json} keep their
 * native cubic-skeleton rendering (single atlas), as do OBJ-less models --
 * this only takes over when an OBJ file is the whole model. FS is untouched:
 * this mixin is gated to Base/CML by {@code BBSFbxMixinPlugin}.</p>
 */
@Mixin(value = CubicModelLoader.class, remap = false)
public abstract class CubicModelLoaderMixinBaseCML
{
    @Inject(method = "load", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$loadObjPerMaterial(
            String id, ModelManager models, Link model, Collection<Link> links, MapType config,
            CallbackInfoReturnable<ModelInstance> cir)
    {
        for (Link link : links)
        {
            if (link.path.endsWith(".bbs.json"))
            {
                return;
            }
        }

        Link mainObj = findMainObj(links, model);

        if (mainObj == null)
        {
            return;
        }

        /* OBJ models with shapes/ folders ride the native cubic path (shape
         * keys baked into ModelMesh.data) -- this takeover would silently
         * drop those, so leave them to the native loader. */
        for (Link link : links)
        {
            if (link.path.endsWith(".obj") && link.path.contains("/shapes/"))
            {
                return;
            }
        }

        Link modelTexture = IModelLoader.getLink(model.combine("model.png"), links, ".png");

        try
        {
            Map<String, MeshesOBJ> compile;
            Link mtl = new Link(mainObj.source, StringUtils.removeExtension(mainObj.path) + ".mtl");

            try (InputStream stream = models.provider.getAsset(mainObj))
            {
                if (stream == null)
                {
                    cir.setReturnValue(null);
                    return;
                }

                InputStream mtlStream = null;

                try
                {
                    mtlStream = models.provider.getAsset(mtl);
                }
                catch (Exception ignored)
                {
                }

                try (InputStream safeMtl = mtlStream)
                {
                    OBJParser parser = new OBJParser(stream, safeMtl);
                    parser.read();
                    compile = parser.compile();
                }
            }

            BOBJData bobjData = OBJToBOBJConverter.convert(compile);

            if (bobjData.meshes.isEmpty())
            {
                cir.setReturnValue(null);
                return;
            }

            bobjData.initiateArmatures();

            FBXCompiledData merged = FBXMeshCompiler.compileMergedWithMaterials(bobjData);

            if (merged.materialNames != null && merged.materialNames.length > 0)
            {
                resolveObjMaterialTextures(merged, compile, model, links, models.provider);
            }

            BOBJArmature armature = bobjData.armatures.values().iterator().next();
            BOBJModel bobjModel = FBXModelLoader.createModel(armature, merged);

            IFbxModel fbxModel = (IFbxModel) bobjModel;
            fbxModel.bbsFbx$setFbxData(merged);
            fbxModel.bbsFbx$setShapeKeyNames(null);

            ModelInstance instance = new ModelInstance(
                    id, bobjModel, new Animations(models.parser), modelTexture);

            instance.applyConfig(config);
            cir.setReturnValue(instance);
        }
        catch (Exception e)
        {
            System.err.println("[BBS FBX] Failed to load OBJ model for " + id + ": "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
            cir.setReturnValue(null);
        }
    }

    /** The model's top-level (non-{@code shapes/}) OBJ, mirroring native link discovery. */
    private static Link findMainObj(Collection<Link> links, Link model)
    {
        for (Link link : links)
        {
            if (link.path.endsWith(".obj") && link.path.startsWith(model.path + "/"))
            {
                String path = link.path.substring(model.path.length() + 1);

                if (!path.contains("/"))
                {
                    return link;
                }
            }
        }

        return null;
    }

    /**
     * Per-material texture resolution for OBJ models -- see the class doc for
     * the order. Names are the OBJ material names carried by the meshes, the
     * same names {@code FBXMeshCompiler} recorded into
     * {@code merged.materialNames}.
     */
    private static void resolveObjMaterialTextures(
            FBXCompiledData merged, Map<String, MeshesOBJ> compile,
            Link model, Collection<Link> links, AssetProvider provider)
    {
        Map<String, Link> saved = FBXMaterialTextureConfig.load(provider, model);
        Map<String, OBJMaterial> materialByName = new HashMap<>();

        for (MeshesOBJ value : compile.values())
        {
            for (MeshOBJ mesh : value.meshes)
            {
                if (mesh.material != null && mesh.material.name != null && !mesh.material.name.isEmpty())
                {
                    materialByName.putIfAbsent(mesh.material.name, mesh.material);
                }
            }
        }

        Link[] textures = new Link[merged.materialNames.length];

        for (int i = 0; i < merged.materialNames.length; i++)
        {
            String name = merged.materialNames[i];

            if (name == null || name.isEmpty())
            {
                continue;
            }

            Link chosen = saved.get(name);

            if (chosen != null)
            {
                textures[i] = chosen;
                continue;
            }

            OBJMaterial material = materialByName.get(name);
            Link resolved = null;

            if (material != null && material.useTexture)
            {
                resolved = resolveMtlTexture(material.texture, model, links);
            }

            if (resolved == null)
            {
                resolved = FBXTextureResolverCML.resolveMaterialTexture(name, model, links);
            }

            if (resolved == null && material != null && !material.useTexture)
            {
                resolved = FBXTextureResolverCML.colorLink(new float[] { material.r, material.g, material.b });
            }

            if (resolved != null)
            {
                textures[i] = resolved;
            }
            else
            {
                /* Never leave a material unbound (the raw-GL per-material loop
                 * would otherwise draw it with whatever texture happened to be
                 * bound before) -- same fallback as BBS FS's native OBJ loader:
                 * surface an empty textures/<material>/ folder on disk and bind
                 * the material's flat Kd color meanwhile. */
                FBXModelLoader.ensureMaterialFolder(provider, model, name);
                float r = 1.0F;
                float g = 1.0F;
                float b = 1.0F;

                if (material != null)
                {
                    r = material.r;
                    g = material.g;
                    b = material.b;
                }

                textures[i] = FBXTextureResolverCML.colorLink(new float[] { r, g, b });
            }
        }

        merged.setMaterialTextures(textures);
    }

    /**
     * The OBJ MTL's own {@code map_Kd} reference, matched against the model's
     * links (the parser stores it raw via {@code Link.create}; it may be
     * absolute or model-relative).
     */
    private static Link resolveMtlTexture(Link mtlTexture, Link model, Collection<Link> links)
    {
        if (mtlTexture == null || mtlTexture.path == null || mtlTexture.path.isEmpty())
        {
            return null;
        }

        Link combined = model.combine(mtlTexture.path);

        if (links.contains(combined))
        {
            return combined;
        }

        for (Link link : links)
        {
            if (link.path.endsWith(mtlTexture.path))
            {
                return link;
            }
        }

        return null;
    }
}
