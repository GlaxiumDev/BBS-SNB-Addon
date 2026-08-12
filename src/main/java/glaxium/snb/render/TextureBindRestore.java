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
    private static final ThreadLocal<SnapshotPool> POOL = ThreadLocal.withInitial(SnapshotPool::new);

    public static final class Snapshot
    {
        private int shaderTexture0;
        private int activeUnit;
        private int boundOnUnit0;
        private SnapshotPool owner;
        private int slot;

        private Snapshot()
        {}

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
        SnapshotPool pool = POOL.get();
        Snapshot snapshot = pool.acquire();
        int shaderTexture0 = RenderSystem.getShaderTexture(0);
        int activeUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

        GL30.glActiveTexture(GL30.GL_TEXTURE0);
        int boundOnUnit0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL30.glActiveTexture(activeUnit);

        snapshot.shaderTexture0 = shaderTexture0;
        snapshot.activeUnit = activeUnit;
        snapshot.boundOnUnit0 = boundOnUnit0;

        return snapshot;
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

        snapshot.owner.release(snapshot);
    }

    /** Small nested-safe pool; normal rendering only ever uses slot zero. */
    private static final class SnapshotPool
    {
        private Snapshot[] snapshots = new Snapshot[4];
        private int depth;

        private Snapshot acquire()
        {
            if (this.depth == this.snapshots.length)
            {
                Snapshot[] grown = new Snapshot[this.snapshots.length * 2];

                System.arraycopy(this.snapshots, 0, grown, 0, this.snapshots.length);
                this.snapshots = grown;
            }

            int slot = this.depth++;
            Snapshot snapshot = this.snapshots[slot];

            if (snapshot == null)
            {
                snapshot = new Snapshot();
                this.snapshots[slot] = snapshot;
            }

            snapshot.owner = this;
            snapshot.slot = slot;

            return snapshot;
        }

        private void release(Snapshot snapshot)
        {
            if (snapshot.owner == this && snapshot.slot == this.depth - 1)
            {
                this.depth--;
            }
        }
    }
}
