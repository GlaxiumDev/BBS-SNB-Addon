package elgatopro300.bbsfbx.model.fbx.convert;

import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
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

    public static Set<String> extract(AIScene scene, AssetProvider provider, Link model)
    {
        Set<String> texturedMaterials = new LinkedHashSet<>();
        int numMaterials = scene.mNumMaterials();

        for (int i = 0; i < numMaterials; i++)
        {
            AIMaterial material = AIMaterial.create(scene.mMaterials().get(i));

            AIString nameStr = AIString.calloc();
            String materialName = null;
            if (Assimp.aiGetMaterialString(material, Assimp.AI_MATKEY_NAME, 0, 0, nameStr) == Assimp.aiReturn_SUCCESS)
            {
                materialName = nameStr.dataString();
            }
            nameStr.free();

            if (materialName == null || materialName.isEmpty())
            {
                continue;
            }

            AIString path = AIString.calloc();
            int result = Assimp.aiGetMaterialTexture(material, Assimp.aiTextureType_DIFFUSE, 0, path, (IntBuffer) null, null, null, null, null, null);
            String texturePath = result == Assimp.aiReturn_SUCCESS ? path.dataString() : null;
            path.free();

            if (texturePath == null || texturePath.isEmpty())
            {
                /* Flat-color material: handled at load time via a synthetic
                 * color Link in FBXTextureResolver, nothing to write here -
                 * and nothing for FBXModelLoader to track either, since
                 * there's no PNG that could ever go missing for it. */
                continue;
            }

            /* This material is supposed to have a PNG on disk, regardless of whether we're about
             * to write one this call or it's already there - FBXModelLoader caches this set so it
             * can tell, on a load-cache hit (no fresh AIScene at all), whether a previously
             * extracted texture has since been deleted and needs re-extracting. */
            texturedMaterials.add(materialName);

            File folder = provider.getFile(model.combine("textures/" + materialName));
            if (folder == null)
            {
                continue;
            }

            File targetFile = new File(folder, "default.png");
            if (targetFile.exists())
            {
                continue;
            }

            AITexture aiTexture = resolveEmbeddedTexture(scene, texturePath);

            if (aiTexture == null)
            {
                continue;
            }

            try
            {
                BufferedImage image = decodeEmbeddedTexture(aiTexture);

                if (image != null)
                {
                    folder.mkdirs();
                    ImageIO.write(image, "png", targetFile);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        return texturedMaterials;
    }

    private static AITexture resolveEmbeddedTexture(AIScene scene, String texturePath)
    {
        int numTextures = scene.mNumTextures();
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
                    return AITexture.create(scene.mTextures().get(index));
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
            AITexture candidate = AITexture.create(scene.mTextures().get(i));
            AIString filenameHint = candidate.mFilename();
            String hint = filenameHint.dataString();

            if (!hint.isEmpty() && baseName(hint).equalsIgnoreCase(targetName))
            {
                return candidate;
            }
        }

        if (numTextures == 1)
        {
            return AITexture.create(scene.mTextures().get(0));
        }

        return null;
    }

    private static String baseName(String path)
    {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static BufferedImage decodeEmbeddedTexture(AITexture aiTexture) throws IOException
    {
        int width = aiTexture.mWidth();
        int height = aiTexture.mHeight();

        long pcDataAddress = MemoryUtil.memGetAddress(aiTexture.address() + AITexture.PCDATA);

        if (pcDataAddress == MemoryUtil.NULL)
        {
            return null;
        }

        if (height == 0)
        {
            ByteBuffer raw = MemoryUtil.memByteBuffer(pcDataAddress, width);
            byte[] bytes = new byte[width];
            raw.get(bytes);

            return ImageIO.read(new ByteArrayInputStream(bytes));
        }
        else
        {
            int texelCount = width * height;
            ByteBuffer raw = MemoryUtil.memByteBuffer(pcDataAddress, texelCount * 4);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            /* Build the whole ARGB array first and write it in one batched
             * setRGB call. A per-pixel setRGB(x, y, rgb) round-trips through
             * the image's raster/color model on every call - for a
             * 1024x1024 embedded texture that's 1M+ individual calls instead
             * of one. */
            int[] pixels = new int[texelCount];
            for (int i = 0; i < texelCount; i++)
            {
                int b = raw.get(i * 4) & 0xFF;
                int g = raw.get(i * 4 + 1) & 0xFF;
                int r = raw.get(i * 4 + 2) & 0xFF;
                int a = raw.get(i * 4 + 3) & 0xFF;

                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            image.setRGB(0, 0, width, height, pixels, 0, width);

            return image;
        }
    }
}