package glaxium.snb.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code FormRenderer<T extends Form>.form} for use by
 * {@link ModelFormRendererMixin}.
 *
 * <p>Same reasoning as {@link UIFormPanelAccessorCML} (see its own doc
 * comment): an {@code @Accessor} mixin targeting {@code FormRenderer}
 * directly -- the class that actually declares {@code form} -- rather than
 * a {@code @Shadow} on {@code ModelFormRenderer} (a subclass that merely
 * inherits it), since shadowing a field declared on a generic superclass
 * isn't reliably resolved by sponge-mixin's field pre-processor.</p>
 *
 * <p>Any instance of {@code ModelFormRenderer} is also an instance of
 * {@code FormRenderer}, so {@code (FormRendererAccessor) (Object) this}
 * inside {@link ModelFormRendererMixin} is a valid cast.</p>
 */
@Mixin(value = FormRenderer.class, remap = false)
public interface FormRendererAccessor
{
    @Accessor("form")
    Form bbsFbx$getForm();
}
