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
 * to one fork family -- used where Base/FS/CML/BBS&nbsp;2.1 disagree on
 * method signature. {@code mixin.basecml} and {@code mixin.basefs} gate a
 * mixin to two of the forks. BBS&nbsp;2.1 reuses most FS render mixins but
 * skips FS-only hooks that target APIs it does not ship
 * ({@code FormUtils.collectPropertyPaths}, three-arg
 * {@code BOBJModelSimpleVAO.processData}); emoticon simple armor on
 * FS21 uses {@code BOBJModelArmorMixinFS21} (two-arg geometric hinge)
 * instead of the Base/CML merged-{@code FBXCompiledData} path.</p>
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
            if (fork == BBSFork.FS)
            {
                /* Wemppy FS uses the 3-arg armor hinge only; track hiding stays
                 * on FormUtils.collectPropertyPaths (FormUtilsMixinFS). */
                return !mixinClassName.endsWith(".BOBJModelArmorMixinFS21")
                        && !mixinClassName.endsWith(".UIReplaysEditorUtilsMixinFS21");
            }

            if (fork == BBSFork.FS21)
            {
                /* FS21 matches FS render/material hooks, but not FormUtils /
                 * the 3-arg armor hinge. Armor uses BOBJModelArmorMixinFS21;
                 * texture-track hiding uses UIReplaysEditorUtilsMixinFS21. */
                return !mixinClassName.endsWith(".FormUtilsMixinFS")
                        && !mixinClassName.endsWith(".BOBJModelArmorMixinFS");
            }

            return false;
        }

        if (mixinClassName.startsWith(CML_PACKAGE))
        {
            if (mixinClassName.equals(CML_PACKAGE + "PreviewFrameMixinCML")
                    || mixinClassName.equals(CML_PACKAGE + "FormUIPreviewCacheMixinCML")
                    || mixinClassName.equals(CML_PACKAGE + "UIFormListPreviewMixinCML"))
            {
                return fork == BBSFork.CML && BBSFbxMixinPlugin.class.getClassLoader().getResource(
                        "mchorse/bbs_mod/forms/FormUIPreviewCache.class") != null;
            }

            if (mixinClassName.equals(CML_PACKAGE + "UIAnimationStateEditorMixinCML")
                    || mixinClassName.equals(CML_PACKAGE + "UIFormPropertyTrackSheetsMixinCML"))
            {
                // Inspect the resource without loading a class before its mixins apply.
                boolean sharedTrackSheets = BBSFbxMixinPlugin.class.getClassLoader().getResource(
                        "mchorse/bbs_mod/ui/forms/editors/utils/UIFormPropertyTrackSheets.class") != null;
                boolean modernMixin = mixinClassName.endsWith(".UIFormPropertyTrackSheetsMixinCML");

                return fork == BBSFork.CML && sharedTrackSheets == modernMixin;
            }

            return fork == BBSFork.CML;
        }

        if (mixinClassName.startsWith(BASECML_PACKAGE))
        {
            /* Merged FBXCompiledData armor hinge is Base/CML only. FS21 keeps
             * per-mesh VAOs and uses BOBJModelArmorMixinFS21 instead. */
            return fork == BBSFork.BASE || fork == BBSFork.CML;
        }

        if (mixinClassName.startsWith(BASEFS_PACKAGE))
        {
            return fork == BBSFork.BASE || fork == BBSFork.FS || fork == BBSFork.FS21;
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
