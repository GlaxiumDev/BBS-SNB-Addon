package glaxium.snb.compat;

import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * {@code IOUtils.readText} on Base/FS/CML; {@code FIOUtils.readText} on BBS&nbsp;2.1.
 */
public final class IOUtilsCompat
{
    private static final Method READ_TEXT;

    static
    {
        Method method = null;

        for (String owner : new String[] {
                "mchorse.bbs_mod.utils.IOUtils",
                "mchorse.bbs_mod.utils.FIOUtils"
        })
        {
            try
            {
                method = Class.forName(owner).getMethod("readText", InputStream.class);
                break;
            }
            catch (ReflectiveOperationException ignored)
            {
            }
        }

        READ_TEXT = method;
    }

    private IOUtilsCompat()
    {
    }

    public static String readText(InputStream stream)
    {
        if (READ_TEXT == null)
        {
            throw new IllegalStateException("Neither IOUtils nor FIOUtils.readText is available");
        }

        try
        {
            return (String) READ_TEXT.invoke(null, stream);
        }
        catch (ReflectiveOperationException e)
        {
            Throwable cause = e.getCause() == null ? e : e.getCause();

            if (cause instanceof RuntimeException runtime)
            {
                throw runtime;
            }

            throw new IllegalStateException("Failed to read text stream", cause);
        }
    }
}
