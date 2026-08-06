package glaxium.snb.model.scene;

import java.util.ArrayList;
import java.util.List;

/** One animation clip with per-node TRS channels. */
public final class SceneAnimation
{
    public String name = "";
    public double ticksPerSecond = 24.0;
    public final List<SceneNodeAnim> channels = new ArrayList<>();
}
