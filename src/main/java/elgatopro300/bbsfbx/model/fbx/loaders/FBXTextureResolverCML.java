package elgatopro300.bbsfbx.model.fbx.loaders;

import elgatopro300.bbsfbx.model.fbx.FBXMesh;

import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import java.util.Collection;

/**
 * CML-target texture/color resolution for the whole model (single texture,
 * no per-material split - matches how BBS FS's own {@code ModelForm} is
 * used: one {@code texture} Link, one {@code color} tint).
 *
 * <p>Resolution order, checked once for the model as a whole:
 * <ol>
 *   <li>A {@code textures/<material>/default.png} folder among the model's
 *       links (this is where {@code FBXTextureExtractor}, shared with the FS
 *       target, writes embedded FBX textures - nothing CML-specific writes
 *       here).</li>
 *   <li>The mesh's own diffuse texture file, if the FBX referenced one
 *       directly as an external file.</li>
 *   <li>Any image file among the model's links, as a last resort.</li>
 * </ol>
 *
 * <p>If none of those find a real texture, {@link #detectSolidColor} looks
 * for a flat Base Color captured off the material by
 * {@code FBXMeshBuilder} - this addon never bakes that color into a PNG or
 * creates any folder for it; the caller applies it straight to
 * {@code ModelInstance.color} (a plain packed-ARGB int CML's own engine
 * already understands, the same native tint every other model type uses).
 */
public final class FBXTextureResolverCML
{
    private FBXTextureResolverCML() {}

    public static Link resolveTexture(BOBJData data, Link model, Collection<Link> links, AssetProvider provider)
    {
        if (data.meshes.isEmpty() || !(data.meshes.get(0) instanceof FBXMesh mesh))
        {
            return firstImageLink(links);
        }

        Link resolved = resolveOne(mesh.name, mesh, model, links);

        return resolved != null ? resolved : firstImageLink(links);
    }

    /** First flat Base Color captured off any mesh's material, or null if every mesh had a real texture. */
    public static float[] detectSolidColor(BOBJData data)
    {
        for (BOBJMesh mesh : data.meshes)
        {
            if (mesh instanceof FBXMesh fbxMesh && fbxMesh.color != null)
            {
                return fbxMesh.color;
            }
        }

        return null;
    }

    /** Packs an {r,g,b} float triple (0-1 each) into an opaque 0xAARRGGBB int, as {@code ModelInstance.color} expects. */
    public static int packColor(float[] rgb)
    {
        int r = clampToByte(rgb[0]);
        int g = clampToByte(rgb[1]);
        int b = clampToByte(rgb[2]);

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /** Same folder-convention lookup as {@link #resolveTexture}, but for one specific material by name. */
    public static Link resolveMaterialTexture(String materialName, Link model, Collection<Link> links)
    {
        return findMaterialTexture(links, model, materialName);
    }

    private static Link resolveOne(String materialName, FBXMesh mesh, Link model, Collection<Link> links)
    {
        if (materialName != null && !materialName.isEmpty())
        {
            Link folderTexture = findMaterialTexture(links, model, materialName);

            if (folderTexture != null)
            {
                return folderTexture;
            }
        }

        if (mesh != null && mesh.texture != null && !mesh.texture.isEmpty())
        {
            Link specificLink = model.combine(mesh.texture);

            if (links.contains(specificLink))
            {
                return specificLink;
            }

            for (Link l : links)
            {
                if (l.path.endsWith(mesh.texture))
                {
                    return l;
                }
            }
        }

        return null;
    }

    private static Link firstImageLink(Collection<Link> links)
    {
        for (Link l : links)
        {
            String path = l.path.toLowerCase();

            if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg"))
            {
                return l;
            }
        }

        return null;
    }

    /**
     * Local reimplementation of BBS FS's
     * {@code IModelLoader.findMaterialTexture} - BBS CML EDITION's
     * IModelLoader doesn't declare it. Only reads what
     * {@code FBXTextureExtractor} (shared with FS) may have already
     * written; never writes anything itself.
     */
    private static Link findMaterialTexture(Collection<Link> links, Link model, String material)
    {
        String prefix = model.toString();
        String folder = "/textures/" + material + "/";

        for (Link link : links)
        {
            String string = link.toString();

            if (string.startsWith(prefix) && string.contains(folder) && string.endsWith(".png"))
            {
                return link;
            }
        }

        return null;
    }

    private static int clampToByte(float value)
    {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }
}
