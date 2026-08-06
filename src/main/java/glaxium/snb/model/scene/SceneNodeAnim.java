package glaxium.snb.model.scene;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** TRS keyframe track targeting a single node by name. */
public final class SceneNodeAnim
{
    public String nodeName = "";
    public double[] positionTimes = new double[0];
    public Vector3f[] positionValues = new Vector3f[0];
    public double[] rotationTimes = new double[0];
    public Quaternionf[] rotationValues = new Quaternionf[0];
    public double[] scalingTimes = new double[0];
    public Vector3f[] scalingValues = new Vector3f[0];
}
