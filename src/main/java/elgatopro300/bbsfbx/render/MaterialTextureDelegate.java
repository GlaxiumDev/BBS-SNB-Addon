package elgatopro300.bbsfbx.render;

import elgatopro300.bbsfbx.model.fbx.FBXShapeKeyModelCML;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXCompiledData;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXMaterialTextureConfig;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The actual {@code IMaterialTextureHolder} logic (read the material list,
 * read/write one material's texture override, persist to the sidecar file),
 * factored out so {@code ModelInstanceMixinBase} and
 * {@code ModelInstanceMixinFS} don't each need their own copy.
 *
 * <p>Lives outside {@code elgatopro300.bbsfbx.mixin} (and its sub-packages)
 * for the same reason {@link MultiMaterialTriangleDraw} does -- see that
 * class's doc comment. {@code mixin.cml.ModelInstanceMixinCML} keeps its
 * own separate, already-shipped copy of this same logic rather than being
 * refactored onto this helper, matching the same "don't touch what's
 * already working" call made for {@code BOBJModelVAOMixinCML}.</p>
 *
 * <p>Checks against {@link FBXShapeKeyModelCML} specifically, which -- despite
 * the name -- is confirmed shared between Base and CML (see that class's own
 * doc comment: {@code BOBJModel}'s constructor is identical on both). It is
 * NOT what BBS FS's own FBX models actually load as, since FS needs its own
 * separate {@code List<CompiledData>}-shaped model loader that doesn't exist
 * in this addon yet (tracked in {@code MIGRATION.md}). So calling this from
 * {@code ModelInstanceMixinFS} is technically wired up and harmless, but
 * won't actually show multi-material buttons on FS until that loader gap is
 * closed -- the model will just never be an {@code FBXShapeKeyModelCML}
 * there yet, so {@link #materialData} returns {@code null} and the picker
 * falls back to the ordinary single-texture button, same as any
 * single-material model.</p>
 */
public final class MaterialTextureDelegate
{
    private MaterialTextureDelegate() {}

    private static FBXCompiledData materialData(IModel model)
    {
        if (model instanceof FBXShapeKeyModelCML fbxModel
                && fbxModel.getMeshData() instanceof FBXCompiledData data
                && data.hasMultipleMaterials())
        {
            return data;
        }

        return null;
    }

    public static List<String> getMaterials(IModel model)
    {
        FBXCompiledData data = materialData(model);

        return data == null ? Collections.emptyList() : List.of(data.materialNames);
    }

    public static Link getMaterialTexture(IModel model, String material)
    {
        FBXCompiledData data = materialData(model);

        if (data == null)
        {
            return null;
        }

        int index = indexOf(data.materialNames, material);

        return index >= 0 && data.materialTextures != null ? data.materialTextures[index] : null;
    }

    public static void setMaterialTexture(IModel model, String instanceId, String material, Link link)
    {
        FBXCompiledData data = materialData(model);

        if (data == null)
        {
            return;
        }

        int index = indexOf(data.materialNames, material);

        if (index < 0)
        {
            return;
        }

        if (data.materialTextures == null)
        {
            data.materialTextures = new Link[data.materialNames.length];
        }

        data.materialTextures[index] = link;

        persist(instanceId, data);
    }

    /**
     * {@code instanceId} is the model's own asset key (same string
     * {@code ModelManager.loadModel(id)} was called with) -- {@code
     * ModelManager} itself builds the actual model {@code Link} loaders
     * receive as {@code Link.assets(MODELS_PREFIX + id)}
     * ({@code ModelManager.loadModel}, confirmed directly, same as CML's own
     * copy of this logic already relies on), which is reproduced here so the
     * sidecar file ends up at the exact same path {@code FBXModelLoaderCML}
     * reads/writes it at.
     */
    private static void persist(String instanceId, FBXCompiledData data)
    {
        AssetProvider provider = BBSModClient.getModels().provider;
        Link model = Link.assets(ModelManager.MODELS_PREFIX + instanceId);

        Map<String, Link> all = new LinkedHashMap<>();

        for (int i = 0; i < data.materialNames.length; i++)
        {
            all.put(data.materialNames[i], data.materialTextures != null ? data.materialTextures[i] : null);
        }

        FBXMaterialTextureConfig.save(provider, model, all);
    }

    private static int indexOf(String[] names, String name)
    {
        for (int i = 0; i < names.length; i++)
        {
            if (names[i].equals(name))
            {
                return i;
            }
        }

        return -1;
    }
}
