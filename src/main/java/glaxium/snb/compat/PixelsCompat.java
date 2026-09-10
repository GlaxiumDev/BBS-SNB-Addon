package glaxium.snb.compat;

import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.resources.Pixels;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Opaque-pixel probe that works with Base/FS/CML {@code Pixels.getARGB()} and
 * BBS&nbsp;2.1's buffer/getColor API (no {@code getARGB}).
 */
public final class PixelsCompat
{
    private static final Method GET_ARGB;

    static
    {
        Method method = null;

        try
        {
            method = Pixels.class.getMethod("getARGB");
        }
        catch (NoSuchMethodException ignored)
        {
        }

        GET_ARGB = method;
    }

    private PixelsCompat()
    {
    }

    public static boolean isOpaque(Pixels pixels, int x, int y)
    {
        if (pixels == null || x < 0 || y < 0 || x >= pixels.width || y >= pixels.height)
        {
            return false;
        }

        if (GET_ARGB != null)
        {
            try
            {
                int[] argb = (int[]) GET_ARGB.invoke(pixels);

                return argb != null && ((argb[y * pixels.width + x] >>> 24) & 0xff) >= 0x80;
            }
            catch (ReflectiveOperationException ignored)
            {
            }
        }

        Color color = pixels.getColor(x, y);

        if (color != null)
        {
            return color.a >= 0.5F;
        }

        ByteBuffer buffer = pixels.getBuffer();

        if (buffer == null)
        {
            return false;
        }

        int index = pixels.toIndex(x, y) * Math.max(1, pixels.bits / 8);

        if (index < 0 || index >= buffer.capacity())
        {
            return false;
        }

        /* RGBA byte order used by BBS Pixels buffers. */
        int alphaOffset = pixels.bits >= 32 ? 3 : 0;

        return (buffer.get(index + alphaOffset) & 0xff) >= 0x80;
    }
}
