package glaxium.snb.render;

/**
 * Limits new CML morph-menu thumbnail work on the render thread.
 *
 * <p>CML 2.1.1 allows up to 96 fills / 28&nbsp;ms per frame. Baking one heavy
 * FBX/glTF preview calls {@code FormUtilsClient.renderUI} into an FBO and can
 * stall the client for seconds (mouse/Alt+F4 appear dead). This budget caps
 * how many <em>new</em> cache fills may start per frame; cached blits are
 * free.</p>
 */
public final class PreviewFrameBudget
{
    /** At most one new FBO bake per frame — each can be multi-second. */
    private static final int MAX_FILLS = 1;
    private static int fills;

    private PreviewFrameBudget() {}

    public static void beginFrame()
    {
        fills = 0;
    }

    /** Returns whether a new thumbnail bake may start this frame. */
    public static boolean tryFill()
    {
        if (fills >= MAX_FILLS)
        {
            return false;
        }

        fills++;
        return true;
    }
}
