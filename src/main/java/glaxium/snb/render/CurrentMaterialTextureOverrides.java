package glaxium.snb.render;

import mchorse.bbs_mod.resources.Link;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;

/**
 * Why this exists: {@code ModelInstance}/{@code IModel}/{@code FBXCompiledData}
 * are all fetched from BBS's GLOBAL model cache, keyed by the model FILE
 * PATH string ({@code ModelFormRenderer.getModel(ModelForm form) { return
 * BBSModClient.getModels().getModel(form.model.get()); }}, confirmed
 * directly from the actual decompiled class) -- NOT per-Form. Every
 * {@code ModelForm} pointing at the same file, including every morph of it,
 * shares the exact same underlying objects. So a per-material texture
 * choice can NEVER correctly live on any of those shared objects (which is
 * exactly the bug this class fixes: the first version of this feature wrote
 * the choice onto {@code FBXCompiledData}, so it leaked across every Form
 * using that model, morphs included).
 *
 * <p>The only place that genuinely knows "which Form is being rendered right
 * now" is the call site -- confirmed to be
 * {@code ModelFormRenderer.renderModel(...)}, the one private method every
 * public render entry point (world, UI preview, first-person arm) funnels
 * through. {@code ModelFormRendererMixin} pushes the current Form's override
 * map here right before that method's real body runs, and pops it right
 * after; {@code BOBJModelVAOMixinBase}/{@code BOBJModelVAOMixinFS}/
 * {@code BOBJModelVAOMixinCML} read {@link #current()} instead of touching
 * the shared model data.</p>
 *
 * <p>A stack, not a single field, because rendering can nest (a Form with
 * body parts that are themselves model Forms) -- push/pop keeps each nested
 * render's own overrides correctly scoped without clobbering its caller's.
 * Not a {@code ThreadLocal}: Minecraft's render path is single-threaded on
 * the render thread, so a plain static stack is sufficient and avoids
 * ThreadLocal's own overhead/leak footguns for something this hot-path.</p>
 */
public final class CurrentMaterialTextureOverrides
{
    private static final Deque<Map<String, Link>> STACK = new ArrayDeque<>();

    private CurrentMaterialTextureOverrides() {}

    public static void push(Map<String, Link> overrides)
    {
        STACK.push(overrides == null ? Collections.emptyMap() : overrides);
    }

    public static void pop()
    {
        if (!STACK.isEmpty())
        {
            STACK.pop();
        }
    }

    /** The innermost currently-rendering Form's material overrides, or empty if none is set (should not normally happen). */
    public static Map<String, Link> current()
    {
        return STACK.isEmpty() ? Collections.emptyMap() : STACK.peek();
    }
}
