package glaxium.snb.mixin;

import glaxium.snb.model.blockbuster.LegacyPoseHolder;
import glaxium.snb.model.blockbuster.LegacyBBModel;
import glaxium.snb.model.blockbuster.LegacyBBRenderer;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.pose.Pose;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Map;

@Mixin(value = ModelInstance.class, remap = false)
public abstract class LegacyModelInstanceMixin implements LegacyPoseHolder
{
    @Shadow public IModel model;
    @Unique private boolean bbsFbx$legacyModel;
    @Unique private Map<String, Pose> bbsFbx$legacyPoses = Collections.emptyMap();
    @Unique private Map<String, Float> bbsFbx$legacySwipeFactors = Collections.emptyMap();
    @Unique private Map<String, Float> bbsFbx$legacyIdleFactors = Collections.emptyMap();

    @Inject(method = "captureMatrices", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$captureLegacyBBMatrices(MatrixCache cache, CallbackInfo ci)
    {
        if (this.model instanceof LegacyBBModel legacy)
        {
            LegacyBBRenderer.captureMatrices(legacy, cache);
            ci.cancel();
        }
    }

    @Inject(method = "fillStencilMap", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$fillLegacyBBStencilMap(StencilMap stencilMap, ModelForm form, CallbackInfo ci)
    {
        if (this.model instanceof LegacyBBModel legacy)
        {
            for (String group : legacy.getAllGroupKeys())
            {
                stencilMap.addPicking(form, group);
            }

            ci.cancel();
        }
    }

    @Override
    public void bbsFbx$setLegacyModel(boolean legacy)
    {
        this.bbsFbx$legacyModel = legacy;
    }

    @Override
    public boolean bbsFbx$isLegacyModel()
    {
        return this.bbsFbx$legacyModel;
    }

    @Override
    public void bbsFbx$setLegacyPoses(Map<String, Pose> poses)
    {
        this.bbsFbx$legacyPoses = poses == null || poses.isEmpty() ? Collections.emptyMap() : Map.copyOf(poses);
    }

    @Override
    public Map<String, Pose> bbsFbx$getLegacyPoses()
    {
        return this.bbsFbx$legacyPoses;
    }

    @Override
    public void bbsFbx$setLegacySwipeFactors(Map<String, Float> factors)
    {
        this.bbsFbx$legacySwipeFactors = factors == null || factors.isEmpty() ? Collections.emptyMap() : Map.copyOf(factors);
    }

    @Override
    public Map<String, Float> bbsFbx$getLegacySwipeFactors()
    {
        return this.bbsFbx$legacySwipeFactors;
    }

    @Override
    public void bbsFbx$setLegacyIdleFactors(Map<String, Float> factors)
    {
        this.bbsFbx$legacyIdleFactors = factors == null || factors.isEmpty() ? Collections.emptyMap() : Map.copyOf(factors);
    }

    @Override
    public Map<String, Float> bbsFbx$getLegacyIdleFactors()
    {
        return this.bbsFbx$legacyIdleFactors;
    }
}
