package elgatopro300.bbsfbx.render;

import elgatopro300.bbsfbx.model.fbx.FBXShapeKeyModelCML;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXCompiledData;

import mchorse.bbs_mod.cubic.IModel;

import java.util.Collections;
import java.util.List;

/**
 * The actual {@code IMaterialTextureHolder} logic -- just the material name
 * list now -- factored out so {@code ModelInstanceMixinBase} and
 * {@code ModelInstanceMixinFS} don't each need their own copy.
 *
 * <p>Lives outside {@code elgatopro300.bbsfbx.mixin} (and its sub-packages)
 * for the same reason {@link MultiMaterialTriangleDraw} does -- see that
 * class's doc comment. {@code mixin.cml.ModelInstanceMixinCML} keeps its
 * own separate, already-shipped copy of this same logic rather than being
 * refactored onto this helper, matching the same "don't touch what's
 * already working" call made for {@code BOBJModelVAOMixinCML}.</p>
 *
 * <p>Originally also had {@code getMaterialTexture}/{@code setMaterialTexture}
 * methods here, reading/writing the chosen texture onto the shared
 * {@code FBXCompiledData} backing the model's mesh. Removed: that data is
 * cached globally by BBS keyed by model file path, shared by every Form (and
 * every morph) using the same model, so writing a per-material texture
 * choice there meant it leaked onto every other Form/morph too. The texture
 * choice itself now lives on {@code ModelForm} instead
 * (see {@code ModelFormMixin}, {@code IFormMaterialTextureHolder}), resolved
 * fresh per render call via {@link CurrentMaterialTextureOverrides} rather
 * than stored on anything shared.</p>
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
 * there yet, so {@link #getMaterials} returns empty and the picker falls
 * back to the ordinary single-texture button, same as any single-material
 * model.</p>
 */
public final class MaterialTextureDelegate
{
    private MaterialTextureDelegate() {}

    public static List<String> getMaterials(IModel model)
    {
        if (model instanceof FBXShapeKeyModelCML fbxModel
                && fbxModel.getMeshData() instanceof FBXCompiledData data
                && data.hasMultipleMaterials())
        {
            return List.of(data.materialNames);
        }

        return Collections.emptyList();
    }
}
