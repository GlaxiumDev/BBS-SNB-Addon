package glaxium.snb.render;

import com.mojang.blaze3d.systems.RenderSystem;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Captures / restores Minecraft + raw GL texture unit 0 state around
 * multi-material BBS draws. Those paths rebind Sampler0 per material
 * (sometimes a solid 1×1 {@code color:} link) and previously left the last
 * bind in place, so later vanilla/ETF entity and block draws in the same
 * frame could sample a leftover solid texture.
 */
public final class TextureBindRestore
{
    public static final class Snapshot
    {
        private final int shaderTexture0;
        private final int activeUnit;
        private final int boundOnUnit0;

        private Snapshot(int shaderTexture0, int activeUnit, int boundOnUnit0)
        {
            this.shaderTexture0 = shaderTexture0;
            this.activeUnit = activeUnit;
            this.boundOnUnit0 = boundOnUnit0;
        }

        /** Minecraft/Iris-tracked Sampler0 id at capture time. */
        public int shaderTexture0()
        {
            return this.shaderTexture0;
        }
    }

    private TextureBindRestore()
    {
    }

    public static Snapshot capture()
    {
        int shaderTexture0 = RenderSystem.getShaderTexture(0);
        int activeUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        GL30.glActiveTexture(GL30.GL_TEXTURE0);
        int boundOnUnit0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL30.glActiveTexture(activeUnit);

        return new Snapshot(shaderTexture0, activeUnit, boundOnUnit0);
    }

    public static void restore(Snapshot snapshot)
    {
        if (snapshot == null)
        {
            return;
        }

        GL30.glActiveTexture(GL30.GL_TEXTURE0);
        RenderSystem.setShaderTexture(0, snapshot.shaderTexture0);
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, snapshot.boundOnUnit0);
        GL30.glActiveTexture(snapshot.activeUnit);
    }
}
