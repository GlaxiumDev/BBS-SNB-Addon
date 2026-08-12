package glaxium.snb;

import glaxium.snb.compat.BBSFork;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

/**
 * Fork gating for this addon's mixins.
 *
 * <p>{@code mixin.base}, {@code mixin.fs} and {@code mixin.cml} gate a mixin
 * to exactly one fork -- used where Base/FS/CML actually disagree on method
 * signature (see the doc comments on those classes). {@code mixin.basecml}
 * and {@code mixin.basefs} gate a mixin to two of the three forks. The
 * {@code basefs} parallel-loader mixins exist in source but are currently
 * <b>not listed</b> in {@code bbs_snb_addon.mixins.json} (the host model map
 * is not safe to mutate during a concurrent reload). Everything else targets
 * classes identical across all three forks and is left ungated.</p>
 *
 * <p>Runs during mixin bootstrap, so it must never touch an actual BBS class
 * -- {@link BBSFork#fromLoadedMods()} only reads Fabric Loader metadata for
 * exactly that reason.</p>
 */
public class BBSFbxMixinPlugin implements IMixinConfigPlugin
{
    private static final String BASE_PACKAGE = "glaxium.snb.mixin.base.";
    private static final String FS_PACKAGE = "glaxium.snb.mixin.fs.";
    private static final String CML_PACKAGE = "glaxium.snb.mixin.cml.";
    private static final String BASECML_PACKAGE = "glaxium.snb.mixin.basecml.";
    private static final String BASEFS_PACKAGE = "glaxium.snb.mixin.basefs.";

    @Override
    public void onLoad(String mixinPackage)
    {
    }

    @Override
    public String getRefMapperConfig()
    {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName)
    {
        BBSFork fork = BBSFork.fromLoadedMods();

        if (mixinClassName.startsWith(BASE_PACKAGE))
        {
            return fork == BBSFork.BASE;
        }

        if (mixinClassName.startsWith(FS_PACKAGE))
        {
            return fork == BBSFork.FS;
        }

        if (mixinClassName.startsWith(CML_PACKAGE))
        {
            return fork == BBSFork.CML;
        }

        if (mixinClassName.startsWith(BASECML_PACKAGE))
        {
            return fork == BBSFork.BASE || fork == BBSFork.CML;
        }

        if (mixinClassName.startsWith(BASEFS_PACKAGE))
        {
            return fork == BBSFork.BASE || fork == BBSFork.FS;
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets)
    {
    }

    @Override
    public List<String> getMixins()
    {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo)
    {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo)
    {
    }
}
