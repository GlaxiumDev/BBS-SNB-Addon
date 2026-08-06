package glaxium.snb.model.fbx.loaders;

import org.lwjgl.assimp.Assimp;

/**
 * The Assimp source formats this addon loads models from, and the handful of
 * things that genuinely differ between them.
 *
 * <p>Everything downstream of "we have an {@code AIScene}" -- {@link
 * glaxium.snb.model.fbx.FBXConverter}, {@link FBXMeshCompiler}, the texture
 * extractor, the per-material render split -- is format-agnostic and shared,
 * which is why glTF/GLB support is this enum plus a couple of call sites
 * rather than a second pipeline. Only three things actually diverge:</p>
 *
 * <ul>
 *   <li><b>UV origin.</b> Assimp's FBX and glTF readers both hand us UVs in
 *       the same convention relative to how BBS uploads PNGs, so every format
 *       needs {@code aiProcess_FlipUVs}. Empirically confirmed: the same Miku
 *       exported as FBX (flip on, looks right) and GLB (flip off) shared
 *       byte-identical textures and identical raw UV.v ranges -- without the
 *       flip the GLB body mapped the face atlas onto the torso.</li>
 *   <li><b>Unit scale.</b> Blender's FBX exporter bakes a 100x cm-&gt;m scale
 *       into node transforms, which {@link #unitScale()} cancels with 0.01
 *       (see {@code FBXConverter}). glTF is defined in meters with no such
 *       bake, so it loads at 1.0 -- using FBX's 0.01 here would shrink every
 *       glTF model 100x.</li>
 *   <li><b>Pivot preservation.</b> {@code AI_CONFIG_IMPORT_FBX_PRESERVE_PIVOTS}
 *       is an FBX-reader-only property ({@link #fbxProperties()}); glTF has no
 *       pivot nodes to collapse.</li>
 * </ul>
 *
 * <p>Enum order is also the priority order {@code FBXModelLoader} uses when a
 * model folder somehow contains more than one importable file, so a model that
 * has always loaded from its {@code .fbx} keeps doing so.</p>
 */
public enum SceneFormat
{
    FBX(".fbx", "fbx", 0.01F, true, Assimp.aiProcess_FlipUVs),
    GLTF(".gltf", "gltf", 1.0F, false, Assimp.aiProcess_FlipUVs),
    GLB(".glb", "glb", 1.0F, false, Assimp.aiProcess_FlipUVs);

    /** Post-process flags every format gets. */
    private static final int SHARED_FLAGS =
            Assimp.aiProcess_Triangulate |
            Assimp.aiProcess_LimitBoneWeights |
            Assimp.aiProcess_JoinIdenticalVertices |
            Assimp.aiProcess_GenSmoothNormals |
            Assimp.aiProcess_PopulateArmatureData;

    private final String extension;
    private final String hint;
    private final float unitScale;
    private final boolean fbxProperties;
    private final int extraFlags;

    SceneFormat(String extension, String hint, float unitScale, boolean fbxProperties, int extraFlags)
    {
        this.extension = extension;
        this.hint = hint;
        this.unitScale = unitScale;
        this.fbxProperties = fbxProperties;
        this.extraFlags = extraFlags;
    }

    /** Lowercase file extension, dot included. */
    public String extension()
    {
        return this.extension;
    }

    /**
     * Format hint for Assimp's in-memory import. Memory imports have no
     * filename for Assimp to sniff an extension off, and glTF's reader keys
     * off the extension before it will even look at the JSON, so this must be
     * passed whenever the bytes (rather than a real file) are imported.
     */
    public String hint()
    {
        return this.hint;
    }

    public float unitScale()
    {
        return this.unitScale;
    }

    /** Whether the FBX reader's import properties apply to this format. */
    public boolean fbxProperties()
    {
        return this.fbxProperties;
    }

    public int postProcessFlags()
    {
        return SHARED_FLAGS | this.extraFlags;
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
