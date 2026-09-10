import glaxium.snb.render.PreviewFrameBudget;

/** Run with PreviewFrameBudget on the classpath; no Minecraft or GPU required. */
public final class PreviewFrameBudgetTest
{
    public static void main(String[] args) throws InterruptedException
    {
        PreviewFrameBudget.beginFrame();
        int accepted = 0;

        for (int i = 0; i < 96; i++)
        {
            if (PreviewFrameBudget.tryFill()) accepted++;
        }

        if (accepted < 1 || accepted > 2)
        {
            throw new AssertionError("Expected one or two new previews, got " + accepted);
        }

        Thread.sleep(40);

        if (PreviewFrameBudget.tryFill())
        {
            throw new AssertionError("Elapsed time renewed the budget within the same frame");
        }

        PreviewFrameBudget.beginFrame();

        if (!PreviewFrameBudget.tryFill())
        {
            throw new AssertionError("A new frame must allow a pending preview");
        }

        System.out.println("Preview frame budget checks passed");
    }
}
