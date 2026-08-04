package elgatopro300.bbsfbx.mixin.basecml;

import elgatopro300.bbsfbx.model.fbx.loaders.FBXMaterialTextureConfig;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXModelLoader;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXTextureResolverCML;
import elgatopro300.bbsfbx.model.fbx.loaders.IModelMaterialTextures;
import elgatopro300.bbsfbx.model.fbx.loaders.IModelMeshMaterial;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.data.model.ModelMesh;
import mchorse.bbs_mod.cubic.model.ModelManager;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Base/CML fix for native {@code CubicModelLoader.load}, which bakes every
 * OBJ material into a single {@code baked.png} atlas (one texture for the
 * whole model). This intercepts pure-OBJ models (no {@code model.bbs.json}
 * skeleton to merge) and loads them as a NATIVE cubic model -- no BOBJ
 * conversion, no dummy armature, no bones -- with one {@code ModelGroup} per
 * OBJ object and one {@code ModelMesh} per OBJ {@code usemtl} group,
 * mirroring how BBS FS's own OBJ loader works. A car tyre/rim with four
 * materials is therefore ONE group again, not four.
 *
 * <p>Each mesh carries its material name ({@code ModelMeshMixin}), and
 * {@code CubicVAOBucketingBuilder} bakes one VAO per material inside each
 * group (exactly FS's structure). At draw time
 * {@code CubicVAORendererMixinBase} / {@code CubicVAORendererMixinCML} draw
 * one material at a time, binding each material's resolved texture
 * (per-Form override first, via {@code CurrentMaterialTextureOverrides},
 * then the material's loaded default). The per-material defaults themselves
 * live on the cubic {@code Model} ({@link IModelMaterialTextures}), which
 * is what makes the material picker, film-editor material sheets and form
 * properties all work unchanged for OBJ models.</p>
 *
 * <p>Per-material default resolution, matching BBS FS's native OBJ loader: a
 * saved per-material pick ({@link FBXMaterialTextureConfig}), else the
 * material's own MTL {@code map_Kd} texture link, else the
 * {@code textures/<material>/} folder convention, else the material's flat Kd
 * color (the folder is created on disk for a PNG to be dropped in later).
 * UVs stay normalized (model texture is 1x1), so each material's own texture
 * tiles its own UV space.</p>
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

            /* One ModelGroup per OBJ object, one ModelMesh per OBJ material
             * -- mirroring BBS FS's native CubicModelLoader.load. The group
             * name IS the object name; each mesh carries its material name
             * (ModelMeshMixin) so CubicVAOBucketingBuilder can bake one VAO
             * per material inside the group. */
            Model theModel = new Model(models.parser);
            theModel.textureWidth = 1;
            theModel.textureHeight = 1;

            List<String> materialNames = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();

            for (Map.Entry<String, MeshesOBJ> entry : compile.entrySet())
            {
                MeshesOBJ value = entry.getValue();

                if (value.meshes.isEmpty())
                {
                    continue;
                }

                ModelGroup group = new ModelGroup(entry.getKey());
                theModel.topGroups.add(group);

                for (MeshOBJ mesh : value.meshes)
                {
                    ModelMesh modelMesh = new ModelMesh();
                    modelMesh.baseData.fill(mesh, theModel.textureWidth, theModel.textureHeight);

                    String name = mesh.material != null && mesh.material.name != null ? mesh.material.name : "";
                    ((IModelMeshMaterial) modelMesh).bbsFbx$setMaterial(name);

                    if (!name.isEmpty() && seen.add(name))
                    {
                        materialNames.add(name);
                    }

                    group.meshes.add(modelMesh);
                }
            }

            if (theModel.topGroups.isEmpty())
            {
                cir.setReturnValue(null);
                return;
            }

            theModel.initialize();

            Map<String, Link> materialTextures = resolveObjMaterialTextures(
                    materialNames, compile, model, links, models.provider);

            ((IModelMaterialTextures) theModel).bbsFbx$setMaterialTextures(materialNames, materialTextures);

            ModelInstance instance = new ModelInstance(id, theModel, new Animations(models.parser), modelTexture);

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
     * Per-material default texture resolution for OBJ models -- see the class
     * doc for the order. Keys are the OBJ material names the meshes were
     * built from.
     */
    private static Map<String, Link> resolveObjMaterialTextures(
            List<String> materials, Map<String, MeshesOBJ> compile,
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

        Map<String, Link> result = new LinkedHashMap<>();

        for (String name : materials)
        {
            Link chosen = saved.get(name);

            if (chosen != null)
            {
                result.put(name, chosen);
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

            if (resolved == null)
            {
                /* Never leave a material unbound (the per-group draw would
                 * otherwise show it with whatever texture happened to be
                 * bound before) -- same fallback as BBS FS's native OBJ
                 * loader: surface an empty textures/<material>/ folder on
                 * disk and bind the material's flat Kd color meanwhile. */
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

                resolved = FBXTextureResolverCML.colorLink(new float[] { r, g, b });
            }

            result.put(name, resolved);
        }

        return result;
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
