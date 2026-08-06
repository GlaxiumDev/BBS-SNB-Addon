package glaxium.snb.model.fbx;

import glaxium.snb.model.scene.Scene;
import glaxium.snb.model.scene.SceneMetadata;

public class FBXMetadata
{
    public int upAxis = 1; /* Default to Y-up */
    public int originalUpAxis = 1;
    public int frontAxis = 2; /* Default to Z-front */
    public int coordAxis = 0; /* Default to X-coord */
    public double unitScaleFactor = 1.0;

    public FBXMetadata(Scene scene)
    {
        SceneMetadata metadata = scene.metadata;

        this.upAxis = metadata.upAxis;
        this.originalUpAxis = metadata.originalUpAxis;
        this.frontAxis = metadata.frontAxis;
        this.coordAxis = metadata.coordAxis;
        this.unitScaleFactor = metadata.unitScaleFactor;
    }
}
