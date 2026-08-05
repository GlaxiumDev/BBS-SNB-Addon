package glaxium.snb.mixin.basecml;

import glaxium.snb.compat.BBSFork;
import glaxium.snb.model.fbx.loaders.IFormMaterialTextureHolder;
import glaxium.snb.render.MaterialTextureDelegate;

import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Second half of the Base/CML per-material-texture-keyframe feature: teach
 * {@code FormProperties} (the film's property-keyframe store, identical on
 * Base and CML) to create, apply and reset the synthetic per-material
 * channels that {@code FormUtilsMixin} exposes.
 *
 * <p>A material channel is a {@code KeyframeChannel} whose id is exactly an
 * FBX material name of the form's model (see
 * {@link MaterialTextureDelegate#isMaterial(Form, String)}). Applying it
 * writes the interpolated {@link Link} into the form's runtime-only
 * material overrides ({@link IFormMaterialTextureHolder#bbsFbx$setRuntimeMaterialTextureOverride})
 * instead of a form property -- the film never touches the persisted
 * texture choice, and the render push in
 * {@code ModelFormRendererMixin*} picks the runtime value up on the next
 * frame. FS's {@code FormProperties} is a different class shape (native
 * per-material support already exists there), so this mixin is gated to
 * Base/CML by {@code glaxium.snb.BBSFbxMixinPlugin}.</p>
 */
@Mixin(value = FormProperties.class, remap = false)
public abstract class FormPropertiesMixin
{
    @Shadow
    public Map<String, KeyframeChannel> properties;

    @Unique
    private static Method bbsFbx$addMethod;

    /**
     * {@code ValueGroup.add(BaseValue)} registers the value in the group's
     * {@code children} map and sets its parent -- required for
     * {@code get(id)} lookups and for film serialization (which walks
     * children). Mixin cannot {@code @Shadow} or {@code @Accessor} inherited
     * members on this mixin version, so the public inherited method is
     * invoked reflectively instead; the call happens once per new material
     * channel, so the cost is irrelevant.
     */
    @Unique
    private static void bbsFbx$addToGroup(Object group, BaseValue value)
    {
        try
        {
            if (bbsFbx$addMethod == null)
            {
                bbsFbx$addMethod = ValueGroup.class.getMethod("add", BaseValue.class);
            }

            bbsFbx$addMethod.invoke(group, value);
        }
        catch (ReflectiveOperationException error)
        {
            throw new RuntimeException(error);
        }
    }

    /**
     * {@code getOrCreate(Form, String)} is the entry point the film editor
     * (and the forms-editor animation-state editor) use to obtain a channel
     * for a collected path. For a material path it normally returns null
     * ({@code FormUtils.getProperty} finds no real value), so this makes it
     * create and register the material channel instead -- in the same two
     * places {@code create(BaseValue)} registers real ones ({@code properties}
     * map + the ValueGroup), so it persists with the replay and survives
     * reloads.
     */
    @Inject(
            method = "getOrCreate(Lmchorse/bbs_mod/forms/forms/Form;Ljava/lang/String;)Lmchorse/bbs_mod/utils/keyframes/KeyframeChannel;",
            at = @At("HEAD"), cancellable = true, remap = false
    )
    private void bbsFbx$materialGetOrCreate(Form form, String path, CallbackInfoReturnable<KeyframeChannel> info)
    {
        if (form == null || path == null || FormUtils.getProperty(form, path) != null)
        {
            return;
        }

        if (!MaterialTextureDelegate.isMaterial(form, path))
        {
            return;
        }

        KeyframeChannel existing = this.properties.get(path);

        if (existing != null)
        {
            info.setReturnValue(existing);

            return;
        }

        KeyframeChannel channel = new KeyframeChannel(path, KeyframeFactories.LINK);

        this.properties.put(path, channel);
        bbsFbx$addToGroup(this, channel);

        info.setReturnValue(channel);
    }

    /**
     * CML-only extension of {@code getOrCreate}: the per-material PBR
     * sub-tracks ({@code <material>:pbr_normal_intensity} /
     * {@code :pbr_specular_intensity}) are synthetic channels just like the
     * material texture tracks, but float-keyed. Gated to the CML fork
     * (where PBR exists); on Base the paths never reach this point anyway.
     */
    @Inject(
            method = "getOrCreate(Lmchorse/bbs_mod/forms/forms/Form;Ljava/lang/String;)Lmchorse/bbs_mod/utils/keyframes/KeyframeChannel;",
            at = @At("HEAD"), cancellable = true, remap = false
    )
    private void bbsFbx$materialPbrGetOrCreate(Form form, String path, CallbackInfoReturnable<KeyframeChannel> info)
    {
        if (form == null || path == null || BBSFork.fromLoadedMods() != BBSFork.CML
                || FormUtils.getProperty(form, path) != null)
        {
            return;
        }

        if (!MaterialTextureDelegate.isMaterialPbrChannel(form, path))
        {
            return;
        }

        KeyframeChannel existing = this.properties.get(path);

        if (existing != null)
        {
            info.setReturnValue(existing);

            return;
        }

        KeyframeChannel channel = new KeyframeChannel(path, KeyframeFactories.FLOAT);

        this.properties.put(path, channel);
        bbsFbx$addToGroup(this, channel);

        info.setReturnValue(channel);
    }

    /**
     * Routes a material channel's application into the form's runtime
     * material overrides. The original {@code applyProperty} would find no
     * form property for the id and return early, so this fully handles
     * material channels and cancels. Real properties (including a form
     * property that happens to share a material's name) are left to the
     * original.
     */
    @Inject(
            method = "applyProperty(FLmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/utils/keyframes/KeyframeChannel;F)V",
            at = @At("HEAD"), cancellable = true, remap = false
    )
    private void bbsFbx$materialApplyProperty(float tick, Form form, KeyframeChannel channel, float factor, CallbackInfo info)
    {
        if (form == null || channel == null)
        {
            return;
        }

        String id = channel.getId();

        if (FormUtils.getProperty(form, id) != null)
        {
            return;
        }

        if (!MaterialTextureDelegate.isMaterial(form, id))
        {
            return;
        }

        info.cancel();

        if (!(form instanceof IFormMaterialTextureHolder holder))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);

        if (segment != null && segment.createInterpolated() instanceof Link link)
        {
            holder.bbsFbx$setRuntimeMaterialTextureOverride(id, link);
        }
        else
        {
            holder.bbsFbx$setRuntimeMaterialTextureOverride(id, null);
        }
    }

    /**
     * CML-only extension of {@code applyProperty}: routes a per-material PBR
     * channel's value into the form's runtime per-material PBR overrides.
     * Like the material texture channels, the original {@code applyProperty}
     * would find no form property for the id and return early, so this fully
     * handles them and cancels. Gated to the CML fork.
     */
    @Inject(
            method = "applyProperty(FLmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/utils/keyframes/KeyframeChannel;F)V",
            at = @At("HEAD"), cancellable = true, remap = false
    )
    private void bbsFbx$materialPbrApplyProperty(float tick, Form form, KeyframeChannel channel, float factor, CallbackInfo info)
    {
        if (form == null || channel == null || BBSFork.fromLoadedMods() != BBSFork.CML)
        {
            return;
        }

        String id = channel.getId();
        String material = MaterialTextureDelegate.materialFromPbrPath(id);

        if (material == null || !MaterialTextureDelegate.isMaterial(form, material)
                || FormUtils.getProperty(form, id) != null)
        {
            return;
        }

        info.cancel();

        if (!(form instanceof IFormMaterialTextureHolder holder))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);
        Float value = segment != null && segment.createInterpolated() instanceof Float f ? f : null;
        boolean normal = id.endsWith(":pbr_normal_intensity");

        holder.bbsFbx$setRuntimeMaterialPbr(material, normal ? value : null, normal ? null : value);
    }

    /**
     * Reimplements {@code resetProperties} whenever material channels are
     * present. The original iterates channels and does
     * {@code if (getProperty(form, id) == null) return;} -- an early return
     * the very first material channel would trigger, skipping every channel
     * after it. The reimplementation resets real properties and clears
     * material runtime overrides, in one pass.
     */
    @Inject(
            method = "resetProperties(Lmchorse/bbs_mod/forms/forms/Form;)V",
            at = @At("HEAD"), cancellable = true, remap = false
    )
    private void bbsFbx$materialResetProperties(Form form, CallbackInfo info)
    {
        if (form == null)
        {
            return;
        }

        boolean hasMaterial = false;

        for (KeyframeChannel channel : this.properties.values())
        {
            String id = channel.getId();

            if (FormUtils.getProperty(form, id) == null && (MaterialTextureDelegate.isMaterial(form, id)
                    || MaterialTextureDelegate.isMaterialPbrChannel(form, id)))
            {
                hasMaterial = true;

                break;
            }
        }

        if (!hasMaterial)
        {
            return;
        }

        info.cancel();

        boolean cml = BBSFork.fromLoadedMods() == BBSFork.CML;

        for (KeyframeChannel channel : this.properties.values())
        {
            String id = channel.getId();
            BaseValueBasic property = FormUtils.getProperty(form, id);

            if (property != null)
            {
                property.setRuntimeValue(null);
            }
            else if (form instanceof IFormMaterialTextureHolder holder)
            {
                if (MaterialTextureDelegate.isMaterial(form, id))
                {
                    holder.bbsFbx$setRuntimeMaterialTextureOverride(id, null);
                }
                else if (cml && MaterialTextureDelegate.isMaterialPbrChannel(form, id))
                {
                    holder.bbsFbx$setRuntimeMaterialPbr(MaterialTextureDelegate.materialFromPbrPath(id), null, null);
                }
            }
        }
    }
}
