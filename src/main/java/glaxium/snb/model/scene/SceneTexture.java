package glaxium.snb.model.scene;

/**
 * Embedded texture. Either compressed image bytes ({@link #height} == 0,
 * {@link #width} == byte length) or raw BGRA texels ({@link #width} x
 * {@link #height}, 4 bytes per pixel in {@link #data}).
 */
public final class SceneTexture
{
    public String filename = "";
    public int width;
    public int height;
    public byte[] data = new byte[0];

    public boolean isCompressed()
    {
        return this.height == 0;
    }
}
