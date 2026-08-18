package glaxium.snb.model.bobj;

import mchorse.bbs_mod.resources.ISourcePack;
import mchorse.bbs_mod.resources.Link;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;

/**
 * Exposes Minecraft/resource-pack textures to BBS's Link-based texture
 * manager. A Link path is an encoded namespaced Identifier, for example
 * {@code minecraft:textures/models/armor/diamond_layer_1.png}. This also
 * lets armor supplied by other mods resolve through their own namespace.
 */
public final class MinecraftTextureSourcePack implements ISourcePack
{
    public static final String SOURCE = "bbs_fbx_minecraft";

    public static Link link(Identifier identifier)
    {
        return new Link(SOURCE, identifier.toString());
    }

    @Override
    public String getPrefix()
    {
        return SOURCE;
    }

    @Override
    public boolean hasAsset(Link link)
    {
        Identifier identifier = parse(link);

        return identifier != null && MinecraftClient.getInstance().getResourceManager().getResource(identifier).isPresent();
    }

    @Override
    public InputStream getAsset(Link link) throws IOException
    {
        Identifier identifier = parse(link);

        if (identifier == null)
        {
            throw new FileNotFoundException(String.valueOf(link));
        }

        return MinecraftClient.getInstance().getResourceManager().open(identifier);
    }

    @Override
    public File getFile(Link link)
    {
        return null;
    }

    @Override
    public Link getLink(File file)
    {
        return null;
    }

    @Override
    public void getLinksFromPath(Collection<Link> links, Link link, boolean recursive)
    {
        /* Armor textures are addressed directly; enumerating the complete
         * Minecraft resource manager would be wasteful and isn't needed. */
    }

    private static Identifier parse(Link link)
    {
        return link == null ? null : Identifier.tryParse(link.path);
    }
}
