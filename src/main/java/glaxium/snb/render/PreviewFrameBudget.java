package glaxium.snb.render;

/** Limits new thumbnail work; cached images do not consume the allowance. */
public final class PreviewFrameBudget
{
    private static final int MAX_FILLS = 2;
    private static final long BUDGET_NS = 2_000_000L;
    private static int fills;
    private static long firstFillNs;

    private PreviewFrameBudget() {}

    public static void beginFrame()
    {
        fills = 0;
        firstFillNs = 0L;
    }

    public static boolean tryFill()
    {
        long now = System.nanoTime();

        if (fills >= MAX_FILLS || (fills > 0 && now - firstFillNs >= BUDGET_NS))
        {
            return false;
        }

        if (fills == 0)
        {
            firstFillNs = now;
        }

        fills++;
        return true;
    }
}
