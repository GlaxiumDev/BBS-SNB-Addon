package glaxium.snb.compat;

import net.fabricmc.loader.api.EntrypointException;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Universal LanguageAdapter for BBS addon entrypoints.
 *
 * <p>BBS FS up to 2.5.2 and BBS CML query {@code "bbs-addon"} for
 * {@code mchorse.bbs_mod.events.BBSAddonMod.class}. BBS FS 2.7+ queries for
 * {@code mchorse.bbs_mod.api.BBSAddonMod.class}. Both are marker interfaces
 * with zero methods. This adapter returns a dynamic proxy satisfying whichever
 * interface BBS asks for, routing events to our common handlers without
 * compile-time or runtime linkage failure.</p>
 */
public class BBSAddonEntrypointAdapter implements LanguageAdapter
{
    @Override
    @SuppressWarnings("unchecked")
    public <T> T create(ModContainer mod, String value, Class<T> type) throws EntrypointException
    {
        try
        {
            Class<?> targetClass = Class.forName(value);
            Object target = targetClass.getDeclaredConstructor().newInstance();

            if (type.isInstance(target))
            {
                return (T) target;
            }

            InvocationHandler handler = (proxy, method, args) -> {
                if ("toString".equals(method.getName()))
                {
                    return "BBSAddonAdapter[" + value + " as " + type.getName() + "]";
                }
                if ("hashCode".equals(method.getName()))
                {
                    return target.hashCode();
                }
                if ("equals".equals(method.getName()) && args != null && args.length == 1)
                {
                    return proxy == args[0] || target.equals(args[0]);
                }
                try
                {
                    Method m = target.getClass().getMethod(method.getName(), method.getParameterTypes());
                    return m.invoke(target, args);
                }
                catch (NoSuchMethodException e)
                {
                    return null;
                }
            };

            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        }
        catch (Throwable t)
        {
            throw new EntrypointException("Failed to create entrypoint for " + value, t);
        }
    }
}
