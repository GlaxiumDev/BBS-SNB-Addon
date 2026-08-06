package glaxium.snb.model.fbx.loaders;

/**
 * Source formats this addon loads, and the handful of things that genuinely
 * differ between them.
 *
 * <p>Everything downstream of "we have a {@link glaxium.snb.model.scene.Scene}"
 * is format-agnostic. Only unit scale and FBX-specific pivot handling diverge.
 * Enum order is the priority {@link FBXModelLoader} uses when a model folder
 * contains more than one importable file.</p>
 */
public enum SceneFormat
{
    FBX(".fbx", 0.01F, true),
    GLTF(".gltf", 1.0F, false),
    GLB(".glb", 1.0F, false);

    private final String extension;
    private final float unitScale;
    private final boolean fbxProperties;

    SceneFormat(String extension, float unitScale, boolean fbxProperties)
    {
        this.extension = extension;
        this.unitScale = unitScale;
        this.fbxProperties = fbxProperties;
    }

    /** Lowercase file extension, dot included. */
    public String extension()
    {
        return this.extension;
    }

    public float unitScale()
    {
        return this.unitScale;
    }

    /** Whether FBX pivot-collapse post-process applies. */
    public boolean fbxProperties()
    {
        return this.fbxProperties;
    }

    public boolean matches(String path)
    {
        return path != null && path.toLowerCase().endsWith(this.extension);
    }

    /** The format for the given path, or null when it isn't an importable model file. */
    public static SceneFormat fromPath(String path)
    {
        for (SceneFormat format : values())
        {
            if (format.matches(path))
            {
                return format;
            }
        }

        return null;
    }
}
