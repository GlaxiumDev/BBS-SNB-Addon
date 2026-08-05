package glaxium.snb.model.fbx.loaders;

import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.CompiledData;
import mchorse.bbs_mod.resources.Link;

import java.util.Map;

public class FBXCompiledData extends CompiledData
{
    /**
     * Shape key name -> the components that key moves, relative to the rest
     * pose held in {@link #posData}/{@link #normData}. Sparse rather than a
     * dense per-key copy of the whole mesh -- see {@link FBXShapeKeyDelta}
     * for why. Empty (never null) on models without blend shapes.
     */
    public final Map<String, FBXShapeKeyDelta> shapeKeyDeltas;

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
            Map<String, FBXShapeKeyDelta> shapeKeyDeltas)
    {
        super(posData, texData, normData, weightData, boneIndexData, indexData, mesh);
        this.shapeKeyDeltas = shapeKeyDeltas;
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

    /**
     * One entry per triangle (this mesh's dominant-material-per-triangle
     * lookup, see {@link glaxium.snb.render.MultiMaterialTriangleDraw
     * #computeDominantMaterialPerTriangle}), computed once and cached here.
     *
     * <p>This is per-COMPILED-MESH data -- {@link #materialIndexData} never
     * changes after the model is loaded, so there's no reason to recompute
     * it. It's deliberately cached on this class rather than per-VAO-instance
     * (the way {@code mixin.cml.BOBJModelVAOMixinCML} caches its own
     * independent copy) so every {@code BOBJModelVAO} instance rendering the
     * same underlying model -- e.g. multiple placed copies of the same car --
     * shares one array instead of each VAO computing/holding its own.</p>
     */
    private int[] dominantMaterialPerTriangle;

    public int[] getDominantMaterialPerTriangle()
    {
        int[] cached = this.dominantMaterialPerTriangle;

        if (cached == null)
        {
            cached = glaxium.snb.render.MultiMaterialTriangleDraw
                    .computeDominantMaterialPerTriangle(this.materialIndexData);
            this.dominantMaterialPerTriangle = cached;
        }

        return cached;
    }

    /**
     * Per-material precomputed {@code glDrawArrays} run ranges (see
     * {@link glaxium.snb.render.MultiMaterialTriangleDraw
     * #computeMaterialRuns}), cached the same way as {@link
     * #getDominantMaterialPerTriangle()} and for the same reason: this is
     * the actual per-frame cost that remained even after that array got
     * cached (finding each material's contiguous runs still meant scanning
     * every triangle once per material, every render call). Computing the
     * runs once here means rendering just replays fixed ranges -- no
     * per-frame scanning of any kind.
     */
    private int[][] materialDrawRuns;

    public int[][] getMaterialDrawRuns()
    {
        int[][] cached = this.materialDrawRuns;

        if (cached == null)
        {
            cached = glaxium.snb.render.MultiMaterialTriangleDraw
                    .computeMaterialRuns(this.getDominantMaterialPerTriangle(), this.materialNames.length);
            this.materialDrawRuns = cached;
        }

        return cached;
    }
}
