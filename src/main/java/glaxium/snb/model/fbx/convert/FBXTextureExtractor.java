package glaxium.snb.model.fbx.convert;

import glaxium.snb.model.scene.Scene;
import glaxium.snb.model.scene.SceneMaterial;
import glaxium.snb.model.scene.SceneTexture;

import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Extracts embedded FBX textures (Blender's "Embed Textures" export option)
 * into {@code <model>/textures/<material>/default.png}, matching where
 * looks. Skips materials whose texture is a plain external file reference,
 * never overwrites a texture that's already there, and falls back to baking a
 * solid-color PNG for materials that only have a flat Base Color.
 */
public final class FBXTextureExtractor
{
    private FBXTextureExtractor() {}

    public static Set<String> extract(Scene scene, AssetProvider provider, Link model)
    {
        Set<String> texturedMaterials = new LinkedHashSet<>();
        int numMaterials = scene.materials.size();

        for (int i = 0; i < numMaterials; i++)
        {
            SceneMaterial material = scene.materials.get(i);
            String materialName = material.name;

            if (materialName == null || materialName.isEmpty())
            {
                continue;
            }

            String texturePath = material.diffuseTexturePath;

            if (texturePath == null || texturePath.isEmpty())
            {
                /* Flat-color material: handled at load time via a synthetic
                 * color Link in FBXTextureResolver, nothing to write here -
                 * and nothing for FBXModelLoader to track either, since
                 * there's no PNG that could ever go missing for it. */
                continue;
            }

            File folder = provider.getFile(model.combine("textures/" + materialName));
            if (folder == null)
            {
                continue;
            }

            File targetFile = new File(folder, "default.png");

            /* A material only goes into texturedMaterials once there really is
             * an extractable PNG for it, either already on disk or written just
             * below - FBXModelLoader caches this set so it can tell, on a
             * load-cache hit (no fresh AIScene at all), whether an extracted
             * texture has since been deleted and needs re-extracting. Tracking
             * a material whose texture is an EXTERNAL file reference instead
             * (nothing embedded to extract, common in "separate" glTF exports)
             * would make that check fail forever and re-import the model from
             * scratch on every single load, defeating the cache entirely. */
            if (targetFile.exists())
            {
                texturedMaterials.add(materialName);

                continue;
            }

            SceneTexture texture = resolveEmbeddedTexture(scene, texturePath);

            if (texture == null)
            {
                continue;
            }

            try
            {
                BufferedImage image = decodeEmbeddedTexture(texture);

                if (image != null)
                {
                    folder.mkdirs();
                    ImageIO.write(image, "png", targetFile);

                    texturedMaterials.add(materialName);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        return texturedMaterials;
    }

    private static SceneTexture resolveEmbeddedTexture(Scene scene, String texturePath)
    {
        int numTextures = scene.textures.size();
        if (numTextures == 0)
        {
            return null;
        }

        if (texturePath.startsWith("*"))
        {
            try
            {
                int index = Integer.parseInt(texturePath.substring(1));
                if (index >= 0 && index < numTextures)
                {
                    return scene.textures.get(index);
                }
            }
            catch (NumberFormatException ignored)
            {
            }
            return null;
        }

        String targetName = baseName(texturePath);

        for (int i = 0; i < numTextures; i++)
        {
            SceneTexture candidate = scene.textures.get(i);
            String hint = candidate.filename;

            if (hint != null && !hint.isEmpty() && baseName(hint).equalsIgnoreCase(targetName))
            {
                return candidate;
            }
        }

        if (numTextures == 1)
        {
            return scene.textures.get(0);
        }

        return null;
    }

    private static String baseName(String path)
    {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static BufferedImage decodeEmbeddedTexture(SceneTexture texture) throws IOException
    {
        int width = texture.width;
        int height = texture.height;
        byte[] data = texture.data;

        if (data == null || data.length == 0)
        {
            return null;
        }

        if (texture.isCompressed())
        {
            return ImageIO.read(new ByteArrayInputStream(data));
        }
        else
        {
            int texelCount = width * height;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            /* Build the whole ARGB array first and write it in one batched
             * setRGB call. A per-pixel setRGB(x, y, rgb) round-trips through
             * the image's raster/color model on every call - for a
             * 1024x1024 embedded texture that's 1M+ individual calls instead
             * of one. */
            int[] pixels = new int[texelCount];
            for (int i = 0; i < texelCount; i++)
            {
                int b = data[i * 4] & 0xFF;
                int g = data[i * 4 + 1] & 0xFF;
                int r = data[i * 4 + 2] & 0xFF;
                int a = data[i * 4 + 3] & 0xFF;

                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            image.setRGB(0, 0, width, height, pixels, 0, width);

            return image;
        }
    }
}