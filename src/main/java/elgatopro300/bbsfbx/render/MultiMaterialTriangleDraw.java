package elgatopro300.bbsfbx.render;

import org.lwjgl.opengl.GL30;

/**
 * The fork-agnostic half of multi-material FBX rendering: figuring out
 * which contiguous triangle runs belong to which material, and issuing the
 * `glDrawArrays` calls for one material's runs. Pure math plus raw GL calls
 * -- no BBS API at all -- so it's identical on every fork and only needs to
 * exist once.
 *
 * <p>Deliberately lives OUTSIDE {@code elgatopro300.bbsfbx.mixin} (and all
 * its sub-packages) even though it's only ever called from mixin code.
 * Sponge Mixin reserves the entire package tree named in
 * {@code bbs_fbx.mixins.json}'s {@code "package"} key for classes actually
 * declared as mixins in that config; a plain (non-mixin) helper class
 * sitting inside that tree throws {@code IllegalClassLoadError} the moment
 * anything tries to load it normally at runtime -- compiles fine, then
 * crashes the first time a mixin that references it actually runs. This was
 * originally placed at {@code elgatopro300.bbsfbx.mixin.MultiMaterialTriangleDraw}
 * and hit exactly that crash on Base (not CML, since CML's mixin doesn't
 * call into this class at all -- it kept its own separate, pre-existing
 * copy of the same algorithm).</p>
 *
 * <p>{@link elgatopro300.bbsfbx.mixin.base.BOBJModelVAOMixinBase} and
 * {@link elgatopro300.bbsfbx.mixin.fs.BOBJModelVAOMixinFS} both call into
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
}
