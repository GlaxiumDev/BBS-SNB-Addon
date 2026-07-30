package elgatopro300.bbsfbx.model.fbx.loaders;

import elgatopro300.bbsfbx.model.fbx.FBXMesh;

import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collection;

/**
 * CML-target texture/color resolution. Two things live here:
 * <ul>
 *   <li>{@link #resolveTexture}/{@link #detectSolidColor} - the model-wide
 *       fallback texture/tint, used when a model has only a single material
 *       (matches how BBS FS's own {@code ModelForm} is used: one
 *       {@code texture} Link, one {@code color} tint).</li>
 *   <li>{@link #resolveMaterialTextures} - per-material textures for models
 *       {@code FBXMeshCompiler#compileMergedWithMaterials} found to have more
 *       than one material, read back out by {@code BOBJModelVAOMixinCML} to
 *       issue one draw call per material with its own texture bound. Neither
 *       Base nor CML's engine has any native concept of "more than one
 *       texture per model" (confirmed directly: their {@code ModelInstance}
 *       carries exactly one {@code texture} field, and {@code BOBJModel}
 *       manages exactly one VAO) - {@code BOBJModelVAOMixinCML} is what
 *       actually makes multiple textures render, by splitting that VAO's
 *       single draw call into one sub-range per material and rebinding the
 *       texture between them.</li>
 * </ul>
 *
 * <p>Resolution order, checked once per material (or once for the model as a
 * whole, for the single-material case):
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
        String folder = "/" + material + "/";

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

    /**
     * Resolves one texture per material for a model {@code compileMergedWithMaterials}
     * found to have more than one distinct material. Index-aligned with
     * {@code compiled.materialNames} - element {@code i} is the texture for
     * material {@code compiled.materialNames[i]}, and is null only if that
     * material has neither a real texture nor a flat color to fall back on
     * (in which case {@code BOBJModelVAOMixinCML} leaves whatever texture
     * was already bound before the model's render call, same as any other
     * unresolved single-texture model would).
     *
     * <p>Same resolution order as {@link #resolveOne} for each material:
     * an already-extracted/user-provided {@code textures/<material>/default.png}
     * folder first, then a flat Base Color baked to a new PNG under that
     * same folder (see {@link #bakeFlatColorTexture}) if the FBX material had
     * one and no real texture was found. A freshly baked PNG isn't in
     * {@code links} yet within this same load - like freshly *extracted*
     * embedded textures, it only resolves on the reload that follows the
     * file appearing on disk (this addon's {@code ModelManagerMixin} already
     * marks {@code .fbx} models as reload-watched for exactly this reason).
     */
    public static Link[] resolveMaterialTextures(BOBJData data, FBXCompiledData compiled, Link model, Collection<Link> links, AssetProvider provider)
    {
        if (compiled.materialNames == null)
        {
            return null;
        }

        Link[] result = new Link[compiled.materialNames.length];

        for (int i = 0; i < compiled.materialNames.length; i++)
        {
            String material = compiled.materialNames[i];

            if (material == null || material.isEmpty())
            {
                continue;
            }

            Link folderTexture = findMaterialTexture(links, model, material);

            if (folderTexture != null)
            {
                result[i] = folderTexture;
                continue;
            }

            float[] color = findMeshColor(data, material);

            if (color != null)
            {
                result[i] = bakeFlatColorTexture(provider, model, material, color);
            }
        }

        return result;
    }

    /** First flat Base Color captured off the mesh with this material name, or null if it had a real texture. */
    private static float[] findMeshColor(BOBJData data, String material)
    {
        for (BOBJMesh mesh : data.meshes)
        {
            if (material.equals(mesh.name) && mesh instanceof FBXMesh fbxMesh && fbxMesh.color != null)
            {
                return fbxMesh.color;
            }
        }

        return null;
    }

    /**
     * Bakes a small solid-color PNG to {@code textures/<material>/default.png},
     * the same folder (and file name) {@code FBXTextureExtractor} uses for
     * embedded textures - so a real texture dropped in later by the user
     * takes over transparently. Never overwrites a file already there (a
     * real texture, or a previously baked color, both win over re-baking).
     * Mirrors {@code FBXTextureExtractor}'s own PNG-writing pattern.
     */
    private static Link bakeFlatColorTexture(AssetProvider provider, Link model, String material, float[] rgb)
    {
        try
        {
            File folder = provider.getFile(model.combine("textures/" + material));

            if (folder == null)
            {
                return null;
            }

            File target = new File(folder, "default.png");

            if (!target.exists())
            {
                folder.mkdirs();

                int packed = packColor(rgb);
                BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);

                for (int x = 0; x < 4; x++)
                {
                    for (int y = 0; y < 4; y++)
                    {
                        image.setRGB(x, y, packed);
                    }
                }

                ImageIO.write(image, "png", target);
            }

            return model.combine("textures/" + material + "/default.png");
        }
        catch (Exception e)
        {
            return null;
        }
    }
}