package glaxium.snb.mixin;

import glaxium.snb.model.fbx.loaders.IFormMaterialTextureHolder;
import glaxium.snb.render.MaterialPbrIntensity;

import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.utils.resources.LinkUtils;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds a genuine per-Form, persisted material-texture-override property to
 * {@code ModelForm} -- the actual fix for "editing one Form's material
 * texture also changes every other Form (and morph) using the same model".
 * See {@link glaxium.snb.render.CurrentMaterialTextureOverrides}'s
 * doc comment for the full root-cause explanation.
 *
 * <p>Confirmed directly against the REAL decompiled {@code ModelForm} from
 * this project's actual jar (not the older/different-build jar this
 * addon's own investigation had been checking up to this point -- see the
 * "what I could not fully verify" note below): its constructor calls
 * {@code this.add(this.texture)}, {@code this.add(this.model)}, etc. for
 * every property, with {@code add} accepting {@code ValueLink},
 * {@code ValueString}, {@code ValueFloat}, {@code ValuePose},
 * {@code ValueActionsConfig}, {@code ValueColor}, and
 * {@code ValueShapeKeys} -- all different concrete types, so {@code add}
 * must take some common supertype. {@code ValueString} is used directly
 * (matching {@code ModelForm.model}'s own declaration, confirmed), same
 * "small text blob" format {@code FBXMaterialTextureConfig} already used.</p>
 *
 * <p><b>{@code add(...)} itself is called reflectively, not directly.</b>
 * {@code Form.class} in the jar this addon's own earlier investigation had
 * been checking (a different, apparently older build than what this
 * project's Gradle setup actually resolves -- same drift already hit
 * several times with other APIs in this addon) does NOT declare {@code add}
 * at all, meaning it's inherited from somewhere else in the real hierarchy
 * this project actually compiles against. Rather than guess its exact
 * declaring class or parameter type a third time in this addon (two earlier
 * guesses about internal BBS render APIs already broke the build once
 * each), this searches {@code ModelForm}'s own method table at runtime for
 * a single-argument method named {@code add} and invokes whichever one it
 * finds, wrapped in the same reflective fail-soft try/catch pattern this
 * addon already uses for {@code UITexturePicker.withFormPreview}. If this
 * guess is somehow still wrong, the property just silently isn't
 * registered -- material texture choices still work for the rest of the
 * current session (this addon's own field still holds the value), they
 * just won't survive a save/reload, and the Form/morph isolation fix below
 * is unaffected either way since that part never depends on {@code add()}
 * having succeeded.</p>
 */
@Mixin(value = ModelForm.class, remap = false)
public abstract class ModelFormMixin implements IFormMaterialTextureHolder
{
    @Unique
    private final ValueString bbsFbx$materialTextures = new ValueString("bbsfbx_material_textures", "");

    /**
     * Runtime-only overrides written by the film editor on Base/CML (see
     * {@link IFormMaterialTextureHolder#bbsFbx$setRuntimeMaterialTextureOverride}).
     * Never persisted, and cleared/rewritten every playback tick -- the film's
     * counterpart to {@code setRuntimeValue} on ordinary form properties and
     * to FS's native {@code ModelForm.materialTextureOverrides}. Lives above
     * the persisted ValueString choice in the merge order.
     */
    @Unique
    private final Map<String, Link> bbsFbx$runtimeMaterialTextureOverrides = new LinkedHashMap<>();

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void bbsFbx$registerMaterialTexturesValue(CallbackInfo info)
    {
        this.bbsFbx$materialTextures.invisible();

        try
        {
            for (Method method : this.getClass().getMethods())
            {
                if (method.getName().equals("add") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(this.bbsFbx$materialTextures))
                {
                    method.invoke(this, this.bbsFbx$materialTextures);

                    return;
                }
            }
        }
        catch (ReflectiveOperationException ignored)
        {
            // Couldn't register - see class doc. Falls back to session-only (non-persisted) overrides.
        }
    }

    /** Parsed straight from {@link #bbsFbx$materialTextures} each call - cheap enough (a handful of materials, called once per render), and avoids a second source of truth to keep in sync. On FS, the native film editor's per-material keyframes write into {@code ModelForm.materialTextureOverrides} (see {@link #bbsFbx$mergeNativeOverrides}) and those are merged in on top. */
    @Override
    @Unique
    public Map<String, Link> bbsFbx$getMaterialTextureOverrides()
    {
        Map<String, Link> result = bbsFbx$parsePersistedOverrides();

        result.putAll(this.bbsFbx$runtimeMaterialTextureOverrides);
        bbsFbx$mergeNativeOverrides(result);

        return result;
    }

    /** Just the persisted choices - no runtime or native-FS overrides mixed in. Used by {@link #bbsFbx$setMaterialTextureOverride} so a save never accidentally bakes in whatever's merely animating at that moment (see that method's own doc comment). */
    @Unique
    private Map<String, Link> bbsFbx$parsePersistedOverrides()
    {
        Map<String, Link> result = new LinkedHashMap<>();
        String raw = (String) this.bbsFbx$materialTextures.get();

        if (raw != null && !raw.isEmpty())
        {
            for (String line : raw.split("\n"))
            {
                line = line.trim();

                if (line.isEmpty())
                {
                    continue;
                }

                int eq = line.indexOf('=');

                if (eq <= 0)
                {
                    continue;
                }

                String material = line.substring(0, eq);
                Link link = bbsFbx$parseLink(line.substring(eq + 1));

                if (link != null)
                {
                    result.put(material, link);
                }
            }
        }

        return result;
    }

    /**
     * FS's film editor keyframes each material's texture into the native
     * {@code ModelForm.materialTextureOverrides} map (its
     * {@code FormProperties.applyProperty} writes there directly) -- a field
     * that only exists on the FS fork, so it's reached reflectively to keep
     * this fork-agnostic mixin compiling against Base/CML jars. Merged over
     * this Form's persisted ValueString overrides: the film's per-tick,
     * time-based keyframes are the authoritative source while they're
     * playing, and the static picker-menu choice is the fallback either side
     * of them (or on the forks without the map). Every consumer -- the
     * render push in {@code ModelFormRendererMixin*} and the picker menu in
     * {@code UIModelFormPanelMixin} -- reads the merged result.
     */
    @Unique
    private void bbsFbx$mergeNativeOverrides(Map<String, Link> result)
    {
        if (bbsFbx$materialTextureOverridesField == null)
        {
            try
            {
                bbsFbx$materialTextureOverridesField = ModelForm.class.getField("materialTextureOverrides");
            }
            catch (NoSuchFieldException ignored)
            {
                // Base/CML have no native material-texture override map - nothing to merge.
                return;
            }
        }

        try
        {
            if (bbsFbx$materialTextureOverridesField.get(this) instanceof Map<?, ?> nativeOverrides)
            {
                for (Map.Entry<?, ?> entry : nativeOverrides.entrySet())
                {
                    if (entry.getKey() instanceof String material && entry.getValue() instanceof Link link)
                    {
                        result.put(material, link);
                    }
                }
            }
        }
        catch (ReflectiveOperationException ignored)
        {
            // Field disappeared on this fork - ValueString overrides still apply.
        }
    }

    @Unique
    private static Field bbsFbx$materialTextureOverridesField;

    /**
     * Assigns (or clears, with a null link) this Form's texture override for
     * one material, persisting into {@link #bbsFbx$materialTextures}.
     *
     * <p>Reads {@link #bbsFbx$parsePersistedOverrides} here, NOT
     * {@link #bbsFbx$getMaterialTextureOverrides} - that one also folds in
     * runtime (film-animated) and native-FS overrides, and this method
     * re-serializes whatever map it starts from back into the PERSISTED
     * property. Starting from the merged view would mean: open the picker,
     * change material A's texture, and if a film is mid-playback and
     * currently animating material B's texture via a runtime override at
     * that exact moment, B's transient animated value gets permanently
     * baked into the save file too - a real bug this class had until this
     * fix, not a hypothetical one.</p>
     */
    @Override
    @Unique
    public void bbsFbx$setMaterialTextureOverride(String material, Link link)
    {
        Map<String, Link> overrides = this.bbsFbx$parsePersistedOverrides();

        if (link == null)
        {
            overrides.remove(material);
        }
        else
        {
            overrides.put(material, link);
        }

        StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, Link> entry : overrides.entrySet())
        {
            sb.append(entry.getKey()).append('=')
                    .append(entry.getValue().source).append(':').append(entry.getValue().path)
                    .append('\n');
        }

        this.bbsFbx$materialTextures.set(sb.toString());
    }

    @Override
    @Unique
    public void bbsFbx$setRuntimeMaterialTextureOverride(String material, Link link)
    {
        if (link == null)
        {
            this.bbsFbx$runtimeMaterialTextureOverrides.remove(material);
        }
        else
        {
            this.bbsFbx$runtimeMaterialTextureOverrides.put(material, link);
        }
    }

    /**
     * Runtime-only per-material PBR overrides for the CML fork's per-material
     * PBR film sub-tracks. Never persisted; rewritten every playback tick.
     * Nullable fields on {@link MaterialPbrIntensity} mean "no override for
     * this channel - fall back to the whole-model value"; an entry whose
     * fields are both null is removed.
     */
    @Unique
    private final Map<String, MaterialPbrIntensity> bbsFbx$runtimeMaterialPbrOverrides = new LinkedHashMap<>();

    @Override
    @Unique
    public Map<String, MaterialPbrIntensity> bbsFbx$getMaterialPbrOverrides()
    {
        return this.bbsFbx$runtimeMaterialPbrOverrides;
    }

    @Override
    @Unique
    public void bbsFbx$setRuntimeMaterialPbr(String material, Float normal, Float specular)
    {
        if (normal == null && specular == null)
        {
            this.bbsFbx$runtimeMaterialPbrOverrides.remove(material);

            return;
        }

        MaterialPbrIntensity intensity = this.bbsFbx$runtimeMaterialPbrOverrides.get(material);

        if (intensity == null)
        {
            intensity = new MaterialPbrIntensity();
            this.bbsFbx$runtimeMaterialPbrOverrides.put(material, intensity);
        }

        if (normal != null)
        {
            intensity.normal = normal;
        }

        if (specular != null)
        {
            intensity.specular = specular;
        }

        if (intensity.normal == null && intensity.specular == null)
        {
            this.bbsFbx$runtimeMaterialPbrOverrides.remove(material);
        }
    }

    @Unique
    private static Link bbsFbx$parseLink(String encoded)
    {
        int colon = encoded.indexOf(':');

        if (colon < 0)
        {
            return LinkUtils.create(encoded);
        }

        return new Link(encoded.substring(0, colon), encoded.substring(colon + 1));
    }
}
