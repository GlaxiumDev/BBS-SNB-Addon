package glaxium.snb.model.fbx.loaders;

/**
 * Formats supported by the pure-Java scene readers. The unit scale bridges
 * Blender FBX centimetre transforms to BBS metres; glTF/GLB are already in
 * metres. Enum order is the deterministic selection priority when a model
 * folder contains more than one supported file.
 */
public enum SceneFormat
{
    FBX(".fbx", 0.01F),
    GLTF(".gltf", 1.0F),
    GLB(".glb", 1.0F);

    private final String extension;
    private final float unitScale;

    SceneFormat(String extension, float unitScale)
    {
        this.extension = extension;
        this.unitScale = unitScale;
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
