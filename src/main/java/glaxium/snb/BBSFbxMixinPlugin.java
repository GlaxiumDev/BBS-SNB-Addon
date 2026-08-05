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
 * <p>Only {@code mixin.base}, {@code mixin.fs} and {@code mixin.cml} are
 * gated -- exactly one of the three {@code ModelInstanceMixin} variants is
 * applied, matching whichever fork is currently running, because that is the
 * one mixin target where Base/FS/CML actually disagree on method signature
 * (see the doc comments on those three classes). Everything else in
 * {@code bbs_fbx.mixins.json} targets classes that are identical across all
 * three forks and is left ungated.</p>
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
