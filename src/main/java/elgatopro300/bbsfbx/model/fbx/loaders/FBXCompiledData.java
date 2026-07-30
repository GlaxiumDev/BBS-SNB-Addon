package elgatopro300.bbsfbx.model.fbx.loaders;

import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.CompiledData;
import mchorse.bbs_mod.resources.Link;

import java.util.Map;

public class FBXCompiledData extends CompiledData
{
    public final Map<String, float[]> shapeKeyVertices;
    public final Map<String, float[]> shapeKeyNormals;

    /**
     * Per-vertex material index into {@link #materialNames}/
     * {@link #materialTextures}, only populated by
     * {@link FBXMeshCompiler#compileMergedWithMaterials}. Null on
     * CompiledData built any other way (single-material FS per-mesh compile,
     * or CML's plain {@link FBXMeshCompiler#compileMerged}).
     */
    public int[] materialIndexData;

    /** Material index -> material (mesh) name, index-aligned with materialIndexData values. */
    public String[] materialNames;

    /**
     * Material index -> resolved texture, index-aligned with
     * materialIndexData values. Filled in by the loader AFTER compiling
     * (Link resolution needs the model/links/provider, which the compiler
     * doesn't have), so it starts out null and is set with
     * {@link #setMaterialTextures}.
     */
    public Link[] materialTextures;

    public FBXCompiledData(
            float[] posData, float[] texData, float[] normData,
            float[] weightData, int[] boneIndexData, int[] indexData,
            BOBJMesh mesh,
            Map<String, float[]> shapeKeyVertices,
            Map<String, float[]> shapeKeyNormals)
    {
        super(posData, texData, normData, weightData, boneIndexData, indexData, mesh);
        this.shapeKeyVertices = shapeKeyVertices;
        this.shapeKeyNormals = shapeKeyNormals;
    }

    public void setMaterialSplit(int[] materialIndexData, String[] materialNames)
    {
        this.materialIndexData = materialIndexData;
        this.materialNames = materialNames;
    }

    public void setMaterialTextures(Link[] materialTextures)
    {
        this.materialTextures = materialTextures;
    }

    /** True when this model actually has more than one distinct material worth splitting draw calls for. */
    public boolean hasMultipleMaterials()
    {
        return this.materialIndexData != null && this.materialNames != null && this.materialNames.length > 1;
    }
}
