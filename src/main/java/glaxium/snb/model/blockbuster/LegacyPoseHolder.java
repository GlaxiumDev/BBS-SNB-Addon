package glaxium.snb.model.blockbuster;

import mchorse.bbs_mod.utils.pose.Pose;

import java.util.Map;

public interface LegacyPoseHolder
{
    void bbsFbx$setLegacyModel(boolean legacy);

    boolean bbsFbx$isLegacyModel();

    void bbsFbx$setLegacyPoses(Map<String, Pose> poses);

    Map<String, Pose> bbsFbx$getLegacyPoses();

    void bbsFbx$setLegacySwipeFactors(Map<String, Float> factors);

    Map<String, Float> bbsFbx$getLegacySwipeFactors();

    void bbsFbx$setLegacyIdleFactors(Map<String, Float> factors);

    Map<String, Float> bbsFbx$getLegacyIdleFactors();
}
