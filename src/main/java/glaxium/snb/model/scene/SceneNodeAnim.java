package glaxium.snb.model.scene;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** TRS keyframe track targeting a single node by name. */
public final class SceneNodeAnim
{
    public String nodeName = "";
    public double[] positionTimes = new double[0];
    public Vector3f[] positionValues = new Vector3f[0];
    public String positionInterpolation = "LINEAR";
    public Vector3f[] positionInTangents = new Vector3f[0];
    public Vector3f[] positionOutTangents = new Vector3f[0];
    public double[] rotationTimes = new double[0];
    public Quaternionf[] rotationValues = new Quaternionf[0];
    public String rotationInterpolation = "LINEAR";
    public Quaternionf[] rotationInTangents = new Quaternionf[0];
    public Quaternionf[] rotationOutTangents = new Quaternionf[0];
    public double[] scalingTimes = new double[0];
    public Vector3f[] scalingValues = new Vector3f[0];
    public String scalingInterpolation = "LINEAR";
    public Vector3f[] scalingInTangents = new Vector3f[0];
    public Vector3f[] scalingOutTangents = new Vector3f[0];
}
