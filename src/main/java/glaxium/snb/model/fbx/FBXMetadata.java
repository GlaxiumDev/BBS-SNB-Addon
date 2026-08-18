package glaxium.snb.model.fbx;

import glaxium.snb.model.fbx.scene.JavaScene;

public class FBXMetadata
{
    public int upAxis = 1; /* Default to Y-up */
    public int originalUpAxis = 1;
    public int frontAxis = 2; /* Default to Z-front */
    public int coordAxis = 0; /* Default to X-coord */
    public double unitScaleFactor = 1.0;

    public FBXMetadata(JavaScene scene)
    {
        if (scene == null || scene.metadata == null) return;
        this.upAxis = scene.metadata.upAxis;
        this.originalUpAxis = scene.metadata.originalUpAxis;
        this.frontAxis = scene.metadata.frontAxis;
        this.coordAxis = scene.metadata.coordAxis;
        this.unitScaleFactor = scene.metadata.unitScaleFactor;
    }
}
