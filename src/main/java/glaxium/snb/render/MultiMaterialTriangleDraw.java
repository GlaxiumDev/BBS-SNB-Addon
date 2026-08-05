package glaxium.snb.render;

import org.lwjgl.opengl.GL30;

/**
 * The fork-agnostic half of multi-material FBX rendering: figuring out
 * which contiguous triangle runs belong to which material, and issuing the
 * `glDrawArrays` calls for one material's runs. Pure math plus raw GL calls
 * -- no BBS API at all -- so it's identical on every fork and only needs to
 * exist once.
 *
 * <p>Deliberately lives OUTSIDE {@code glaxium.snb.mixin} (and all
 * its sub-packages) even though it's only ever called from mixin code.
 * Sponge Mixin reserves the entire package tree named in
 * {@code bbs_fbx.mixins.json}'s {@code "package"} key for classes actually
 * declared as mixins in that config; a plain (non-mixin) helper class
 * sitting inside that tree throws {@code IllegalClassLoadError} the moment
 * anything tries to load it normally at runtime -- compiles fine, then
 * crashes the first time a mixin that references it actually runs. This was
 * originally placed at {@code glaxium.snb.mixin.MultiMaterialTriangleDraw}
 * and hit exactly that crash on Base (not CML, since CML's mixin doesn't
 * call into this class at all -- it kept its own separate, pre-existing
 * copy of the same algorithm).</p>
 *
 * <p>{@link glaxium.snb.mixin.base.BOBJModelVAOMixinBase} and
 * {@link glaxium.snb.mixin.fs.BOBJModelVAOMixinFS} both call into
 * this; {@code mixin.cml.BOBJModelVAOMixinCML} keeps its own
 * pre-existing, independently-written copy of the same algorithm rather
 * than being refactored to share this -- it was already shipped and
 * working, so it was left alone rather than risk it over a duplication
 * cleanup.</p>
 */
public final class MultiMaterialTriangleDraw
{
    private MultiMaterialTriangleDraw() {}

    /**
     * One entry per triangle, using its first vertex's material index --
     * safe because every vertex belonging to one triangle came from the
     * same originating mesh/material in
     * {@code FBXMeshCompiler#compileMergedWithMaterials}.
     */
    public static int[] computeDominantMaterialPerTriangle(int[] materialIndexData)
    {
        int triangleCount = materialIndexData.length / 3;
        int[] dominant = new int[triangleCount];

        for (int triangle = 0; triangle < triangleCount; triangle++)
        {
            dominant[triangle] = materialIndexData[triangle * 3];
        }

        return dominant;
    }

    /** Contiguous-run {@code glDrawArrays} calls for every triangle whose dominant material matches. */
    public static void drawTrianglesForMaterial(int[] dominant, int materialIndex)
    {
        int start = -1;

        for (int i = 0; i < dominant.length; i++)
        {
            boolean draw = dominant[i] == materialIndex;

            if (draw && start == -1)
            {
                start = i;
            }
            else if (!draw && start != -1)
            {
                GL30.glDrawArrays(GL30.GL_TRIANGLES, start * 3, (i - start) * 3);
                start = -1;
            }
        }

        if (start != -1)
        {
            GL30.glDrawArrays(GL30.GL_TRIANGLES, start * 3, (dominant.length - start) * 3);
        }
    }

    /**
     * Precomputes every material's contiguous-run boundaries in ONE pass
     * over the mesh, instead of {@code drawTrianglesForMaterial} being
     * called once per material and each call re-scanning every triangle
     * (an {@code O(triangles * materialCount)} cost that was the actual
     * remaining per-frame cost even after {@code dominant} itself got
     * cached -- confirmed by testing: FPS scales with material count, not
     * texture count, exactly what an {@code O(materialCount)} per-frame
     * factor predicts).
     *
     * <p>Result is indexed by material: {@code runs[materialIndex]} is a
     * flat {@code [startTriangle, lengthTriangles, startTriangle,
     * lengthTriangles, ...]} array, ready for {@link #drawRuns}. Intended
     * to be computed once and cached (see {@code FBXCompiledData
     * #getMaterialDrawRuns()}), not recomputed per render call.</p>
     */
    public static int[][] computeMaterialRuns(int[] dominant, int materialCount)
    {
        // Two-pass counting first (avoids ArrayList/boxing): pass 1 counts
        // how many runs each material has so pass 2 can fill pre-sized
        // arrays directly.
        int[] runCounts = new int[materialCount];
        int previousMaterial = -1;

        for (int triangle = 0; triangle < dominant.length; triangle++)
        {
            int material = dominant[triangle];

            if (material != previousMaterial)
            {
                runCounts[material]++;
            }

            previousMaterial = material;
        }

        int[][] runs = new int[materialCount][];

        for (int m = 0; m < materialCount; m++)
        {
            runs[m] = new int[runCounts[m] * 2];
        }

        int[] writeIndex = new int[materialCount];
        int runStart = 0;
        previousMaterial = dominant.length > 0 ? dominant[0] : -1;

        for (int triangle = 1; triangle <= dominant.length; triangle++)
        {
            int material = triangle < dominant.length ? dominant[triangle] : -1;

            if (material != previousMaterial)
            {
                int[] target = runs[previousMaterial];
                int idx = writeIndex[previousMaterial];

                target[idx] = runStart;
                target[idx + 1] = triangle - runStart;

                writeIndex[previousMaterial] = idx + 2;
                runStart = triangle;
            }

            previousMaterial = material;
        }

        return runs;
    }

    /** Issues the {@code glDrawArrays} calls for one material's precomputed runs (see {@link #computeMaterialRuns}). No scanning -- just replays cached ranges. */
    public static void drawRuns(int[] runs)
    {
        for (int i = 0; i < runs.length; i += 2)
        {
            GL30.glDrawArrays(GL30.GL_TRIANGLES, runs[i] * 3, runs[i + 1] * 3);
        }
    }
}
