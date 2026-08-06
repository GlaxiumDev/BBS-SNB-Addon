package glaxium.snb.mixin.cml;

import glaxium.snb.render.MaterialTextureDelegate;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.utils.StringUtils;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * CML-only gate for the per-material film tracks. CML's
 * {@code UIReplaysEditor.updateChannelsList} filters every collected path
 * through {@code isCompatiblePropertyPath(Form, String)}, which rejects any
 * path without a real form property -- exactly what a synthetic material
 * channel is. This lets the material paths from {@code FormUtilsMixin}
 * through to {@code FormProperties.getOrCreate}. Base has no such filter
 * (its copy is a different class shape), so this mixin is gated to CML by
 * {@code glaxium.snb.BBSFbxMixinPlugin}.</p>
 *
 * <p>Beyond the gate it also lays the material tracks out the way the film
 * editor should show them: on a multi-material model the whole-model
 * {@code texture} track (and its nested whole-model PBR tracks) is hidden,
 * each material sheet becomes expandable and nests its own per-material PBR
 * sub-tracks, and the material block is moved above the {@code model}
 * track.</p>
 */
@Mixin(value = UIReplaysEditor.class, remap = false)
public abstract class UIReplaysEditorMixinCML
{
    @Shadow
    private Replay replay;

    @Shadow
    private Map<String, Boolean> collapsedModelTracks;

    @Shadow
    private void updateChannelsList() {};

    @Inject(
            method = "isCompatiblePropertyPath(Lmchorse/bbs_mod/forms/forms/Form;Ljava/lang/String;)Z",
            at = @At("HEAD"), cancellable = true, remap = false
    )
    private void bbsFbx$allowMaterialPaths(Form form, String path, CallbackInfoReturnable<Boolean> info)
    {
        if (form == null || path == null)
        {
            return;
        }

        if (MaterialTextureDelegate.hasMultipleMaterials(form) && path.equals("texture"))
        {
            info.setReturnValue(false);

            return;
        }

        if (MaterialTextureDelegate.isMaterial(form, path) || MaterialTextureDelegate.isMaterialPbrChannel(form, path))
        {
            info.setReturnValue(true);
        }
    }

    /**
     * Gives every material sheet a synthetic {@code BaseValueBasic} (see
     * {@link MaterialTextureDelegate#materialSheetProperty}) so new
     * keyframes default to the material's texture instead of the link
     * factory's {@code error.png} fallback. {@code updateChannelsList} reads
     * the sheet property via {@code FormUtils.getProperty(form, key)}, which
     * is null for a material key -- this swaps in the synthetic one. CML's
     * per-material PBR sub-tracks get a synthetic {@code ValueFloat} the
     * same way, so their new keyframes default to {@code 1.0} (the neutral
     * PBR intensity) rather than the float factory's {@code 0.0}.
     */
    @Redirect(
            method = "updateChannelsList()V",
            at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/forms/FormUtils;getProperty(Lmchorse/bbs_mod/forms/forms/Form;Ljava/lang/String;)Lmchorse/bbs_mod/settings/values/base/BaseValueBasic;"),
            remap = false
    )
    private BaseValueBasic bbsFbx$materialSheetProperty(Form form, String key)
    {
        BaseValueBasic property = FormUtils.getProperty(form, key);

        if (property != null)
        {
            return property;
        }

        if (MaterialTextureDelegate.isMaterial(form, key))
        {
            return MaterialTextureDelegate.materialSheetProperty(form, key);
        }

        return MaterialTextureDelegate.materialPbrSheetProperty(form, key);
    }

    /**
     * After the vanilla priority sort, moves the whole material block
     * (material sheets + their nested PBR sub-tracks) above the
     * {@code model} track. The vanilla {@code getPriority} gives material
     * paths the {@code 500} fallback (bottom of the list); rather than
     * reimplementing its whole priority ladder, the original sort runs
     * untouched and then the material sheets are lifted in a single stable
     * block to right above the first sheet whose file name is {@code model}.
     * The second {@code List.sort} in {@code updateChannelsList} sorts the
     * sub-form path strings, which is passed straight through untouched.
     */
    @Redirect(
            method = "updateChannelsList()V",
            at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V"),
            remap = false
    )
    private void bbsFbx$sortChannels(List<UIKeyframeSheet> sheets, Comparator<UIKeyframeSheet> comparator)
    {
        if (sheets.isEmpty() || !(sheets.get(0) instanceof UIKeyframeSheet))
        {
            ((List<Object>) (Object) sheets).sort((Comparator<Object>) (Object) comparator);

            return;
        }

        sheets.sort(comparator);
        this.bbsFbx$moveMaterialTracksAboveModel(sheets);
    }

