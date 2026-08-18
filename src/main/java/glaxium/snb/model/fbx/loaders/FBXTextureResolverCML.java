package glaxium.snb.model.fbx.loaders;

import glaxium.snb.model.fbx.FBXMesh;

import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import java.util.Collection;

/**
 * Texture/color resolution for the whole model (single texture, no
 * per-material split - matches how every fork's {@code ModelInstance} is
 * used: one texture {@code Link}, one {@code color} tint). Shared by all
 * three forks -- despite the "CML" class name, which predates this loader
 * becoming the fork-agnostic {@link FBXModelLoader}'s single resolver and
 * was kept rather than churn a rename.
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
 * {@code FBXMeshBuilder}, and the caller turns it into a synthetic color
 * texture {@link Link} via {@link #colorLink} - the exact mechanism the
 * original BBS-FS-only addon used ({@code LinkUtils.color}): a
 * {@code Link("color", <hex>)} whose pixels BBS's TextureManager generates
 * in memory, with no PNG ever written to disk. FS's own TextureManager
 * special-cases that source natively; {@code TextureManagerMixinBaseCML}
 * gives Base and CML the same handling.</p>
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

    /**
     * Builds a synthetic solid-color texture {@code Link} for a flat
     * {r,g,b} triple (0-1 each) - the exact mechanism the original
     * BBS-FS-only addon used via {@code LinkUtils.color}: a
     * {@code Link("color", <hex>)} that BBS's TextureManager turns into a
     * single-pixel in-memory texture. No file is written. FS handles the
     * {@code "color"} source natively; {@code TextureManagerMixinBaseCML}
     * gives Base and CML the same handling.
     */
    public static Link colorLink(float[] rgb)
    {
        int r = clampToByte(rgb[0]);
        int g = clampToByte(rgb[1]);
        int b = clampToByte(rgb[2]);

        return new Link("color", Integer.toHexString((0xFF << 24) | (r << 16) | (g << 8) | b));
    }

    /** Same folder-convention lookup as {@link #resolveTexture}, but for one specific material by name. */
    public static Link resolveMaterialTexture(String materialName, Link model, Collection<Link> links)
    {
        return findMaterialTexture(links, model, materialName);
    }

    /**
     * Folder-convention lookup for one material, then that material's own
     * external texture file reference as a fallback. Worth passing the mesh
     * whenever it's available: a model whose textures are loose image files
     * next to it rather than embedded (the usual "separate" glTF export, and
     * FBX exported without "Embed Textures") has nothing under
     * {@code textures/<material>/} for the folder lookup to find, so
     * per-material textures would otherwise all collapse to the one
     * model-wide texture {@link #resolveTexture} picks.
     */
    public static Link resolveMaterialTexture(String materialName, FBXMesh mesh, Link model, Collection<Link> links)
    {
        return resolveOne(materialName, mesh, model, links);
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
        Link fallback = null;

        for (Link l : links)
        {
            String path = l.path.toLowerCase();

            if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg"))
            {
                /* Iris-style companion maps (_n = normal, _s = specular, _e =
                 * emissive) sit next to the main texture and must never be
                 * picked as the model's texture itself. Prefer a plain
                 * default.png when there is one, so adding a _s file next to
                 * it doesn't change what the model shows. */
                if (isCompanionFile(path))
                {
                    continue;
                }

                if (path.endsWith("default.png"))
                {
                    return l;
                }

                if (fallback == null)
                {
                    fallback = l;
                }
            }
        }

        return fallback;
    }

    /** True for Iris-style companion maps ({@code *_n.png}, {@code *_s.png}, {@code *_e.png}) that accompany a material's main texture. */
    private static boolean isCompanionFile(String path)
    {
        int dot = path.lastIndexOf('.');

        if (dot < 0)
        {
            return false;
        }

        String stem = path.substring(0, dot);

        return stem.endsWith("_n") || stem.endsWith("_s") || stem.endsWith("_e");
    }

    /**
     * Local reimplementation of BBS FS's
     * {@code IModelLoader.findMaterialTexture} - BBS CML EDITION's
     * IModelLoader doesn't declare it. Only reads what
     * {@code FBXTextureExtractor} (shared with FS) may have already
     * written; never writes anything itself.
     *
     * <p>The material folder commonly holds {@code default.png} plus
     * Iris-style companion maps ({@code default_s.png} specular,
     * {@code default_n.png} normal, {@code default_e.png} emissive). Those
     * companions must never be chosen as the material's diffuse itself --
     * the link collection is unordered, so without an explicit preference
     * the "first .png" could be the specular/glow map (a mostly-blue file
     * that renders as the material's color). {@code default.png} is always
     * preferred when present; companion files are skipped outright.</p>
     */
    private static Link findMaterialTexture(Collection<Link> links, Link model, String material)
    {
        String prefix = model.toString();
        String folder = "/textures/" + material + "/";
        Link fallback = null;

        for (Link link : links)
        {
            String string = link.toString();

            if (!string.startsWith(prefix) || !string.contains(folder) || !string.endsWith(".png"))
            {
                continue;
            }

            String lower = link.path.toLowerCase();

            if (isCompanionFile(lower))
            {
                continue;
            }

            if (lower.endsWith("default.png"))
            {
                return link;
            }

            if (fallback == null)
            {
                fallback = link;
            }
        }

        return fallback;
    }

    private static int clampToByte(float value)
    {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }
}
