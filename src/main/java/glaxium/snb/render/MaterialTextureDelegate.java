package glaxium.snb.render;

import glaxium.snb.model.bobj.EmoticonArmorSidecar;
import glaxium.snb.model.fbx.loaders.FBXCompiledData;
import glaxium.snb.model.fbx.loaders.IFbxModel;
import glaxium.snb.model.fbx.loaders.IModelMaterialTextures;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The {@code IMaterialTextureHolder} logic -- material names, and the
 * shared per-material DEFAULT texture -- factored out so
 * {@code ModelInstanceMixinBase} and {@code ModelInstanceMixinFS} don't
 * each need their own copy. {@code mixin.cml.ModelInstanceMixinCML} keeps
 * its own separate, already-shipped copy of this same logic rather than
 * being refactored onto this helper, matching the same "don't touch what's
 * already working" call made for {@code BOBJModelVAOMixinCML}.
 *
 * <p>Originally also had {@code getMaterialTexture}/{@code setMaterialTexture}
 * methods here that read/wrote the CHOSEN (per-Form) texture onto the
 * shared {@code FBXCompiledData} backing the model's mesh. That's the part
 * that was genuinely broken and got removed: that data is cached globally
 * by BBS keyed by model file path, shared by every Form (and every morph)
 * using the same model, so writing a per-Form choice there leaked it onto
 * every other Form/morph too. That choice now lives on {@code ModelForm}
 * instead (see {@code ModelFormMixin}, {@code IFormMaterialTextureHolder}),
 * resolved fresh per render call via {@link CurrentMaterialTextureOverrides}
 * rather than stored on anything shared.</p>
 *
 * <p><b>{@link #getDefaultMaterialTexture} is a DIFFERENT thing, and was
 * cut from this class by mistake in that same pass</b> -- it's a pure
 * read, of the material's own resolved {@code textures/<material>/} folder
 * default computed once at load time by {@code FBXModelLoader} and
 * stored on {@code FBXCompiledData.materialTextures}. That data is exactly
 * as safe to share as the material NAME list already was (every Form using
 * this model file sees the same resolved default, same as they'd see the
 * same whole-model default texture) - it just isn't where a per-Form
 * CHOICE should ever be written. Removing read access to it entirely was
 * an over-correction: with nowhere left to read it from, every
 * un-overridden material fell through straight to the whole-model default
 * texture (frequently null on a genuinely multi-material FBX, which has no
 * single texture that makes sense for the whole mesh) instead of its own
 * resolved one -- the flat, textureless/grey rendering this reintroduces
 * the fix for.</p>
 *
 * <p>Checks against {@link IFbxModel} specifically -- the mixin interface
 * {@code BOBJModelMixin} adds to {@code BOBJModel} on every fork, so this
 * works identically for Base, FS and CML now that the FBX model is a plain
 * {@code BOBJModel} (the old per-fork {@code FBXShapeKeyModel} subclasses
 * are gone -- see {@code BOBJModelMixin}'s doc comment).</p>
 */
public final class MaterialTextureDelegate
{
    private MaterialTextureDelegate() {}

    private static FBXCompiledData materialData(IModel model)
    {
        if (model instanceof IFbxModel fbxModel)
        {
            FBXCompiledData data = fbxModel.bbsFbx$getFbxData();

            if (data != null && data.hasMultipleMaterials())
            {
                return data;
            }
        }

        return null;
    }

    public static List<String> getMaterials(IModel model)
    {
        FBXCompiledData data = materialData(model);

        if (data != null)
        {
            return uiMaterials(data.materialNames);
        }

        if (model instanceof IModelMaterialTextures cubic)
        {
            return cubic.bbsFbx$getMaterials();
        }

        return Collections.emptyList();
    }

    /**
     * UI-facing material list. The armor sidecar shells are equipped-state
     * geometry, not selectable/animated materials: they must never appear in
     * the model panel's per-material pick menu or the film editor's material
     * tracks, on any fork. The per-material RENDER loop reads
     * {@code FBXCompiledData.materialNames} directly and is unaffected.
     */
    private static List<String> uiMaterials(String[] materialNames)
    {
        if (materialNames == null || materialNames.length == 0)
        {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>(materialNames.length);

        for (String name : materialNames)
        {
            if (name != null && !EmoticonArmorSidecar.isArmorMesh(name))
            {
                result.add(name);
            }
        }

        return result;
    }

    /** Materials of the FBX model behind this Form, empty when the Form isn't a multi-material FBX {@code ModelForm}. */
    public static List<String> getMaterials(Form form)
    {
        IModel model = getModel(form);

        return model == null ? Collections.emptyList() : getMaterials(model);
    }

    /**
     * True when the FBX model behind this Form has more than one material.
     * This is the "multi-texture" condition the film editor uses to decide
     * whether the whole-model {@code texture} track is redundant (each
     * material already has its own track) and should be hidden.
     */
    public static boolean hasMultipleMaterials(Form form)
    {
        return getMaterials(form).size() > 1;
    }

    /**
     * Material name behind a per-material PBR channel path, or null. The CML
     * fork's film editor nests {@code pbr_normal_intensity} /
     * {@code pbr_specular_intensity} sub-tracks under each material sheet,
     * keyed {@code <material>:pbr_normal_intensity} / {@code ...:pbr_specular_intensity}
     * (the same {@code parent:child} convention the pose limb tracks use).
     */
    public static String materialFromPbrPath(String path)
    {
        if (path == null)
        {
            return null;
        }

        if (!path.endsWith(":pbr_normal_intensity") && !path.endsWith(":pbr_specular_intensity"))
        {
            return null;
        }

        int colon = path.lastIndexOf(':');

        return colon > 0 ? path.substring(0, colon) : null;
    }

    /** True when {@code path} is a per-material PBR channel of this Form's FBX model (CML). */
    public static boolean isMaterialPbrChannel(Form form, String path)
    {
        String material = materialFromPbrPath(path);

        return material != null && isMaterial(form, material);
    }

    /**
     * True when {@code name} is one of the FBX materials behind this Form.
     * The film editor's synthetic per-material keyframe channels are keyed
     * exactly by the material name (no prefix - the track label IS the
     * material name, same as FS's material sheets), so this doubles as the
     * "is this a material channel" check. {@code getProperty(form, name)}
     * is checked separately by callers so a real form property that happens
     * to share a name always wins.
     */
    public static boolean isMaterial(Form form, String name)
    {
        IModel model = getModel(form);

        return model != null && name != null && !name.isEmpty() && isMaterial(model, name);
    }

    public static boolean isMaterial(IModel model, String name)
    {
        if (name == null)
        {
            return false;
        }

        /* Armor shells are never material channels, even though they exist in
         * the compiled data -- keeps any old armor keyframes in a film from
         * applying (and from being recreated in the editor). */
        if (EmoticonArmorSidecar.isArmorMesh(name))
        {
            return false;
        }

        FBXCompiledData data = materialData(model);

        if (data != null)
        {
            for (String material : data.materialNames)
            {
                if (material.equals(name))
                {
                    return true;
                }
            }

            return false;
        }

        return model instanceof IModelMaterialTextures cubic && cubic.bbsFbx$getMaterials().contains(name);
    }

    private static IModel getModel(Form form)
    {
        if (form instanceof ModelForm modelForm)
        {
            ModelInstance instance = ModelFormRenderer.getModel(modelForm);

            if (instance != null)
            {
                return instance.model;
            }
        }

        return null;
    }

    /** The shared, resolved-at-load-time default texture for one material - see class doc. Null if nothing was resolved for it. */
    public static Link getDefaultMaterialTexture(IModel model, String material)
    {
        FBXCompiledData data = materialData(model);

        if (data != null)
        {
            int index = indexOf(data.materialNames, material);

            return index >= 0 && data.materialTextures != null && index < data.materialTextures.length
                    ? data.materialTextures[index]
                    : null;
        }

        return model instanceof IModelMaterialTextures cubic ? cubic.bbsFbx$getDefaultMaterialTexture(material) : null;
    }

    /**
     * The texture one material should be drawn with right now: the current
     * Form's per-material override ({@link CurrentMaterialTextureOverrides})
     * if one is set, else the material's shared loaded default. Used by the
     * cubic-renderer mixins ({@code CubicVAORendererMixinBase},
     * {@code CubicVAORendererMixinCML}) exactly the way the per-material
     * {@code BOBJModelVAO} mixins resolve it, so OBJ models render
     * multi-textured on the native cubic path.
     */
    public static Link resolveMaterialTexture(IModel model, String material)
    {
        Map<String, Link> overrides = CurrentMaterialTextureOverrides.current();

        if (material != null)
        {
            Link override = overrides.get(material);

            if (override != null)
            {
                return override;
            }
        }

        return getDefaultMaterialTexture(model, material);
    }

    /**
     * Synthetic {@code BaseValueBasic} backing a per-material film track's
     * {@code UIKeyframeSheet} on Base/CML. The vanilla default-value path
     * used when a new keyframe is created reads {@code property.get()} when
     * the sheet has a property and falls back to
     * {@code KeyframeChannel.getFactory().createEmpty()} (the error texture
     * for link channels) when it does not -- material channels have no real
     * form property, so without this every new material keyframe would
     * default to {@code bbs:textures/error.png}. Mirroring FS's native
     * per-material implementation (which hands each material sheet a
     * {@code ValueLink} seeded with the material default), the parent is set
     * so {@code FormUtils.getForm(property)} still resolves the owning form
     * for track grouping/sorting.
     */
    public static BaseValueBasic materialSheetProperty(Form form, String material)
    {
        IModel model = getModel(form);
        Link link = getDefaultMaterialTexture(model, material);
        ValueLink property = new ValueLink(material, link);

        property.setParent(form);

        return property;
    }

    /**
     * Synthetic {@code BaseValueBasic} backing a per-material PBR sub-track
     * on the CML fork ({@code <material>:pbr_normal_intensity} /
     * {@code <material>:pbr_specular_intensity}). A {@code ValueFloat}
     * seeded with the neutral intensity ({@code 1.0}) and parented to the
     * owning Form, so new keyframes default to {@code 1.0} instead of the
     * float factory's {@code createEmpty()} ({@code 0.0}, which would mean
     * "no PBR effect") and so {@code FormUtils.getForm(property)} still
     * resolves the owning Form for grouping/sorting. Returns null for any
     * path that isn't a per-material PBR path.
     */
    public static BaseValueBasic materialPbrSheetProperty(Form form, String path)
    {
        String material = materialFromPbrPath(path);

        if (material == null || !isMaterial(form, material))
        {
            return null;
        }

        ValueFloat property = new ValueFloat(path, 1.0f, 0.0f, 4.0f);

        property.setParent(form);

        return property;
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
