package glaxium.snb.model.scene;

/**
 * FBX GlobalSettings-style axis / unit metadata. glTF leaves the defaults
 * (Y-up). Axis values match Assimp/FBX: 0=X, 1=Y, 2=Z.
 */
public final class SceneMetadata
{
    public int upAxis = 1;
    public int originalUpAxis = 1;
    public int frontAxis = 2;
    public int coordAxis = 0;
    public double unitScaleFactor = 1.0;
}
