package elgatopro300.bbsfbx.mixin;

import elgatopro300.bbsfbx.mixin.cml.UIModelFormPanelMixinCML;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.forms.editors.panels.UIFormPanel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code UIFormPanel<T extends Form>.form} for use by
 * {@link UIModelFormPanelMixinCML}.
 *
 * <p>This is deliberately an {@code @Accessor} mixin targeting
 * {@code UIFormPanel} directly - the class that actually <em>declares</em>
 * {@code form} - rather than an {@code @Shadow} on {@code UIModelFormPanel}
 * (a subclass that merely inherits it). Shadowing a field declared on a
 * generic superclass from a mixin targeting the concrete subclass is not
 * reliably resolved by sponge-mixin's field pre-processor even when the
 * erased descriptor matches on paper (this is exactly what produced the
 * "@Shadow field form was not located in the target class" crash). Pointing
 * the mixin at the declaring class itself sidesteps that resolution path
 * entirely.</p>
 *
 * <p>Any instance of {@code UIModelFormPanel} is also an instance of
 * {@code UIFormPanel}, so {@code (UIFormPanelAccessorCML) (Object) this}
 * inside {@link UIModelFormPanelMixinCML} is a valid cast.</p>
 */
@Mixin(value = UIFormPanel.class, remap = false)
public interface UIFormPanelAccessorCML
{
    @Accessor("form")
    Form bbsFbx$getForm();
}
