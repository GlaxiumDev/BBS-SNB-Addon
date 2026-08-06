package glaxium.snb.model.fbx.parse;

import java.util.ArrayList;
import java.util.List;

/** Parsed FBX syntax tree and the file version that controls binary layout. */
final class FbxDocument
{
    int version;
    final List<FbxNode> roots = new ArrayList<>();

    FbxNode root(String name)
    {
        for (FbxNode root : this.roots)
        {
            if (root.name.equals(name))
            {
                return root;
            }
        }

        return null;
    }
}