    private void bbsFbx$moveMaterialTracksAboveModel(List<UIKeyframeSheet> sheets)
    {
        List<UIKeyframeSheet> materials = new ArrayList<>();
        Map<String, List<UIKeyframeSheet>> pbrChildren = new java.util.LinkedHashMap<>();
        List<UIKeyframeSheet> kept = new ArrayList<>();

        for (UIKeyframeSheet sheet : sheets)
        {
            if (!this.bbsFbx$isMaterialSheet(sheet))
            {
                kept.add(sheet);

                continue;
            }

            String material = MaterialTextureDelegate.materialFromPbrPath(sheet.id);

            if (material == null)
            {
                materials.add(sheet);
            }
            else
            {
                pbrChildren.computeIfAbsent(material, key -> new ArrayList<>()).add(sheet);
            }
        }

        if (materials.isEmpty() && pbrChildren.isEmpty())
        {
            return;
        }

        List<UIKeyframeSheet> ordered = new ArrayList<>();

        for (UIKeyframeSheet material : materials)
        {
            ordered.add(material);

            List<UIKeyframeSheet> children = pbrChildren.remove(material.id);

            if (children != null)
            {
                ordered.addAll(children);
            }
        }

        for (List<UIKeyframeSheet> children : pbrChildren.values())
        {
            ordered.addAll(children);
        }

        sheets.clear();
        sheets.addAll(kept);

        int modelIndex = -1;

        for (int i = 0; i < sheets.size(); i++)
        {
            if (StringUtils.fileName(sheets.get(i).id).equals("model"))
            {
                modelIndex = i;

                break;
            }
        }

        if (modelIndex == -1)
        {
            sheets.addAll(ordered);
        }
        else
        {
            sheets.addAll(modelIndex, ordered);
        }
    }

    private boolean bbsFbx$isMaterialSheet(UIKeyframeSheet sheet)
    {
        if (sheet == null || sheet.property == null)
        {
            return false;
        }

        Form form = FormUtils.getForm(sheet.property);

        return form != null && (MaterialTextureDelegate.isMaterial(form, sheet.id)
                || MaterialTextureDelegate.isMaterialPbrChannel(form, sheet.id));
    }

