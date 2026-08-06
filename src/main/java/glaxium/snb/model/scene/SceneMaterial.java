package glaxium.snb.model.scene;

/**
 * Material with an optional diffuse/base-color texture path and flat color.
 * Texture path may be a relative URI, a bare filename, or {@code *N} for an
 * embedded {@link SceneTexture} index.
 */
public final class SceneMaterial
{
    public String name = "";
    public String diffuseTexturePath;
    /** RGB in 0..1 when no image texture; null if unset. */
    public float[] color;
}
