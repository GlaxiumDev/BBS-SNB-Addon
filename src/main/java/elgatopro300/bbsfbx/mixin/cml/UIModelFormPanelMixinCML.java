package elgatopro300.bbsfbx.mixin.cml;

import elgatopro300.bbsfbx.mixin.UIFormPanelAccessorCML;
import elgatopro300.bbsfbx.model.fbx.loaders.IMaterialTextureHolder;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.forms.editors.panels.UIModelFormPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.UITexturePicker;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

/**
 * Makes the "pick texture" button on the model panel show a per-material
 * context menu (mirroring BBS FS's own multi-texture UI - same
 * {@code Icons.MATERIAL} icon + material name per entry, same
 * {@code replaceContextMenu} mechanism, both confirmed against FS's real
 * source) whenever the currently-edited model has more than one material,
 * instead of jumping straight to the texture picker. Single-material models
 * (including all non-FBX models, and single-material FBX ones) are
 * completely unaffected - {@link #bbsFbx$onPickTexture} falls through to
 * exactly the original single-texture-picker call for those.
 *
 * <p>{@code UIButton}'s click handler ({@code UIClickable.callback}) is a
 * plain public mutable field (confirmed against real source), so rather
 * than fighting with the button's own constructor-time lambda via
 * {@code @Redirect}/{@code @ModifyVariable}, this just lets the original
 * constructor run untouched and reassigns {@code pick.callback} once at the
 * very end via {@code @Inject(... at = @At("RETURN"))} - simple and
 * resilient to unrelated changes elsewhere in the constructor.</p>
 *
 * <p>{@code getContext()} lives on {@code UIElement}, a superclass of the
 * mixin target rather than something declared directly on
 * {@code UIModelFormPanel} - it's reached here via the usual Mixin
 * {@code (UIElement) (Object) this} idiom (already used elsewhere in this
 * addon, e.g. the VAO mixins) rather than {@code @Shadow}, since it's a
 * concrete inherited method rather than something the target class itself
 * declares.</p>
 *
 * <p>{@code form} is declared as {@code protected T form;} on the generic
 * superclass {@code UIFormPanel<T extends Form>} (confirmed directly
 * against real source), not on {@code UIModelFormPanel} itself. Shadowing
 * it directly on this mixin (even with the correctly-erased {@code Form}
 * type) is unreliable in sponge-mixin - the field pre-processor doesn't
 * consistently resolve {@code @Shadow} fields that are only inherited from
 * a generic superclass, which is what produced the "@Shadow field form was
 * not located in the target class" crash. Instead, {@code form} is read via
 * {@link UIFormPanelAccessorCML}, an {@code @Accessor} mixin targeting
 * {@code UIFormPanel} directly (the class that actually declares it) - see
 * {@link #bbsFbx$formAccessor()}. An explicit cast to {@code ModelForm} is
 * still needed at the one place ({@link #bbsFbx$modelForm}) that actually
 * needs {@code ModelForm}-specific members ({@code .texture}).</p>
 *
 * <p>{@code UITexturePicker.withFormPreview(Supplier<Form>)} does not exist
 * at all on base BBS (confirmed directly against mchorse/bbs-mod's real
 * source) - it's a CML-only addition, unlike everything else this class
 * touches. Calling it by name fails to compile against a base BBS jar on
 * the classpath, so it's invoked reflectively via
 * {@link #bbsFbx$tryWithFormPreview} instead, the same pattern this addon
 * already uses for {@code FormColorGradePatch}. On forks where the method
 * doesn't exist, the texture picker just opens without a live form preview
 * thumbnail - multi-material picking itself is completely unaffected.</p>
 */
@Mixin(value = UIModelFormPanel.class, remap = false)
public abstract class UIModelFormPanelMixinCML
{
    @Shadow public UIButton pick;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void bbsFbx$wireMultiMaterialPicker(UIForm editor, CallbackInfo info)
    {
        this.pick.callback = (b) -> this.bbsFbx$onPickTexture();
    }

    @Unique
    private void bbsFbx$onPickTexture()
    {
        ModelInstance model = ModelFormRenderer.getModel(this.bbsFbx$modelForm());
        List<String> materials = model instanceof IMaterialTextureHolder holder
                ? holder.bbsFbx$getMaterials()
                : List.of();

        if (materials.size() <= 1)
        {
            this.bbsFbx$openPickerForWholeModel(model);

            return;
        }

        this.bbsFbx$getContext().replaceContextMenu((menu) ->
        {
            for (String material : materials)
            {
                menu.action(Icons.MATERIAL, IKey.constant(material), () -> this.bbsFbx$openPickerForMaterial(model, material));
            }
        });
    }

    /** Same "pick a single texture for the whole model" behaviour as the host's original button - untouched otherwise. */
    @Unique
    private void bbsFbx$openPickerForWholeModel(ModelInstance model)
    {
        ModelForm modelForm = this.bbsFbx$modelForm();
        Link link = modelForm.texture.get();

        if (model != null && link == null)
        {
            link = model.texture;
        }

        UITexturePicker picker = UITexturePicker.open(this.bbsFbx$getContext(), link, (l) -> modelForm.texture.set(l));

        if (picker != null)
        {
            this.bbsFbx$tryWithFormPreview(picker);
        }
    }

    @Unique
    private void bbsFbx$openPickerForMaterial(ModelInstance model, String material)
    {
        IMaterialTextureHolder holder = model instanceof IMaterialTextureHolder h ? h : null;
        Link link = holder != null ? holder.bbsFbx$getMaterialTexture(material) : null;

        if (link == null && model != null)
        {
            link = model.texture;
        }

        UITexturePicker picker = UITexturePicker.open(this.bbsFbx$getContext(), link, (l) ->
        {
            if (holder != null)
            {
                holder.bbsFbx$setMaterialTexture(material, l);
            }
        });

        if (picker != null)
        {
            this.bbsFbx$tryWithFormPreview(picker);
        }
    }

    @Unique
    private UIContext bbsFbx$getContext()
    {
        return ((UIElement) (Object) this).getContext();
    }

    /**
     * Reflective wrapper around {@code UITexturePicker.withFormPreview(Supplier<Form>)} -
     * see the class doc for why this can't be a direct call.
     */
    @Unique
    private void bbsFbx$tryWithFormPreview(UITexturePicker picker)
    {
        try
        {
            Method withFormPreview = UITexturePicker.class.getMethod("withFormPreview", Supplier.class);
            Supplier<Form> formSupplier = () -> this.bbsFbx$formAccessor().bbsFbx$getForm();

            withFormPreview.invoke(picker, formSupplier);
        }
        catch (ReflectiveOperationException ignored)
        {
            // Not present on this BBS fork (e.g. base BBS) - the picker just
            // opens without a live form preview thumbnail.
        }
    }

    /** See the class doc for why {@code form} is reached via an accessor mixin instead of a direct {@code @Shadow}. */
    @Unique
    private UIFormPanelAccessorCML bbsFbx$formAccessor()
    {
        return (UIFormPanelAccessorCML) (Object) this;
    }

    /** {@code this.bbsFbx$formAccessor().bbsFbx$getForm()} is only ever actually a {@code ModelForm} on this panel. */
    @Unique
    private ModelForm bbsFbx$modelForm()
    {
        return (ModelForm) this.bbsFbx$formAccessor().bbsFbx$getForm();
    }
}