    /**
     * Routes material sheets and their PBR sub-tracks through the film
     * editor's track-nesting machinery. Material sheets become expandable
     * (like the {@code texture} sheet) with their own collapse key, and the
     * per-material PBR sub-tracks hide while that key is collapsed and
     * render one level deeper with the CML PBR labels when expanded. The
     * whole-model {@code texture}/{@code pbr_*} tracks (and everything else)
     * are left to the original method.
     */
    @Inject(
            method = "processTrack(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;Ljava/lang/String;ILjava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;)V",
            at = @At("HEAD"), cancellable = true, remap = false
    )
    private void bbsFbx$processMaterialTrack(UIKeyframeSheet sheet, String groupKey, int level,
            List<UIKeyframeSheet> before, List<UIKeyframeSheet> pose, List<UIKeyframeSheet> limbs,
            List<UIKeyframeSheet> overlayRoots, List<UIKeyframeSheet> overlayLimbs,
            List<UIKeyframeSheet> after, CallbackInfo info)
    {
        if (sheet == null || sheet.property == null)
        {
            return;
        }

        Form form = FormUtils.getForm(sheet.property);

        if (form == null)
        {
            return;
        }

        /* On a multi-material model the whole-model texture/PBR tracks are replaced by the
         * per-material ones - drop them (the "texture" path is already filtered out of
         * collectPropertyPaths/isCompatiblePropertyPath, but the whole-model PBR paths are
         * real ModelForm properties, so they still reach processTrack and must be dropped here). */
        if (MaterialTextureDelegate.hasMultipleMaterials(form)
                && (sheet.id.equals("texture")
                || sheet.id.equals("pbr_normal_intensity")
                || sheet.id.equals("pbr_specular_intensity")))
        {
            info.cancel();

            return;
        }

        if (MaterialTextureDelegate.isMaterial(form, sheet.id))
        {
            String parentKey = this.bbsFbx$replayUuid() + ":" + sheet.id;

            /* Material sheets get the same texture icon as the whole-model {@code texture}
             * sheet (and as FS's native per-material tracks) - the vanilla getIcon() gives a
             * bare material name Icons.NONE, so it's swapped here for the real one. */
            sheet.icon(UIReplaysEditor.getIcon("texture"));

            bbsFbx$setSheetField(sheet, "level", level);
            bbsFbx$setSheetField(sheet, "expanded", this.collapsedModelTracks.getOrDefault(parentKey, true) == false);
            bbsFbx$setSheetField(sheet, "toggleExpanded", (Runnable) () -> {
                this.collapsedModelTracks.put(parentKey, this.collapsedModelTracks.getOrDefault(parentKey, true) == false);
                this.updateChannelsList();
            });

            String customTitle = this.bbsFbx$customSheetTitle(sheet.id);

            if (customTitle != null && !customTitle.isEmpty())
            {
                bbsFbx$setSheetField(sheet, "title", IKey.constant(customTitle));
            }

            before.add(sheet);
            info.cancel();

            return;
        }

        String material = MaterialTextureDelegate.materialFromPbrPath(sheet.id);

        if (material != null && MaterialTextureDelegate.isMaterial(form, material))
        {
            String parentKey = this.bbsFbx$replayUuid() + ":" + material;

            if (this.collapsedModelTracks.getOrDefault(parentKey, true))
            {
                info.cancel();

                return;
            }

            /* The material label now carries the texture icon (see the material branch above),
             * which pushes it one icon-width further right than the bare label it replaced; the
             * PBR children indent one extra step so their text lines up under it instead of
             * looking like a misaligned child row. */
            bbsFbx$setSheetField(sheet, "level", level + 2);

            String suffix = sheet.id.substring(sheet.id.lastIndexOf(':') + 1);

            bbsFbx$setSheetField(sheet, "title", suffix.equals("pbr_normal_intensity")
                    ? bbsFbx$pbrKey("FILM_REPLAY_TRACK_PBR_NORMAL_INTENSITY")
                    : bbsFbx$pbrKey("FILM_REPLAY_TRACK_PBR_SPECULAR_INTENSITY"));

            before.add(sheet);
            info.cancel();
        }
    }

    /**
     * CML-only {@code Replay.uuid} ({@code ValueString}) - read via
     * reflection so this class still compiles against Base/FS (where the
     * mixin is never applied).
     */
    private String bbsFbx$replayUuid()
    {
        try
        {
            Object uuid = Replay.class.getField("uuid").get(this.replay);
            Object value = uuid.getClass().getMethod("get").invoke(uuid);

            return value == null ? "" : value.toString();
        }
        catch (ReflectiveOperationException ignored)
        {
            return "";
        }
    }

    /**
     * CML-only {@code Replay.getCustomSheetTitle(String)} - invoked via
     * reflection so this class still compiles against Base/FS.
     */
    private String bbsFbx$customSheetTitle(String id)
    {
        try
        {
            Object title = Replay.class.getMethod("getCustomSheetTitle", String.class).invoke(this.replay, id);

            return title == null ? null : title.toString();
        }
        catch (ReflectiveOperationException ignored)
        {
            return null;
        }
    }

    /**
     * CML-only {@code UIKeys.FILM_REPLAY_TRACK_PBR_*_INTENSITY} keys - read
     * via reflection so this class still compiles against Base/FS, falling
     * back to a plain label when absent.
     */
    private static IKey bbsFbx$pbrKey(String name)
    {
        try
        {
            Object key = UIKeys.class.getField(name).get(null);

            if (key instanceof IKey)
            {
                return (IKey) key;
            }
        }
        catch (ReflectiveOperationException ignored)
        {
            // Not present on this fork - fall back to a plain label.
        }

        return IKey.constant(name);
    }

    /**
     * CML-only {@code UIKeyframeSheet.level}/{@code expanded}/
     * {@code toggleExpanded}/{@code title} fields - set via reflection so
     * this class still compiles against Base/FS, where those fields don't
     * exist (Base even declares {@code title} {@code final}). Only ever
     * invoked on CML at runtime, where all of them are public.
     */
    private static void bbsFbx$setSheetField(UIKeyframeSheet sheet, String name, Object value)
    {
        try
        {
            UIKeyframeSheet.class.getField(name).set(sheet, value);
        }
        catch (ReflectiveOperationException ignored)
        {
            // Not present on this fork - nothing to do.
        }
    }
}
