package glaxium.snb.model.fbx.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A format-neutral FBX element. Binary records and ASCII {@code Name: ...}
 * declarations are normalized to this small tree before scene conversion.
 */
final class FbxNode
{
    final String name;
    final List<Object> properties;
    final List<FbxNode> children;

    FbxNode(String name)
    {
        this(name, new ArrayList<>(), new ArrayList<>());
    }

    FbxNode(String name, List<Object> properties, List<FbxNode> children)
    {
        this.name = name == null ? "" : name;
        this.properties = properties == null ? new ArrayList<>() : properties;
        this.children = children == null ? new ArrayList<>() : children;
    }

    FbxNode child(String childName)
    {
        for (FbxNode child : this.children)
        {
            if (child.name.equals(childName))
            {
                return child;
            }
        }

        return null;
    }

    List<FbxNode> children(String childName)
    {
        if (this.children.isEmpty())
        {
            return Collections.emptyList();
        }

        List<FbxNode> matches = new ArrayList<>();

        for (FbxNode child : this.children)
        {
            if (child.name.equals(childName))
            {
                matches.add(child);
            }
        }

        return matches;
    }
}
